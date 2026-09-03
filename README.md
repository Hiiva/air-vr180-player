# Air VR180 Player

Air VR180 Player targets Xreal/Nreal Air hardware with separate Android and Windows players.
Both talk directly to the glasses for IMU head tracking and full-SBS display mode.

## What it supports

- VR180 equirectangular, VR190 fisheye, and VR200 fisheye SBS video
- 3DoF head tracking with recentering, plus zoom, scene-center, and horizon adjustment
- Local playback with saved resume position and projection mode
- Seeking, mute, and A/B loops
- Windows Remote mode, where the Android app controls the Windows player instead of playing locally

## How the pieces fit together

- **Android** is a phone-side player/controller. It renders VR video to the glasses as an external display and streams server-library videos over HTTP.
- **Windows** is a native PC player using `libmpv` and OpenGL. It can be controlled from Android with Windows Remote mode.
- **Server** is optional shared library infrastructure for indexing video folders, thumbnails, watch history, and saved scenes. Windows opens server-library files directly from disk, so the Windows player and server are expected to run on the same PC.

## Repo layout

```text
android/    Android app
windows/    Windows app
server/     Video library server
docs/       Screenshots and notes
```

## Build Android

```powershell
cd android
.\gradlew.bat assembleDebug
```

Android details: [android/README.md](android/README.md)

## Build Windows

```powershell
cd windows
.\scripts\get-libmpv.ps1 -Destination target\release
cargo build --release
.\target\release\air-windows-player.exe
```

Windows details: [windows/README.md](windows/README.md)

## Run the server

The server requires Python plus `ffmpeg` and `ffprobe` on `PATH`.

```powershell
Copy-Item server\.env.example server\.env
py -m venv server\.venv
server\.venv\Scripts\pip install -r server\requirements.txt
server\.venv\Scripts\python server\main.py
```

Configure `server/.env` before starting it. The main settings are:

```dotenv
API_KEY=replace-with-a-private-value
HOST=127.0.0.1
PORT=50050
SOURCES=C:\Videos\VR180
EXTENSIONS=.mp4,.mkv,.mov
CACHE_DIR=cache
USE_HTTPS=false
```

`SOURCES` is a semicolon-separated list of folders to index. Use a reachable
`HOST`, such as `0.0.0.0`, when Android needs to connect over the LAN.

The server is optional. Android and Windows can both play local files without it.

## License

See [LICENSE](LICENSE).
