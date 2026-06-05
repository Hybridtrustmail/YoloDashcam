# YoloDashcam

Android dashcam project for on-device recording, YOLO-based road-scene
detection, and telemetry logging.

## Status

This is an active Android project, not a finished consumer dashcam product.

The current direction is practical:

- keep recording stable on real phones
- use offline AI analysis where live combined pipelines are unreliable
- build around hardware limits instead of pretending they do not exist

## Install

Download the latest signed APK from the
[**Releases**](https://github.com/Hybridtrustmail/YoloDashcam/releases) page.

1. On an Android phone (Android 6.0 / API 23 or newer), download
   `YoloDashcam-v1.0.apk` from the latest release.
2. Open it and allow installation from unknown sources if prompted.
3. Grant camera and location permissions on first launch.

Recording defaults to **FHD (1080p)** and clips are saved under
`Downloads/DashCAM` (configurable as a subfolder of Downloads in Settings).
Auto-record is triggered by **GPS movement** by default; you can switch to
motion-sensor or both under Settings (the gear icon).

Screenshots of the app are in the [`Screenshots/`](Screenshots) folder.

## What works now

- `Record` mode is the most stable path
- `Log+AI` is the most practical AI workflow on limited hardware
- telemetry logging is integrated with recorded clips

## What is still experimental

- `Live AI` stability depends on device behavior
- richer live tracking and live speed overlays are not the current stability
  baseline
- generalized presets across multiple Android phones are still future work

Important hardware note:

On at least one tested device class, the combined CameraX use-case set
`Preview + ImageAnalysis + VideoCapture` is unreliable. Because of that, this
project deliberately prioritizes stability over “all features active at once.”

If you are interested in Android camera pipelines, on-device YOLO, clip
logging, and hardware-aware AI tradeoffs, this repository is a useful base.

## Current modes

### `Record`

- foreground-service video recording
- designed to keep recording with screen off
- lowest-risk mode for long sessions

### `Live AI`

- preview + YOLO detection overlay
- currently tuned for safer live detection, not maximum features
- on constrained hardware, tracking and live speed estimation may be reduced or
  disabled to keep the camera preview responsive

Important note:

The codebase still contains tracking, speed-estimation, and richer analysis
components, but the current live path is intentionally limited on some devices.
This is a hardware-stability choice, not a product limitation in principle.

### `Log+AI`

- records clips first
- analyzes saved clips afterward
- writes CSV outputs from the offline analysis pass

For limited phones, this is currently the most realistic AI mode because it
avoids unstable combined `Preview + ImageAnalysis + VideoCapture` pipelines.

## Architecture summary

- `DashcamService.kt`
  Headless/background-oriented recording path.
- `RecordingWorker.java`
  Segmented clip recording and rollover.
- `MainActivity.java`
  UI, mode switching, and live preview-analysis path.
- `YoloDetector.java`
  On-device YOLO11 TensorFlow Lite inference.
- `ClipAnalysisWorker.java`
  Offline analysis queue for recorded clips.
- `ClipStorage.java`
  Output paths, naming, and retention.

## Hardware reality

This app is being developed with real Android device limitations in mind.

- on at least one tested Android 11 device path, the combined CameraX set
  `Preview + ImageAnalysis + VideoCapture` is not reliable

Practical consequence:

- `Record` and `Log+AI` are treated as recording-first modes
- `Live AI` is treated as a separate preview-analysis mode
- stability is prioritized over feature count

This is why some features that are technically possible in the code are not
always enabled in the live path today.

## Speed estimation and calibration

The project includes camera-geometry-based speed estimation and calibration
parameters in `app/src/main/java/com/dashcam/Config.java`.

Examples:

```java
public static float CAMERA_HEIGHT = 1.3f;
public static float CAMERA_ANGLE = 30.0f;
public static float DISTANCE_TO_ROAD = 10.0f;
```

Current status:

- available in the codebase
- used more naturally in tracking/offline analysis flows
- not something to promise as fully stable live behavior on every phone yet

## Outputs

By default, the app tries to save clips under public `Downloads/DashCAM`.
If that is not available, it falls back to app-specific external storage.

Typical files per clip:

```text
clip_2026-06-03_12-00-00-123.mp4
clip_2026-06-03_12-00-00-123.telemetry.csv
clip_2026-06-03_12-00-00-123.csv
```

Research summaries may also be written separately by the offline analysis path.

## Build notes

### Current project facts

- package/application id: `com.dashcam`
- compile SDK: 33
- target SDK: 33
- minimum SDK: 23
- current testing notes mainly reflect Android 11-era hardware

### Requirements

- Android Studio
- physical Android device
- camera and location permissions

The YOLO11 model asset `yolo11n_f16.tflite` is already present in
`app/src/main/assets/` in the current project state.

## Performance notes

The current implementation favors stable CPU inference on the tested phone.

Today this means:

- TensorFlow Lite runs with a conservative CPU setup
- NNAPI is currently disabled in code
- live AI is intentionally throttled/tuned for stability

Future capability profiles and device overrides are planned, but detailed
private testing notes are intentionally not included in this public export.

## Project status

Current priority order:

1. stable `Record`
2. preserve/recover a known-good `Live AI` baseline
3. keep `Log+AI` useful as the practical offline AI path
4. add capability profiles and device-specific overrides later

## Collaboration

Bug reports, test feedback, and technically grounded suggestions are useful.

The most valuable contributions for this project are:

- reproducible bug reports
- device compatibility notes
- recording stability findings
- live AI performance observations
- improvements to offline analysis and logging
- codevelopment on hardware-aware defaults, storage, telemetry, and AI
  processing
- discussion or prototypes for possible future platform variants, including iOS
  or other device ecosystems, while Android remains the current primary target

If you want to cooperate, it is most helpful to include:

- phone model and Android version
- selected app mode
- short reproduction steps
- logs, screenshots, or short sample outputs when available

Support in other forms can also make sense later, including help with testing,
documentation, codevelopment, and eventually project maintenance or financing
if the project grows beyond personal experimentation.

## License

This project is licensed under the Apache License 2.0. See
`LICENSE`.
