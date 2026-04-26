# Air VR180 Player

Air VR180 Player is an Android app for watching VR180 side-by-side videos on
Nreal/Xreal Air glasses. The phone app handles video selection and playback
controls, while the glasses are used as the external display. USB IMU data from
the glasses drives 3DoF head tracking for the rendered VR view.

This repository also contains the low-level Android USB driver code used to
discover the glasses, request permission, read HID packets, decode IMU/button
events, and feed tracking data into the player.

<img src="docs/Screenshot_20260426_000116_Air%20VR180%20Player.png" alt="Air VR180 Player screenshot" width="420" />

## Features

- Select local videos through Android's system file picker.
- Play VR180 side-by-side video through Media3 ExoPlayer.
- Render the VR view with OpenGL ES using an external video texture.
- Prefer an attached Nreal/Xreal Air display for presentation output.
- Automatically switch to side-by-side stereo output on wide external displays.
- Read Nreal Air USB HID IMU packets for 3DoF head tracking.
- Recenter tracking from the app UI.
- Adjust view scale, scene center, and horizon offset.
- Use playback controls for play/pause, +/-10 second seek, and A-B loop.
- Remember recent videos and playback positions.
- Toggle dark mode and mute audio.
- Show diagnostics for USB connection, external display, tracking, and playback.

## Current Status

This is a working experimental app, not a polished SDK.

Supported and actively used:

- Nreal Air USB device ID `3318:0424`
- Android USB host access
- External display presentation
- 3DoF IMU-driven VR180 viewing

Not yet implemented or incomplete:

- Nreal Light support
- 6DoF tracking
- Camera or microphone access
- A packaged standalone library API
- Broad device compatibility testing

## Requirements

- Android Studio with Android Gradle Plugin 9.2.0 support.
- JDK compatible with the Android Gradle Plugin version in this repo.
- Android SDK 36 installed.
- Android device running Android 11 / API 30 or newer.
- USB host capable Android device.
- Nreal Air or compatible Xreal Air glasses.
- A USB-C setup that exposes both the display output and the USB HID interface
  to the phone.

## Build

Clone the repository:

```bash
git clone https://github.com/hiiva/android-nreal.git
cd android-nreal
```

Build a debug APK:

```bash
./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Run

1. Connect the glasses to the Android device.
2. Install and open the app from Android Studio, or install the debug APK.
3. Grant the USB permission prompt when Android asks for access to the glasses.
4. Tap `Select` and choose a local VR180 video.
5. Use `Recenter` after putting the glasses on.
6. Adjust `View scale`, `Scene center`, and `Horizon` if the projection needs
   alignment.

The phone screen remains the controller. The VR video is presented on the
external glasses display when Android exposes it as a presentation display.

## Project Layout

```text
app/src/main/java/com/enricoros/nreal/
  MainActivity.java              App UI, playback, external display routing
  VectorDisplayView.java         Diagnostic/vector display view

app/src/main/java/com/enricoros/nreal/player/
  Vr180Renderer.java             OpenGL ES VR180 renderer
  VrVideoSurfaceView.java        GLSurfaceView wrapper
  VrPlayerPresentation.java      External display presentation
  HeadTracker.java               3DoF tracking integration
  Quaternion.java                Rotation math
  RecentVideo*.java              Recent video persistence

app/src/main/java/com/enricoros/nreal/driver/
  NrealManager.java              USB connection lifecycle
  NrealDeviceThread.java         HID reader thread
  ImuDataRaw.java                Raw IMU packet model
  UsbUtils.java                  USB discovery helpers
  data/MagnetometerPreprocessor.java
```

## Implementation Notes

- Video playback uses `androidx.media3:media3-exoplayer`.
- The renderer consumes a `SurfaceTexture` backed by an
  `GL_TEXTURE_EXTERNAL_OES` texture.
- A display is treated as stereo when it is very wide, currently `width >= 3000`
  or aspect ratio `>= 2.4`.
- USB device matching is declared in `app/src/main/res/xml/device_filter.xml`.
- Viewer settings are stored in Android shared preferences.

## Troubleshooting

- `No attached Nreal devices found`: check that the USB data path is connected,
  not only display output.
- `USB permission denied`: unplug/replug the glasses and grant the Android USB
  permission dialog.
- `Output: no external Air display`: Android is not exposing the glasses as an
  external presentation display.
- Tracking is stale or unavailable: confirm the app has USB permission and the
  diagnostics line says the IMU is streaming.
- Video opens but looks wrong: confirm the source is VR180 side-by-side video.

## Credits

Thanks to members of the Nreal community whose early work and notes helped make
the USB/IMU side possible:

- edwatt: https://github.com/edwatt/imu-inspector
- MattXer: https://github.com/MSmithDev/AirAPI_Windows
- Noot: https://github.com/abls/imu-inspector

## License

See [LICENSE](LICENSE).
