use serde::Serialize;
use std::io::{BufRead, BufReader, Read, Write};
use std::sync::{Arc, RwLock};
use winit::event_loop::EventLoopProxy;

use crate::server;

#[derive(Clone, Debug, Default, Serialize)]
pub struct PlayerState {
    pub media: String,
    pub current_server_id: String,
    pub server_url: String,
    pub playing: bool,
    pub position_seconds: f64,
    pub duration_seconds: f64,
    pub speed: f64,
    pub volume: i64,
    pub muted: bool,
    pub zoom: f32,
    pub yaw_degrees: f32,
    pub horizon_degrees: f32,
    pub projection_mode: i32,
    pub stereo: bool,
    pub stereo_mode: String,
    pub fullscreen_on_glasses: bool,
    pub output_width: u32,
    pub output_height: u32,
    pub loop_start_seconds: Option<f64>,
    pub loop_end_seconds: Option<f64>,
    pub loop_paused: bool,
    pub tracking: String,
    pub render_fps: f32,
    pub server_configured: bool,
    pub server_library_count: usize,
    pub server_played_count: usize,
}

#[derive(Clone, Debug)]
pub enum ControlCommand {
    PlayPause,
    Stop,
    SeekRelative(f64),
    SeekAbsolute(f64),
    LoadScene {
        video_id: String,
        start_ms: u64,
        end_ms: Option<u64>,
        reply: Option<std::sync::mpsc::Sender<Result<(), String>>>,
    },
    SetVolume(i64),
    SetMuted(bool),
    ToggleMute,
    SetSpeed(f64),
    SetZoom(f32),
    SetYaw(f32),
    SetHorizon(f32),
    ResetView,
    SetProjection(i32),
    SetStereo(bool),
    SetStereoAuto,
    Recenter,
    ReloadLibrary,
    PlayServer(usize),
    PlayServerId(String),
    PlayAdjacent(i32),
    ToggleServerDelete(String),
    ClearServerHistory,
    SetServerSettings {
        url: String,
        api_key: String,
    },
    SetServerLibrary {
        videos: Vec<server::ServerVideo>,
        played: Vec<server::ServerVideo>,
    },
    OpenLocalFile,
    LoadMedia(String),
    RemoveRecent(String),
    ClearRecents,
    SetLoopStart,
    SetLoopEnd,
    ClearLoopStart,
    ClearLoopEnd,
    ToggleLoopPaused,
    ClearLoop,
    FullscreenGlasses,
}

pub type SharedState = Arc<RwLock<PlayerState>>;

pub fn start(proxy: EventLoopProxy<ControlCommand>) -> anyhow::Result<(String, SharedState)> {
    let state = Arc::new(RwLock::new(PlayerState::default()));
    let bind = std::env::var("AIR_REMOTE_BIND")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "0.0.0.0:57050".to_owned());
    let listener = std::net::TcpListener::bind(&bind)
        .map_err(|error| anyhow::anyhow!("could not bind Windows remote control at {bind}: {error}"))?;
    let port = listener.local_addr()?.port();
    let url = format!("http://127.0.0.1:{port}");
    println!("[remote] Windows remote listening on {bind}; authentication uses the configured server API key");

    let server_state = state.clone();
    std::thread::spawn(move || {
        while let Ok((stream, peer)) = listener.accept() {
            let proxy = proxy.clone();
            let state = server_state.clone();
            std::thread::spawn(move || handle_client(stream, peer, proxy, state));
        }
    });
    Ok((url, state))
}

fn handle_client(
    stream: std::net::TcpStream,
    peer: std::net::SocketAddr,
    proxy: EventLoopProxy<ControlCommand>,
    state: SharedState,
) {
    let mut reader = BufReader::new(match stream.try_clone() {
        Ok(value) => value,
        Err(_) => return,
    });
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() {
        return;
    }
    let mut request_parts = request_line.split_whitespace();
    let method = request_parts.next().unwrap_or_default().to_owned();
    let path = request_parts.next().unwrap_or_default().to_owned();
    let mut content_length = 0usize;
    let mut api_key_header = String::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).is_err() || line == "\r\n" || line.is_empty() {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            if name.trim().eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0);
            } else if name.trim().eq_ignore_ascii_case("x-api-key") {
                api_key_header = value.trim().to_owned();
            }
        }
    }
    if content_length > 1024 * 1024 {
        respond(stream, 413, "application/json", b"{\"error\":\"request too large\"}");
        return;
    }
    let mut body = vec![0; content_length];
    if !body.is_empty() && reader.read_exact(&mut body).is_err() {
        return;
    }

    let route = path.split('?').next().unwrap_or("/");
    if route == "/health" {
        respond(stream, 200, "application/json", b"{\"status\":\"ok\"}");
        return;
    }

    let query_key = query_parameter(&path, "api_key").unwrap_or_default();
    let supplied_key = if api_key_header.is_empty() { query_key } else { api_key_header };
    let Some(expected_key) = remote_api_key() else {
        respond(stream, 503, "application/json", b"{\"error\":\"remote key unavailable\"}");
        return;
    };
    if supplied_key != expected_key {
        eprintln!("[remote] rejected unauthenticated request from {peer}");
        respond(stream, 401, "application/json", b"{\"error\":\"unauthorized\"}");
        return;
    }

    match (method.as_str(), route) {
        ("GET", "/state") => match state.read() {
            Ok(state) => json_response(stream, &*state),
            Err(_) => respond(
                stream,
                500,
                "application/json",
                b"{\"error\":\"state unavailable\"}",
            ),
        },
        ("POST", "/command") => {
            if let Some(mut command) = parse_remote_command(&body) {
                let completion = if let ControlCommand::LoadScene { reply, .. } = &mut command {
                    let (sender, receiver) = std::sync::mpsc::channel();
                    *reply = Some(sender);
                    Some(receiver)
                } else { None };
                if proxy.send_event(command).is_err() {
                    respond(stream, 503, "application/json", b"{\"error\":\"player unavailable\"}");
                } else if let Some(completion) = completion {
                    match completion.recv_timeout(std::time::Duration::from_secs(2)) {
                        Ok(Ok(())) => respond(stream, 200, "application/json", b"{\"ok\":true}"),
                        Ok(Err(error)) => {
                            let body = serde_json::json!({"error": error}).to_string();
                            respond(stream, 409, "application/json", body.as_bytes());
                        }
                        Err(_) => respond(stream, 503, "application/json", b"{\"error\":\"player did not respond\"}"),
                    }
                } else {
                    respond(stream, 202, "application/json", b"{\"ok\":true}");
                }
            } else {
                respond(
                    stream,
                    400,
                    "application/json",
                    b"{\"error\":\"invalid or unsupported command\"}",
                );
            }
        }
        _ => respond(stream, 404, "application/json", b"{\"error\":\"not found\"}"),
    }
}

fn remote_api_key() -> Option<String> {
    server::load_settings()
        .map(|settings| settings.api_key)
        .filter(|value| !value.trim().is_empty())
}

fn query_parameter(path: &str, key: &str) -> Option<String> {
    let query = path.split_once('?')?.1;
    for pair in query.split('&') {
        let (name, value) = pair.split_once('=').unwrap_or((pair, ""));
        if name == key {
            return Some(percent_decode(value));
        }
    }
    None
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0usize;
    while index < bytes.len() {
        match bytes[index] {
            b'+' => {
                out.push(b' ');
                index += 1;
            }
            b'%' if index + 2 < bytes.len() => {
                if let (Some(high), Some(low)) = (hex_value(bytes[index + 1]), hex_value(bytes[index + 2])) {
                    out.push((high << 4) | low);
                    index += 3;
                } else {
                    out.push(bytes[index]);
                    index += 1;
                }
            }
            byte => {
                out.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn parse_remote_command(body: &[u8]) -> Option<ControlCommand> {
    let value: serde_json::Value = serde_json::from_slice(body).ok()?;
    let action = value.get("action")?.as_str()?;
    let number = || value.get("value").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
    let text = |key: &str| {
        value
            .get(key)
            .and_then(serde_json::Value::as_str)
            .map(str::to_owned)
    };

    Some(match action {
        "playPause" => ControlCommand::PlayPause,
        "stop" => ControlCommand::Stop,
        "seekRelative" => ControlCommand::SeekRelative(number()),
        "seekAbsolute" => ControlCommand::SeekAbsolute(number()),
        "loadScene" => {
            let video_id = text("video_id")?;
            if video_id.len() != 24 || !video_id.bytes().all(|b| b.is_ascii_hexdigit()) {
                return None;
            }
            let start_ms = value.get("start_ms")?.as_u64()?;
            let end_ms = match value.get("end_ms")? {
                serde_json::Value::Null => None,
                value => Some(value.as_u64()?),
            };
            if start_ms > 9_007_199_254_740_991
                || end_ms.is_some_and(|end| end <= start_ms || end > 9_007_199_254_740_991) {
                return None;
            }
            ControlCommand::LoadScene { video_id, start_ms, end_ms, reply: None }
        }
        "volume" => ControlCommand::SetVolume(number().round() as i64),
        "muted" => ControlCommand::SetMuted(value.get("value").and_then(serde_json::Value::as_bool)?),
        "speed" => ControlCommand::SetSpeed(number()),
        "zoom" => ControlCommand::SetZoom(number() as f32),
        "yaw" => ControlCommand::SetYaw(number() as f32),
        "horizon" => ControlCommand::SetHorizon(number() as f32),
        "resetView" => ControlCommand::ResetView,
        "projection" => ControlCommand::SetProjection(number() as i32),
        "stereo" => ControlCommand::SetStereo(value.get("value").and_then(serde_json::Value::as_bool)?),
        "stereoAuto" => ControlCommand::SetStereoAuto,
        "recenter" => ControlCommand::Recenter,
        "reloadLibrary" => ControlCommand::ReloadLibrary,
        "playServerId" => ControlCommand::PlayServerId(text("id")?),
        "playAdjacent" => ControlCommand::PlayAdjacent(number().round() as i32),
        "loopStart" => ControlCommand::SetLoopStart,
        "loopEnd" => ControlCommand::SetLoopEnd,
        "clearLoopStart" => ControlCommand::ClearLoopStart,
        "clearLoopEnd" => ControlCommand::ClearLoopEnd,
        "toggleLoopPaused" => ControlCommand::ToggleLoopPaused,
        "clearLoop" => ControlCommand::ClearLoop,
        "fullscreenGlasses" => ControlCommand::FullscreenGlasses,
        _ => return None,
    })
}

fn json_response<T: Serialize>(stream: std::net::TcpStream, value: &T) {
    match serde_json::to_vec(value) {
        Ok(payload) => respond(stream, 200, "application/json", &payload),
        Err(_) => respond(
            stream,
            500,
            "application/json",
            b"{\"error\":\"encode failed\"}",
        ),
    }
}

fn respond(mut stream: impl Write, status: u16, content_type: &str, body: &[u8]) {
    let status_text = match status {
        200 => "OK",
        202 => "Accepted",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        413 => "Payload Too Large",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "OK",
    };
    let response = format!(
        "HTTP/1.1 {status} {status_text}\r\nContent-Type: {content_type}\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(response.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}
