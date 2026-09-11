# SentinelAgent — Android Debug Capture App

**Package:** `com.sentinelagent.debug`  
**Min SDK:** 26 (Android 8.0 Oreo)  
**Target SDK:** 34 (Android 14)  
**Language:** Kotlin  
**Build System:** Gradle with Kotlin DSL  

---

## Overview

SentinelAgent is a foreground-service-based Android debug app that periodically captures:

- **Screenshots** — via MediaProjection API + VirtualDisplay + ImageReader
- **Camera Stills** — via Camera2 API (front or rear camera)
- **Microphone Audio** — via AudioRecord, chunked into 5-second WAV files
- **Sensor Data** — accelerometer, gyroscope, light, proximity via SensorManager

All captured data is uploaded to a configurable HTTPS endpoint using OkHttp.

---

## Project Structure

```
SentinelAgent/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/sentinelagent/debug/
        │   ├── MainActivity.kt          — UI + permission handling
        │   ├── CaptureService.kt        — Foreground service, orchestration
        │   ├── UploadManager.kt         — OkHttp uploads with retry
        │   ├── ScreenCaptureManager.kt  — MediaProjection screen capture
        │   ├── CameraCaptureManager.kt  — Camera2 still capture
        │   ├── AudioCaptureManager.kt   — AudioRecord + WAV chunking
        │   ├── SensorCaptureManager.kt  — SensorManager data collection
        │   └── NotificationHelper.kt    — Persistent notification
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            ├── values/themes.xml
            ├── values/colors.xml
            ├── drawable/ic_notification.xml
            ├── drawable/ic_launcher_background.xml
            ├── drawable/ic_launcher_foreground.xml
            └── mipmap-*/ic_launcher*.xml
```

---

## Architecture

### MainActivity
- Displays configuration UI (server URL, interval, camera mode, mic/sensor toggles)
- Requests all necessary runtime permissions
- Launches MediaProjection permission dialog (required for screen capture)
- Starts/stops `CaptureService` via explicit intents

### CaptureService (Foreground Service)
- Type: `mediaProjection | microphone | camera | specialUse`
- Runs persistent notification via `NotificationHelper`
- Initializes all capture managers
- Runs 4 independent coroutine loops:
  - **Screenshot loop** — every N seconds
  - **Camera loop** — every N seconds (if enabled)
  - **Audio loop** — checks every 1 second for completed 5-second WAV chunks
  - **Sensor loop** — every N seconds (if enabled)

### UploadManager (Singleton)
- Uses OkHttp with 30s connect / 60s read/write timeouts
- Multipart form-data for images (JPEG quality 80) and audio (WAV)
- JSON body for sensor data
- 3 retries with exponential backoff: 1s → 2s → 4s
- All uploads on `Dispatchers.IO`

### ScreenCaptureManager
- Wraps `MediaProjection` + `VirtualDisplay` + `ImageReader`
- Format: `PixelFormat.RGBA_8888`
- Handles row stride padding correctly
- Returns `Bitmap?` — caller must recycle

### CameraCaptureManager
- Camera2 API with background `HandlerThread`
- Finds camera by facing direction (front/rear)
- Uses `TEMPLATE_STILL_CAPTURE` for single stills
- Semaphore-based synchronization for capture completion
- Properly closes camera + releases resources on stop

### AudioCaptureManager
- `AudioRecord` with 16kHz, Mono, PCM_16BIT
- Background recording thread accumulates PCM data
- When 160,000 bytes (5 seconds) accumulate, converts to WAV
- WAV header: standard 44-byte PCM WAV (little-endian)
- `getAudioChunk()` returns and clears latest chunk (no re-upload)

### SensorCaptureManager
- Registers listeners for 4 sensor types
- Stores latest readings in `ConcurrentHashMap<Int, SensorReading>`
- `getSensorDataJson()` builds JSON with all available sensor readings
- Gracefully handles absent sensors (e.g., no gyroscope on some devices)

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Upload data to server |
| `FOREGROUND_SERVICE` | Run persistent service |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ screen capture |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 10+ mic in foreground |
| `FOREGROUND_SERVICE_CAMERA` | Android 10+ camera in foreground |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Fallback for older Android |
| `RECORD_AUDIO` | Microphone capture |
| `CAMERA` | Camera still capture |
| `POST_NOTIFICATIONS` | Android 13+ notification permission |
| `WAKE_LOCK` | Keep CPU alive during capture |

---

## Upload API Format

### Screenshot / Camera Still
```
POST <server_url>
Content-Type: multipart/form-data

device_id: <android_id>
type: "screenshot" | "camera"
timestamp: <epoch_millis>
image: <filename.jpg> (JPEG, quality 80)
```

### Audio Chunk
```
POST <server_url>
Content-Type: multipart/form-data

device_id: <android_id>
type: "audio"
timestamp: <epoch_millis>
audio: chunk.wav (WAV, 16kHz mono PCM_16BIT, ~5 seconds)
```

### Sensor Data
```
POST <server_url>
Content-Type: application/json

{
  "device_id": "abc123",
  "type": "sensors",
  "timestamp": 1700000000000,
  "sensors": {
    "accelerometer": {"x": 0.1, "y": 0.2, "z": 9.8, "accuracy": 3},
    "gyroscope": {"x": 0.01, "y": 0.02, "z": 0.03, "accuracy": 3},
    "light": {"lux": 450.0, "accuracy": 3},
    "proximity": {"distance_cm": 5.0, "accuracy": 3}
  }
}
```

---

## Build Instructions

1. Open in Android Studio Hedgehog (2023.1.1) or newer
2. Ensure `local.properties` has valid `sdk.dir` path
3. Sync Gradle
4. Build: `./gradlew assembleDebug`
5. Install: `./gradlew installDebug`

---

## Testing Checklist

- [ ] Grant all permissions on first run
- [ ] Screen capture permission dialog appears
- [ ] Notification appears with "Stop" action button
- [ ] Upload server receives multipart POST for screenshots
- [ ] Upload server receives multipart POST for camera images
- [ ] Upload server receives WAV files for audio chunks
- [ ] Upload server receives JSON for sensor data
- [ ] Stop button in notification stops all capture loops
- [ ] Stop button in app UI stops service
- [ ] App survives screen rotation
- [ ] App survives going to background

---

## ⚠️ Legal Notice

This app captures sensitive device data including screen content, camera images,
audio, and location-adjacent sensor data. Use only on devices you own or have
explicit authorization to monitor. Unauthorized use may violate privacy laws
including GDPR, CCPA, CFAA, and local equivalents.
