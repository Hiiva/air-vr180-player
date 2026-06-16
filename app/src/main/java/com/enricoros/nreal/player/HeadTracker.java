package com.enricoros.nreal.player;

import com.enricoros.nreal.driver.ImuDataRaw;

public final class HeadTracker {
  private static final float NS_TO_SECONDS = 1.0e-9f;
  private static final float MIN_DT_SECONDS = 0.001f;
  private static final float MAX_DT_SECONDS = 0.050f;
  private static final float ACCEL_CORRECTION_GAIN = 1.4f;
  private static final float MIN_ACCEL_G = 0.65f;
  private static final float MAX_ACCEL_G = 1.35f;
  private static final float STATIONARY_MIN_ACCEL_G = 0.92f;
  private static final float STATIONARY_MAX_ACCEL_G = 1.08f;
  private static final float STATIONARY_GYRO_RAD_PER_SECOND = 0.070f;
  private static final float STATIONARY_DEADBAND_RAD_PER_SECOND = 0.010f;
  private static final float GYRO_BIAS_TIME_CONSTANT_SECONDS = 2.0f;
  private static final float MAX_GYRO_BIAS_RAD_PER_SECOND = 0.25f;

  private final Quaternion orientation = new Quaternion();
  private final Quaternion recenterCorrection = new Quaternion();
  private final Quaternion delta = new Quaternion();
  private final Quaternion recentered = new Quaternion();
  private final float[] gyroBias = new float[3];
  private final float[] gyro = new float[3];
  private final float[] accel = new float[3];
  private final float[] expectedUpInHead = new float[3];
  private final float[] rotationMatrix = new float[9];
  private long lastTimestampNs = 0L;
  private boolean initialized = false;
  private boolean gyroBiasInitialized = false;

  public synchronized float[] update(ImuDataRaw sample) {
    long timestampNs = sample.getUptimeNs();
    sample.getGyroscopeRadiansPerSecond(gyro);
    sample.getAccelerationGs(accel);

    if (!initialized || timestampNs <= 0L) {
      initialized = true;
      lastTimestampNs = timestampNs;
      return getRotationMatrixLocked();
    }

    float dt = (timestampNs - lastTimestampNs) * NS_TO_SECONDS;
    lastTimestampNs = timestampNs;
    if (dt < MIN_DT_SECONDS || dt > MAX_DT_SECONDS) {
      return getRotationMatrixLocked();
    }

    float accelMagnitude = magnitude(accel[0], accel[1], accel[2]);
    boolean stationary = isStationary(gyro, accelMagnitude);
    updateGyroBias(gyro, stationary, dt);
    subtractGyroBias(gyro);
    zeroStationaryResidualGyro(gyro, stationary);

    applyAccelerometerCorrection(gyro, accel, accelMagnitude);
    delta.setFromAngularVelocity(gyro[0], gyro[1], gyro[2], dt);
    orientation.multiplyRight(delta);
    orientation.normalize();
    return getRotationMatrixLocked();
  }

  public synchronized void recenter() {
    recenterCorrection.setConjugated(orientation);
  }

  public synchronized void reset() {
    orientation.setIdentity();
    recenterCorrection.setIdentity();
    gyroBias[0] = 0.0f;
    gyroBias[1] = 0.0f;
    gyroBias[2] = 0.0f;
    gyroBiasInitialized = false;
    initialized = false;
    lastTimestampNs = 0L;
  }

  public synchronized float[] getRotationMatrix() {
    return getRotationMatrixLocked();
  }

  private boolean isStationary(float[] gyro, float accelMagnitude) {
    if (accelMagnitude < STATIONARY_MIN_ACCEL_G || accelMagnitude > STATIONARY_MAX_ACCEL_G) {
      return false;
    }
    return magnitude(gyro[0], gyro[1], gyro[2]) < STATIONARY_GYRO_RAD_PER_SECOND;
  }

  private void updateGyroBias(float[] gyro, boolean stationary, float dtSeconds) {
    if (!stationary) {
      return;
    }

    if (!gyroBiasInitialized) {
      gyroBias[0] = clamp(gyro[0], -MAX_GYRO_BIAS_RAD_PER_SECOND, MAX_GYRO_BIAS_RAD_PER_SECOND);
      gyroBias[1] = clamp(gyro[1], -MAX_GYRO_BIAS_RAD_PER_SECOND, MAX_GYRO_BIAS_RAD_PER_SECOND);
      gyroBias[2] = clamp(gyro[2], -MAX_GYRO_BIAS_RAD_PER_SECOND, MAX_GYRO_BIAS_RAD_PER_SECOND);
      gyroBiasInitialized = true;
      return;
    }

    float alpha = clamp(dtSeconds / GYRO_BIAS_TIME_CONSTANT_SECONDS, 0.0f, 0.05f);
    for (int i = 0; i < 3; i++) {
      gyroBias[i] += (gyro[i] - gyroBias[i]) * alpha;
      gyroBias[i] = clamp(gyroBias[i], -MAX_GYRO_BIAS_RAD_PER_SECOND, MAX_GYRO_BIAS_RAD_PER_SECOND);
    }
  }

  private void subtractGyroBias(float[] gyro) {
    if (!gyroBiasInitialized) {
      return;
    }
    gyro[0] -= gyroBias[0];
    gyro[1] -= gyroBias[1];
    gyro[2] -= gyroBias[2];
  }

  private void zeroStationaryResidualGyro(float[] gyro, boolean stationary) {
    if (!stationary) {
      return;
    }
    if (magnitude(gyro[0], gyro[1], gyro[2]) < STATIONARY_DEADBAND_RAD_PER_SECOND) {
      gyro[0] = 0.0f;
      gyro[1] = 0.0f;
      gyro[2] = 0.0f;
    }
  }

  private void applyAccelerometerCorrection(float[] gyro, float[] accel, float accelMagnitude) {
    if (accelMagnitude < MIN_ACCEL_G || accelMagnitude > MAX_ACCEL_G) {
      return;
    }

    float ax = accel[0] / accelMagnitude;
    float ay = accel[1] / accelMagnitude;
    float az = accel[2] / accelMagnitude;

    orientation.inverseRotate(0.0f, 1.0f, 0.0f, expectedUpInHead);
    float errorX = expectedUpInHead[1] * az - expectedUpInHead[2] * ay;
    float errorY = expectedUpInHead[2] * ax - expectedUpInHead[0] * az;
    float errorZ = expectedUpInHead[0] * ay - expectedUpInHead[1] * ax;

    gyro[0] += errorX * ACCEL_CORRECTION_GAIN;
    gyro[1] += errorY * ACCEL_CORRECTION_GAIN;
    gyro[2] += errorZ * ACCEL_CORRECTION_GAIN;
  }

  private float[] getRotationMatrixLocked() {
    recentered.set(recenterCorrection);
    recentered.multiplyRight(orientation);
    recentered.normalize();
    recentered.toColumnMajorMatrix3(rotationMatrix);
    return rotationMatrix;
  }

  private static float magnitude(float x, float y, float z) {
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
