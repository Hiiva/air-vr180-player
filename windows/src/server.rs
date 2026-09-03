use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ServerVideo {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default, rename = "file_name")]
    pub file_name: String,
    #[serde(default)]
    pub subfolder: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default, rename = "duration_ms")]
    pub duration_ms: u64,
    #[serde(default)]
    pub modified: String,
    #[serde(default)]
    pub modified_ms: u64,
    #[serde(default, rename = "file_date")]
    pub file_date: String,
    #[serde(default, rename = "flagged_for_deletion")]
    pub flagged_for_deletion: bool,
    #[serde(default)]
    pub local_path: String,
    #[serde(default)]
    pub thumbnail_url: String,
    #[serde(default)]
    pub last_played_at_ms: u64,
    #[serde(default)]
    pub position_ms: u64,
    #[serde(default)]
    pub watched_ms: u64,
    #[serde(default)]
    pub projection_mode: i32,
    #[serde(default)]
    pub loop_start_ms: Option<u64>,
    #[serde(default)]
    pub loop_end_ms: Option<u64>,
    #[serde(default)]
    pub available: bool,
}

#[derive(Debug, Deserialize)]
struct LibraryResponse {
    #[serde(default)]
    videos: Vec<ServerVideo>,
    #[serde(default)]
    history: Vec<ServerVideo>,
}

#[derive(Debug, Deserialize)]
struct HistoryResponse {
    #[serde(default)]
    history: Vec<ServerVideo>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct HistoryUpdate {
    pub id: String,
    pub title: String,
    pub file_name: String,
    pub subfolder: String,
    pub size: u64,
    pub duration_ms: u64,
    pub modified_ms: u64,
    pub file_date: String,
    pub position_ms: u64,
    pub watched_ms: u64,
    pub projection_mode: i32,
    pub loop_start_ms: Option<u64>,
    pub loop_end_ms: Option<u64>,
    pub last_played_at_ms: u64,
}

#[derive(Clone, Debug, Serialize)]
pub struct ServerLibrary {
    pub videos: Vec<ServerVideo>,
    pub played: Vec<ServerVideo>,
}

#[derive(Clone, Debug, Serialize)]
pub struct SettingsSnapshot {
    pub server_url: String,
    pub api_key_configured: bool,
    pub environment_override: bool,
}

#[derive(Clone, Debug)]
pub struct ServerSettings {
    pub url: String,
    pub api_key: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct PendingHistory {
    server_url: String,
    video_id: String,
    played: bool,
    update: HistoryUpdate,
    token: u64,
}

static PENDING_HISTORY_FLUSH_RUNNING: AtomicBool = AtomicBool::new(false);
static NEXT_PENDING_HISTORY_TOKEN: AtomicU64 = AtomicU64::new(1);

fn pending_history_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

pub fn load_settings() -> Option<ServerSettings> {
    if let (Some(url), Some(key)) = (
        std::env::var("AIR_SERVER_URL")
            .ok()
            .filter(|value| !value.trim().is_empty()),
        std::env::var("AIR_API_KEY")
            .ok()
            .filter(|value| !value.trim().is_empty()),
    ) {
        return Some(ServerSettings {
            url: normalize_url(&url),
            api_key: key,
        });
    }

    let path = settings_path()?;
    let text = std::fs::read_to_string(path).ok()?;
    #[derive(Deserialize)]
    struct FileSettings {
        #[serde(default)]
        server_url: String,
        #[serde(default)]
        api_key: String,
    }
    let settings: FileSettings = serde_json::from_str(&text).ok()?;
    if settings.server_url.trim().is_empty() || settings.api_key.trim().is_empty() {
        return None;
    }
    Some(ServerSettings {
        url: normalize_url(&settings.server_url),
        api_key: settings.api_key.trim().to_owned(),
    })
}

pub fn settings_snapshot() -> SettingsSnapshot {
    let environment_override = std::env::var("AIR_SERVER_URL")
        .ok()
        .is_some_and(|value| !value.trim().is_empty())
        || std::env::var("AIR_API_KEY")
            .ok()
            .is_some_and(|value| !value.trim().is_empty());
    match load_settings() {
        Some(settings) => SettingsSnapshot {
            server_url: settings.url,
            api_key_configured: !settings.api_key.is_empty(),
            environment_override,
        },
        None => SettingsSnapshot {
            server_url: String::new(),
            api_key_configured: false,
            environment_override,
        },
    }
}

pub fn save_settings(url: &str, api_key: &str) -> anyhow::Result<ServerSettings> {
    let url = normalize_url(url);
    let api_key = api_key.trim().to_owned();
    anyhow::ensure!(!url.is_empty(), "server URL is required");
    anyhow::ensure!(!api_key.is_empty(), "server API key is required");
    let path =
        settings_path().ok_or_else(|| anyhow::anyhow!("could not locate executable directory"))?;
    let payload = serde_json::json!({ "server_url": url, "api_key": api_key });
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, serde_json::to_vec_pretty(&payload)?)?;
    if let Err(error) = std::fs::rename(&temporary, &path) {
        if error.kind() != std::io::ErrorKind::AlreadyExists {
            return Err(error.into());
        }
        std::fs::remove_file(&path)?;
        std::fs::rename(&temporary, &path)?;
    }
    Ok(ServerSettings { url, api_key })
}

/// Queue a history update locally before attempting to send it. The queue is
/// keyed by server URL and video ID, so frequent playback updates collapse to
/// the newest state while the server is unavailable.
pub fn queue_history(
    settings: &ServerSettings,
    video_id: &str,
    update: HistoryUpdate,
    played: bool,
) {
    if video_id.trim().is_empty() {
        return;
    }
    let pending = PendingHistory {
        server_url: settings.url.clone(),
        video_id: video_id.to_owned(),
        played,
        update,
        token: NEXT_PENDING_HISTORY_TOKEN.fetch_add(1, Ordering::Relaxed) ^ unix_millis(),
    };
    if let Ok(_guard) = pending_history_lock().lock() {
        let mut entries = load_pending_history();
        entries.retain(|entry| {
            entry.server_url != pending.server_url || entry.video_id != pending.video_id
        });
        entries.push(pending);
        entries.truncate(256);
        save_pending_history(&entries);
    }
    flush_pending_history();
}

/// Start the background retry worker, if one is not already running.
pub fn flush_pending_history() {
    if PENDING_HISTORY_FLUSH_RUNNING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
        .is_err()
    {
        return;
    }
    std::thread::spawn(|| {
        loop {
            let entries = pending_history_entries();
            if entries.is_empty() {
                break;
            }
            let mut succeeded = false;
            let mut attempted = false;
            for entry in entries {
                let Some(current_settings) = load_settings() else {
                    break;
                };
                attempted = true;
                // Keep the URL that was active when the update was queued,
                // while using the currently configured key. This mirrors the
                // Android client's behavior when settings change offline.
                let settings = ServerSettings {
                    url: entry.server_url.clone(),
                    api_key: current_settings.api_key,
                };
                match update_history(&settings, &entry.video_id, &entry.update, entry.played) {
                    Ok(()) => {
                        remove_pending_history(entry.token);
                        succeeded = true;
                    }
                    Err(error) => eprintln!(
                        "Could not sync queued server history for {}: {error:#}",
                        entry.video_id
                    ),
                }
            }
            if !attempted || !succeeded {
                // Avoid a tight retry loop when the server is offline. A new
                // update will also wake the worker immediately.
                std::thread::sleep(std::time::Duration::from_secs(30));
            }
        }
        PENDING_HISTORY_FLUSH_RUNNING.store(false, Ordering::Release);
        // A queue update can race with the worker's final empty check. Start
        // another pass if anything arrived after that check.
        if !pending_history_entries().is_empty() {
            flush_pending_history();
        }
    });
}

pub fn clear_pending_history() {
    if let Ok(_guard) = pending_history_lock().lock() {
        if let Some(path) = pending_history_path() {
            let _ = std::fs::remove_file(path);
        }
    }
}

pub fn clear_history(settings: &ServerSettings) -> anyhow::Result<()> {
    ureq::delete(&format!("{}/history", settings.url))
        .set("X-API-Key", &settings.api_key)
        .call()?;
    Ok(())
}

pub fn set_delete_flag(
    settings: &ServerSettings,
    video_id: &str,
    flagged: bool,
) -> anyhow::Result<()> {
    ureq::post(&format!("{}/videos/{}/delete-flag", settings.url, video_id))
        .set("X-API-Key", &settings.api_key)
        .query("flagged", if flagged { "true" } else { "false" })
        .call()?;
    Ok(())
}

pub fn fetch_library(settings: &ServerSettings) -> anyhow::Result<ServerLibrary> {
    let response = ureq::get(&format!("{}/videos", settings.url))
        .set("X-API-Key", &settings.api_key)
        .query("refresh", "cache")
        .query("client", "windows")
        .call()?;
    let mut library: LibraryResponse = response.into_json()?;
    let history_response: HistoryResponse = ureq::get(&format!("{}/history", settings.url))
        .set("X-API-Key", &settings.api_key)
        .query("client", "windows")
        .call()?
        .into_json()?;
    if library.history.is_empty() {
        library.history = history_response.history;
    } else {
        let mut seen = std::collections::HashSet::new();
        library
            .history
            .retain(|video| seen.insert(video.id.clone()));
        for video in history_response.history {
            if seen.insert(video.id.clone()) {
                library.history.push(video);
            }
        }
    }

    let mut videos = library.videos;
    let played = library.history;
    for video in &mut videos {
        // Items returned by `/videos` come from the server's current
        // library, so they are streamable even when they have no history
        // record yet. History-only rows may still be unavailable.
        video.available = true;
        if let Some(history) = played.iter().find(|history| history.id == video.id) {
            video.last_played_at_ms = history.last_played_at_ms;
            video.position_ms = history.position_ms;
            video.watched_ms = history.watched_ms;
            video.projection_mode = history.projection_mode;
            video.loop_start_ms = history.loop_start_ms;
            video.loop_end_ms = history.loop_end_ms;
        }
    }
    Ok(ServerLibrary { videos, played })
}

pub fn fetch_videos(settings: &ServerSettings) -> anyhow::Result<Vec<ServerVideo>> {
    Ok(fetch_library(settings)?.videos)
}

pub fn update_history(
    settings: &ServerSettings,
    video_id: &str,
    update: &HistoryUpdate,
    played: bool,
) -> anyhow::Result<()> {
    ureq::put(&format!(
        "{}/videos/{}/history?played={}",
        settings.url, video_id, played
    ))
    .set("X-API-Key", &settings.api_key)
    .send_json(update)?
    .into_json::<serde_json::Value>()?;
    Ok(())
}

fn pending_history_entries() -> Vec<PendingHistory> {
    let Ok(_guard) = pending_history_lock().lock() else {
        return Vec::new();
    };
    load_pending_history()
}

fn remove_pending_history(token: u64) {
    let Ok(_guard) = pending_history_lock().lock() else {
        return;
    };
    let mut entries = load_pending_history();
    let before = entries.len();
    entries.retain(|entry| entry.token != token);
    if entries.len() != before {
        save_pending_history(&entries);
    }
}

fn load_pending_history() -> Vec<PendingHistory> {
    let Some(path) = pending_history_path() else {
        return Vec::new();
    };
    std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_pending_history(entries: &[PendingHistory]) {
    let Some(path) = pending_history_path() else {
        return;
    };
    let Ok(payload) = serde_json::to_vec_pretty(entries) else {
        return;
    };
    let temporary = path.with_extension("json.tmp");
    let result = std::fs::write(&temporary, payload).and_then(|_| {
        match std::fs::rename(&temporary, &path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                std::fs::remove_file(&path)?;
                std::fs::rename(&temporary, &path)
            }
            Err(error) => Err(error),
        }
    });
    if let Err(error) = result {
        eprintln!("[server] could not save pending history: {error}");
        let _ = std::fs::remove_file(temporary);
    }
}

pub fn unix_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

fn normalize_url(value: &str) -> String {
    value.trim().trim_end_matches('/').to_owned()
}

fn settings_path() -> Option<std::path::PathBuf> {
    let executable = std::env::current_exe().ok()?;
    let directory = executable.parent()?;
    Some(directory.join("server.json"))
}

fn pending_history_path() -> Option<std::path::PathBuf> {
    let executable = std::env::current_exe().ok()?;
    let directory = executable.parent()?;
    Some(directory.join("history-pending.json"))
}
