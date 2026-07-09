# Android Mic Bridge (miccpy)

**Android Mic Bridge** (`miccpy`) is a lightweight, low-latency, open-source tool inspired by `scrcpy` that streams audio from your Android device's microphone directly to your computer.

Use your mobile device as a high-quality PC microphone over **USB** (via ADB) or **Wi-Fi** with no cloud accounts, no third-party servers, and absolute local privacy.

---

## Features

- **Real-Time Capture**: Captures clear audio via the Android `AudioRecord` API (using `VOICE_COMMUNICATION` processing for automatic echo cancellation and noise suppression).
- **Opus & PCM encoding**: Supports high-quality Opus compression (using Android's native `MediaCodec`) to minimize bandwidth or uncompressed raw PCM for minimal CPU usage.
- **ADB Port Forwarding**: Automatic port forwarding (`adb forward tcp:61394`) and app launch when connecting over USB.
- **Wi-Fi Streaming**: Direct TCP connection over local network for wire-free microphoning.
- **Cross-Platform PC Client**: Written in Python 3.12, utilizing `sounddevice` (PortAudio) for ultra-low latency and `pyogg` / `libopus` for decoding.
- **Jitter Buffer**: Dynamic buffering queue to ensure a smooth audio flow even over unstable networks.
- **Gain & Volume Control**: Client-side digital volume multiplier with clip protection.

---

## Architecture

```
[ Android Device (Server) ]
        │ (AudioRecord MIC / VOICE_COMMUNICATION)
        ▼
[ Audio Encoder (Opus / PCM) ]
        │ (TCP packets formatted with 4-byte length headers)
        ▼
  [ TCP Socket Server (61394) ]
        │  ▲
        │  │  ADB USB Port Forwarding
        │  │         OR
        │  │  Local Wi-Fi Network
        ▼  │
  [ TCP Socket Client ]
        │
        ▼
[ Opus / PCM Decoder ]
        │ (Digital Gain / Clipping filter)
        ▼
[ PortAudio Playback Output ] (Local Speakers/Virtual Audio Cable)
```

---

## Installation

### 1. Build and Install Android App
Ensure your Android device has **USB Debugging** enabled and is connected to your computer.

From the root directory:
```bash
cd android
./gradlew installDebug
```

### 2. Set Up Desktop Client Dependencies
Make sure you have **Python 3.12+** installed. Install the required libraries:
```bash
pip install sounddevice pyogg numpy
```

---

## Usage

You can use the root-level scripts `miccpy.bat` (Windows) or `miccpy` (Linux/macOS) to start the connection easily.

### Connect via USB (Default)
Run:
```cmd
miccpy
```
This automatically:
1. Detects your Android device via ADB.
2. Forwards TCP port `61394` from phone to PC.
3. Launches the Android Mic Bridge app on your phone.
4. Connects, starts the streaming, and plays it back on your PC.

### Connect via Wi-Fi
Ensure your phone is on the same local network as your PC. Check the local IP address on the Android app, start the bridge on your phone, and run:
```cmd
miccpy --wifi <PHONE_IP_ADDRESS>
```

### Options and Customization

```bash
miccpy --help
```

- `--port <port>`: Use a custom TCP port (default: `61394`).
- `--codec <opus|pcm>`: Set the decoding format (default: `opus`).
- `--rate <16000|24000|48000>`: Set the sampling rate in Hz (default: `48000`).
- `--volume <multiplier>`: Digitally adjust gain (e.g. `--volume 1.5` to boost, `--volume 0.5` to reduce).
- `--buffer <count>`: Set the size of the jitter buffer in packets (default: `3`).
- `--monitor`: Enable real-time metrics showing bandwidth, buffer size, and dropped packets.

---

## License

This project is open-source under the **MIT License**.
