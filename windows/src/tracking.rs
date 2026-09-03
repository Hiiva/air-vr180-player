#[derive(Clone, Copy, Debug, Default)]
pub struct ImuSample {
    pub uptime_ns: i64,
    pub gyro_rad_s: [f32; 3],
    pub accel_g: [f32; 3],
}

pub struct Quaternion {
    pub w: f32,
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

const MAX_DT_NS: i64 = 50_000_000;
const ACCEL_CORRECTION_GAIN: f32 = 1.4;
const MIN_ACCEL_G: f32 = 0.65;
const MAX_ACCEL_G: f32 = 1.35;
const STATIONARY_MIN_ACCEL_G: f32 = 0.92;
const STATIONARY_MAX_ACCEL_G: f32 = 1.08;
const STATIONARY_GYRO_RAD_PER_SECOND: f32 = 0.070;
const STATIONARY_DEADBAND_RAD_PER_SECOND: f32 = 0.010;
const GYRO_BIAS_TIME_CONSTANT_SECONDS: f32 = 2.0;
const MAX_GYRO_BIAS_RAD_PER_SECOND: f32 = 0.25;

impl Default for Quaternion {
    fn default() -> Self {
        Self::identity()
    }
}

impl Quaternion {
    pub fn identity() -> Self {
        Self {
            w: 1.0,
            x: 0.0,
            y: 0.0,
            z: 0.0,
        }
    }

    fn set(&mut self, other: &Self) {
        self.w = other.w;
        self.x = other.x;
        self.y = other.y;
        self.z = other.z;
    }

    fn set_conjugated(&mut self, other: &Self) {
        self.w = other.w;
        self.x = -other.x;
        self.y = -other.y;
        self.z = -other.z;
    }

    pub fn normalize(&mut self) {
        let length = (self.w * self.w + self.x * self.x + self.y * self.y + self.z * self.z).sqrt();
        if length < 1.0e-6 {
            *self = Self::identity();
            return;
        }
        self.w /= length;
        self.x /= length;
        self.y /= length;
        self.z /= length;
    }

    fn multiply_right(&mut self, rhs: &Self) {
        let nw = self.w * rhs.w - self.x * rhs.x - self.y * rhs.y - self.z * rhs.z;
        let nx = self.w * rhs.x + self.x * rhs.w + self.y * rhs.z - self.z * rhs.y;
        let ny = self.w * rhs.y - self.x * rhs.z + self.y * rhs.w + self.z * rhs.x;
        let nz = self.w * rhs.z + self.x * rhs.y - self.y * rhs.x + self.z * rhs.w;
        self.w = nw;
        self.x = nx;
        self.y = ny;
        self.z = nz;
    }

    fn set_from_angular_velocity(&mut self, wx: f32, wy: f32, wz: f32, dt: f32) {
        let magnitude = (wx * wx + wy * wy + wz * wz).sqrt();
        if magnitude < 1.0e-6 || dt <= 0.0 {
            *self = Self::identity();
            return;
        }
        let half_angle = magnitude * dt * 0.5;
        let scale = half_angle.sin() / magnitude;
        self.w = half_angle.cos();
        self.x = wx * scale;
        self.y = wy * scale;
        self.z = wz * scale;
    }

    fn inverse_rotate(&self, vector: [f32; 3]) -> [f32; 3] {
        let [vx, vy, vz] = vector;
        let rw = self.w;
        let rx = -self.x;
        let ry = -self.y;
        let rz = -self.z;
        let iw = -(rx * vx + ry * vy + rz * vz);

        let ix = rw * vx + ry * vz - rz * vy;
        let iy = rw * vy + rz * vx - rx * vz;
        let iz = rw * vz + rx * vy - ry * vx;

        [
            ix * rw + iw * -rx + iy * -rz - iz * -ry,
            iy * rw + iw * -ry + iz * -rx - ix * -rz,
            iz * rw + iw * -rz + ix * -ry - iy * -rx,
        ]
    }

    pub fn to_column_major_matrix3(&self) -> [f32; 9] {
        let xx = self.x * self.x;
        let yy = self.y * self.y;
        let zz = self.z * self.z;
        let xy = self.x * self.y;
        let xz = self.x * self.z;
        let yz = self.y * self.z;
        let wx = self.w * self.x;
        let wy = self.w * self.y;
        let wz = self.w * self.z;

        [
            1.0 - 2.0 * (yy + zz),
            2.0 * (xy + wz),
            2.0 * (xz - wy),
            2.0 * (xy - wz),
            1.0 - 2.0 * (xx + zz),
            2.0 * (yz + wx),
            2.0 * (xz + wy),
            2.0 * (yz - wx),
            1.0 - 2.0 * (xx + yy),
        ]
    }
}

pub struct HeadTracker {
    orientation: Quaternion,
    recenter_correction: Quaternion,
    recentered: Quaternion,
    delta: Quaternion,
    gyro_bias: [f32; 3],
    last_timestamp_ns: i64,
    initialized: bool,
    gyro_bias_initialized: bool,
}

impl Default for HeadTracker {
    fn default() -> Self {
        Self::new()
    }
}

impl HeadTracker {
    pub fn new() -> Self {
        Self {
            orientation: Quaternion::identity(),
            recenter_correction: Quaternion::identity(),
            recentered: Quaternion::identity(),
            delta: Quaternion::identity(),
            gyro_bias: [0.0; 3],
            last_timestamp_ns: 0,
            initialized: false,
            gyro_bias_initialized: false,
        }
    }

    pub fn update(&mut self, sample: &ImuSample) -> [f32; 9] {
        let mut gyro = sample.gyro_rad_s;
        let accel = sample.accel_g;

        if !self.initialized || sample.uptime_ns <= 0 {
            self.initialized = true;
            self.last_timestamp_ns = sample.uptime_ns;
            return self.rotation_matrix();
        }

        let delta_ns = sample.uptime_ns - self.last_timestamp_ns;
        self.last_timestamp_ns = sample.uptime_ns;
        if delta_ns <= 0 || delta_ns > MAX_DT_NS {
            return self.rotation_matrix();
        }
        let dt = (delta_ns as f32) * 1.0e-9;

        let accel_magnitude = magnitude(accel);
        let stationary = is_stationary(gyro, accel_magnitude);
        self.update_gyro_bias(gyro, stationary, dt, MAX_GYRO_BIAS_RAD_PER_SECOND);
        if self.gyro_bias_initialized {
            for (value, bias) in gyro.iter_mut().zip(self.gyro_bias) {
                *value -= bias;
            }
        }
        if stationary && magnitude(gyro) < STATIONARY_DEADBAND_RAD_PER_SECOND {
            gyro = [0.0; 3];
        }
        if (MIN_ACCEL_G..=MAX_ACCEL_G).contains(&accel_magnitude) {
            let ax = accel[0] / accel_magnitude;
            let ay = accel[1] / accel_magnitude;
            let az = accel[2] / accel_magnitude;
            let expected_up_in_head = self.orientation.inverse_rotate([0.0, 1.0, 0.0]);
            let error_x = expected_up_in_head[1] * az - expected_up_in_head[2] * ay;
            let error_y = expected_up_in_head[2] * ax - expected_up_in_head[0] * az;
            let error_z = expected_up_in_head[0] * ay - expected_up_in_head[1] * ax;
            gyro[0] += error_x * ACCEL_CORRECTION_GAIN;
            gyro[1] += error_y * ACCEL_CORRECTION_GAIN;
            gyro[2] += error_z * ACCEL_CORRECTION_GAIN;
        }

        self.delta
            .set_from_angular_velocity(gyro[0], gyro[1], gyro[2], dt);
        self.orientation.multiply_right(&self.delta);
        self.orientation.normalize();
        self.rotation_matrix()
    }

    pub fn recenter(&mut self) {
        self.recenter_correction.set_conjugated(&self.orientation);
    }

    pub fn reset(&mut self) {
        self.orientation.set(&Quaternion::identity());
        self.recenter_correction.set(&Quaternion::identity());
        self.gyro_bias = [0.0; 3];
        self.initialized = false;
        self.last_timestamp_ns = 0;
        self.gyro_bias_initialized = false;
    }

    fn rotation_matrix(&mut self) -> [f32; 9] {
        self.recentered.set(&self.recenter_correction);
        self.recentered.multiply_right(&self.orientation);
        self.recentered.normalize();
        self.recentered.to_column_major_matrix3()
    }

    fn update_gyro_bias(&mut self, gyro: [f32; 3], stationary: bool, dt: f32, limit: f32) {
        if !stationary {
            return;
        }
        if !self.gyro_bias_initialized {
            self.gyro_bias = gyro.map(|value| value.clamp(-limit, limit));
            self.gyro_bias_initialized = true;
            return;
        }
        let alpha = (dt / GYRO_BIAS_TIME_CONSTANT_SECONDS).clamp(0.0, 0.05);
        for (bias, value) in self.gyro_bias.iter_mut().zip(gyro) {
            *bias += (value - *bias) * alpha;
            *bias = bias.clamp(-limit, limit);
        }
    }
}

fn magnitude(values: [f32; 3]) -> f32 {
    values.iter().map(|value| value * value).sum::<f32>().sqrt()
}

fn is_stationary(gyro: [f32; 3], accel_magnitude: f32) -> bool {
    (STATIONARY_MIN_ACCEL_G..=STATIONARY_MAX_ACCEL_G).contains(&accel_magnitude)
        && magnitude(gyro) < STATIONARY_GYRO_RAD_PER_SECOND
}
