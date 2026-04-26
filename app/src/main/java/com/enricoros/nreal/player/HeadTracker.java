package com.enricoros.nreal.player;

import com.enricoros.nreal.driver.ImuDataRaw;

public final class HeadTracker {
  private static final float NS_TO_SECONDS = 1.0e-9f;
  private static final float MIN_DT_SECONDS = 0.001f;
  private static final float MAX_DT_SECONDS = 0.050f;
  private static final float ACCEL_CORRECTION_GAIN = 1.4f;
  private static final float MIN_ACCEL_G = 0.65f;
  private static final float MAX_ACCEL_G = 1.35f;

  private final Quaternion orientation = new Quaternion();
  private final Quaternion recenterCorrection = new Quaternion();
  private long lastTimestampNs = 0L;
  private boolean initialized = false;

  public synchronized float[] update(ImuDataRaw sample) {
    long timestampNs = sample.getUptimeNs();
    float[] gyro = sample.getGyroscopeRadiansPerSecond();
    float[] accel = sample.getAccelerationGs();

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

    applyAccelerometerCorrection(gyro, accel);
    Quaternion delta = Quaternion.fromAngularVelocity(gyro[0], gyro[1], gyro[2], dt);
    orientation.multiplyRight(delta);
    orientation.normalize();
    return getRotationMatrixLocked();
  }

  public synchronized void recenter() {
    recenterCorrection.set(orientation.conjugated());
  }

  public synchronized void reset() {
    orientation.setIdentity();
    recenterCorrection.setIdentity();
    initialized = false;
    lastTimestampNs = 0L;
  }

  public synchronized float[] getRotationMatrix() {
    return getRotationMatrixLocked();
  }

  private void applyAccelerometerCorrection(float[] gyro, float[] accel) {
    float ax = accel[0];
    float ay = accel[1];
    float az = accel[2];
    float accelMagnitude = (float) Math.sqrt(ax * ax + ay * ay + az * az);
    if (accelMagnitude < MIN_ACCEL_G || accelMagnitude > MAX_ACCEL_G) {
      return;
    }

    ax /= accelMagnitude;
    ay /= accelMagnitude;
    az /= accelMagnitude;

    float[] expectedUpInHead = orientation.inverseRotate(0.0f, 1.0f, 0.0f);
    float errorX = expectedUpInHead[1] * az - expectedUpInHead[2] * ay;
    float errorY = expectedUpInHead[2] * ax - expectedUpInHead[0] * az;
    float errorZ = expectedUpInHead[0] * ay - expectedUpInHead[1] * ax;

    gyro[0] += errorX * ACCEL_CORRECTION_GAIN;
    gyro[1] += errorY * ACCEL_CORRECTION_GAIN;
    gyro[2] += errorZ * ACCEL_CORRECTION_GAIN;
  }

  private float[] getRotationMatrixLocked() {
    Quaternion recentered = Quaternion.multiply(recenterCorrection, orientation);
    recentered.normalize();
    return recentered.toColumnMajorMatrix3();
  }
}
