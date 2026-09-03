use serde::{Deserialize, Serialize};
use std::path::PathBuf;

const MAX_RECENTS: usize = 24;

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct RecentVideo {
    pub path: String,
    pub title: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub last_position_ms: u64,
    #[serde(default)]
    pub watched_ms: u64,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default)]
    pub projection_mode: i32,
    #[serde(default)]
    pub last_played_at_ms: u64,
}

pub fn load() -> Vec<RecentVideo> {
    let Some(path) = storage_path() else {
        return Vec::new();
    };
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    match serde_json::from_str::<Vec<RecentVideo>>(&text) {
        Ok(mut videos) => {
            videos.retain(|video| !video.path.trim().is_empty());
            videos.truncate(MAX_RECENTS);
            videos
        }
        Err(error) => {
            eprintln!("[recents] could not parse saved recents: {error}");
            Vec::new()
        }
    }
}

pub fn save(videos: &[RecentVideo]) {
    let Some(path) = storage_path() else {
        return;
    };
    let mut values = videos.to_vec();
    values.truncate(MAX_RECENTS);
    let Ok(payload) = serde_json::to_vec_pretty(&values) else {
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
        eprintln!("[recents] could not save recents: {error}");
        let _ = std::fs::remove_file(temporary);
    }
}

pub fn upsert(videos: &mut Vec<RecentVideo>, mut video: RecentVideo) {
    if video.path.trim().is_empty() {
        return;
    }
    if let Some(index) = videos
        .iter()
        .position(|existing| same_path(&existing.path, &video.path))
    {
        let existing = videos.remove(index);
        if video.title.trim().is_empty() {
            video.title = existing.title;
        }
        if video.size == 0 {
            video.size = existing.size;
        }
        if video.duration_ms == 0 {
            video.duration_ms = existing.duration_ms;
        }
        if video.watched_ms == 0 {
            video.watched_ms = existing.watched_ms;
        }
    }
    videos.insert(0, video);
    videos.truncate(MAX_RECENTS);
}

pub fn remove(videos: &mut Vec<RecentVideo>, path: &str) -> bool {
    let before = videos.len();
    videos.retain(|video| !same_path(&video.path, path));
    before != videos.len()
}

pub fn clear(videos: &mut Vec<RecentVideo>) {
    videos.clear();
    if let Some(path) = storage_path() {
        let _ = std::fs::remove_file(path);
    }
}

pub fn storage_path() -> Option<PathBuf> {
    let executable = std::env::current_exe().ok()?;
    Some(executable.parent()?.join("recent.json"))
}

fn same_path(left: &str, right: &str) -> bool {
    left.eq_ignore_ascii_case(right)
}
