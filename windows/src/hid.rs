use crate::tracking::{HeadTracker, ImuSample};
use std::ffi::CString;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

const VENDOR_ID: u16 = 0x3318;
const PRODUCT_ID: u16 = 0x0424;
const MAX_FACTORY_CONFIG_BYTES: usize = 128 * 1024;

fn c_path(path: &[u8]) -> CString {
    CString::new(path.split(|byte| *byte == 0).next().unwrap_or(&[])).expect("HID path is valid")
}

#[derive(Clone, Debug)]
pub struct DeviceStatus {
    pub connected: bool,
    pub streaming: bool,
    pub message: String,
}

struct SharedState {
    tracker: Mutex<HeadTracker>,
    rotation: Mutex<[f32; 9]>,
    gyro_calibration: Mutex<[f32; 3]>,
    status: Mutex<DeviceStatus>,
}

impl SharedState {
    fn new(message: &'static str) -> Self {
        Self {
            tracker: Mutex::new(HeadTracker::new()),
            rotation: Mutex::new([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]),
            gyro_calibration: Mutex::new([0.0; 3]),
            status: Mutex::new(DeviceStatus {
                connected: false,
                streaming: false,
                message: message.to_owned(),
            }),
        }
    }
}

#[derive(Clone)]
pub struct NrealHid {
    state: Arc<SharedState>,
}

impl Default for NrealHid {
    fn default() -> Self {
        Self::new()
    }
}

impl NrealHid {
    pub fn new() -> Self {
        Self {
            state: Arc::new(SharedState::new("Looking for Xreal Air glasses")),
        }
    }

    pub fn rotation(&self) -> [f32; 9] {
        self.state
            .rotation
            .lock()
            .map(|rotation| *rotation)
            .unwrap_or([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0])
    }

    pub fn status(&self) -> DeviceStatus {
        self.state
            .status
            .lock()
            .map(|status| status.clone())
            .unwrap_or(DeviceStatus {
                connected: false,
                streaming: false,
                message: "Locked".to_owned(),
            })
    }

    pub fn recenter(&self) {
        if let Ok(mut tracker) = self.state.tracker.lock() {
            tracker.recenter();
        }
    }

    fn set_status(
        self_state: &SharedState,
        message: impl Into<String>,
        streaming: bool,
        connected: bool,
    ) {
        if let Ok(mut status) = self_state.status.lock() {
            let message = message.into();
            if status.message != message {
                eprintln!("[hid] {}", message);
            }
            status.message = message;
            status.streaming = streaming;
            status.connected = connected;
        }
    }

    fn reset_tracking(self_state: &SharedState) {
        if let Ok(mut tracker) = self_state.tracker.lock() {
            tracker.reset();
        }
        if let Ok(mut rotation) = self_state.rotation.lock() {
            *rotation = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0];
        }
        if let Ok(mut calibration) = self_state.gyro_calibration.lock() {
            *calibration = [0.0; 3];
        }
    }

    fn set_gyro_calibration(self_state: &SharedState, calibration: [f32; 3]) {
        if let Ok(mut target) = self_state.gyro_calibration.lock() {
            *target = calibration;
        }
    }

    pub fn start(&self) {
        let state = Arc::clone(&self.state);
        std::thread::spawn(move || loop {
            let quit = Arc::new(AtomicBool::new(false));
            match hidapi::HidApi::new() {
                Err(error) => {
                    Self::set_status(&state, format!("HID init failed: {error}"), false, false);
                    std::thread::sleep(std::time::Duration::from_secs(2));
                }
                Ok(api) => {
                    let devices: Vec<_> = api
                        .device_list()
                        .filter(|device| {
                            device.vendor_id() == VENDOR_ID && device.product_id() == PRODUCT_ID
                        })
                        .cloned()
                        .collect();
                    if devices.is_empty() {
                        Self::set_status(&state, "No Xreal Air glasses found", false, false);
                        std::thread::sleep(std::time::Duration::from_secs(1));
                        continue;
                    }

                    // Match Android's connection lifecycle: every physical
                    // reconnect starts from a clean pose instead of carrying
                    // a stale orientation into the new IMU stream.
                    Self::reset_tracking(&state);

                    let imu_paths: Vec<Vec<u8>> = devices
                        .iter()
                        .filter(|device| interface_matches(device, 3))
                        .map(|device| device.path().to_bytes_with_nul().to_vec())
                        .collect();
                    let other_paths: Vec<Vec<u8>> = devices
                        .iter()
                        .filter(|device| interface_matches(device, 4))
                        .map(|device| device.path().to_bytes_with_nul().to_vec())
                        .collect();
                    eprintln!(
                        "[hid] Xreal interfaces found: imu={} other={}",
                        imu_paths.len(),
                        other_paths.len()
                    );
                    if let Some(path) = imu_paths.first() {
                        if let Some(calibration) = read_factory_gyro_calibration(&api, path) {
                            eprintln!(
                                "[hid] factory gyro calibration loaded: {:.5}, {:.5}, {:.5}",
                                calibration[0], calibration[1], calibration[2]
                            );
                            Self::set_gyro_calibration(&state, calibration);
                        }
                    }
                    let magic = [0x00, 0xaa, 0xc5, 0xd1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01];
                    for path in &imu_paths {
                        if let Ok(device) = api.open_path(&c_path(path)) {
                            let written = device.write(&magic).unwrap_or(0);
                            eprintln!("[hid] IMU start command written={written}");
                        }
                    }

                    if imu_paths.is_empty() {
                        Self::set_status(
                            &state,
                            "Glasses found, but the IMU interface was not found",
                            false,
                            true,
                        );
                        std::thread::sleep(std::time::Duration::from_secs(2));
                        continue;
                    }

                    let display_mode_sent = send_display_mode(&api, &other_paths);
                    eprintln!(
                        "[hid] display-mode SBS command sent={display_mode_sent} to {} interfaces",
                        other_paths.len()
                    );
                    let status_message = if display_mode_sent {
                        "Xreal Air SBS mode and IMU stream active"
                    } else {
                        "Xreal Air IMU stream active; SBS mode request failed"
                    };
                    Self::set_status(&state, status_message, true, true);
                    let reader_quit = Arc::clone(&quit);
                    let handles: Vec<_> = imu_paths
                        .iter()
                        .filter_map(|path| {
                            let state = Arc::clone(&state);
                            let path = path.clone();
                            api.open_path(&c_path(&path)).ok().map(|device| {
                                let quit = Arc::clone(&reader_quit);
                                std::thread::Builder::new()
                                    .name("nreal-imu".to_owned())
                                    .spawn(move || read_device(device, &path, state, &quit))
                                    .ok()
                            })?
                        })
                        .collect();

                    if handles.is_empty() {
                        Self::set_status(
                            &state,
                            "Glasses found, but the IMU interface could not be opened",
                            false,
                            true,
                        );
                        std::thread::sleep(std::time::Duration::from_secs(1));
                        continue;
                    }

                    while handles.iter().all(|handle| !handle.is_finished()) {
                        std::thread::sleep(std::time::Duration::from_millis(200));
                    }
                    quit.store(true, Ordering::Relaxed);
                    for handle in handles {
                        let _ = handle.join();
                    }
                    Self::set_status(&state, "Glasses disconnected; reconnecting", false, false);
                }
            }
        });
    }
}

fn interface_matches(device: &hidapi::DeviceInfo, interface: i32) -> bool {
    if device.interface_number() == interface {
        return true;
    }
    let path = device.path().to_string_lossy().to_lowercase();
    let hexadecimal = format!("mi_{interface:02x}");
    let decimal = format!("mi_{interface:02}");
    path.contains(&hexadecimal) || path.contains(&decimal)
}

fn send_display_mode(api: &hidapi::HidApi, paths: &[Vec<u8>]) -> bool {
    if paths.is_empty() {
        return false;
    }
    let packet = build_mcu_command_packet(0x08, &[0x03]);
    for attempt in 1..=3 {
        let mut accepted = false;
        for path in paths {
            if let Ok(device) = api.open_path(&c_path(path)) {
                // hidapi's native Windows backend returns Ok(0) when WriteFile
                // completes synchronously. Only an error means the report was
                // not sent.
                if device.write(&packet).is_ok() {
                    let mut response = [0u8; 129];
                    match device.read_timeout(&mut response, 500) {
                        Ok(size) if size > 0 => {
                            eprintln!(
                                "[hid] display-mode response bytes={size} head={:?}",
                                &response[..size.min(16)]
                            );
                            if mcu_response_status(&response[..size]) != Some(false) {
                                accepted = true;
                                continue;
                            }
                            eprintln!("[hid] display-mode command was rejected; retrying");
                        }
                        Ok(_) => {
                            // Some firmware revisions apply the mode switch
                            // without returning a response, just like Android.
                            eprintln!("[hid] display-mode response timed out; assuming success");
                            accepted = true;
                        }
                        Err(error) => {
                            eprintln!(
                                "[hid] display-mode response error: {error}; assuming success"
                            );
                            accepted = true;
                        }
                    }
                }
            }
        }
        if accepted {
            return true;
        }
        if attempt < 3 {
            std::thread::sleep(std::time::Duration::from_millis(150));
        }
    }
    false
}

fn mcu_response_status(response: &[u8]) -> Option<bool> {
    if response.first() != Some(&0xfd) || response.len() < 17 {
        return None;
    }
    let command_id = u16::from_le_bytes([response[15], response[16]]);
    if command_id != 0x08 {
        return None;
    }
    let length = u16::from_le_bytes([response[5], response[6]]) as usize;
    let data_length = length.checked_sub(17)?;
    if data_length == 0 {
        return Some(true);
    }
    response.get(22).map(|status| *status == 0)
}

fn read_factory_gyro_calibration(api: &hidapi::HidApi, path: &[u8]) -> Option<[f32; 3]> {
    let device = api.open_path(&c_path(path)).ok()?;
    // Pause the IMU stream while asking the glasses for their JSON factory
    // calibration blob, matching the Android USB sequence.
    send_imu_command(&device, 0x19, &[0x00])?;
    let length_bytes = send_imu_command(&device, 0x14, &[])?;
    if length_bytes.len() < 4 {
        return None;
    }
    let config_length = u32::from_le_bytes(length_bytes[..4].try_into().ok()?) as usize;
    if config_length == 0 || config_length > MAX_FACTORY_CONFIG_BYTES {
        eprintln!("[hid] factory gyro calibration length out of range: {config_length}");
        return None;
    }
    let mut config = Vec::with_capacity(config_length);
    while config.len() < config_length {
        let chunk = send_imu_command(&device, 0x15, &[])?;
        if chunk.is_empty() {
            return None;
        }
        let remaining = config_length - config.len();
        config.extend_from_slice(&chunk[..chunk.len().min(remaining)]);
    }
    let value: serde_json::Value = serde_json::from_slice(&config).ok()?;
    let bias = value
        .get("IMU")?
        .get("device_1")?
        .get("gyro_bias")?
        .as_array()?;
    if bias.len() < 3 {
        return None;
    }
    let values = [
        bias[0].as_f64()? as f32,
        bias[1].as_f64()? as f32,
        bias[2].as_f64()? as f32,
    ];
    Some([-values[0], values[1], values[2]])
}

fn send_imu_command(device: &hidapi::HidDevice, command_id: u8, data: &[u8]) -> Option<Vec<u8>> {
    let packet = build_imu_command_packet(command_id, data);
    device.write(&packet).ok()?;
    let mut response = [0u8; 129];
    for _ in 0..8 {
        let received = device.read_timeout(&mut response, 500).ok()?;
        if received == 0 {
            return None;
        }
        let offset = if response.first() == Some(&0) && response.get(1) == Some(&0xaa) {
            1
        } else {
            0
        };
        if response.get(offset) != Some(&0xaa)
            || response.get(offset + 7) != Some(&command_id)
            || received < offset + 8
        {
            continue;
        }
        let response_length =
            u16::from_le_bytes([response[offset + 5], response[offset + 6]]) as usize;
        let data_length = response_length
            .saturating_sub(3)
            .min(received.saturating_sub(offset + 8));
        return Some(response[offset + 8..offset + 8 + data_length].to_vec());
    }
    None
}

fn build_imu_command_packet(command_id: u8, data: &[u8]) -> Vec<u8> {
    let length = data.len().saturating_add(3);
    let mut packet = vec![0u8; 9 + data.len()];
    // HID report ID followed by the Android IMU command frame.
    packet[1] = 0xaa;
    put_le16(&mut packet, 6, length as u16);
    packet[8] = command_id;
    packet[9..].copy_from_slice(data);
    let crc = crc32(&packet[6..6 + length]);
    put_le32(&mut packet, 2, crc);
    packet
}

/// Build the 64-byte MCU command used by the Android USB implementation,
/// prefixed with the report-id byte required by Windows HID.
fn build_mcu_command_packet(command_id: u16, data: &[u8]) -> [u8; 65] {
    assert!(data.len() <= 42, "MCU command data is limited to 42 bytes");

    let mut packet = [0u8; 65];
    // packet[0] is the HID report ID. The remaining bytes are the Android
    // bulk-transfer packet at a one-byte offset.
    packet[1] = 0xfd;
    let length = data.len() as u16 + 17;
    put_le16(&mut packet, 6, length);
    put_le32(&mut packet, 8, 0x1337);
    put_le32(&mut packet, 12, 0);
    put_le16(&mut packet, 16, command_id);
    packet[23..23 + data.len()].copy_from_slice(data);

    let crc_end = 6 + length as usize;
    let crc = crc32(&packet[6..crc_end]);
    put_le32(&mut packet, 2, crc);
    packet
}

fn put_le16(target: &mut [u8], offset: usize, value: u16) {
    target[offset] = value as u8;
    target[offset + 1] = (value >> 8) as u8;
}

fn put_le32(target: &mut [u8], offset: usize, value: u32) {
    target[offset] = value as u8;
    target[offset + 1] = (value >> 8) as u8;
    target[offset + 2] = (value >> 16) as u8;
    target[offset + 3] = (value >> 24) as u8;
}

fn read_device(
    device: hidapi::HidDevice,
    _path: &[u8],
    state: Arc<SharedState>,
    quit: &AtomicBool,
) {
    device.set_blocking_mode(false).ok();
    let mut previous_ns: i64 = 0;
    let mut buffer = [0u8; 129];
    let mut valid_packets = 0_u64;
    let mut invalid_packets = 0_u64;
    let mut updated_packets = 0_u64;
    let mut rejected_timestamps = 0_u64;
    let mut first_packet_logged = false;
    let mut last_stats_at = std::time::Instant::now();

    while !quit.load(Ordering::Relaxed) {
        let size = match device.read_timeout(&mut buffer, 100) {
            Ok(size) => size,
            Err(_) => return,
        };
        if size < 64 {
            continue;
        }
        let data = &buffer[..size];
        if data[0] != 1
            || data[1] != 2
            || data.get(12) != Some(&0xa0)
            || data.get(13) != Some(&0x0f)
            || data.get(27) != Some(&0x20)
            || data.get(42) != Some(&0)
        {
            invalid_packets += 1;
            continue;
        }
        valid_packets += 1;
        if !first_packet_logged {
            eprintln!(
                "[hid] first IMU packet bytes={size} head={:?}",
                &data[..size.min(24)]
            );
            first_packet_logged = true;
        }
        if last_stats_at.elapsed() >= std::time::Duration::from_secs(5) {
            eprintln!(
                "[hid] packets in last 5s: valid={valid_packets} invalid={invalid_packets} pose_updates={updated_packets} timestamp_rejects={rejected_timestamps}"
            );
            valid_packets = 0;
            invalid_packets = 0;
            updated_packets = 0;
            rejected_timestamps = 0;
            last_stats_at = std::time::Instant::now();
        }
        let uptime_ns = i64::from_le_bytes(data[4..12].try_into().expect("fixed range"));
        let raw_gyro_x = i32::from_le_bytes([
            data[18],
            data[19],
            data[20],
            if data[20] & 0x80 != 0 { 0xff } else { 0 },
        ]);
        let raw_gyro_y = i32::from_le_bytes([
            data[21],
            data[22],
            data[23],
            if data[23] & 0x80 != 0 { 0xff } else { 0 },
        ]);
        let raw_gyro_z = i32::from_le_bytes([
            data[24],
            data[25],
            data[26],
            if data[26] & 0x80 != 0 { 0xff } else { 0 },
        ]);
        let raw_accel_x = i32::from_le_bytes([
            data[33],
            data[34],
            data[35],
            if data[35] & 0x80 != 0 { 0xff } else { 0 },
        ]);
        let raw_accel_y = i32::from_le_bytes([
            data[36],
            data[37],
            data[38],
            if data[38] & 0x80 != 0 { 0xff } else { 0 },
        ]);
        let raw_accel_z = i32::from_le_bytes([
            data[39],
            data[40],
            data[41],
            if data[41] & 0x80 != 0 { 0xff } else { 0 },
        ]);

        let accel_scale = 16.0_f32 / 8388608.0;
        let radians_per_count = (2000.0_f64 / 8388608.0_f64).to_radians() as f32;
        let calibration = state
            .gyro_calibration
            .lock()
            .map(|calibration| *calibration)
            .unwrap_or([0.0; 3]);
        let gyro = [
            -(raw_gyro_x as f32) * radians_per_count + calibration[0],
            (raw_gyro_z as f32) * radians_per_count + calibration[1],
            (raw_gyro_y as f32) * radians_per_count + calibration[2],
        ];
        let accel = [
            -raw_accel_x as f32 * accel_scale,
            raw_accel_z as f32 * accel_scale,
            raw_accel_y as f32 * accel_scale,
        ];

        if previous_ns > 0 && uptime_ns <= previous_ns {
            rejected_timestamps += 1;
            continue;
        }
        if previous_ns > 0 {
            let delta_ns = uptime_ns - previous_ns;
            // The Air is nominally a 1 kHz sensor, but real device timestamps
            // jitter around the 1 ms interval. Reject only non-forward or
            // implausibly large gaps; sub-1 ms positive deltas are valid and
            // must still reach the fusion filter.
            if delta_ns > 50_000_000 {
                rejected_timestamps += 1;
            } else {
                updated_packets += 1;
            }
        } else {
            updated_packets += 1;
        }
        previous_ns = uptime_ns;
        let sample = ImuSample {
            uptime_ns,
            gyro_rad_s: gyro,
            accel_g: accel,
        };
        if let Ok(mut tracker) = state.tracker.lock() {
            let matrix = tracker.update(&sample);
            if let Ok(mut rotation) = state.rotation.lock() {
                *rotation = matrix;
            }
        }
    }
}

fn crc32(data: &[u8]) -> u32 {
    let mut table = [0u32; 256];
    for (index, entry) in table.iter_mut().enumerate() {
        let mut value = index as u32;
        for _ in 0..8 {
            value = if value & 1 != 0 {
                0xedb88320 ^ (value >> 1)
            } else {
                value >> 1
            };
        }
        *entry = value;
    }
    let mut result = 0xffffffff_u32;
    for byte in data {
        result = table[((result ^ *byte as u32) & 0xff) as usize] ^ (result >> 8);
    }
    !result
}
