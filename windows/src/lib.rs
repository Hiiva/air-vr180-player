pub mod app;
pub mod control;
pub mod display;
pub mod hid;
pub mod mpv;
pub mod preferences;
pub mod projection;
pub mod recent;
pub mod renderer;
pub mod server;
pub mod tracking;

pub fn run() -> anyhow::Result<()> {
    crate::app::run()
}
