package com.enricoros.nreal.driver;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

public final class ImuDataRaw {
  private static final float GYRO_SCALE_RADIANS_PER_SECOND =
      (float) Math.toRadians(2000.0f / 8388608.0f);
  private static final float ACCEL_SCALE_G = 16.0f / 8388608.0f;

  int accelX, accelY, accelZ;
  int angVelX, angVelY, angVelZ;
  int magX, magY, magZ;
  long uptimeNs;
  String _tmpOther;

  private final float[] accelerationGs = new float[3];
  private final float[] gyroscopeRadiansPerSecond = new float[3];
  private final float[] magnetometerDirection = new float[3];
  private float magnetometerFieldMagnitude;
  private boolean magnetometerValid;
  private boolean magnetometerCalibrated;

  void update(
      int accelX,
      int accelY,
      int accelZ,
      int angVelX,
      int angVelY,
      int angVelZ,
      int magX,
      int magY,
      int magZ,
      long uptimeNs,
      float[] calibratedAccelerationGs,
      float[] calibratedGyroscopeRadiansPerSecond,
      float[] processedMagnetometerDirection,
      float magnetometerFieldMagnitude,
      boolean magnetometerCalibrated) {
    this.accelX = accelX;
    this.accelY = accelY;
    this.accelZ = accelZ;
    this.angVelX = angVelX;
    this.angVelY = angVelY;
    this.angVelZ = angVelZ;
    this.magX = magX;
    this.magY = magY;
    this.magZ = magZ;
    this.uptimeNs = uptimeNs;

    copyVector(calibratedAccelerationGs, accelerationGs);
    copyVector(calibratedGyroscopeRadiansPerSecond, gyroscopeRadiansPerSecond);
    copyVector(processedMagnetometerDirection, magnetometerDirection);
    this.magnetometerFieldMagnitude = magnetometerFieldMagnitude;
    this.magnetometerValid = isUsableVector(magnetometerDirection)
        && Float.isFinite(magnetometerFieldMagnitude)
        && magnetometerFieldMagnitude > 0.0f;
    this.magnetometerCalibrated = magnetometerCalibrated;
  }

  /** Legacy raw update retained for callers that do not provide calibrated vectors. */
  void update(
      int accelX,
      int accelY,
      int accelZ,
      int angVelX,
      int angVelY,
      int angVelZ,
      int magX,
      int magY,
      int magZ,
      long uptimeNs) {
    float[] acceleration = new float[] {
        -accelX * ACCEL_SCALE_G,
        accelZ * ACCEL_SCALE_G,
        accelY * ACCEL_SCALE_G
    };
    float[] gyroscope = new float[] {
        -angVelX * GYRO_SCALE_RADIANS_PER_SECOND,
        angVelZ * GYRO_SCALE_RADIANS_PER_SECOND,
        angVelY * GYRO_SCALE_RADIANS_PER_SECOND
    };
    // XREAL Air-family transform=1 magnetic mapping is [sourceY, sourceZ, sourceX].
    float[] magnetometer = new float[] {magY, magZ, magX};
    float magnitude = magnitude(magnetometer[0], magnetometer[1], magnetometer[2]);
    if (magnitude > 1.0e-6f) {
      magnetometer[0] /= magnitude;
      magnetometer[1] /= magnitude;
      magnetometer[2] /= magnitude;
    }
    update(
        accelX,
        accelY,
        accelZ,
        angVelX,
        angVelY,
        angVelZ,
        magX,
        magY,
        magZ,
        uptimeNs,
        acceleration,
        gyroscope,
        magnetometer,
        magnitude,
        false);
  }

  void update(String other) {
    this._tmpOther = other;
  }

  public ImuDataRaw() {
    // Fields intentionally default to zero.
  }

  // Copy constructor used to send samples between threads without sharing mutable arrays.
  public ImuDataRaw(@NonNull ImuDataRaw other) {
    copyFrom(other);
  }

  void copyFrom(@NonNull ImuDataRaw other) {
    this.accelX = other.accelX;
    this.accelY = other.accelY;
    this.accelZ = other.accelZ;
    this.angVelX = other.angVelX;
    this.angVelY = other.angVelY;
    this.angVelZ = other.angVelZ;
    this.magX = other.magX;
    this.magY = other.magY;
    this.magZ = other.magZ;
    this.uptimeNs = other.uptimeNs;
    this._tmpOther = other._tmpOther;
    System.arraycopy(other.accelerationGs, 0, accelerationGs, 0, 3);
    System.arraycopy(other.gyroscopeRadiansPerSecond, 0, gyroscopeRadiansPerSecond, 0, 3);
    System.arraycopy(other.magnetometerDirection, 0, magnetometerDirection, 0, 3);
    this.magnetometerFieldMagnitude = other.magnetometerFieldMagnitude;
    this.magnetometerValid = other.magnetometerValid;
    this.magnetometerCalibrated = other.magnetometerCalibrated;
  }

  @Override
  @NonNull
  @SuppressLint("DefaultLocale")
  public String toString() {
    return String.format(
        " - accel:  %d %d %d\n - angVel: %d %d %d\n - mag: %d %d %d\n - uptime: %d (s)%s",
        accelX,
        accelY,
        accelZ,
        angVelX,
        angVelY,
        angVelZ,
        magX,
        magY,
        magZ,
        (long) (uptimeNs / 1e9),
        _tmpOther != null ? _tmpOther : "n/a");
  }

  public float[] getAcceleration() {
    return new float[] {(float) accelX, (float) accelY, (float) accelZ};
  }

  public float[] getAccelerationGs() {
    float[] acceleration = new float[3];
    getAccelerationGs(acceleration);
    return acceleration;
  }

  public void getAccelerationGs(float[] acceleration) {
    requireThreeElements(acceleration);
    System.arraycopy(accelerationGs, 0, acceleration, 0, 3);
  }

  public float[] getGyroscopeRadiansPerSecond() {
    float[] gyroscope = new float[3];
    getGyroscopeRadiansPerSecond(gyroscope);
    return gyroscope;
  }

  public void getGyroscopeRadiansPerSecond(float[] gyroscope) {
    requireThreeElements(gyroscope);
    System.arraycopy(gyroscopeRadiansPerSecond, 0, gyroscope, 0, 3);
  }

  public float[] getMagnetometerDirection() {
    float[] magnetometer = new float[3];
    getMagnetometerDirection(magnetometer);
    return magnetometer;
  }

  public void getMagnetometerDirection(float[] magnetometer) {
    requireThreeElements(magnetometer);
    System.arraycopy(magnetometerDirection, 0, magnetometer, 0, 3);
  }

  public boolean hasMagnetometer() {
    return magnetometerValid;
  }

  public boolean isMagnetometerCalibrated() {
    return magnetometerCalibrated;
  }

  public float getMagnetometerFieldMagnitude() {
    return magnetometerFieldMagnitude;
  }

  public long getUptimeNs() {
    return uptimeNs;
  }

  private static void copyVector(float[] source, float[] destination) {
    if (source == null || source.length < 3) {
      destination[0] = 0.0f;
      destination[1] = 0.0f;
      destination[2] = 0.0f;
      return;
    }
    System.arraycopy(source, 0, destination, 0, 3);
  }

  private static boolean isUsableVector(float[] vector) {
    return Float.isFinite(vector[0])
        && Float.isFinite(vector[1])
        && Float.isFinite(vector[2])
        && magnitude(vector[0], vector[1], vector[2]) > 1.0e-6f;
  }

  private static float magnitude(float x, float y, float z) {
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  private static void requireThreeElements(float[] values) {
    if (values == null || values.length < 3) {
      throw new IllegalArgumentException("Output array must contain at least three elements");
    }
  }
}
