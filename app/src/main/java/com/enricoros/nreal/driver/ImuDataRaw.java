package com.enricoros.nreal.driver;


import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

public class ImuDataRaw {
  private static final float GYRO_SCALE_RADIANS_PER_SECOND = (float) Math.toRadians(2000f / 8388608f);
  private static final float ACCEL_SCALE_G = 16f / 8388608f;

  int accelX, accelY, accelZ;
  int angVelX, angVelY, angVelZ;
  int magX, magY, magZ;
  long uptimeNs;
  String _tmpOther;
  private final float[] gyroCalibrationRadiansPerSecond = new float[3];

  void update(int accelX, int accelY, int accelZ, int angVelX, int angVelY, int angVelZ, int magX, int magY, int magZ, long uptimeNs) {
    update(accelX, accelY, accelZ, angVelX, angVelY, angVelZ, magX, magY, magZ, uptimeNs, null);
  }

  void update(int accelX, int accelY, int accelZ, int angVelX, int angVelY, int angVelZ, int magX, int magY, int magZ, long uptimeNs, float[] gyroCalibrationRadiansPerSecond) {
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
    if (gyroCalibrationRadiansPerSecond != null && gyroCalibrationRadiansPerSecond.length >= 3) {
      System.arraycopy(gyroCalibrationRadiansPerSecond, 0, this.gyroCalibrationRadiansPerSecond, 0, 3);
    }
  }

  void update(String other) {
    this._tmpOther = other;
  }

  public ImuDataRaw() {
    this.accelX = 0;
    this.accelY = 0;
    this.accelZ = 0;
    this.angVelX = 0;
    this.angVelY = 0;
    this.angVelZ = 0;
    this.magX = 0;
    this.magY = 0;
    this.magZ = 0;
    this.uptimeNs = 0;
    this._tmpOther = null;
  }

  // copy constructor - used for now to send between threads
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
    System.arraycopy(other.gyroCalibrationRadiansPerSecond, 0, gyroCalibrationRadiansPerSecond, 0, 3);
  }

  // string every vector
  @Override
  @NonNull
  @SuppressLint("DefaultLocale")
  public String toString() {
    return String.format(" - accel:  %d %d %d\n - angVel: %d %d %d\n - mag: %d %d %d\n - uptime: %d (s)%s",
        accelX, accelY, accelZ, angVelX, angVelY, angVelZ, magX, magY, magZ, (long) (uptimeNs / 1e9), _tmpOther != null ? _tmpOther : "n/a");
  }

  public float[] getAcceleration() {
    return new float[]{(float) accelX, (float) accelY, (float) accelZ};
  }

  public float[] getAccelerationGs() {
    float[] acceleration = new float[3];
    getAccelerationGs(acceleration);
    return acceleration;
  }

  public void getAccelerationGs(float[] acceleration) {
    acceleration[0] = -accelX * ACCEL_SCALE_G;
    acceleration[1] = accelZ * ACCEL_SCALE_G;
    acceleration[2] = accelY * ACCEL_SCALE_G;
  }

  public float[] getGyroscopeRadiansPerSecond() {
    float[] gyroscope = new float[3];
    getGyroscopeRadiansPerSecond(gyroscope);
    return gyroscope;
  }

  public void getGyroscopeRadiansPerSecond(float[] gyroscope) {
    gyroscope[0] = -angVelX * GYRO_SCALE_RADIANS_PER_SECOND + gyroCalibrationRadiansPerSecond[0];
    gyroscope[1] = angVelZ * GYRO_SCALE_RADIANS_PER_SECOND + gyroCalibrationRadiansPerSecond[1];
    gyroscope[2] = angVelY * GYRO_SCALE_RADIANS_PER_SECOND + gyroCalibrationRadiansPerSecond[2];
  }

  public long getUptimeNs() {
    return uptimeNs;
  }
}
