# SentinelAgent — Android Debug Capture App

**Package:** `com.sentinelagent.debug`  
**Min SDK:** 26 (Android 8.0 Oreo)  
**Target / Compile SDK:** 34 (Android 14)  
**Language:** Kotlin  
**Build system:** Gradle 8.6 + AGP 8.3.2 (Kotlin DSL, version catalog)

Compatible with **Code on the Go (CoGo)** on Android (AGP 7.3–8.11, Kotlin ~1.9.x).

---

## Overview

SentinelAgent is a foreground-service Android debug app that periodically captures:

- **Screenshots** — MediaProjection + VirtualDisplay + ImageReader
- **Camera stills** — Camera2 API (front or rear)
- **Microphone audio** — AudioRecord, 5-second WAV chunks @ 16 kHz mono
- **Sensor data** — accelerometer, gyroscope, light, proximity

Captured data is uploaded to a configurable HTTPS endpoint with OkHttp (multipart for media, JSON for sensors).

---

## Clone and build in Code on the Go (Android)

1. Install [Code on the Go](https://appdevforall.org/code-on-the-go/) (release 26.37 or newer).
2. Clone this repository from CoGo (Git → Clone) or copy the project folder onto the device.
3. Open the project root (the folder that contains `settings.gradle.kts` and `gradlew`).
4. Let CoGo sync Gradle. On first build it downloads the Gradle 8.6 distribution and dependencies (needs network once).
5. Build **Debug** and run/install on the device.

CLI equivalent inside CoGo’s terminal (or any machine with the Android SDK):

```bash
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug   # if a device/emulator is attached
```

### CoGo notes

| Item | This project |
|---|---|
| AGP | `8.3.2` (within CoGo’s 7.3.0–8.11.0 range) |
| Gradle | `8.6` (wrapper included: `gradlew` + `gradle-wrapper.jar`) |
| Kotlin | `1.9.22` (aligned with CoGo’s ~1.9.2 toolchain) |
| JDK | Java 17 language level (CoGo ships JDK 21) |
| `local.properties` | **Not committed.** CoGo / Android Studio create `sdk.dir` automatically. |
| RAM | `gradle.properties` caps the daemon at ~1.5 GB for on-device builds. |

> **Upcoming CoGo toolchain (Sept 2026):** a later CoGo release moves to Gradle 9.6 / AGP 9.3 / Kotlin 2.3. Do **not** apply that migration until you upgrade CoGo itself. See the [CoGo migration guide](https://github.com/appdevforall/CodeOnTheGo/wiki/IMPORTANT:-Fix-project-breaking-changes-after-the-Gradle-AGP-Kotlin-toolchain-upgrade).

---

## Build with Android Studio (desktop)

1. Open the project in Android Studio Hedgehog (2023.1.1) or newer.
2. Ensure `local.properties` contains a valid `sdk.dir=...` (Studio writes this on open).
3. Sync Gradle → **Build → Make Project**, or:

```bash
./gradlew assembleDebug
```

---

## Project structure

```
SentinelAgent/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/sentinelagent/debug/
        │   ├── MainActivity.kt
        │   ├── CaptureService.kt
        │   ├── UploadManager.kt
        │   ├── ScreenCaptureManager.kt
        │   ├── CameraCaptureManager.kt
        │   ├── AudioCaptureManager.kt
        │   ├── SensorCaptureManager.kt
        │   └── NotificationHelper.kt
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,themes,colors}.xml
            ├── drawable/
            └── mipmap-anydpi-v26/
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Upload data to server |
| `FOREGROUND_SERVICE` | Persistent capture service |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture FGS (Android 14+) |
| `FOREGROUND_SERVICE_MICROPHONE` | Mic FGS |
| `FOREGROUND_SERVICE_CAMERA` | Camera FGS |
| `RECORD_AUDIO` | Microphone capture |
| `CAMERA` | Camera stills |
| `POST_NOTIFICATIONS` | Notification on Android 13+ |
| `WAKE_LOCK` | Keep CPU awake during capture |
| `ACCESS_NETWORK_STATE` | Connectivity checks |

Runtime grants: camera, mic, notifications. Screen capture uses the system MediaProjection consent dialog.

---

## Upload API

### Screenshot / camera still

```
POST <server_url>
Content-Type: multipart/form-data

device_id, type ("screenshot"|"camera"), timestamp, image (JPEG q=80)
```

### Audio chunk

```
POST <server_url>
Content-Type: multipart/form-data

device_id, type ("audio"), timestamp, audio (WAV 16 kHz mono PCM, ~5 s)
```

### Sensor data

```
POST <server_url>
Content-Type: application/json

{
  "device_id": "...",
  "type": "sensors",
  "timestamp": 1700000000000,
  "sensors": { "accelerometer": {...}, "gyroscope": {...}, ... }
}
```

---

## Troubleshooting

The status card in the app now shows the real service state, and every start
failure is reported there (in red) instead of crashing silently:

| Status text | Meaning |
|---|---|
| `Starting…` | Consent granted, service start in progress |
| `Monitoring active` | Foreground service is up and capture loops are running |
| `Not running` | Service is stopped (or was never started) |
| `Start failed: <message>` | The service could not start — the message is the actual exception (e.g. `Media projections require a foreground service...`) |
| `Screen capture unavailable: <message>` | Service is running, but the screen VirtualDisplay could not be created (other captures continue) |
| `Screen capture ended — tap Start ...` | The projection was stopped (share chip, lock screen on Android 15+). Tap **Start** and accept the system dialog again — see below |

Android 14+ (targetSdk 34) requires this exact order: user accepts the
**Share screen** dialog → `startForegroundService()` → the service calls
`startForeground()` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` → only
then `getMediaProjection()`. Any deviation throws `SecurityException`.

MediaProjection consent is **single-session**: every Start shows a fresh
system dialog, and the granted token is used once and never cached or
persisted (it is valid only in the current process). When the projection
ends, the old token is dead — restarting always requires a new consent
grant, which is why the status card asks you to tap Start again.

If start keeps failing on your device:

1. Make sure notifications are allowed for the app (Android 13+ shows a
   permission prompt; the foreground service notification must be able to
   appear).
2. Disable battery optimization / "autostart" restrictions for the app —
   aggressive OEM ROMs (MIUI, ColorOS, One UI) can revoke permissions or kill
   the service right after start.
3. Uninstall the old build completely before installing a new one, so stale
   runtime-permission state is cleared.

---

## Testing checklist

- [ ] Grant runtime permissions on first run
- [ ] MediaProjection dialog appears and is accepted
- [ ] Persistent notification shows with **Stop**
- [ ] Server receives screenshot / camera / audio / sensor uploads
- [ ] Stop from notification and from app UI both halt capture
- [ ] Survives rotation and backgrounding

---

## Legal notice

This app captures sensitive device data (screen, camera, audio, sensors). Use only on devices you own or are explicitly authorized to monitor. Unauthorized use may violate privacy laws including GDPR, CCPA, CFAA, and local equivalents.
