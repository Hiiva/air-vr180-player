use std::collections::HashMap;

use anyhow::Result;
use windows_sys::Win32::Graphics::Gdi::{GetMonitorInfoW, MONITORINFO, MONITORINFOEXW};
use winit::monitor::MonitorHandle;
use winit::platform::windows::MonitorHandleExtWindows;
use windows_sys::Win32::Devices::Display::{
    DisplayConfigGetDeviceInfo, GetDisplayConfigBufferSizes, QueryDisplayConfig,
    DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME, DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME,
    DISPLAYCONFIG_MODE_INFO, DISPLAYCONFIG_PATH_INFO, DISPLAYCONFIG_SOURCE_DEVICE_NAME,
    DISPLAYCONFIG_TARGET_DEVICE_NAME, QDC_ONLY_ACTIVE_PATHS,
};

pub fn active_monitor_names() -> Result<HashMap<String, String>> {
    let mut path_count = 0;
    let mut mode_count = 0;
    let status = unsafe {
        GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &mut path_count, &mut mode_count)
    };
    anyhow::ensure!(status == 0, "GetDisplayConfigBufferSizes failed: {status}");

    let mut paths = vec![DISPLAYCONFIG_PATH_INFO::default(); path_count as usize];
    let mut modes = vec![DISPLAYCONFIG_MODE_INFO::default(); mode_count as usize];
    let status = unsafe {
        QueryDisplayConfig(
            QDC_ONLY_ACTIVE_PATHS,
            &mut path_count,
            paths.as_mut_ptr(),
            &mut mode_count,
            modes.as_mut_ptr(),
            std::ptr::null_mut(),
        )
    };
    anyhow::ensure!(status == 0, "QueryDisplayConfig failed: {status}");
    paths.truncate(path_count as usize);

    let mut names = HashMap::new();
    for path in &paths {
        let mut source = DISPLAYCONFIG_SOURCE_DEVICE_NAME::default();
        source.header.r#type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
        source.header.size = std::mem::size_of_val(&source) as u32;
        source.header.adapterId = path.sourceInfo.adapterId;
        source.header.id = path.sourceInfo.id;

        let mut target = DISPLAYCONFIG_TARGET_DEVICE_NAME::default();
        target.header.r#type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME;
        target.header.size = std::mem::size_of_val(&target) as u32;
        target.header.adapterId = path.targetInfo.adapterId;
        target.header.id = path.targetInfo.id;

        let source_status = unsafe { DisplayConfigGetDeviceInfo(&mut source.header) };
        let target_status = unsafe { DisplayConfigGetDeviceInfo(&mut target.header) };
        if source_status != 0 || target_status != 0 {
            continue;
        }

        let gdi_name = wide_string(&source.viewGdiDeviceName)
            .trim_start_matches("\\\\.\\")
            .to_lowercase();
        let monitor_name = wide_string(&target.monitorFriendlyDeviceName);
        names.insert(gdi_name, monitor_name);
    }
    Ok(names)
}

#[derive(Clone, Debug)]
pub struct MonitorInfo {
    pub device_name: String,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

/// Read monitor geometry directly from Win32 instead of winit's `name()` /
/// `size()` helpers. During the Xreal 2D -> SBS mode switch Windows can
/// invalidate an HMONITOR between enumeration and inspection. winit 0.30.13
/// unwraps GetMonitorInfoW failures in those helpers, which turns that normal
/// hot-plug race into a process panic. Here a stale handle is simply skipped
/// and the normal fullscreen retry will enumerate a fresh handle later.
pub fn monitor_info(monitor: &MonitorHandle) -> Option<MonitorInfo> {
    let mut info: MONITORINFOEXW = unsafe { std::mem::zeroed() };
    info.monitorInfo.cbSize = std::mem::size_of::<MONITORINFOEXW>() as u32;
    let status = unsafe {
        GetMonitorInfoW(
            monitor.hmonitor() as _,
            (&mut info as *mut MONITORINFOEXW).cast::<MONITORINFO>(),
        )
    };
    if status == 0 {
        return None;
    }

    let rect = info.monitorInfo.rcMonitor;
    Some(MonitorInfo {
        device_name: wide_string(&info.szDevice),
        x: rect.left,
        y: rect.top,
        width: (rect.right - rect.left).max(0) as u32,
        height: (rect.bottom - rect.top).max(0) as u32,
    })
}

fn wide_string(value: &[u16]) -> String {
    let length = value
        .iter()
        .position(|character| *character == 0)
        .unwrap_or(value.len());
    String::from_utf16_lossy(&value[..length])
}
