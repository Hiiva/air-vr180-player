use crate::control::{ControlCommand, PlayerState, SharedState};
use crate::hid::NrealHid;
use crate::mpv::Mpv;
use crate::recent::RecentVideo;
use crate::renderer::{Renderer, PROJECTION_EQUIRECT_VR180};
use crate::server;
use std::collections::HashMap;
use std::num::NonZeroU32;

use glutin::config::{ConfigTemplateBuilder, GlConfig};
use glutin::context::{
    ContextAttributesBuilder, NotCurrentContext, NotCurrentGlContext, PossiblyCurrentContext,
    Version,
};
use glutin::display::GetGlDisplay;
use glutin::prelude::*;
use glutin::surface::{GlSurface, SwapInterval};
use glutin::surface::{Surface, SurfaceAttributesBuilder, WindowSurface};
use glutin_winit::DisplayBuilder;
use winit::application::ApplicationHandler;
use winit::event::{ElementState, KeyEvent, WindowEvent};
use winit::event_loop::{ActiveEventLoop, EventLoop, EventLoopProxy};
use winit::keyboard::{Key, NamedKey};
use winit::monitor::{MonitorHandle, VideoModeHandle};
use winit::raw_window_handle::HasWindowHandle;
use winit::window::{Fullscreen, Window, WindowId};

pub fn run() -> anyhow::Result<()> {
    let event_loop = EventLoop::<ControlCommand>::with_user_event().build()?;
    let proxy = event_loop.create_proxy();
    let (control_url, control_state) = crate::control::start(proxy.clone())?;
    println!("[remote] local endpoint: {control_url}");
    let mut application = AirApp {
        pending_media: std::env::args().nth(1),
        control_proxy: Some(proxy),
        control_state,
        ..AirApp::default()
    };
    event_loop.run_app(&mut application)?;
    Ok(())
}

#[derive(Default)]
struct AirApp {
    window: Option<Window>,
    gl_display: Option<glutin::display::Display>,
    gl_context: Option<PossiblyCurrentContext>,
    not_current_context: Option<NotCurrentContext>,
    gl_surface: Option<Surface<WindowSurface>>,
    renderer: Option<Renderer>,
    mpv: Option<Mpv>,
    hid: Option<NrealHid>,
    control_state: SharedState,
    server_videos: Vec<server::ServerVideo>,
    server_played_videos: Vec<server::ServerVideo>,
    recent_videos: Vec<RecentVideo>,
    pending_media: Option<String>,
    pending_server_play_id: Option<String>,
    control_proxy: Option<EventLoopProxy<ControlCommand>>,
    current_server: Option<server::ServerVideo>,
    server_settings: Option<server::ServerSettings>,
    watch_started_at: Option<std::time::Instant>,
    last_history_sync_at: Option<std::time::Instant>,
    last_local_progress_save_at: Option<std::time::Instant>,
    loop_start_ms: Option<u64>,
    loop_end_ms: Option<u64>,
    loop_paused: bool,
    stereo_override: Option<bool>,
    last_title_update: std::cell::Cell<Option<std::time::Instant>>,
    last_control_update: std::cell::Cell<Option<std::time::Instant>>,
    last_frame_at: Option<std::time::Instant>,
    next_frame_at: Option<std::time::Instant>,
    average_frame_interval: f32,
    current_media_path: String,
    current_media_title: String,
    zoom: f32,
    frame_count: u64,
    view_center_degrees: f32,
    view_horizon_degrees: f32,
    projection_mode: i32,
    muted: bool,
    pending_seek: Option<PendingSeek>,
    glasses_fullscreen_requested: bool,
    last_glasses_fullscreen_attempt: Option<std::time::Instant>,
}

struct PendingSeek {
    path: String,
    seconds: f64,
    queued_at: std::time::Instant,
    last_attempt_at: Option<std::time::Instant>,
    attempts: u32,
}

impl ApplicationHandler<ControlCommand> for AirApp {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }

        let window_attributes = Window::default_attributes()
            .with_title("Air Windows Player")
            .with_inner_size(winit::dpi::LogicalSize::new(1280.0_f64, 720.0));
        let (window, gl_config) = DisplayBuilder::new()
            .with_window_attributes(Some(window_attributes))
            .build(event_loop, ConfigTemplateBuilder::new(), |configs| {
                configs
                    .max_by_key(|config| {
                        (config.hardware_accelerated() as u32) * 100_000_000
                            + config.num_samples() as u32
                    })
                    .expect("no OpenGL configuration")
            })
            .expect("OpenGL display");
        let window = window.expect("OpenGL window");
        let display = gl_config.display();
        let raw_window_handle = window.window_handle().expect("window handle").as_raw();
        let context_attributes = ContextAttributesBuilder::new()
            .with_context_api(glutin::context::ContextApi::OpenGl(Some(Version::new(
                3, 3,
            ))))
            .build(Some(raw_window_handle));
        let not_current_context =
            unsafe { display.create_context(&gl_config, &context_attributes) }
                .expect("OpenGL context");
        let window_size = window.inner_size();
        let surface_attributes = SurfaceAttributesBuilder::<WindowSurface>::new().build(
            raw_window_handle,
            NonZeroU32::new(window_size.width.max(1)).unwrap(),
            NonZeroU32::new(window_size.height.max(1)).unwrap(),
        );
        let surface = unsafe { display.create_window_surface(&gl_config, &surface_attributes) }
            .expect("OpenGL surface");
        let context = not_current_context
            .make_current(&surface)
            .expect("current OpenGL context");
        let _ = surface.set_swap_interval(
            &context,
            SwapInterval::Wait(NonZeroU32::new(1).expect("non-zero swap interval")),
        );
        let glow_context = unsafe {
            glow::Context::from_loader_function_cstr(|name| display.get_proc_address(name))
        };

        let renderer = unsafe { Renderer::new(glow_context) }.expect("renderer");
        let loader_display = display.clone();
        let mpv_loader: Box<dyn Fn(&std::ffi::CStr) -> *mut std::ffi::c_void> =
            Box::new(move |name| loader_display.get_proc_address(name).cast_mut());
        let mpv = Mpv::new(mpv_loader)
            .expect("libmpv-2.dll was not found beside the executable or on PATH");
        let preferences = crate::preferences::load();
        let hid = NrealHid::new();
        hid.start();
        window.request_redraw();
        window.set_visible(true);

        self.window = Some(window);
        self.gl_display = Some(display);
        self.not_current_context = None;
        self.gl_context = Some(context);
        self.gl_surface = Some(surface);
        self.renderer = Some(renderer);
        self.mpv = Some(mpv);
        self.hid = Some(hid);
        self.stereo_override = match preferences.stereo_mode {
            0 => Some(false),
            -1 => None,
            _ => Some(true),
        };
        self.recent_videos = crate::recent::load();
        self.zoom = preferences.zoom.clamp(0.60, 1.80);
        self.view_center_degrees = preferences.yaw_degrees.clamp(-45.0, 45.0);
        self.view_horizon_degrees = preferences.horizon_degrees.clamp(-30.0, 30.0);
        self.muted = preferences.muted;
        self.projection_mode = PROJECTION_EQUIRECT_VR180;
        if let Some(mpv) = &self.mpv {
            let _ = mpv.set_muted(self.muted);
        }
        self.request_fullscreen_on_glasses();
        server::flush_pending_history();
        self.load_server_library();
        if let Some(media) = self.pending_media.take() {
            self.load_media(&media);
        }
    }

    fn window_event(
        &mut self,
        event_loop: &ActiveEventLoop,
        _window_id: WindowId,
        event: WindowEvent,
    ) {
        match event {
            WindowEvent::CloseRequested => {
                self.sync_current_progress(true);
                event_loop.exit();
            }
            WindowEvent::RedrawRequested => self.draw(),
            WindowEvent::Resized(size) => {
                if let (Some(context), Some(surface)) = (&self.gl_context, &self.gl_surface) {
                    surface.resize(
                        context,
                        NonZeroU32::new(size.width.max(1)).unwrap(),
                        NonZeroU32::new(size.height.max(1)).unwrap(),
                    );
                }
                if let Some(window) = &self.window {
                    window.request_redraw();
                }
            }
            WindowEvent::KeyboardInput { event, .. } => self.handle_key(event, event_loop),
            WindowEvent::DroppedFile(path) => {
                let media_path = crate::mpv::canonical_media_path(&path);
                self.load_media(&media_path);
            }
            _ => {}
        }
    }

    fn user_event(&mut self, _event_loop: &ActiveEventLoop, command: ControlCommand) {
        self.handle_command(command);
    }
}

impl AirApp {
    fn handle_command(&mut self, command: ControlCommand) {
        match command {
            ControlCommand::PlayPause => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.play_pause();
                }
            }
            ControlCommand::Stop => {
                self.sync_current_progress(true);
                self.stop_playback_without_sync();
            }
            ControlCommand::SeekRelative(seconds) => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.seek_relative(seconds);
                }
            }
            ControlCommand::SeekAbsolute(seconds) => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.seek_absolute(seconds);
                }
            }
            ControlCommand::LoadScene { video_id, start_ms, end_ms, reply } => {
                let result = self.restore_saved_scene(&video_id, start_ms, end_ms)
                    .map_err(|error| error.to_string());
                if let Some(reply) = reply { let _ = reply.send(result); }
            }
            ControlCommand::SetVolume(volume) => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.set_volume(volume.clamp(0, 130));
                }
            }
            ControlCommand::SetMuted(muted) => {
                self.muted = muted;
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.set_muted(muted);
                }
                self.save_preferences();
            }
            ControlCommand::ToggleMute => {
                if let Some(mpv) = &self.mpv {
                    self.muted = !mpv.is_muted();
                    let _ = mpv.set_muted(self.muted);
                }
                self.save_preferences();
            }
            ControlCommand::SetSpeed(speed) => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.set_speed(speed.clamp(0.25, 4.0));
                }
            }
            ControlCommand::SetZoom(zoom) => {
                self.zoom = zoom.clamp(0.60, 1.80);
                self.save_preferences();
            }
            ControlCommand::SetYaw(yaw) => {
                self.view_center_degrees = yaw.clamp(-45.0, 45.0);
                self.save_preferences();
            }
            ControlCommand::SetHorizon(horizon) => {
                self.view_horizon_degrees = horizon.clamp(-30.0, 30.0);
                self.save_preferences();
            }
            ControlCommand::ResetView => {
                self.view_center_degrees = 0.0;
                self.view_horizon_degrees = 0.0;
                self.save_preferences();
            }
            ControlCommand::SetProjection(mode) => {
                self.projection_mode = mode.clamp(0, 2);
                self.persist_current_projection();
            }
            ControlCommand::SetStereo(stereo) => {
                self.stereo_override = Some(stereo);
                self.save_preferences();
            }
            ControlCommand::SetStereoAuto => {
                self.stereo_override = None;
                self.save_preferences();
            }
            ControlCommand::Recenter => {
                if let Some(hid) = &self.hid {
                    hid.recenter();
                }
            }
            ControlCommand::ReloadLibrary => self.load_server_library(),
            ControlCommand::PlayServer(index) => self.play_server_index(index),
            ControlCommand::PlayServerId(id) => self.play_server_id(&id),
            ControlCommand::PlayAdjacent(direction) => self.play_adjacent_server_video(direction),
            ControlCommand::ToggleServerDelete(id) => self.toggle_server_delete(&id),
            ControlCommand::ClearServerHistory => self.clear_server_history(),
            ControlCommand::SetServerSettings { url, api_key } => {
                let api_key = if api_key.trim().is_empty() {
                    server::load_settings()
                        .map(|settings| settings.api_key)
                        .unwrap_or_default()
                } else {
                    api_key
                };
                match server::save_settings(&url, &api_key) {
                    Ok(settings) => {
                        self.server_settings = Some(settings);
                        self.server_videos.clear();
                        self.server_played_videos.clear();
                        server::flush_pending_history();
                        self.load_server_library();
                    }
                    Err(error) => eprintln!("Could not save server settings: {error:#}"),
                }
            }
            ControlCommand::SetServerLibrary { videos, played } => {
                self.server_settings = server::load_settings();
                self.server_videos = videos;
                self.server_played_videos = played;
                if let Some(id) = self.pending_server_play_id.take() {
                    self.play_server_id(&id);
                }
            }
            ControlCommand::OpenLocalFile => self.open_file(),
            ControlCommand::LoadMedia(path) => {
                self.load_media(&path);
            }
            ControlCommand::RemoveRecent(path) => {
                if crate::recent::remove(&mut self.recent_videos, &path) {
                    crate::recent::save(&self.recent_videos);
                    if self.current_server.is_none()
                        && self.current_media_path.eq_ignore_ascii_case(&path)
                    {
                        self.stop_playback_without_sync();
                    }
                }
            }
            ControlCommand::ClearRecents => {
                crate::recent::clear(&mut self.recent_videos);
                if self.current_server.is_none() {
                    self.stop_playback_without_sync();
                }
            }
            ControlCommand::SetLoopStart => {
                self.loop_start_ms = self
                    .mpv
                    .as_ref()
                    .and_then(|mpv| mpv.position_seconds())
                    .map(|seconds| (seconds * 1000.0).max(0.0) as u64);
                self.persist_current_loop();
            }
            ControlCommand::SetLoopEnd => {
                self.loop_end_ms = self
                    .mpv
                    .as_ref()
                    .and_then(|mpv| mpv.position_seconds())
                    .map(|seconds| (seconds * 1000.0).max(0.0) as u64);
                self.persist_current_loop();
            }
            ControlCommand::ClearLoopStart => {
                self.loop_start_ms = None;
                self.persist_current_loop();
            }
            ControlCommand::ClearLoopEnd => {
                self.loop_end_ms = None;
                self.persist_current_loop();
            }
            ControlCommand::ToggleLoopPaused => {
                self.loop_paused = !self.loop_paused;
                if !self.loop_paused {
                    self.enforce_loop();
                }
            }
            ControlCommand::ClearLoop => {
                self.loop_start_ms = None;
                self.loop_end_ms = None;
                self.loop_paused = false;
                self.persist_current_loop();
            }
            ControlCommand::FullscreenGlasses => self.request_fullscreen_on_glasses(),
        }
    }

    fn load_media(&mut self, source: &str) {
        if source.starts_with("http://") || source.starts_with("https://") {
            self.sync_current_progress(true);
            self.current_server = None;
            self.current_media_path = source.to_owned();
            self.current_media_title = source.to_owned();
            self.loop_start_ms = None;
            self.loop_end_ms = None;
            self.loop_paused = false;
            self.watch_started_at = None;
            self.last_local_progress_save_at = None;
            self.load_media_named(source, source, None);
        } else {
            self.play_local_path(source);
        }
    }

    fn stop_playback_without_sync(&mut self) {
        if let Some(mpv) = &self.mpv {
            let _ = mpv.stop();
        }
        self.current_media_path.clear();
        self.current_media_title.clear();
        self.current_server = None;
        self.loop_start_ms = None;
        self.loop_end_ms = None;
        self.loop_paused = false;
        self.watch_started_at = None;
        self.last_history_sync_at = None;
        self.last_local_progress_save_at = None;
        self.pending_seek = None;
    }

    fn play_local_path(&mut self, source: &str) {
        let path = crate::mpv::canonical_media_path(std::path::Path::new(source));
        self.sync_current_progress(true);
        let path_info = std::path::Path::new(&path);
        let title = path_info.file_name().map_or_else(
            || source.to_owned(),
            |name| name.to_string_lossy().into_owned(),
        );
        let size = std::fs::metadata(path_info)
            .map(|metadata| metadata.len())
            .unwrap_or(0);
        let existing = self
            .recent_videos
            .iter()
            .find(|video| video.path.eq_ignore_ascii_case(&path))
            .cloned();
        let recent = existing.unwrap_or_else(|| RecentVideo {
            path: path.clone(),
            title: title.clone(),
            size,
            last_position_ms: 0,
            watched_ms: 0,
            duration_ms: 0,
            projection_mode: crate::projection::guess_from_name(&title),
            last_played_at_ms: 0,
        });
        self.current_server = None;
        self.current_media_path = path.clone();
        self.current_media_title = if recent.title.is_empty() {
            title
        } else {
            recent.title.clone()
        };
        self.projection_mode = recent.projection_mode.clamp(0, 2);
        self.loop_start_ms = None;
        self.loop_end_ms = None;
        self.loop_paused = false;
        self.watch_started_at = None;
        self.last_local_progress_save_at = None;
        self.pending_seek = None;
        crate::recent::upsert(&mut self.recent_videos, recent.clone());
        crate::recent::save(&self.recent_videos);
        let start_seconds = recent.last_position_ms as f64 / 1000.0;
        if let Some(mpv) = &self.mpv {
            let result = mpv.load_file(&path);
            if let Err(error) = result {
                eprintln!("Could not open media: {error:#}");
            } else if start_seconds > 1.0 {
                self.pending_seek = Some(PendingSeek {
                    path,
                    seconds: start_seconds,
                    queued_at: std::time::Instant::now(),
                    last_attempt_at: None,
                    attempts: 0,
                });
            }
        }
    }

    fn load_media_named(
        &mut self,
        source: &str,
        projection_hint: &str,
        start_seconds: Option<f64>,
    ) {
        self.projection_mode = crate::projection::guess_from_name(projection_hint).clamp(0, 2);
        self.pending_seek = None;
        if let Some(mpv) = &self.mpv {
            let result = mpv.load_file(source);
            if let Err(error) = result {
                eprintln!("Could not open media: {error:#}");
            } else if let Some(start) = start_seconds.filter(|start| *start > 1.0) {
                self.pending_seek = Some(PendingSeek {
                    path: source.to_owned(),
                    seconds: start,
                    queued_at: std::time::Instant::now(),
                    last_attempt_at: None,
                    attempts: 0,
                });
            }
        }
    }

    fn apply_pending_seek(&mut self) {
        const INITIAL_DELAY: std::time::Duration = std::time::Duration::from_millis(150);
        const RETRY_INTERVAL: std::time::Duration = std::time::Duration::from_millis(250);
        const RESUME_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

        let Some(pending) = self.pending_seek.as_ref() else {
            return;
        };
        let now = std::time::Instant::now();
        if now.duration_since(pending.queued_at) < INITIAL_DELAY {
            return;
        }
        if now.duration_since(pending.queued_at) >= RESUME_TIMEOUT {
            eprintln!(
                "Could not resume media at {:.3}s after {} attempts; giving up",
                pending.seconds, pending.attempts
            );
            self.pending_seek = None;
            return;
        }
        if pending
            .last_attempt_at
            .is_some_and(|last| now.duration_since(last) < RETRY_INTERVAL)
        {
            return;
        }

        let pending_path = pending.path.clone();
        let pending_seconds = pending.seconds;
        let Some(mpv) = self.mpv.as_ref() else {
            return;
        };
        let path_matches = mpv
            .path()
            .is_some_and(|path| path.eq_ignore_ascii_case(&pending_path));
        let media_ready = mpv
            .duration_seconds()
            .is_some_and(|duration| duration.is_finite() && duration > 0.0);
        let playback_started = mpv.position_seconds().is_some();
        if !path_matches || !media_ready || !playback_started || !mpv.is_seekable() {
            return;
        }

        let result = mpv.seek_absolute(pending_seconds);
        let attempt = if let Some(pending) = self.pending_seek.as_mut() {
            pending.last_attempt_at = Some(now);
            pending.attempts = pending.attempts.saturating_add(1);
            pending.attempts
        } else {
            return;
        };

        match result {
            Ok(()) => {
                eprintln!(
                    "[resume] seek accepted at {:.3}s after {} attempt{}",
                    pending_seconds,
                    attempt,
                    if attempt == 1 { "" } else { "s" }
                );
                self.pending_seek = None;
            }
            Err(error) => {
                // loadfile is asynchronous. Even after properties for the new
                // file appear, the demuxer can transiently reject a seek. Keep
                // the saved position queued and retry instead of losing it.
                if attempt == 1 || attempt % 20 == 0 {
                    eprintln!(
                        "[resume] seek to {:.3}s not ready yet (attempt {}): {error:#}",
                        pending_seconds, attempt
                    );
                }
            }
        }
    }

    fn persist_current_projection(&mut self) {
        if let Some(mut current_server) = self.current_server.clone() {
            current_server.projection_mode = self.projection_mode;
            self.current_server = Some(current_server.clone());
            self.upsert_server_played(current_server);
            self.sync_history(true);
            return;
        }
        if self.current_media_path.is_empty() || self.current_media_path.starts_with("http") {
            return;
        }
        if let Some(recent) = self
            .recent_videos
            .iter_mut()
            .find(|video| video.path.eq_ignore_ascii_case(&self.current_media_path))
        {
            recent.projection_mode = self.projection_mode;
            crate::recent::save(&self.recent_videos);
        }
    }

    fn save_preferences(&self) {
        let stereo_mode = match self.stereo_override {
            Some(false) => 0,
            Some(true) => 1,
            None => -1,
        };
        crate::preferences::save(&crate::preferences::PlayerPreferences {
            zoom: self.zoom,
            yaw_degrees: self.view_center_degrees,
            horizon_degrees: self.view_horizon_degrees,
            muted: self.muted,
            stereo_mode,
        });
    }

    fn restore_saved_scene(&mut self, video_id: &str, start_ms: u64, end_ms: Option<u64>) -> anyhow::Result<()> {
        anyhow::ensure!(self.current_server.as_ref().is_some_and(|video| video.id == video_id),
            "The current video changed. Reopen Scenes.");
        let mpv = self.mpv.as_ref().ok_or_else(|| anyhow::anyhow!("No video loaded"))?;
        anyhow::ensure!(mpv.has_media() && mpv.is_seekable(), "The video is not ready to seek");
        if let Some(duration) = mpv.duration_seconds().filter(|value| value.is_finite() && *value > 0.0) {
            let duration_ms = (duration * 1000.0).round() as u64;
            anyhow::ensure!(start_ms < duration_ms && end_ms.is_none_or(|end| end <= duration_ms),
                "This scene is outside the video's duration");
        }
        // This runs as one event, before the render loop can enforce the old A/B range.
        let paused = mpv.is_paused();
        mpv.seek_absolute(start_ms as f64 / 1000.0)?;
        mpv.set_paused(paused)?;
        self.pending_seek = None;
        self.loop_start_ms = end_ms.map(|_| start_ms);
        self.loop_end_ms = end_ms;
        self.loop_paused = false;
        self.persist_current_loop();
        Ok(())
    }

    fn persist_current_loop(&mut self) {
        if let Some(mut current_server) = self.current_server.clone() {
            current_server.loop_start_ms = self.loop_start_ms;
            current_server.loop_end_ms = self.loop_end_ms;
            self.current_server = Some(current_server.clone());
            self.upsert_server_played(current_server);
            self.sync_history(true);
        }
    }

    fn sync_current_progress(&mut self, force: bool) {
        if self.current_server.is_some() {
            self.sync_history(force);
        } else {
            self.sync_local_progress(force);
        }
    }

    fn sync_local_progress(&mut self, force: bool) {
        const SYNC_INTERVAL: std::time::Duration = std::time::Duration::from_secs(15);
        if self.current_media_path.is_empty() || self.current_media_path.starts_with("http") {
            return;
        }
        let now = std::time::Instant::now();
        if !force
            && self
                .last_local_progress_save_at
                .is_some_and(|last| now.duration_since(last) < SYNC_INTERVAL)
        {
            return;
        }
        let Some(mpv) = self.mpv.as_ref() else {
            return;
        };
        let paused = mpv.is_paused();
        let position_ms = mpv
            .position_seconds()
            .map_or(0, |seconds| (seconds.max(0.0) * 1000.0).round() as u64);
        let duration_ms = mpv
            .duration_seconds()
            .map_or(0, |seconds| (seconds.max(0.0) * 1000.0).round() as u64);
        let watched_delta_ms = self.watch_started_at.take().map_or(0, |started| {
            if paused {
                0
            } else {
                started.elapsed().as_millis() as u64
            }
        });
        self.watch_started_at = if paused { None } else { Some(now) };
        if let Some(recent) = self
            .recent_videos
            .iter_mut()
            .find(|video| video.path.eq_ignore_ascii_case(&self.current_media_path))
        {
            let mut position_ms = position_ms;
            if duration_ms > 0 && duration_ms.saturating_sub(position_ms) < 2_500 {
                position_ms = 0;
            }
            recent.last_position_ms = position_ms;
            if duration_ms > 0 {
                recent.duration_ms = duration_ms;
            }
            recent.watched_ms = recent.watched_ms.saturating_add(watched_delta_ms);
            recent.projection_mode = self.projection_mode;
            recent.last_played_at_ms = server::unix_millis();
            crate::recent::save(&self.recent_videos);
        }
        self.last_local_progress_save_at = Some(now);
    }

    fn refresh_watch_anchor(&mut self) {
        let Some(mpv) = self.mpv.as_ref() else {
            return;
        };
        if mpv.has_media() && !mpv.is_paused() {
            if self.watch_started_at.is_none() {
                self.watch_started_at = Some(std::time::Instant::now());
            }
        } else {
            self.watch_started_at = None;
        }
    }

    fn enforce_loop(&mut self) {
        if self.loop_paused {
            return;
        }
        let (Some(start_ms), Some(end_ms)) = (self.loop_start_ms, self.loop_end_ms) else {
            return;
        };
        if end_ms <= start_ms {
            return;
        }
        let Some(position_seconds) = self.mpv.as_ref().and_then(|mpv| mpv.position_seconds())
        else {
            return;
        };
        let position_ms = (position_seconds * 1000.0).max(0.0) as u64;
        if position_ms >= end_ms || position_ms < start_ms {
            if let Some(mpv) = &self.mpv {
                let _ = mpv.seek_absolute(start_ms as f64 / 1000.0);
            }
        }
    }

    fn draw(&mut self) {
        self.frame_count = self.frame_count.wrapping_add(1);
        self.retry_fullscreen_on_glasses();
        self.apply_pending_seek();
        self.enforce_loop();
        self.refresh_watch_anchor();
        self.sync_current_progress(false);
        let (Some(window), Some(context), Some(surface), Some(renderer), Some(mpv), Some(hid)) = (
            self.window.as_ref(),
            self.gl_context.as_ref(),
            self.gl_surface.as_ref(),
            self.renderer.as_ref(),
            self.mpv.as_ref(),
            self.hid.as_ref(),
        ) else {
            return;
        };
        let size = window.inner_size();
        let width = size.width.max(1) as usize;
        let height = size.height.max(1) as usize;
        let frame_started_at = std::time::Instant::now();
        if let Some(next_frame_at) = self.next_frame_at {
            if frame_started_at < next_frame_at {
                window.request_redraw();
                return;
            }
        }
        if renderer
            .draw(
                mpv,
                width,
                height,
                &hid.rotation(),
                self.zoom,
                [
                    self.view_center_degrees.to_radians(),
                    self.view_horizon_degrees.to_radians(),
                ],
                self.projection_mode,
                self.stereo_override,
            )
            .is_err()
        {
            // A transient decoder error should not close the controller.
        }
        let _ = surface.swap_buffers(context);
        mpv.report_swap();
        let now = std::time::Instant::now();
        let refresh_hz = window
            .current_monitor()
            .and_then(|monitor| monitor.refresh_rate_millihertz())
            .map(|refresh_millihertz| refresh_millihertz as f32 / 1000.0)
            .unwrap_or(60.0)
            .clamp(30.0, 240.0);
        self.next_frame_at =
            Some(frame_started_at + std::time::Duration::from_secs_f32(1.0 / refresh_hz));
        if let Some(last_frame_at) = self.last_frame_at {
            let interval = now
                .duration_since(last_frame_at)
                .as_secs_f32()
                .clamp(0.0001, 0.5);
            if self.average_frame_interval <= 0.0 {
                self.average_frame_interval = interval;
            } else {
                self.average_frame_interval = self.average_frame_interval * 0.90 + interval * 0.10;
            }
        }
        self.last_frame_at = Some(now);
        let render_fps = if self.average_frame_interval > 0.0 {
            1.0 / self.average_frame_interval
        } else {
            0.0
        };
        if self
            .last_control_update
            .get()
            .is_none_or(|last| now.duration_since(last) >= std::time::Duration::from_millis(100))
        {
            let status = hid.status();
            let stereo_mode = match self.stereo_override {
                Some(true) => "SBS",
                Some(false) => "mono",
                None if crate::renderer::should_render_stereo(width, height) => "SBS",
                None => "mono",
            };
            let fullscreen_on_glasses = window.fullscreen().is_some();
            if let Ok(mut state) = self.control_state.write() {
                *state = PlayerState {
                    media: if self.current_media_path.is_empty() {
                        String::new()
                    } else if self.current_media_title.is_empty() {
                        mpv.media_title().unwrap_or_default()
                    } else {
                        self.current_media_title.clone()
                    },
                    current_server_id: self
                        .current_server
                        .as_ref()
                        .map(|video| video.id.clone())
                        .unwrap_or_default(),
                    server_url: self.server_settings.as_ref().map(|settings| settings.url.clone()).unwrap_or_default(),
                    playing: mpv.has_media() && !mpv.is_paused(),
                    position_seconds: mpv.position_seconds().unwrap_or_default(),
                    duration_seconds: mpv.duration_seconds().unwrap_or_default(),
                    speed: mpv.speed(),
                    volume: mpv.volume(),
                    muted: mpv.is_muted(),
                    zoom: self.zoom,
                    yaw_degrees: self.view_center_degrees,
                    horizon_degrees: self.view_horizon_degrees,
                    projection_mode: self.projection_mode,
                    stereo: stereo_mode == "SBS",
                    stereo_mode: match self.stereo_override {
                        Some(true) => "sbs",
                        Some(false) => "mono",
                        None => "auto",
                    }
                    .to_owned(),
                    fullscreen_on_glasses,
                    output_width: size.width,
                    output_height: size.height,
                    loop_start_seconds: self.loop_start_ms.map(|value| value as f64 / 1000.0),
                    loop_end_seconds: self.loop_end_ms.map(|value| value as f64 / 1000.0),
                    loop_paused: self.loop_paused,
                    tracking: status.message.clone(),
                    render_fps,
                    server_configured: self.server_settings.is_some(),
                    server_library_count: self.server_videos.len(),
                    server_played_count: self.server_played_videos.len(),
                };
            }
            self.last_control_update.set(Some(now));
        }
        if self
            .last_title_update
            .get()
            .is_none_or(|last| now.duration_since(last) >= std::time::Duration::from_secs(1))
        {
            let stereo_mode = match self.stereo_override {
                Some(true) => "SBS",
                Some(false) => "mono",
                None if crate::renderer::should_render_stereo(width, height) => "SBS",
                None => "mono",
            };
            let frame_count = self.frame_count;
            let projection_name = projection_name(self.projection_mode);
            window.set_title(&format!(
                "Air Windows Player - {} - {} FPS - frame {frame_count} - {stereo_mode} - {projection_name}",
                hid.status().message,
                render_fps.round() as u32,
            ));
            self.last_title_update.set(Some(now));
        }
        window.request_redraw();
    }

    fn handle_key(&mut self, event: KeyEvent, event_loop: &ActiveEventLoop) {
        if event.state != ElementState::Pressed {
            return;
        }
        match event.logical_key {
            Key::Named(named) => self.handle_named(named, event_loop),
            other => self.handle_character(&other),
        }
    }

    fn handle_named(&mut self, named: NamedKey, event_loop: &ActiveEventLoop) {
        match named {
            NamedKey::Escape => event_loop.exit(),
            NamedKey::Space => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.play_pause();
                }
            }
            NamedKey::ArrowLeft => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.seek_relative(-10.0);
                }
            }
            NamedKey::ArrowRight => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.seek_relative(10.0);
                }
            }
            NamedKey::ArrowUp => {
                self.view_horizon_degrees = (self.view_horizon_degrees + 1.0).clamp(-30.0, 30.0);
                self.save_preferences();
            }
            NamedKey::ArrowDown => {
                self.view_horizon_degrees = (self.view_horizon_degrees - 1.0).clamp(-30.0, 30.0);
                self.save_preferences();
            }
            NamedKey::F10 => {
                self.stereo_override = match self.stereo_override {
                    None => Some(true),
                    Some(true) => Some(false),
                    Some(false) => None,
                };
                self.save_preferences();
            }
            NamedKey::F11 => self.toggle_fullscreen(),
            _ => {}
        }
    }

    fn handle_character(&mut self, key: &Key) {
        let text = match key {
            Key::Character(text) => text.to_lowercase(),
            _ => return,
        };
        match text.as_str() {
            "o" => self.open_file(),
            "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" => {
                let index = text.parse::<usize>().unwrap_or(1) - 1;
                self.play_server_index(index);
            }
            "s" => self.load_server_library(),
            "[" => {
                self.loop_start_ms = self
                    .mpv
                    .as_ref()
                    .and_then(|mpv| mpv.position_seconds())
                    .map(|seconds| (seconds * 1000.0).max(0.0) as u64);
                self.persist_current_loop();
            }
            "]" => {
                self.loop_end_ms = self
                    .mpv
                    .as_ref()
                    .and_then(|mpv| mpv.position_seconds())
                    .map(|seconds| (seconds * 1000.0).max(0.0) as u64);
                self.persist_current_loop();
            }
            "\\" => {
                self.loop_start_ms = None;
                self.loop_end_ms = None;
                self.loop_paused = false;
                self.persist_current_loop();
            }
            "p" => {
                if let Some(mpv) = &self.mpv {
                    let _ = mpv.play_pause();
                }
            }
            "r" => {
                if let Some(hid) = &self.hid {
                    hid.recenter();
                }
            }
            "g" => self.request_fullscreen_on_glasses(),
            "+" | "=" => {
                self.zoom = (self.zoom + 0.05).clamp(0.60, 1.80);
                self.save_preferences();
            }
            "-" | "_" => {
                self.zoom = (self.zoom - 0.05).clamp(0.60, 1.80);
                self.save_preferences();
            }
            "," => {
                self.view_center_degrees = (self.view_center_degrees - 1.0).clamp(-45.0, 45.0);
                self.save_preferences();
            }
            "." => {
                self.view_center_degrees = (self.view_center_degrees + 1.0).clamp(-45.0, 45.0);
                self.save_preferences();
            }
            "m" => {
                if let Some(mpv) = &self.mpv {
                    self.muted = !mpv.is_muted();
                    let _ = mpv.set_muted(self.muted);
                }
                self.save_preferences();
            }
            "b" => {
                self.projection_mode = (self.projection_mode + 2) % 3;
                self.persist_current_projection();
            }
            "n" => {
                self.projection_mode = (self.projection_mode + 1) % 3;
                self.persist_current_projection();
            }
            _ => {}
        }
    }

    fn open_file(&mut self) {
        let Some(proxy) = self.control_proxy.clone() else {
            return;
        };
        std::thread::spawn(move || {
            let selected = rfd::FileDialog::new()
                .set_title("Open VR media")
                .add_filter(
                    "Media",
                    &["mp4", "mkv", "mov", "webm", "avi", "png", "jpg", "jpeg"],
                )
                .pick_file();
            if let Some(path) = selected {
                let media_path = crate::mpv::canonical_media_path(&path);
                let _ = proxy.send_event(ControlCommand::LoadMedia(media_path));
            }
        });
    }

    fn load_server_library(&mut self) {
        let Some(settings) = server::load_settings() else {
            println!(
                "Set AIR_SERVER_URL/AIR_API_KEY or create server.json beside the exe:\n{{ \"server_url\": \"http://host:50050\", \"api_key\": \"private-key\" }}"
            );
            return;
        };
        let proxy = self.control_proxy.clone().expect("control proxy");
        std::thread::spawn(move || match server::fetch_library(&settings) {
            Ok(library) => {
                let _ = proxy.send_event(ControlCommand::SetServerLibrary {
                    videos: library.videos,
                    played: library.played,
                });
            }
            Err(error) => eprintln!("Could not load server library: {error:#}"),
        });
    }

    fn play_server_index(&mut self, index: usize) {
        let Some(video) = self.server_videos.get(index).cloned() else {
            return;
        };
        self.play_server_video(video);
    }

    fn play_server_id(&mut self, id: &str) {
        let video = self
            .server_videos
            .iter()
            .chain(self.server_played_videos.iter())
            .find(|video| video.id == id)
            .cloned();
        if let Some(video) = video {
            self.pending_server_play_id = None;
            self.play_server_video(video);
            return;
        }
        // The Android remote can become ready before the asynchronous Windows
        // library refresh has completed. Remember the requested id, refresh,
        // and automatically retry once the library arrives instead of dropping
        // the user's tap.
        self.pending_server_play_id = Some(id.to_owned());
        self.load_server_library();
    }

    fn play_server_video(&mut self, video: server::ServerVideo) {
        let local_path = video.local_path.trim().to_owned();
        if local_path.is_empty() {
            eprintln!("Could not play server video without a local path: {}", video.id);
            return;
        }
        if !std::path::Path::new(&local_path).is_file() {
            eprintln!(
                "Could not play server video because the local file is unavailable: {}",
                local_path
            );
            return;
        }

        self.sync_current_progress(true);
        let mut video = video;
        video.last_played_at_ms = server::unix_millis();
        self.current_media_path = local_path.clone();
        self.current_media_title = display_name(&video);
        self.projection_mode = video.projection_mode.clamp(0, 2);
        self.current_server = Some(video.clone());
        self.watch_started_at = None;
        self.last_history_sync_at = Some(std::time::Instant::now());
        self.last_local_progress_save_at = None;
        self.pending_seek = None;
        self.loop_start_ms = video.loop_start_ms;
        self.loop_end_ms = video.loop_end_ms;
        self.loop_paused = false;
        self.upsert_server_played(video.clone());
        if let Some(mpv) = &self.mpv {
            let start_seconds = video.position_ms as f64 / 1000.0;
            let load_result = mpv.load_file(&local_path);
            if let Err(error) = load_result {
                eprintln!("Could not open local server video: {error:#}");
            } else if start_seconds > 1.0 {
                self.pending_seek = Some(PendingSeek {
                    path: local_path,
                    seconds: start_seconds,
                    queued_at: std::time::Instant::now(),
                    last_attempt_at: None,
                    attempts: 0,
                });
            }
        }
        if let Some(settings) = self.server_settings.clone() {
            self.send_server_history(settings, video, true);
        }
    }

    fn sync_history(&mut self, force: bool) {
        const SYNC_INTERVAL: std::time::Duration = std::time::Duration::from_secs(15);
        let now = std::time::Instant::now();
        if !force
            && self
                .last_history_sync_at
                .is_some_and(|last| now.duration_since(last) < SYNC_INTERVAL)
        {
            return;
        }
        let (Some(settings), Some(mut video), Some(mpv)) = (
            self.server_settings.clone(),
            self.current_server.clone(),
            self.mpv.as_ref(),
        ) else {
            return;
        };
        let watched_delta_ms = self.watch_started_at.take().map_or(0, |started| {
            if mpv.is_paused() {
                0
            } else {
                started.elapsed().as_millis() as u64
            }
        });
        self.watch_started_at = if mpv.is_paused() { None } else { Some(now) };
        video.watched_ms += watched_delta_ms;
        video.position_ms = mpv.position_seconds().map_or(video.position_ms, |seconds| {
            (seconds * 1000.0).max(0.0) as u64
        });
        if let (Some(duration), Some(position)) = (mpv.duration_seconds(), mpv.position_seconds()) {
            if duration > 0.0 && duration - position < 2.5 {
                video.position_ms = 0;
            }
            video.duration_ms = video.duration_ms.max((duration * 1000.0) as u64);
        }
        video.projection_mode = self.projection_mode;
        video.loop_start_ms = self.loop_start_ms;
        video.loop_end_ms = self.loop_end_ms;
        self.current_server = Some(video.clone());
        self.upsert_server_played(video.clone());
        self.last_history_sync_at = Some(now);

        self.send_server_history(settings, video, force);
    }

    fn send_server_history(
        &self,
        settings: server::ServerSettings,
        video: server::ServerVideo,
        played: bool,
    ) {
        let update = server::HistoryUpdate {
            id: video.id.clone(),
            title: video.title.clone(),
            file_name: video.file_name.clone(),
            subfolder: video.subfolder.clone(),
            size: video.size,
            duration_ms: video.duration_ms,
            modified_ms: video.modified_ms,
            file_date: video.file_date.clone(),
            position_ms: video.position_ms,
            watched_ms: video.watched_ms,
            projection_mode: video.projection_mode,
            loop_start_ms: video.loop_start_ms,
            loop_end_ms: video.loop_end_ms,
            last_played_at_ms: server::unix_millis(),
        };
        server::queue_history(&settings, &video.id, update, played);
    }

    fn upsert_server_played(&mut self, video: server::ServerVideo) {
        if video.id.is_empty() {
            return;
        }
        self.server_played_videos.retain(|item| item.id != video.id);
        self.server_played_videos.insert(0, video.clone());
        if let Some(library_video) = self
            .server_videos
            .iter_mut()
            .find(|item| item.id == video.id)
        {
            library_video.last_played_at_ms = video.last_played_at_ms;
            library_video.position_ms = video.position_ms;
            library_video.watched_ms = video.watched_ms;
            library_video.projection_mode = video.projection_mode;
            library_video.loop_start_ms = video.loop_start_ms;
            library_video.loop_end_ms = video.loop_end_ms;
        }
    }

    fn play_adjacent_server_video(&mut self, direction: i32) {
        if self.server_videos.is_empty() {
            return;
        }
        let current_id = self.current_server.as_ref().map(|video| video.id.as_str());
        let current_index =
            current_id.and_then(|id| self.server_videos.iter().position(|video| video.id == id));
        let direction = direction.signum();
        let target = match current_index {
            Some(index) => {
                let next = index as i32 + direction;
                next.clamp(0, self.server_videos.len().saturating_sub(1) as i32) as usize
            }
            None => {
                if direction < 0 {
                    self.server_videos.len().saturating_sub(1)
                } else {
                    0
                }
            }
        };
        if let Some(video) = self.server_videos.get(target).cloned() {
            self.play_server_video(video);
        }
    }

    fn toggle_server_delete(&mut self, id: &str) {
        let next_flag = self
            .server_videos
            .iter()
            .chain(self.server_played_videos.iter())
            .find(|video| video.id == id)
            .map(|video| !video.flagged_for_deletion);
        let Some(next_flag) = next_flag else {
            return;
        };
        for video in self
            .server_videos
            .iter_mut()
            .chain(self.server_played_videos.iter_mut())
        {
            if video.id == id {
                video.flagged_for_deletion = next_flag;
            }
        }
        let Some(settings) = self.server_settings.clone().or_else(server::load_settings) else {
            return;
        };
        let id = id.to_owned();
        std::thread::spawn(move || {
            if let Err(error) = server::set_delete_flag(&settings, &id, next_flag) {
                eprintln!("Could not update server deletion flag: {error:#}");
            }
        });
    }

    fn clear_server_history(&mut self) {
        if self.current_server.is_some() {
            self.stop_playback_without_sync();
        }
        self.server_played_videos.clear();
        server::clear_pending_history();
        let Some(settings) = self.server_settings.clone().or_else(server::load_settings) else {
            return;
        };
        let proxy = self.control_proxy.clone();
        std::thread::spawn(move || match server::clear_history(&settings) {
            Ok(()) => {
                if let Some(proxy) = proxy {
                    let _ = proxy.send_event(ControlCommand::ReloadLibrary);
                }
            }
            Err(error) => eprintln!("Could not clear server history: {error:#}"),
        });
    }

    fn toggle_fullscreen(&mut self) {
        let Some(window) = &self.window else {
            return;
        };
        self.glasses_fullscreen_requested = false;
        self.last_glasses_fullscreen_attempt = None;
        let next_fullscreen = if window.fullscreen().is_none() {
            let monitor = window.current_monitor();
            Some(Fullscreen::Borderless(monitor))
        } else {
            None
        };
        window.set_fullscreen(next_fullscreen);
    }

    fn request_fullscreen_on_glasses(&mut self) {
        self.glasses_fullscreen_requested = true;
        self.last_glasses_fullscreen_attempt = None;
        self.fullscreen_on_glasses();
    }

    fn retry_fullscreen_on_glasses(&mut self) {
        if !self.glasses_fullscreen_requested {
            return;
        }
        const RETRY_INTERVAL: std::time::Duration = std::time::Duration::from_millis(1000);
        if self
            .last_glasses_fullscreen_attempt
            .is_some_and(|last| last.elapsed() < RETRY_INTERVAL)
        {
            return;
        }
        self.fullscreen_on_glasses();
    }

    fn fullscreen_on_glasses(&mut self) {
        self.last_glasses_fullscreen_attempt = Some(std::time::Instant::now());
        let Some(window) = &self.window else {
            return;
        };

        let monitor_names = crate::display::active_monitor_names().unwrap_or_default();
        let current_info = window
            .current_monitor()
            .and_then(|monitor| crate::display::monitor_info(&monitor));
        println!(
            "[display] before selection current_monitor={:?}",
            current_info.as_ref().map(|info| info.device_name.as_str()),
        );

        // Xreal's 2D -> SBS switch changes the Windows monitor topology. A
        // MonitorHandle returned immediately before that change can become
        // stale. Read name/geometry through Win32's fallible GetMonitorInfoW
        // wrapper and skip stale handles instead of calling winit 0.30.13's
        // name()/size() methods, which unwrap that failure and panic.
        let mut candidates = Vec::new();
        for monitor in window.available_monitors() {
            let Some(info) = crate::display::monitor_info(&monitor) else {
                println!("[display] skipping stale monitor handle during mode transition");
                continue;
            };
            let name = normalize_display_name(&info.device_name);
            let score = monitor_glasses_score(&monitor, &info, &monitor_names);
            println!(
                "[display] candidate name={name:?} edid={:?} position=({}, {}) size={}x{}",
                monitor_names.get(&name).map(String::as_str),
                info.x,
                info.y,
                info.width,
                info.height,
            );
            candidates.push((monitor, info, score));
        }

        let selected = candidates
            .into_iter()
            .max_by_key(|(_, _, score)| *score)
            .filter(|(_, _, score)| *score > 0);
        let Some((monitor, _, _)) = selected else {
            println!("[display] No Air monitor found; staying on the desktop");
            return;
        };

        // Revalidate the native handle immediately before using it. If Windows
        // invalidated it while the mode list was changing, leave the request
        // pending and retry with a freshly enumerated handle on the next tick.
        let Some(info) = crate::display::monitor_info(&monitor) else {
            println!("[display] Air monitor handle changed during selection; retrying");
            return;
        };
        println!(
            "[display] selected name={:?} position=({}, {}) size={}x{}",
            Some(info.device_name.as_str()),
            info.x,
            info.y,
            info.width,
            info.height,
        );

        // Do not move/fullscreen the window while the glasses are still in
        // their pre-SBS 1920x1080 mode. The HID mode command rebuilds the
        // Windows monitor topology, and attaching the window to the old handle
        // here can leave it on the desktop after that transition. Keep the
        // request pending until the Air exposes its SBS mode (or is already
        // running at the exact SBS framebuffer size).
        let monitor_is_sbs_sized = info.width == SBS_WIDTH && info.height == SBS_HEIGHT;
        let already_fullscreen_on_air = window_is_fullscreen_on_monitor(window, &info);
        if already_fullscreen_on_air {
            self.glasses_fullscreen_requested = false;
            println!("[display] Air fullscreen confirmed");
            window.request_redraw();
            return;
        }

        if let Some(video_mode) = sbs_video_mode(&monitor) {
            let mode_size = video_mode.size();
            println!(
                "[display] selecting SBS video mode {}x{} @ {} mHz",
                mode_size.width,
                mode_size.height,
                video_mode.refresh_rate_millihertz(),
            );
            window.set_fullscreen(Some(Fullscreen::Exclusive(video_mode)));
            // Applying fullscreen is asynchronous. Leave the request pending;
            // the next retry confirms both the monitor and framebuffer size.
            self.glasses_fullscreen_requested = true;
        } else {
            println!(
                "[display] SBS video mode not exposed yet (current={}x{}); waiting for display transition",
                info.width, info.height,
            );
            if monitor_is_sbs_sized {
                // Some drivers update the active 3840x1080 mode before winit's
                // video-mode enumeration catches up. Once that exact native
                // size is visible, borderless fullscreen is safe to request.
                window.set_fullscreen(Some(Fullscreen::Borderless(Some(monitor.clone()))));
                window.set_outer_position(winit::dpi::PhysicalPosition::new(info.x, info.y));
                let requested = window.request_inner_size(winit::dpi::PhysicalSize::new(
                    info.width,
                    info.height,
                ));
                println!(
                    "[display] after fullscreen requested={requested:?} inner={}x{}",
                    window.inner_size().width,
                    window.inner_size().height,
                );
            }
            self.glasses_fullscreen_requested = true;
        }
        window.request_redraw();
    }

}

const SBS_WIDTH: u32 = 3840;
const SBS_HEIGHT: u32 = 1080;

fn sbs_video_mode(monitor: &MonitorHandle) -> Option<VideoModeHandle> {
    monitor
        .video_modes()
        .filter(|mode| {
            let size = mode.size();
            size.width == SBS_WIDTH && size.height == SBS_HEIGHT
        })
        // Mode 3 is the 60 Hz SBS mode requested from the glasses. Prefer it
        // if the driver exposes additional refresh rates as well.
        .min_by_key(|mode| {
            (
                mode.refresh_rate_millihertz().abs_diff(60_000),
                mode.bit_depth(),
            )
        })
}

fn monitor_glasses_score(
    monitor: &MonitorHandle,
    info: &crate::display::MonitorInfo,
    monitor_names: &HashMap<String, String>,
) -> i32 {
    let name = normalize_display_name(&info.device_name);
    let edid = monitor_names
        .get(&name)
        .map(String::as_str)
        .unwrap_or_default()
        .to_lowercase();
    let named =
        edid == "air" || edid.contains("xreal") || edid.contains("nreal") || edid.contains("air");
    // winit's video_modes() path already handles GetMonitorInfoW failure by
    // returning an empty iterator, unlike name()/size() in 0.30.13.
    let has_sbs_mode = monitor.video_modes().any(|mode| {
        let size = mode.size();
        size.width == SBS_WIDTH && size.height == SBS_HEIGHT
    });
    let dimensions_look_like_sbs = info.width == SBS_WIDTH && info.height == SBS_HEIGHT;
    i32::from(named) * 100 + i32::from(has_sbs_mode) * 10 + i32::from(dimensions_look_like_sbs)
}

fn window_is_fullscreen_on_monitor(window: &Window, selected: &crate::display::MonitorInfo) -> bool {
    if window.fullscreen().is_none() {
        return false;
    }
    let size = window.inner_size();
    if size.width != SBS_WIDTH || size.height != SBS_HEIGHT {
        return false;
    }
    window
        .current_monitor()
        .and_then(|monitor| crate::display::monitor_info(&monitor))
        .is_some_and(|current| current.device_name.eq_ignore_ascii_case(&selected.device_name))
}

fn normalize_display_name(name: &str) -> String {
    name.trim_start_matches("\\\\.\\")
        .trim_start_matches('\\')
        .to_lowercase()
}

fn display_name(video: &server::ServerVideo) -> String {
    if video.subfolder.is_empty() {
        video.file_name.clone()
    } else {
        format!("{} / {}", video.subfolder, video.file_name)
    }
}

fn projection_name(mode: i32) -> &'static str {
    match mode {
        crate::renderer::PROJECTION_FISHEYE_VR190 => "VR190 fisheye",
        crate::renderer::PROJECTION_FISHEYE_VR200 => "VR200 fisheye",
        _ => "VR180 equirectangular",
    }
}
