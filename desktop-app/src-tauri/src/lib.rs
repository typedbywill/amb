// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::sync::atomic::Ordering;
use tokio::io::AsyncReadExt;

// --- Data Structures ---

#[derive(serde::Serialize, Clone)]
struct AdbDevice {
    serial: String,
    status: String,
}

#[derive(serde::Serialize, Clone)]
struct AudioDevice {
    name: String,
    is_default: bool,
}

#[derive(serde::Deserialize, Clone)]
struct BridgeConfig {
    is_usb: bool,
    device: String,
    ip: String,
    port: u16,
    codec: String,
    volume: f32,
    out_device: String,
    virtual_mic: bool,
}

#[derive(serde::Serialize, Clone)]
struct AudioMetrics {
    bandwidth: f64,
    buffer_size: usize,
    buffer_ms: usize,
    lost_packets: usize,
}

struct AppState {
    stop_tx: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    volume: Arc<std::sync::atomic::AtomicU32>,
}

// --- Audio Device List Helper ---

fn get_audio_output_devices() -> Vec<AudioDevice> {
    let host = cpal::default_host();
    let default_device_name = host.default_output_device().map(|d| d.name().unwrap_or_default());
    let mut devices = Vec::new();
    if let Ok(device_list) = host.output_devices() {
        for dev in device_list {
            if let Ok(name) = dev.name() {
                let is_default = default_device_name.as_ref() == Some(&name);
                devices.push(AudioDevice { name, is_default });
            }
        }
    }
    devices
}

// --- ADB Management Helpers ---

fn get_adb_path() -> String {
    if cfg!(target_os = "windows") {
        if let Some(home) = std::env::var_os("USERPROFILE") {
            let path = std::path::Path::new(&home).join(".miccpy").join("adb.exe");
            if path.exists() {
                return path.to_string_lossy().to_string();
            }
        }
    } else {
        if let Some(home) = std::env::var_os("HOME") {
            let path = std::path::Path::new(&home).join(".local").join("share").join("miccpy").join("adb");
            if path.exists() {
                return path.to_string_lossy().to_string();
            }
        }
    }
    "adb".to_string()
}

fn run_adb_cmd(device: &str, args: &[&str]) -> Result<String, String> {
    let adb_path = get_adb_path();
    let mut cmd = std::process::Command::new(&adb_path);
    if !device.is_empty() {
        cmd.arg("-s").arg(device);
    }
    cmd.args(args);
    
    match cmd.output() {
        Ok(out) => {
            if out.status.success() {
                Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
            } else {
                Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
            }
        }
        Err(e) => Err(format!("Falha ao executar ADB ({}): {}", adb_path, e)),
    }
}

fn get_adb_connected_devices() -> Vec<AdbDevice> {
    let mut devices = Vec::new();
    let adb_path = get_adb_path();
    let output = std::process::Command::new(adb_path)
        .arg("devices")
        .output();
        
    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        let mut lines = stdout.lines();
        lines.next(); // Skip header
        for line in lines {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                devices.push(AdbDevice {
                    serial: parts[0].to_string(),
                    status: parts[1].to_string(),
                });
            }
        }
    }
    devices
}

// --- Audio Playback Engine ---

fn build_audio_stream(
    device_name: &str,
    virtual_mic: bool,
    input_sample_rate: u32,
    volume: Arc<std::sync::atomic::AtomicU32>,
    audio_queue: Arc<Mutex<VecDeque<i16>>>,
) -> Result<cpal::Stream, String> {
    let host = cpal::default_host();
    
    let device = if virtual_mic {
        let mut found_dev = None;
        if let Ok(devices) = host.output_devices() {
            for dev in devices {
                if let Ok(name) = dev.name() {
                    let name_lower = name.to_lowercase();
                    if name_lower.contains("cable input") || name_lower.contains("vb-audio") || name_lower.contains("virtual cable") {
                        found_dev = Some(dev);
                        break;
                    }
                }
            }
        }
        found_dev.ok_or_else(|| "Nenhum cabo de áudio virtual (VB-Cable) encontrado.".to_string())?
    } else if !device_name.is_empty() {
        let mut found_dev = None;
        if let Ok(devices) = host.output_devices() {
            for dev in devices {
                if let Ok(name) = dev.name() {
                    if name == device_name {
                        found_dev = Some(dev);
                        break;
                    }
                }
            }
        }
        found_dev.ok_or_else(|| format!("Dispositivo de áudio '{}' não encontrado.", device_name))?
    } else {
        host.default_output_device().ok_or_else(|| "Nenhum dispositivo de áudio padrão encontrado.".to_string())?
    };

    let config = device.default_output_config().map_err(|e| e.to_string())?;
    let channels = config.channels() as usize;
    let sample_rate = config.sample_rate().0;
    let ratio = input_sample_rate as f64 / sample_rate as f64;
    
    let mut input_index = 0.0f64;
    let err_fn = |err| eprintln!("Erro na reprodução CPAL: {}", err);

    let stream = match config.sample_format() {
        cpal::SampleFormat::F32 => {
            device.build_output_stream(
                &config.into(),
                move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                    let vol = volume.load(Ordering::Relaxed) as f32 / 1000.0;
                    let mut queue = audio_queue.lock().unwrap();
                    
                    // Prevent latency buildup: if buffer queue is too large, drop excess
                    let max_samples = 5 * 960; // ~100ms
                    if queue.len() > max_samples {
                        let excess = queue.len() - max_samples;
                        queue.drain(0..excess);
                    }

                    for frame in data.chunks_mut(channels) {
                        let input_needed = input_index.floor() as usize;
                        
                        if queue.len() > input_needed {
                            let s = queue[input_needed];
                            let mut sample_f32 = (s as f32 / 32768.0) * vol;
                            
                            // Clip limiter
                            if sample_f32 > 1.0 { sample_f32 = 1.0; }
                            else if sample_f32 < -1.0 { sample_f32 = -1.0; }

                            for channel_sample in frame.iter_mut() {
                                *channel_sample = sample_f32;
                            }
                            
                            input_index += ratio;
                            let consume = input_index.floor() as usize;
                            if consume > 0 {
                                queue.drain(0..consume);
                                input_index -= consume as f64;
                            }
                        } else {
                            for channel_sample in frame.iter_mut() {
                                *channel_sample = 0.0;
                            }
                            input_index = 0.0;
                        }
                    }
                },
                err_fn,
                None
            ).map_err(|e| e.to_string())?
        }
        cpal::SampleFormat::I16 => {
            device.build_output_stream(
                &config.into(),
                move |data: &mut [i16], _: &cpal::OutputCallbackInfo| {
                    let vol = volume.load(Ordering::Relaxed) as f32 / 1000.0;
                    let mut queue = audio_queue.lock().unwrap();
                    
                    let max_samples = 5 * 960;
                    if queue.len() > max_samples {
                        let excess = queue.len() - max_samples;
                        queue.drain(0..excess);
                    }

                    for frame in data.chunks_mut(channels) {
                        let input_needed = input_index.floor() as usize;
                        
                        if queue.len() > input_needed {
                            let s = queue[input_needed];
                            let mut sample_f32 = s as f32 * vol;
                            
                            if sample_f32 > 32767.0 { sample_f32 = 32767.0; }
                            else if sample_f32 < -32768.0 { sample_f32 = -32768.0; }
                            let sample_i16 = sample_f32 as i16;

                            for channel_sample in frame.iter_mut() {
                                *channel_sample = sample_i16;
                            }
                            
                            input_index += ratio;
                            let consume = input_index.floor() as usize;
                            if consume > 0 {
                                queue.drain(0..consume);
                                input_index -= consume as f64;
                            }
                        } else {
                            for channel_sample in frame.iter_mut() {
                                *channel_sample = 0;
                            }
                            input_index = 0.0;
                        }
                    }
                },
                err_fn,
                None
            ).map_err(|e| e.to_string())?
        }
        _ => return Err("Formato de dispositivo incompatível (apenas suporte a f32/i16).".to_string())
    };

    Ok(stream)
}

// --- Background Socket Streaming Task ---

async fn run_bridge_loop(
    config: BridgeConfig,
    volume: Arc<std::sync::atomic::AtomicU32>,
    stop_rx: &mut tokio::sync::oneshot::Receiver<()>,
    window: tauri::Window,
) -> Result<(), String> {
    let host = if config.is_usb { "127.0.0.1".to_string() } else { config.ip.clone() };
    let port = config.port;

    if config.is_usb {
        let _ = window.emit("backend-message", "Configurando USB via ADB...");
        
        let adb_devices = get_adb_connected_devices();
        let device_exists = adb_devices.iter().any(|d| d.serial == config.device);
        if !device_exists && !config.device.is_empty() {
            return Err(format!("Dispositivo ADB '{}' não conectado.", config.device));
        }

        // Grant audio recorder and notifications permissions
        let _ = run_adb_cmd(&config.device, &["shell", "pm", "grant", "com.example.androidmicbridge", "android.permission.RECORD_AUDIO"]);
        let _ = run_adb_cmd(&config.device, &["shell", "pm", "grant", "com.example.androidmicbridge", "android.permission.POST_NOTIFICATIONS"]);

        // Port forwarding
        let port_str = port.to_string();
        let forward_arg = format!("tcp:{}", port_str);
        if let Err(e) = run_adb_cmd(&config.device, &["forward", &forward_arg, &forward_arg]) {
            return Err(format!("Falha ao encaminhar porta ADB: {}", e));
        }

        // Intent flags to launch the App
        let is_opus = if config.codec == "opus" { "true" } else { "false" };
        let am_start_args = vec![
            "shell", "am", "start", "-n", "com.example.androidmicbridge/.MainActivity",
            "--ez", "autostart", "true",
            "--ez", "isOpus", is_opus,
            "--ei", "sampleRate", "48000",
            "--ei", "port", &port_str
        ];
        
        let _ = window.emit("backend-message", "Iniciando App no Android...");
        if let Err(e) = run_adb_cmd(&config.device, &am_start_args) {
            return Err(format!("Falha ao iniciar app Android via adb intent: {}", e));
        }

        // Sleep 1.5s to let socket start
        tokio::time::sleep(tokio::time::Duration::from_millis(1500)).await;
    }

    // Connect to server
    let addr = format!("{}:{}", host, port);
    let mut socket_stream = None;
    let mut retries = 5;
    
    let _ = window.emit("backend-message", format!("Conectando ao bridge em {}...", addr));
    
    while retries > 0 {
        tokio::select! {
            _ = stop_rx => {
                return Ok(());
            }
            res = tokio::net::TcpStream::connect(&addr) => {
                match res {
                    Ok(s) => {
                        socket_stream = Some(s);
                        break;
                    }
                    Err(_) => {
                        retries -= 1;
                        if retries == 0 {
                            return Err("Conexão falhou. O servidor de áudio do Android está ativo?".to_string());
                        }
                        let _ = window.emit("backend-message", format!("Conexão recusada. Re-tentando em 1.5s... ({} restantes)", retries));
                        tokio::time::sleep(tokio::time::Duration::from_millis(1500)).await;
                    }
                }
            }
        }
    }

    let mut tcp_stream = socket_stream.unwrap();
    let _ = window.emit("connection-status", "connected");

    // Initialize playback
    let audio_queue = Arc::new(Mutex::new(VecDeque::new()));
    let input_sample_rate = 48000;
    let output_stream = build_audio_stream(
        &config.out_device,
        config.virtual_mic,
        input_sample_rate,
        volume,
        audio_queue.clone()
    )?;
    
    output_stream.play().map_err(|e| e.to_string())?;

    // Opus Decoder
    let mut decoder = if config.codec == "opus" {
        Some(opus_decoder::OpusDecoder::new(input_sample_rate, 1).map_err(|e| e.to_string())?)
    } else {
        None
    };

    let mut last_report = tokio::time::Instant::now();
    let mut bytes_in_sec = 0;
    let mut packets_lost = 0;

    let mut read_buf = vec![0u8; 65536];
    let mut packet_header = [0u8; 4];

    loop {
        tokio::select! {
            _ = stop_rx => {
                break;
            }
            header_res = tcp_stream.read_exact(&mut packet_header) => {
                if let Err(e) = header_res {
                    let _ = window.emit("backend-message", format!("Conexão fechada: {}", e));
                    break;
                }
                
                let length = u32::from_be_bytes(packet_header) as usize;
                if length > read_buf.len() {
                    read_buf.resize(length, 0u8);
                }
                
                let payload_res = tcp_stream.read_exact(&mut read_buf[0..length]).await;
                if let Err(e) = payload_res {
                    let _ = window.emit("backend-message", format!("Erro de payload: {}", e));
                    break;
                }

                let payload = &read_buf[0..length];
                bytes_in_sec += length + 4;

                let mut decoded_pcm = vec![0i16; 960];
                let mut decode_success = true;

                if let Some(dec) = &mut decoder {
                    match dec.decode(payload, &mut decoded_pcm, false) {
                        Ok(samples) => {
                            decoded_pcm.truncate(samples);
                        }
                        Err(_) => {
                            packets_lost += 1;
                            decode_success = false;
                        }
                    }
                } else {
                    decoded_pcm.clear();
                    for chunk in payload.chunks_exact(2) {
                        decoded_pcm.push(i16::from_le_bytes([chunk[0], chunk[1]]));
                    }
                }

                if decode_success {
                    let mut queue = audio_queue.lock().unwrap();
                    queue.extend(decoded_pcm.iter().copied());

                    // Calculate peak audio level for frontend meter
                    let mut max_val = 0;
                    for &s in &decoded_pcm {
                        let abs = s.abs();
                        if abs > max_val {
                            max_val = abs;
                        }
                    }
                    let level = max_val as f32 / 32768.0;
                    let _ = window.emit("audio-level", level);
                }

                // Send metric stats every 1 second
                if last_report.elapsed() >= tokio::time::Duration::from_secs(1) {
                    let bandwidth = (bytes_in_sec * 8) as f64 / 1000.0;
                    let qsize = {
                        let queue = audio_queue.lock().unwrap();
                        queue.len()
                    };
                    let buffer_ms = (qsize as f64 / input_sample_rate as f64) * 1000.0;
                    
                    let metrics = AudioMetrics {
                        bandwidth,
                        buffer_size: qsize,
                        buffer_ms: buffer_ms as usize,
                        lost_packets: packets_lost,
                    };
                    
                    let _ = window.emit("audio-metrics", metrics);
                    bytes_in_sec = 0;
                    last_report = tokio::time::Instant::now();
                }
            }
        }
    }

    Ok(())
}

// --- Tauri Commands ---

#[tauri::command]
fn get_audio_devices() -> Vec<AudioDevice> {
    get_audio_output_devices()
}

#[tauri::command]
fn get_adb_devices() -> Vec<AdbDevice> {
    get_adb_connected_devices()
}

#[tauri::command]
fn set_volume(volume: f32, state: tauri::State<'_, AppState>) {
    state.volume.store((volume * 1000.0) as u32, Ordering::SeqCst);
}

#[tauri::command]
fn stop_bridge(state: tauri::State<'_, AppState>) {
    let mut stop_tx_guard = state.stop_tx.lock().unwrap();
    if let Some(tx) = stop_tx_guard.take() {
        let _ = tx.send(());
    }
}

#[tauri::command]
async fn start_bridge(
    config: BridgeConfig,
    state: tauri::State<'_, AppState>,
    window: tauri::Window,
) -> Result<(), String> {
    // Stop any existing stream first
    let mut stop_tx_guard = state.stop_tx.lock().unwrap();
    if let Some(tx) = stop_tx_guard.take() {
        let _ = tx.send(());
    }

    // Set initial volume
    state.volume.store((config.volume * 1000.0) as u32, Ordering::SeqCst);

    // Create stop channel
    let (tx, mut rx) = tokio::sync::oneshot::channel::<()>();
    *stop_tx_guard = Some(tx);

    let app_state_volume = state.volume.clone();

    tokio::spawn(async move {
        let window_clone = window.clone();
        let _ = window.emit("connection-status", "connecting");
        
        if let Err(e) = run_bridge_loop(config, app_state_volume, &mut rx, window_clone).await {
            let _ = window.emit("backend-message", format!("Erro na execução: {}", e));
        }

        let _ = window.emit("connection-status", "disconnected");
    });

    Ok(())
}

#[tauri::command]
fn install_virtual_cable() -> Result<String, String> {
    if !cfg!(target_os = "windows") {
        return Err("Instalação automática do VB-Cable só é suportada no Windows.".to_string());
    }
    
    // Command via powershell to download, unzip, run install dialog as Admin, and clean up.
    let script = r#"
        $url = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip"
        $tempDir = [System.IO.Path]::GetTempPath()
        $zipPath = Join-Path $tempDir "vbcable.zip"
        $extractDir = Join-Path $tempDir "vbcable_extracted"
        
        Write-Output "Baixando VB-CABLE..."
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
        
        Write-Output "Extraindo..."
        Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
        
        $is64 = [Environment]::Is64BitOperatingSystem
        $installer = if ($is64) { "VBCABLE_Setup_x64.exe" } else { "VBCABLE_Setup.exe" }
        $installerPath = Join-Path $extractDir $installer
        
        Write-Output "Executando instalador como Administrador..."
        Start-Process $installerPath -Verb RunAs -Wait
        
        Remove-Item $zipPath -Force
        Remove-Item $extractDir -Recurse -Force
    "#;
    
    let output = std::process::Command::new("powershell")
        .arg("-Command")
        .arg(script)
        .output();
        
    match output {
        Ok(out) => {
            if out.status.success() {
                Ok("Instalador do VB-Cable concluído. Por favor, reinicie seu computador.".to_string())
            } else {
                Err(String::from_utf8_lossy(&out.stderr).to_string())
            }
        }
        Err(e) => Err(format!("Erro ao iniciar processo de instalação: {}", e)),
    }
}

// --- Entrypoint ---

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppState {
            stop_tx: Mutex::new(None),
            volume: Arc::new(std::sync::atomic::AtomicU32::new(1000)),
        })
        .invoke_handler(tauri::generate_handler![
            get_audio_devices,
            get_adb_devices,
            set_volume,
            start_bridge,
            stop_bridge,
            install_virtual_cable
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
