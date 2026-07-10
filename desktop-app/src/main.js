const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// State
let activeTab = "usb";
let isConnectingOrConnected = false;
let currentTheme = "dark";

// DOM Elements
let tabUsb, tabWifi, panelUsb, panelWifi;
let adbDeviceSelect, refreshAdbBtn, wifiIpInput;
let portInput, codecSelect, actionBtn;
let audioDeviceSelect, refreshAudioBtn, virtualMicToggle;
let volumeSlider, volumeVal, meterBar;
let connectionBadge, themeToggleBtn, themeIcon;
let statBandwidth, statBuffer, statLost, statusMessage;

// SVG Icons for Theme Toggle
const sunIconSvg = `
  <circle cx="12" cy="12" r="5"></circle>
  <line x1="12" y1="1" x2="12" y2="3"></line>
  <line x1="12" y1="21" x2="12" y2="23"></line>
  <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
  <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
  <line x1="1" y1="12" x2="3" y2="12"></line>
  <line x1="21" y1="12" x2="23" y2="12"></line>
  <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
  <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
`;

const moonIconSvg = `
  <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
`;

// Initialize UI
window.addEventListener("DOMContentLoaded", () => {
  initDOMElements();
  setupEventListeners();
  loadDevices();
  setupTheme();
  setupTauriListeners();
});

function initDOMElements() {
  tabUsb = document.getElementById("tab-usb");
  tabWifi = document.getElementById("tab-wifi");
  panelUsb = document.getElementById("panel-usb");
  panelWifi = document.getElementById("panel-wifi");
  
  adbDeviceSelect = document.getElementById("adb-device-select");
  refreshAdbBtn = document.getElementById("refresh-adb-btn");
  wifiIpInput = document.getElementById("wifi-ip-input");
  
  portInput = document.getElementById("port-input");
  codecSelect = document.getElementById("codec-select");
  actionBtn = document.getElementById("action-btn");
  
  audioDeviceSelect = document.getElementById("audio-device-select");
  refreshAudioBtn = document.getElementById("refresh-audio-btn");
  virtualMicToggle = document.getElementById("virtual-mic-toggle");
  
  volumeSlider = document.getElementById("volume-slider");
  volumeVal = document.getElementById("volume-val");
  meterBar = document.getElementById("meter-bar");
  
  connectionBadge = document.getElementById("connection-badge");
  themeToggleBtn = document.getElementById("theme-toggle");
  themeIcon = document.getElementById("theme-icon");
  
  statBandwidth = document.getElementById("stat-bandwidth");
  statBuffer = document.getElementById("stat-buffer");
  statLost = document.getElementById("stat-lost");
  statusMessage = document.getElementById("status-message");
}

function setupEventListeners() {
  // Tabs
  tabUsb.addEventListener("click", () => setTab("usb"));
  tabWifi.addEventListener("click", () => setTab("wifi"));
  
  // Refreshes
  refreshAdbBtn.addEventListener("click", loadAdbDevices);
  refreshAudioBtn.addEventListener("click", loadAudioDevices);
  
  // Theme Toggle
  themeToggleBtn.addEventListener("click", toggleTheme);
  
  // Volume Slider
  volumeSlider.addEventListener("input", (e) => {
    const val = parseFloat(e.target.value);
    volumeVal.textContent = val.toFixed(1) + "x";
    // Send volume updates in real-time if stream is active
    invoke("set_volume", { volume: val }).catch(console.error);
  });
  
  // Virtual Mic Toggle
  virtualMicToggle.addEventListener("change", (e) => {
    audioDeviceSelect.disabled = e.target.checked;
  });
  
  // Primary Connection Button
  actionBtn.addEventListener("click", handleAction);
}

// Switch tabs USB / Wi-Fi
function setTab(tab) {
  if (isConnectingOrConnected) return; // Prevent changing tabs during connection
  activeTab = tab;
  if (tab === "usb") {
    tabUsb.classList.add("active");
    tabWifi.classList.remove("active");
    panelUsb.classList.add("active");
    panelWifi.classList.remove("active");
  } else {
    tabUsb.classList.remove("active");
    tabWifi.classList.add("active");
    panelUsb.classList.remove("active");
    panelWifi.classList.add("active");
  }
}

// Load Devices from Rust Backend
async function loadDevices() {
  await Promise.all([loadAdbDevices(), loadAudioDevices()]);
}

async function loadAdbDevices() {
  adbDeviceSelect.innerHTML = '<option value="">Buscando dispositivos...</option>';
  try {
    const devices = await invoke("get_adb_devices");
    adbDeviceSelect.innerHTML = "";
    if (devices.length === 0) {
      adbDeviceSelect.innerHTML = '<option value="">Nenhum dispositivo encontrado</option>';
    } else {
      devices.forEach((dev) => {
        const opt = document.createElement("option");
        opt.value = dev.serial;
        opt.textContent = `${dev.serial} (${dev.status})`;
        adbDeviceSelect.appendChild(opt);
      });
    }
  } catch (err) {
    adbDeviceSelect.innerHTML = '<option value="">Erro ao buscar dispositivos</option>';
    showStatus(`Erro ao buscar ADB: ${err}`, true);
  }
}

async function loadAudioDevices() {
  audioDeviceSelect.innerHTML = '<option value="">Carregando saídas...</option>';
  try {
    const devices = await invoke("get_audio_devices");
    audioDeviceSelect.innerHTML = "";
    if (devices.length === 0) {
      audioDeviceSelect.innerHTML = '<option value="">Sem saídas de áudio</option>';
    } else {
      devices.forEach((dev) => {
        const opt = document.createElement("option");
        opt.value = dev.name;
        opt.textContent = dev.name;
        if (dev.is_default) {
          opt.selected = true;
          opt.textContent += " (Padrão)";
        }
        audioDeviceSelect.appendChild(opt);
      });
    }
  } catch (err) {
    audioDeviceSelect.innerHTML = '<option value="">Erro ao ler saídas</option>';
    showStatus(`Erro ao buscar saídas: ${err}`, true);
  }
}

// Theme handling
function setupTheme() {
  const savedTheme = localStorage.getItem("theme") || "dark";
  setTheme(savedTheme);
}

function setTheme(theme) {
  currentTheme = theme;
  localStorage.setItem("theme", theme);
  if (theme === "light") {
    document.body.classList.remove("dark-theme");
    document.body.classList.add("light-theme");
    themeIcon.innerHTML = moonIconSvg;
  } else {
    document.body.classList.remove("light-theme");
    document.body.classList.add("dark-theme");
    themeIcon.innerHTML = sunIconSvg;
  }
}

function toggleTheme() {
  setTheme(currentTheme === "dark" ? "light" : "dark");
}

// Status notifications
function showStatus(msg, isError = false) {
  statusMessage.textContent = msg;
  statusMessage.style.color = isError ? "var(--danger-color)" : "var(--text-secondary)";
}

// Primary connection action
async function handleAction() {
  if (isConnectingOrConnected) {
    // Stop the bridge
    showStatus("Parando conexão...");
    try {
      await invoke("stop_bridge");
    } catch (err) {
      showStatus(`Erro ao parar: ${err}`, true);
    }
  } else {
    // Start the bridge
    const port = parseInt(portInput.value);
    const codec = codecSelect.value;
    const volume = parseFloat(volumeSlider.value);
    const virtualMic = virtualMicToggle.checked;
    const outDevice = audioDeviceSelect.value;
    
    let ip = "";
    let device = "";
    
    if (activeTab === "usb") {
      device = adbDeviceSelect.value;
      if (!device) {
        showStatus("Por favor, selecione um dispositivo Android via USB.", true);
        return;
      }
    } else {
      ip = wifiIpInput.value.trim();
      if (!ip) {
        showStatus("Por favor, insira o endereço IP do celular.", true);
        return;
      }
    }

    showStatus("Iniciando ponte de áudio...");
    updateUIConnectionState("connecting");

    const config = {
      is_usb: activeTab === "usb",
      device,
      ip,
      port,
      codec,
      volume,
      out_device: outDevice,
      virtual_mic: virtualMic
    };

    try {
      await invoke("start_bridge", { config });
    } catch (err) {
      updateUIConnectionState("disconnected");
      showStatus(`Erro ao conectar: ${err}`, true);
    }
  }
}

// Update connection state in UI
function updateUIConnectionState(state) {
  connectionBadge.className = "badge " + state;
  if (state === "disconnected") {
    connectionBadge.textContent = "Desconectado";
    actionBtn.textContent = "Iniciar Microfone";
    actionBtn.className = "primary-btn";
    isConnectingOrConnected = false;
    // Clear meter
    meterBar.style.width = "0%";
    
    // Enable inputs
    tabUsb.disabled = false;
    tabWifi.disabled = false;
    adbDeviceSelect.disabled = false;
    wifiIpInput.disabled = false;
    portInput.disabled = false;
    codecSelect.disabled = false;
    refreshAdbBtn.disabled = false;
  } else if (state === "connecting") {
    connectionBadge.textContent = "Conectando";
    actionBtn.textContent = "Conectando...";
    actionBtn.className = "primary-btn";
    isConnectingOrConnected = true;
    
    // Disable inputs during connection setup
    tabUsb.disabled = true;
    tabWifi.disabled = true;
    adbDeviceSelect.disabled = true;
    wifiIpInput.disabled = true;
    portInput.disabled = true;
    codecSelect.disabled = true;
    refreshAdbBtn.disabled = true;
  } else if (state === "connected") {
    connectionBadge.textContent = "Conectado";
    actionBtn.textContent = "Parar Microfone";
    actionBtn.className = "primary-btn stop";
    isConnectingOrConnected = true;
  }
}

// Setup event listeners for backend events
async function setupTauriListeners() {
  // Listen to connection status events from Rust
  await listen("connection-status", (event) => {
    const status = event.payload; // "disconnected" | "connecting" | "connected"
    updateUIConnectionState(status);
    if (status === "connected") {
      showStatus("Conectado! Áudio sendo transmitido.");
    } else if (status === "disconnected") {
      showStatus("Ponte de áudio finalizada.");
    }
  });

  // Listen to audio metrics (bandwidth, lost packets, latency)
  await listen("audio-metrics", (event) => {
    const metrics = event.payload; // { bandwidth, buffer_size, buffer_ms, lost_packets }
    statBandwidth.textContent = metrics.bandwidth.toFixed(1) + " kbps";
    statBuffer.textContent = metrics.buffer_ms + " ms";
    statLost.textContent = metrics.lost_packets;
  });

  // Listen to signal level updates (0.0 to 1.0)
  await listen("audio-level", (event) => {
    const level = event.payload; // f32
    // Math.min(100, level * 100) to keep it inside bounds
    const percent = Math.min(100, Math.max(0, level * 100));
    meterBar.style.width = percent + "%";
  });

  // Listen to standard logging / status messages from Rust
  await listen("backend-message", (event) => {
    const msg = event.payload;
    showStatus(msg);
  });
}
