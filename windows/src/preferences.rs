use serde::{Deserialize, Serialize};
use std::path::PathBuf;

fn default_zoom() -> f32 {
    0.80
}

fn default_muted() -> bool {
    true
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct PlayerPreferences {
    #[serde(default = "default_zoom")]
    pub zoom: f32,
    #[serde(default)]
    pub yaw_degrees: f32,
    #[serde(default)]
    pub horizon_degrees: f32,
    #[serde(default = "default_muted")]
    pub muted: bool,
    #[serde(default = "default_stereo_mode")]
    pub stereo_mode: i8,
}

impl Default for PlayerPreferences {
    fn default() -> Self {
        Self {
            zoom: default_zoom(),
            yaw_degrees: 0.0,
            horizon_degrees: 0.0,
            muted: default_muted(),
            stereo_mode: default_stereo_mode(),
        }
    }
}

fn default_stereo_mode() -> i8 {
    // Match Android: derive SBS from the active ultra-wide glasses surface.
    // A forced mode remains available through the controller/preferences.
    -1
}

pub fn load() -> PlayerPreferences {
    let Some(path) = storage_path() else {
        return PlayerPreferences::default();
    };
    std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

pub fn save(preferences: &PlayerPreferences) {
    let Some(path) = storage_path() else {
        return;
    };
    let Ok(payload) = serde_json::to_vec_pretty(preferences) else {
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
        eprintln!("[preferences] could not save player preferences: {error}");
        let _ = std::fs::remove_file(temporary);
    }
}

fn storage_path() -> Option<PathBuf> {
    let executable = std::env::current_exe().ok()?;
    Some(executable.parent()?.join("player.json"))
}
