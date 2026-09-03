# Air VR180 Player - Android

Android player and controller for Xreal/Nreal Air glasses.

The phone handles media playback and the UI, while the glasses are used as the
external SBS display. The app also talks directly to the glasses over USB for
display-mode control and IMU head tracking.

<img src="../docs/Screenshot.png" alt="Air VR180 Player screenshot" width="420">

## Features

- VR180 equirectangular, VR190 fisheye, and VR200 fisheye projection
- Full-SBS output on the glasses external display
- 3DoF head tracking with recentering
- View scale, scene-center, and horizon adjustment
- Local playback with Media3/ExoPlayer
- Recent files with saved resume position, watched time, and projection mode
- Seeking, mute, and A/B loops
- Saved positions and saved A/B scenes
- Optional server library browsing and HTTP streaming
- Windows Remote mode for controlling the Windows player from the same Android UI

The glasses currently targeted by the USB driver use vendor/product ID
`3318:0424`.

## Requirements

- Android 11 / API 30 or newer
- Android SDK 36
- Android Gradle Plugin 9.2.0
- Android NDK 28.2.13676358
- CMake 3.22.1
- A JDK supported by the configured Android Gradle Plugin
- A USB-host-capable Android device
- Xreal/Nreal Air glasses connected over USB-C with display output and HID access

Shared server requirements and setup are documented in the root
[README](../README.md).

## Build

From `android/` on Windows:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The debug APK is written under:

```text
app/build/outputs/apk/debug/
```

## Local playback

1. Connect the glasses to the Android device.
2. Open the app and grant the USB permission prompt.
3. Tap `Select` and choose a local video.
4. Tap `Recenter` after putting on the glasses.
5. Select the correct projection mode and adjust view scale, scene center, or horizon if needed.

The app requests the glasses' full-SBS display mode over USB. Android must also
expose the glasses as an external presentation display for VR output to appear in
the glasses.

Local recent entries remember playback position, watched time, duration, and the
selected projection mode. Projection mode is also guessed from common VR190 and
VR200 filename markers when a file is first added.

## Server playback

The shared server itself is configured and run from the monorepo root. In the
Android app, enter its URL and the same API key, for example:

```text
http://192.168.1.20:50050
```

Server videos are streamed to Android with byte-range requests. The Android
library UI uses the server's thumbnails, metadata, played history, saved playback
position, projection mode, and A/B loop state.

## Saved scenes

Tap `Scenes` for the current video.

- With a complete A/B range, saving creates a loop scene.
- Without a complete A/B range, saving creates a position bookmark.
- Restoring a loop scene restores both loop points.
- Restoring a position clears the active loop and seeks to the saved position.
- Restoring a scene preserves whether playback was paused or playing.

Scenes for local files are stored on the Android device. Scenes for server videos
are stored by the shared server, so the same server-backed scenes can also be used
while Android is controlling Windows playback.

## Windows Remote mode

Enable `Control Windows player` to use the Android app as the controller for the
Windows player. In this mode Windows owns playback, the glasses USB connection,
head tracking, and display output; Android polls Windows state and sends playback
commands instead of driving the glasses locally.

The Windows player listens on port `57050` by default. Set `Windows remote URL`
in the Android app explicitly, or leave it blank and the app will derive:

```text
http://<server-host>:57050
```

from the configured server URL.

Windows Remote authentication uses the same server API key configured on both
devices. The Android UI can remotely control play/pause, seeking, mute, A/B loops,
projection mode, recentering, view offsets, zoom, server video selection, saved
scenes, and fullscreen-on-glasses requests.

## Head tracking

Android reads every complete IMU report directly from the USB reader thread and
feeds it to the native VQF-based tracker. The driver also loads the glasses'
factory IMU calibration before starting the stream.

Magnetic heading is not used to steer the rendered pose. Detailed tracking notes
and validation are in [docs/head-tracking.md](../docs/head-tracking.md).

## Project layout

```text
app/src/main/java/com/enricoros/nreal/
  MainActivity.java              UI, playback, server library, remote mode
  SavedScenesController.java     Saved-scene UI coordination
  WindowsRemoteClient.java       Windows state/command client

app/src/main/java/com/enricoros/nreal/player/
  Vr180Renderer.java             OpenGL ES projection renderer
  VrVideoSurfaceView.java        GLSurfaceView wrapper
  VrPlayerPresentation.java      External-display presentation
  HeadTracker.java               JNI wrapper for native tracking
  RecentVideo*.java              Local recent/resume persistence
  SavedScene*.java               Local/server saved scenes

app/src/main/java/com/enricoros/nreal/driver/
  NrealManager.java              USB lifecycle and dispatch
  NrealDeviceThread.java         IMU/button HID communication
  FactoryImuCalibration.java     Factory calibration parsing
  ImuDataRaw.java                Decoded IMU sample data

app/src/main/cpp/
  tracking_filter.*              VQF integration and bias handling
  vqf/                           Vendored VQF source
```

## Credits

Forked from https://github.com/enricoros/android-nreal

USB/IMU work also builds on information from:

- https://github.com/edwatt/imu-inspector
- https://github.com/MSmithDev/AirAPI_Windows
- https://github.com/abls/imu-inspector

## License

See [LICENSE](../LICENSE).
