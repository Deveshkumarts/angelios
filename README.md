# iTANTRA // AI-Powered Adaptive Multilingual Semantic Communication
### Smart India Hackathon (SIH) Problem Statement: SIH26173

> **"CONNECT. COMMUNICATE. STAY SAFE. ANYWHERE."**  
> *ISRO-Grade Tactical & Disaster Mesh Communication System*

---

## Overview

During major natural catastrophes (floods, cyclones, earthquakes) or tactical operations in remote border terrains, terrestrial telecommunication towers, fiber backbones, and satellite uplinks frequently experience complete blackouts. 

**iTANTRA** is a complete, production-grade, offline-first Android application designed to operate in total infrastructure absence. By transforming standard smartphones into an autonomous, decentralised, multi-hop mesh network, iTANTRA enables life-critical communication across peer devices using **Bluetooth Low Energy (BLE 5.0)**, **Wi-Fi Direct (P2P)**, and **LoRa (SX1262)** transceivers.

Rather than streaming fragile, high-bandwidth raw voice audio over degraded radio links, iTANTRA utilizes an on-device **Semantic Communication Engine**:
$$\text{Voice} \longrightarrow \text{Offline STT} \longrightarrow \text{Meaning (Intents + Entities)} \longrightarrow \text{Adaptive Compression} \longrightarrow \text{CRC16 Packet} \longrightarrow \text{Mesh Relays}$$

At the receiver node, the compressed semantic tokens are decoded and reconstructed into fluent natural speech in the recipient's preferred native language via on-device **Offline Text-to-Speech (TTS)**.

---

## Key Technical Innovations

### 1. 99.94% Bandwidth Reduction via Semantic Communication
- **Standard 2-Second 16kHz 16-bit Mono PCM Voice:** $16000 \times 2 \times 2 = 64,000\text{ Bytes}$
- **Deflate Compressed Text:** $\approx 64\text{ Bytes}$ ($99.90\%$ reduction)
- **iTANTRA Semantic Intent & Entity Packet:** $\approx 38\text{ Bytes}$ ($99.94\%$ reduction)
- **iTANTRA Ultra-Compact Emergency SOS Bitstream:** $12\text{ Bytes}$ ($99.98\%$ reduction)

### 2. 10 Supported Indian Languages (100% Offline)
Full end-to-end voice capture, semantic extraction, and local speech synthesis across:
1. **Hindi** (हिन्दी)
2. **Tamil** (தமிழ்)
3. **Telugu** (తెలుగు)
4. **Kannada** (ಕನ್ನಡ)
5. **Malayalam** (മലയാളം)
6. **Bengali** (বাংলা)
7. **Marathi** (मराठी)
8. **Gujarati** (ગુજરાતી)
9. **Odia** (ଓଡ଼ିଆ)
10. **English**

Punjabi (ਪੰਜਾਬੀ) is also supported as an extra language.

### 2a. Open-source, on-device speech (no proprietary SDKs, no internet)
| Stage | Model | Runtime | Licence |
|---|---|---|---|
| Pause detection | Silero VAD | sherpa-onnx | MIT |
| Speech-to-text | AI4Bharat IndicConformer (CTC, int8); English: NVIDIA NeMo fast-conformer CTC | sherpa-onnx (ONNX Runtime) | MIT / CC-BY-4.0 |
| Text-to-speech | Meta MMS-TTS (VITS), one voice per language | sherpa-onnx | CC-BY-NC-4.0 (non-commercial) |

- **Sentence streaming:** while PTT is held, the VAD closes a sentence at every pause (0.6 s); that sentence is recognised and transmitted immediately instead of waiting for release.
- **Phone mode:** switch PTT to PHONE on the Talk screen for an open mic (voice-call input path with echo cancellation); every sentence is sent on a pause, and the mic is muted while incoming speech plays.
- **Alerts:** SOS/distress messages are spoken on the alarm channel at maximum alarm volume with exclusive audio focus, jump ahead of queued speech and cannot be cut off.
- **Voice notes:** each received message is kept as a WAV voice note, so replay is instant.
- **Size:** each language pack is about 310 MB (STT 198 MB + TTS 114 MB; English 290 MB). Packs are not bundled in the APK; one language's STT and TTS are loaded at a time.
- **Round-trip benchmark** (`tools/eval_speech_roundtrip.py`, MMS speaks 5 test sentences and IndicConformer transcribes them, laptop CPU): Hindi WER 0%, Tamil WER 1.4%, English WER 27% (the English MMS voice is the weak link); STT RTF about 0.04, TTS RTF about 0.3. Synthetic speech is cleaner than a real microphone, so measure real speakers for final numbers.

**Installing language packs**
```powershell
python tools\download_language_packs.py hi ta en     # or: all   (downloads to models\packs, git-ignored)
.\INSTALL_LANGUAGE_PACKS.bat hi ta en                 # copies them to a USB-connected phone
```
Alternatively, start the base station on the laptop and tap **ME > On-device speech packs > Get pack from base station** on the phone; the pack is pulled over the local Wi-Fi/hotspot (`GET /api/speechpack/...`).

### 3. Adaptive Communication Engine
Computes a Link Quality Score (LQS) from measured link behaviour:
- **Packet loss**: the share of expected 1-per-second peer HELLO beacons that actually arrived in the last 10 s.
- **Latency and jitter**: round-trip times of HEARTBEAT pings and message ACKs, which don't depend on clock sync.
- **RSSI**: Wi-Fi association RSSI, BLE scan RSSI, or LoRa packet RSSI reported by the radio bridge.

The LQS selects the payload format:


| Link Quality | Score Range | Strategy Selected | Wire Payload Format |
|---|---|---|---|
| **GOOD** | $\ge 0.75$ | Full Fidelity UTF-8 Text | Uncompressed text |
| **MEDIUM** | $0.50 - 0.74$ | Compressed Text | Deflate / Huffman Byte Stream |
| **POOR** | $0.25 - 0.49$ | Compact Semantic Representation | Binary Intent + Entities ($< 40\text{B}$) |
| **EMERGENCY** | $< 0.25$ | Emergency SOS Beacon | 12-byte hardware bitstream |

### 4. Wire-Level Binary Packet Specification
Packets are transmitted with strict byte alignment and CRC16-CCITT integrity verification:
```
Offset (Bytes)   Field Name         Description
[0..1]           MAGIC_HEADER       0x53, 0x49 ("SI")
[2]              VERSION            1 = plaintext (SOS only), 2 = AES-256-GCM encrypted payload
[3..10]          MESSAGE_ID         Unique 64-bit Long timestamp/UUID
[11..18]         SENDER_ID          8-byte ASCII node identifier
[19..26]         RECEIVER_ID        8-byte target node ID or "BRDCAST_"
[27]             LANGUAGE_ID        1-byte language code (0 to 9)
[28]             PRIORITY           NORMAL (0), IMPORTANT (1), EMERGENCY (2)
[29]             TTL                Time-To-Live hop count (e.g. 5)
[30]             PACKET_TYPE        NORMAL_TEXT (1), COMPRESSED (2), SEMANTIC (3), EMERGENCY (4),
                                    SOS (5), ACK (6), HELLO (8), HEARTBEAT (9)
[31..32]         PAYLOAD_LENGTH     16-bit unsigned short payload size
[33..N]          PAYLOAD            N bytes payload data
[N+1..N+2]       CRC16              16-bit CCITT checksum (Polynomial: 0x1021)
```

Encrypted packets carry `[12B IV][ciphertext][16B GCM tag]` as the payload. The immutable header fields (message ID, sender, receiver, language, priority, type) are authenticated as associated data, so relays can decrement the TTL but can't forge the sender or recipient.

### 5. Multi-Hop Mesh Protocol
- **Multi-bearer flooding:** every packet goes out on all available bearers (Wi-Fi LAN/hotspot, BLE GATT, LoRa) at once.
- **Duplicate suppression:** a sliding-window cache keyed by `(sender, message ID)` stops broadcast storms and routing loops.
- **TTL decrement:** forwarding nodes decrement the TTL and discard expired packets.
- **Delivery confirmation:** receivers ACK every content packet. The sender retries up to 3 times (2.5 s apart) and only then marks the message FAILED. A message is shown as delivered only after a real ACK.
- **Priority queue:** outbound `EMERGENCY` packets jump ahead of normal traffic.

### 6. Emergency SOS Hardware Command
- **GPS location** (last-known GNSS fix, no internet needed) is embedded in the beacon as a 9-byte block.
- SOS beacons are deliberately **not encrypted**, so any iTANTRA device in range, including rescuers outside the team, can receive them.
- The audit log records how many peers actually ACKed each SOS.
- Hardware camera strobe (`CameraManager.setTorchMode`)
- Acoustic siren frequency modulator (`AudioTrack` 600Hz / 900Hz alternating tone)
- Haptic buzzer (SOS Morse code `. . . - - - . . .`)
- Queue-bypass multi-hop emergency broadcast

### 7. Encryption
- **Over the air:** AES-256-GCM. The key is derived (PBKDF2-HMAC-SHA256) from a **team mesh passphrase** set in *Settings*, and every device in a team uses the same passphrase. Packets from other teams or tampered packets fail authentication and are dropped. Until you set a passphrase, a public default is used and the app shows a warning.
- **At rest:** message history and the SOS log are stored in AES-256-GCM files whose key is generated inside the **Android Keystore** and never leaves it. The mesh passphrase is stored the same way.

---

## Screen Directory

1. **Console (`HomeScreen`)**: Immediate answer to *"Can I communicate right now?"*, active RF status, link quality score, and quick PTT.
2. **PTT Walkie-Talkie (`TalkScreen`)**: Real-time microphone RMS amplitude, dynamic waveform visualizer, recording duration counter, packet size, and compression ratio.
3. **Mesh Command (`NetworkScreen`)**: Rotating tactical radar sweep with concentric range rings, nearby node blips, active route visualization, and signal strength.
4. **Communication History (`MessagesScreen`)**: Encrypted chat bubbles with expandable **Technical Details Drawer** for hackathon judges (STT latency, semantic NLP time, compression time, network transit time, reassembly time, TTS time, total latency).
5. **SOS Command Center (`EmergencyScreen`)**: Deliberate confirmation SOS button, hardware strobe/siren toggles, and local emergency audit logs.
6. **Device Directory (`DevicesScreen`)**: Discovered nearby nodes, signal strength, battery %, and priority routing toggles.
7. **Diagnostics & Benchmarks (`DiagnosticsScreen`)**: live on-device measurements of STT latency and confidence, TTS start latency and real-time factor, cross-device latency, CPU/RAM, and link metrics. No canned numbers are shown. WER and MOS need labelled test sets and human listeners, so the app doesn't report them.
8. **SIH Demonstration Studio (`JudgeDemoScreen`)**: Dual-node interactive testbench (Phone A $\rightarrow$ Phone B) allowing judges to select languages, simulate network degradation, and observe step-by-step semantic voice reconstruction.
9. **Onboarding (`OnboardingScreen`)**: 5-step aerospace mission brief.
10. **Settings (`SettingsScreen`)**: Hardware radio configuration, battery optimization thresholds, and encryption status.

---

## Build & Test Instructions

### Prerequisites
- Android SDK 34 (Android 14) / Min SDK 26 (Android 8.0 Oreo)
- JDK 17 (point `JAVA_HOME` at it, e.g. Android Studio's bundled `jbr`)
- Gradle 8.5 (Included via `gradlew.bat`)

### Running Unit Tests
```powershell
$env:JAVA_HOME="<path to JDK 17>"
.\gradlew.bat :app:testDebugUnitTest
```

### Assembling Debug APK
```powershell
.\gradlew.bat :app:assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `INSTALL_APK_TO_PHONE.bat` installs it over ADB. APKs aren't committed to git; CI publishes one as a build artifact.

### Release Signing
Copy `keystore.properties.example` to `keystore.properties` (it's git-ignored) and fill in your keystore details. Without it, `assembleRelease` falls back to the debug key and prints a warning.

---

## Edge AI Base Station (optional)

A laptop with a GPU on the same Wi-Fi or hotspot can provide higher-quality neural translation (AI4Bharat IndicTrans2 / IndicConformer / IndicF5):

```powershell
pip install -r backend/requirements.txt
python backend/download_models.py      # gated models: needs a Hugging Face token
.\start_sih_model_server.bat            # serves on port 5000
```

Phones find it through the Wi-Fi gateway or common hotspot addresses, or you can enter its IP in the app. The app uses `GET /api/status`, `POST /api/translate` and `GET /api/pack/{flores_tag}`. If the server is unreachable, or has no model for a language pair, the app falls back to downloaded translation packs and its built-in phrase and grammar engines. It also serves on-device speech packs at `GET /api/speechpack/{lang}/{stt|tts}/{file}` from `models/packs`.

## LoRa Radio Bridge (optional)

An ESP32 + SX1262 board flashed with `firmware/lora_bridge` and connected over USB-OTG adds a long-range LoRa bearer. See [firmware/lora_bridge/README.md](firmware/lora_bridge/README.md). Frames are limited to 255 bytes, so only compact packets (semantic, ACK, SOS) go over LoRa.

## Known Limitations
- **Speech packs** must be installed per language (about 310 MB each) before that language can speak or listen; with both models loaded the app uses roughly 450 MB of RAM, which is tight on 2 GB phones.
- **Translation** has no on-device neural model: free-form sentences outside the curated phrases translate best when the base station (IndicTrans2) is reachable.
- **MMS-TTS voices** are licensed CC-BY-NC-4.0 (non-commercial use only).
- **BLE** works phone-to-phone with a GATT write per peer (up to 7 peers per send). It's slower than Wi-Fi and meant as a fallback bearer.
- **Wi-Fi** works over a shared hotspot or LAN (UDP broadcast + TCP + mDNS). It doesn't form Wi-Fi Direct groups by itself.
- The **mesh passphrase** is a shared team secret with no per-user keys or forward secrecy.
