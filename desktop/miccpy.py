#!/usr/bin/env python3
import sys
import os
import socket
import struct
import argparse
import threading
import queue
import time
import subprocess
import ctypes
import numpy as np
import sounddevice as sd

# Try importing pyogg
try:
    import pyogg.opus
    PYOGG_AVAILABLE = True
except ImportError:
    PYOGG_AVAILABLE = False

# Opus Decoder Wrapper via ctypes
class OpusDecoderWrapper:
    def __init__(self, sample_rate, channels=1):
        if not PYOGG_AVAILABLE:
            raise Exception("pyogg is not installed or available.")
            
        # Configure ctypes argtypes and restype
        pyogg.opus.opus_decode.argtypes = [
            pyogg.opus.od_p,                     # st
            ctypes.POINTER(ctypes.c_ubyte),      # data
            ctypes.c_int32,                      # len
            ctypes.POINTER(ctypes.c_short),      # pcm
            ctypes.c_int,                        # frame_size
            ctypes.c_int                         # decode_fec
        ]
        pyogg.opus.opus_decode.restype = ctypes.c_int

        err = ctypes.c_int(0)
        self.decoder = pyogg.opus.opus_decoder_create(sample_rate, channels, ctypes.byref(err))
        if err.value != 0:
            raise Exception(f"Failed to create Opus Decoder: error code {err.value}")
        self.sample_rate = sample_rate
        self.channels = channels
        self.max_frame_samples = int(sample_rate * 0.12) # 120ms max frame size
        self.pcm_buffer = (ctypes.c_short * (self.max_frame_samples * channels))()

    def decode(self, packet_bytes):
        data_len = len(packet_bytes)
        data_ptr = (ctypes.c_ubyte * data_len).from_buffer_copy(packet_bytes)
        
        samples_decoded = pyogg.opus.opus_decode(
            self.decoder,
            data_ptr,
            data_len,
            self.pcm_buffer,
            self.max_frame_samples,
            0
        )
        
        if samples_decoded < 0:
            raise Exception(f"Opus decode error: code {samples_decoded}")
            
        return bytes(self.pcm_buffer)[:samples_decoded * self.channels * 2]

    def close(self):
        if hasattr(self, 'decoder') and self.decoder:
            pyogg.opus.opus_decoder_destroy(self.decoder)
            self.decoder = None

def read_exact(sock, num_bytes):
    buf = b''
    while len(buf) < num_bytes:
        chunk = sock.recv(num_bytes - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf

def run_adb(args):
    cmd = ["adb"] + args
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        return res.stdout.strip()
    except subprocess.CalledProcessError as e:
        raise Exception(f"ADB command failed: {e.stderr.strip()}")
    except FileNotFoundError:
        raise Exception("ADB executable not found in PATH. Please install Android Platform Tools.")

def resolve_output_device(device_spec):
    if device_spec is None:
        return None
        
    try:
        devices = sd.query_devices()
    except Exception as e:
        print(f"Error querying audio devices: {e}")
        return None
    
    # Try parsing as integer index first
    try:
        idx = int(device_spec)
        if 0 <= idx < len(devices):
            if devices[idx]['max_output_channels'] > 0:
                return idx
            else:
                print(f"Warning: Device index {idx} is not an output device (max_output_channels = 0).")
        else:
            print(f"Warning: Device index {idx} is out of range.")
    except ValueError:
        pass
        
    # Search by substring (case-insensitive)
    query = device_spec.lower()
    matches = []
    for idx, dev in enumerate(devices):
        if dev['max_output_channels'] > 0:
            if query in dev['name'].lower():
                matches.append((idx, dev['name']))
                
    if not matches:
        print(f"Error: No audio output device matches '{device_spec}'.")
        return None
        
    if len(matches) == 1:
        return matches[0][0]
        
    print(f"Multiple audio output devices matched '{device_spec}':")
    for idx, name in matches:
        print(f"  [{idx}] {name}")
    print(f"Selecting the first one: [{matches[0][0]}] {matches[0][1]}")
    return matches[0][0]

def find_virtual_cable_device():
    try:
        devices = sd.query_devices()
        keywords = ["cable input", "vb-audio virtual cable", "virtual cable", "virtual audio cable", "line 1 (virtual audio cable)"]
        for idx, dev in enumerate(devices):
            if dev['max_output_channels'] > 0:
                name_lower = dev['name'].lower()
                for keyword in keywords:
                    if keyword in name_lower:
                        return idx, dev['name']
    except Exception:
        pass
    return None, None

def install_vb_cable():
    import urllib.request
    import zipfile
    import tempfile
    import shutil
    
    print("\n--- VB-CABLE Virtual Audio Cable Installer ---")
    print("This helper will download and launch the installer for VB-CABLE.")
    print("VB-CABLE is a free/donationware virtual audio driver.")
    print("Once installed, it routes audio from miccpy to a virtual microphone.")
    print("After installation, you MUST restart your computer for it to take effect.")
    
    confirm = input("Do you want to proceed with downloading and installing VB-CABLE? (y/N): ").strip().lower()
    if confirm not in ['y', 'yes']:
        print("Installation cancelled. You can install it manually from https://vb-audio.com/Cable/")
        return False
        
    url = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip"
    temp_dir = tempfile.mkdtemp(prefix="miccpy_vbcable_")
    zip_path = os.path.join(temp_dir, "vbcable.zip")
    
    print(f"Downloading VB-CABLE from {url}...")
    try:
        urllib.request.urlretrieve(url, zip_path)
        print("Download complete. Extracting files...")
        
        extract_dir = os.path.join(temp_dir, "extracted")
        os.makedirs(extract_dir, exist_ok=True)
        with zipfile.ZipFile(zip_path, 'r') as zip_ref:
            zip_ref.extractall(extract_dir)
            
        print("Extraction complete.")
        
        # Determine architecture
        import platform
        is_64bit = platform.machine().endswith('64') or platform.architecture()[0] == '64bit'
        installer_name = "VBCABLE_Setup_x64.exe" if is_64bit else "VBCABLE_Setup.exe"
        installer_path = os.path.join(extract_dir, installer_name)
        
        if not os.path.exists(installer_path):
            print(f"Error: Could not find installer {installer_name} in the extracted folder.")
            return False
            
        print(f"Launching installer: {installer_name}")
        print("IMPORTANT: A Windows UAC dialog will prompt you for Administrator privileges.")
        print("Please accept the prompt, click 'Install Driver' in the setup window, and reboot.")
        
        if sys.platform == 'win32':
            cmd = f'powershell.exe Start-Process "{installer_path}" -Verb RunAs -Wait'
            subprocess.run(cmd, shell=True)
            print("\nInstaller finished or closed. If you completed the installation, please RESTART your computer.")
            print("After rebooting, run 'miccpy --virtual-mic' to route audio to the virtual microphone.")
        else:
            print("Automatic installation is only supported on Windows.")
            
        return True
    except Exception as e:
        print(f"Error during installation: {e}")
        return False
    finally:
        try:
            shutil.rmtree(temp_dir, ignore_errors=True)
        except Exception:
            pass

def main():
    parser = argparse.ArgumentParser(
        description="miccpy: Android Mic Bridge Desktop Client",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--usb", action="store_true", default=True, help="Connect via USB (ADB port forwarding)")
    group.add_argument("--wifi", type=str, metavar="IP", help="Connect via Wi-Fi directly to the device IP")
    
    parser.add_argument("-p", "--port", type=int, default=61394, help="Port of the server")
    parser.add_argument("-c", "--codec", type=str, choices=["opus", "pcm"], default="opus", help="Audio codec to use")
    parser.add_argument("-r", "--rate", type=int, choices=[16000, 24000, 48000], default=48000, help="Audio sample rate (Hz)")
    parser.add_argument("-v", "--volume", type=float, default=1.0, help="Digital gain/volume multiplier")
    parser.add_argument("-b", "--buffer", type=int, default=3, help="Jitter buffer size in packets (higher = more stable, lower = lower latency)")
    parser.add_argument("-m", "--monitor", action="store_true", help="Print real-time bandwidth and latency metrics")
    parser.add_argument("-d", "--device", type=str, help="Specific ADB device serial number")
    parser.add_argument("-o", "--out-device", type=str, help="Name or index of the audio output device (e.g., 'CABLE Input')")
    parser.add_argument("--list-audio-devices", action="store_true", help="List all available audio output devices and exit")
    parser.add_argument("--virtual-mic", action="store_true", help="Automatically search for and route audio to a virtual audio cable")
    
    # Check if --wifi was set or default to --usb
    args = parser.parse_args()
    if args.wifi:
        args.usb = False

    if args.list_audio_devices:
        print("Available Audio Devices:")
        try:
            print(sd.query_devices())
        except Exception as e:
            print(f"Error querying audio devices: {e}")
        sys.exit(0)

    out_device_id = None
    if args.virtual_mic:
        v_idx, v_name = find_virtual_cable_device()
        if v_idx is not None:
            out_device_id = v_idx
            print(f"Virtual microphone device found: {v_name}")
            print("Routing audio output to it. Set your target app (Discord, Zoom, etc.) input to 'CABLE Output'.")
        else:
            print("Error: No virtual audio cable device found.")
            if sys.platform == 'win32':
                if install_vb_cable():
                    sys.exit(0)
                else:
                    sys.exit(1)
            else:
                print("Please install a virtual audio cable (like VB-Cable) for your OS and try again.")
                sys.exit(1)
    elif args.out_device:
        resolved = resolve_output_device(args.out_device)
        if resolved is not None:
            out_device_id = resolved
            try:
                dev_name = sd.query_devices(out_device_id)['name']
                print(f"Routing audio output to: {dev_name}")
            except Exception:
                pass
        else:
            print("Failed to resolve specified audio output device.")
            sys.exit(1)

    print("=== Android Mic Bridge (miccpy) ===")
    
    host = "127.0.0.1"
    port = args.port

    if args.usb:
        print("Configuring USB connection via ADB...")
        try:
            # Check devices
            devices_out = run_adb(["devices"])
            lines = [line for line in devices_out.splitlines()[1:] if line.strip()]
            devices = []
            for line in lines:
                parts = line.split()
                if len(parts) >= 2:
                    devices.append((parts[0], parts[1]))
            
            if not devices:
                print("Error: No Android devices found via ADB. Is USB debugging enabled?")
                sys.exit(1)
            
            selected_device = None
            if args.device:
                matched = [d for d in devices if d[0] == args.device]
                if matched:
                    selected_device = args.device
                else:
                    print(f"Warning: Specified device '{args.device}' not found in ADB devices list.")
                    selected_device = args.device
            else:
                if len(devices) == 1:
                    selected_device = devices[0][0]
                else:
                    print("Multiple ADB devices found:")
                    for idx, (serial, status) in enumerate(devices):
                        print(f"  [{idx}] {serial} ({status})")
                    while True:
                        try:
                            choice = input(f"Select device [0-{len(devices)-1}]: ").strip()
                            choice_idx = int(choice)
                            if 0 <= choice_idx < len(devices):
                                selected_device = devices[choice_idx][0]
                                break
                            else:
                                print(f"Please enter a number between 0 and {len(devices)-1}.")
                        except ValueError:
                            print("Invalid input. Please enter a number.")
                        except (KeyboardInterrupt, EOFError):
                            print("\nCancelled.")
                            sys.exit(1)
            
            device_args = ["-s", selected_device]
            
            # Grant permissions via ADB automatically
            print("Granting required permissions on Android device...")
            try:
                run_adb(device_args + ["shell", "pm", "grant", "com.example.androidmicbridge", "android.permission.RECORD_AUDIO"])
                # POST_NOTIFICATIONS is Android 13+ only.
                try:
                    run_adb(device_args + ["shell", "pm", "grant", "com.example.androidmicbridge", "android.permission.POST_NOTIFICATIONS"])
                except Exception:
                    pass
            except Exception as e:
                print(f"Warning: Could not automatically grant permissions: {e}")
            
            # Setup port forwarding
            print(f"Forwarding TCP port {port}...")
            run_adb(device_args + ["forward", f"tcp:{port}", f"tcp:{port}"])
            
            # Start MainActivity automatically
            print("Launching Android Mic Bridge app on device...")
            is_opus = "true" if args.codec == "opus" else "false"
            run_adb(device_args + [
                "shell", "am", "start", "-n", "com.example.androidmicbridge/.MainActivity",
                "--ez", "autostart", "true",
                "--ez", "isOpus", is_opus,
                "--ei", "sampleRate", str(args.rate),
                "--ei", "port", str(port)
            ])
            
            # Give the app a moment to start/bind
            time.sleep(1.5)
        except Exception as e:
            print(f"ADB Initialization Error: {e}")
            sys.exit(1)
    else:
        host = args.wifi
        print(f"Configuring Wi-Fi connection to {host}:{port}...")

    # Initialize audio queue and sync
    audio_queue = queue.Queue(maxsize=100)
    stop_event = threading.Event()

    # Metrics
    metrics = {
        'bytes_received': 0,
        'packets_received': 0,
        'packets_lost': 0,
        'latency_ms': 0
    }

    # Audio Playback Thread
    def audio_play_loop():
        # Setup sounddevice Output Stream
        try:
            stream = sd.OutputStream(
                device=out_device_id,
                samplerate=args.rate,
                channels=1,
                dtype='int16',
                latency='low'
            )
            stream.start()
        except Exception as e:
            print(f"\nAudio Device Error: Failed to open output stream: {e}")
            stop_event.set()
            return

        buffering = True
        
        while not stop_event.is_set():
            try:
                # If buffering, wait until we accumulate the required buffer packets
                if buffering:
                    if audio_queue.qsize() >= args.buffer:
                        buffering = False
                    else:
                        # Write silence while buffering to prevent sound card underflow crash
                        silence = np.zeros(int(args.rate * 0.02), dtype=np.int16)
                        stream.write(silence)
                        time.sleep(0.01)
                        continue

                # Get block from queue
                try:
                    pcm_data = audio_queue.get(timeout=0.2)
                except queue.Empty:
                    # Underflow: re-enable buffering to prevent stuttering
                    buffering = True
                    continue

                # Apply volume gain
                if args.volume != 1.0:
                    samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)
                    samples *= args.volume
                    np.clip(samples, -32768, 32767, out=samples)
                    pcm_data = samples.astype(np.int16)
                else:
                    pcm_data = np.frombuffer(pcm_data, dtype=np.int16)

                stream.write(pcm_data)
                
            except Exception as e:
                print(f"\nPlayback error: {e}")
                break
                
        stream.stop()
        stream.close()

    # Network Connection Thread
    def net_recv_loop():
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5.0)
        
        # Connect
        print(f"Connecting to server at {host}:{port}...")
        connected = False
        retries = 5
        while not stop_event.is_set() and retries > 0:
            try:
                sock.connect((host, port))
                connected = True
                print("Successfully connected to Android Mic Bridge!")
                break
            except (socket.timeout, ConnectionRefusedError):
                print(f"Connection refused. Retrying... ({retries} left)")
                retries -= 1
                time.sleep(1.5)
                
        if not connected:
            print("\nError: Could not connect to the Android Mic Bridge server.")
            print("Please ensure the Android app is running, 'Start Bridge' is clicked, and permissions are granted.")
            stop_event.set()
            sock.close()
            return

        sock.settimeout(None) # blocking mode for streaming

        # Initialize Opus Decoder if needed
        decoder = None
        if args.codec == "opus":
            try:
                decoder = OpusDecoderWrapper(args.rate, 1)
                print(f"Opus decoder initialized at {args.rate} Hz.")
            except Exception as e:
                print(f"\nOpus initialization failed: {e}")
                print("Falling back to raw PCM mode (please change Android app settings accordingly).")
                args.codec = "pcm"

        # Recording stats
        start_time = time.time()
        last_report_time = start_time
        bytes_in_sec = 0

        while not stop_event.is_set():
            try:
                # Read 4-byte header
                header = read_exact(sock, 4)
                if not header:
                    print("\nConnection closed by device.")
                    break
                    
                length = struct.unpack(">I", header)[0]
                
                # Read payload
                payload = read_exact(sock, length)
                if not payload:
                    print("\nIncomplete packet received. Connection closed.")
                    break

                # Update stats
                metrics['bytes_received'] += length + 4
                metrics['packets_received'] += 1
                bytes_in_sec += length + 4

                # Decode / Process
                if args.codec == "opus" and decoder:
                    try:
                        pcm_bytes = decoder.decode(payload)
                    except Exception as e:
                        metrics['packets_lost'] += 1
                        continue
                else:
                    # PCM data is already in pcm format
                    pcm_bytes = payload

                # Put in queue
                if not audio_queue.full():
                    audio_queue.put(pcm_bytes)
                else:
                    # Drop oldest if full to avoid lag accumulation
                    try:
                        audio_queue.get_nowait()
                    except queue.Empty:
                        pass
                    audio_queue.put(pcm_bytes)

                # Monitor report
                current_time = time.time()
                if args.monitor and current_time - last_report_time >= 1.0:
                    kbps = (bytes_in_sec * 8) / 1000.0
                    qsize = audio_queue.qsize()
                    # Latency estimate: buffer size in queue * 20ms frame
                    latency_est = qsize * 20
                    print(f"\r[Monitor] Bandwidth: {kbps:.1f} kbps | Buffer Queue: {qsize} frames ({latency_est}ms est.) | Lost Packets: {metrics['packets_lost']}", end="", flush=True)
                    bytes_in_sec = 0
                    last_report_time = current_time

            except socket.error as e:
                print(f"\nSocket error: {e}")
                break
            except Exception as e:
                print(f"\nError: {e}")
                break

        if decoder:
            decoder.close()
        sock.close()
        stop_event.set()

    # Start threads
    t_net = threading.Thread(target=net_recv_loop, name="NetworkReceiver")
    t_audio = threading.Thread(target=audio_play_loop, name="AudioPlayback")
    
    t_net.start()
    t_audio.start()

    try:
        while not stop_event.is_set():
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\nStopping client...")
        stop_event.set()

    # Join threads
    t_net.join(timeout=1.0)
    t_audio.join(timeout=1.0)
    print("Bridge client stopped. Goodbye!")

if __name__ == "__main__":
    main()
