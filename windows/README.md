# Air VR180 Player - Windows

Native Windows player for Xreal/Nreal Air glasses. Video is decoded and rendered
through `libmpv`, then warped by the Rust/OpenGL renderer for the selected VR
projection. The player also talks directly to the glasses over HID for head
tracking and SBS display-mode control.

## Features

- VR180 equirectangular, VR190 fisheye, and VR200 fisheye projection
- Local file playback, file picker, and drag-and-drop
- Persistent local recents with resume position, watched time, and projection mode
- Automatic VR190/VR200 filename detection for new local files
- Accelerometer-assisted 3DoF tracking with recentering and gyro-bias correction
- Factory gyro calibration loaded from the glasses when available
- Automatic request for the glasses' full-SBS display mode
- Automatic selection of the 3840x1080 glasses display mode when Windows exposes it
- Zoom, scene-center, horizon, seek, pause, mute, and A/B loop control
- Automatic, forced-SBS, or forced-mono output
- Optional shared server library and watch-history integration
- Android remote control over the local network

The glasses currently targeted by the HID code use vendor/product ID
`3318:0424`.

## Requirements

- Windows 10 or 11
- Rust stable
- A working Windows C toolchain for the Rust dependencies; the current setup uses `x86_64-pc-windows-gnu` with MinGW-w64
- `libmpv-2.dll` beside the executable or available on the normal Windows DLL search path
- 7-Zip available as `7z` if using `scripts/get-libmpv.ps1`

The optional shared server is in [`../server/`](../server/main.py).

## Build and run

From `windows/`:

```powershell
.\scripts\get-libmpv.ps1 -Destination target\release
cargo build --release
.\target\release\air-windows-player.exe
```

The helper downloads a 64-bit mpv development build and copies
`libmpv-2.dll` into the requested target directory.

For a debug build:

```powershell
.\scripts\get-libmpv.ps1 -Destination target\debug
cargo build
.\target\debug\air-windows-player.exe
```

You can also pass a local path or HTTP/HTTPS media URL at startup:

```powershell
.\target\release\air-windows-player.exe D:\Videos\VR180\clip.mp4
```

## Controls

| Action | Key |
| --- | --- |
| Open local file | <kbd>O</kbd> |
| Refresh server library | <kbd>S</kbd> |
| Play server item 1 through 9 | <kbd>1</kbd>...<kbd>9</kbd> |
| Pause / resume | <kbd>Space</kbd> or <kbd>P</kbd> |
| Skip backward / forward 10 seconds | <kbd>Left</kbd> / <kbd>Right</kbd> |
| Zoom out / in | <kbd>-</kbd> / <kbd>=</kbd> |
| Scene center left / right | <kbd>,</kbd> / <kbd>.</kbd> |
| Horizon down / up | <kbd>Down</kbd> / <kbd>Up</kbd> |
| Projection previous / next | <kbd>B</kbd> / <kbd>N</kbd> |
| Recenter tracking | <kbd>R</kbd> |
| Toggle mute | <kbd>M</kbd> |
| Set loop A / set loop B / clear loop | <kbd>[</kbd> / <kbd>]</kbd> / <kbd>\</kbd> |
| Fullscreen current monitor | <kbd>F11</kbd> |
| Fullscreen on glasses | <kbd>G</kbd> |
| Stereo auto / forced SBS / forced mono | <kbd>F10</kbd> |
| Exit | <kbd>Esc</kbd> |

At startup the player asks the glasses to switch to full SBS. It then looks for
the Air display in the active Windows monitor topology and selects a 3840x1080
mode, preferring 60 Hz when that mode is exposed. Display selection is retried
while the glasses are changing modes or reconnecting.

## Server library

The shared server itself is configured and run from the monorepo root; see the
root [README](../README.md). Configure the Windows player's connection to it with
either environment variables:

```powershell
$env:AIR_SERVER_URL = "http://127.0.0.1:50050"
$env:AIR_API_KEY = "replace-with-a-private-value"
```

or `server.json` beside the executable:

```json
{
  "server_url": "http://127.0.0.1:50050",
  "api_key": "replace-with-a-private-value"
}
```

For server-library playback, the Windows player does not stream the video through
HTTP. It requests the library with `client=windows`, receives the indexed local
filesystem path, and opens that file directly with `libmpv`. The normal setup is
therefore for the Windows player and Python server to run on the same PC.

The server is still used for library metadata and shared watch history. Server
playback position, watched time, projection mode, and A/B loop state are synced
back about every 15 seconds and on important playback transitions. If the server
is temporarily unavailable, pending history is stored in `history-pending.json`
and retried later.

Press <kbd>S</kbd> to refresh the server library. Keys <kbd>1</kbd> through
<kbd>9</kbd> play the corresponding first nine entries. The Android remote can
also select server videos by ID.

## Android remote control

The Windows player starts a small HTTP control endpoint automatically. By default
it listens on:

```text
0.0.0.0:57050
```

Override the bind address with `AIR_REMOTE_BIND` if needed.

`/health` is unauthenticated. `/state` and `/command` require the same API key
configured for the shared server. Because the remote endpoint obtains its key
from the Windows server settings, `AIR_SERVER_URL`/`AIR_API_KEY` or `server.json`
must be configured before authenticated Android remote control will work.

On Android, enable `Control Windows player`. Set `Windows remote URL` explicitly,
or leave it blank and Android will derive port `57050` from the host in its server
URL.

The Android UI can control Windows play/pause, seeking, mute, zoom, view offsets,
projection mode, recentering, A/B loops, server video selection, and
fullscreen-on-glasses. It also receives Windows playback, tracking, render, and
output state.

For a server video, Android's `Scenes` UI can send a saved position or A/B range
to Windows. Windows verifies that the requested server video is still current,
seeks to the saved position, restores the loop when present, and preserves pause
state.

## Persistence

Files stored beside the executable:

- `player.json`: zoom, view offsets, mute state, and stereo override
- `recent.json`: local recent files, resume positions, watched time, and projection mode
- `server.json`: optional server URL and API key
- `history-pending.json`: queued server-history updates while the server is unavailable

## Tracking and rendering

The HID reader loads the glasses' factory gyro bias when available, starts the
IMU stream, and feeds device-timestamped gyro/accelerometer samples into the
Windows head tracker. The Windows tracker is independent from Android's VQF
implementation.

`libmpv` renders into an OpenGL texture at source resolution unless the active
GPU's reported maximum texture size requires it to be reduced. The final shader
applies head rotation, projection mapping, view offsets, zoom, and mono/SBS output.

## Project layout

```text
src/app.rs          Player lifecycle, controls, server playback, fullscreen logic
src/control.rs      Android remote state and command HTTP endpoint
src/hid.rs          Xreal/Nreal HID communication and display-mode command
src/tracking.rs     Windows 3DoF fusion and recentering
src/mpv.rs          libmpv loading, playback, properties, and render API
src/renderer.rs     OpenGL VR projection renderer
src/server.rs       Shared server client and pending history queue
src/recent.rs       Local recent-file persistence
src/preferences.rs  Player preference persistence
```
