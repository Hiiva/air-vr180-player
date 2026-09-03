package com.enricoros.nreal.driver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Applies the factory accelerometer and gyroscope calibration stored in the glasses. */
final class FactoryImuCalibration {
  private static final float STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED = 9.806f;
  private static final float GYRO_SCALE_RADIANS_PER_SECOND =
      (float) Math.toRadians(2000.0f / 8388608.0f);
  private static final float ACCEL_SCALE_G = 16.0f / 8388608.0f;

  private final float[] gyroscopeMisalignment = identityMatrix();
  private final float[] gyroscopeSensitivity = onesVector();
  private final float[] gyroscopeOffset = new float[3];

  private final float[] accelerometerMisalignment = identityMatrix();
  private final float[] accelerometerSensitivity = onesVector();
  private final float[] accelerometerOffset = new float[3];

  private final float[] sourceFrameVector = new float[3];
  private final float[] scaledVector = new float[3];
  private final float[] calibratedSourceVector = new float[3];
  private boolean loaded;

  boolean load(String configJson) throws JSONException {
    loaded = false;
    JSONObject imuDevice = new JSONObject(configJson)
        .getJSONObject("IMU")
        .getJSONObject("device_1");

    float[] accelBias = readVector(imuDevice, "accel_bias", zeroVector());
    float[] gyroBias = readVector(imuDevice, "gyro_bias", zeroVector());
    float[] scaleAccel = readVector(imuDevice, "scale_accel", onesVector());
    float[] scaleGyro = readVector(imuDevice, "scale_gyro", onesVector());
    float[] accelToGyro = readQuaternion(imuDevice, "accel_q_gyro");

    // Factory coefficients stay in the JSON component order. Calibrate in that source frame first,
    // then apply the report/runtime axis mapping once at the end.
    quaternionToMatrix(accelToGyro, gyroscopeMisalignment);
    setIdentity(accelerometerMisalignment);

    copyValidatedSensitivity(scaleGyro, gyroscopeSensitivity);
    copyValidatedSensitivity(scaleAccel, accelerometerSensitivity);

    System.arraycopy(gyroBias, 0, gyroscopeOffset, 0, 3);
    for (int i = 0; i < 3; i++) {
      accelerometerOffset[i] = accelBias[i] / STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED;
    }

    loaded = true;
    return true;
  }

  boolean isLoaded() {
    return loaded;
  }

  void calibrateGyroscope(int rawX, int rawY, int rawZ, float[] outputHeadAxes) {
    sourceFrameVector[0] = rawX * GYRO_SCALE_RADIANS_PER_SECOND;
    sourceFrameVector[1] = rawY * GYRO_SCALE_RADIANS_PER_SECOND;
    sourceFrameVector[2] = rawZ * GYRO_SCALE_RADIANS_PER_SECOND;
    calibrateSourceVector(
        sourceFrameVector,
        gyroscopeMisalignment,
        gyroscopeSensitivity,
        gyroscopeOffset);
    mapGyroAccelSourceToHead(calibratedSourceVector, outputHeadAxes);
  }

  void calibrateAccelerometer(int rawX, int rawY, int rawZ, float[] outputHeadAxes) {
    sourceFrameVector[0] = rawX * ACCEL_SCALE_G;
    sourceFrameVector[1] = rawY * ACCEL_SCALE_G;
    sourceFrameVector[2] = rawZ * ACCEL_SCALE_G;
    calibrateSourceVector(
        sourceFrameVector,
        accelerometerMisalignment,
        accelerometerSensitivity,
        accelerometerOffset);
    mapGyroAccelSourceToHead(calibratedSourceVector, outputHeadAxes);
  }

  private void calibrateSourceVector(
      float[] input,
      float[] misalignment,
      float[] sensitivity,
      float[] offset) {
    for (int i = 0; i < 3; i++) {
      scaledVector[i] = (input[i] - offset[i]) * sensitivity[i];
    }

    calibratedSourceVector[0] = misalignment[0] * scaledVector[0]
        + misalignment[1] * scaledVector[1]
        + misalignment[2] * scaledVector[2];
    calibratedSourceVector[1] = misalignment[3] * scaledVector[0]
        + misalignment[4] * scaledVector[1]
        + misalignment[5] * scaledVector[2];
    calibratedSourceVector[2] = misalignment[6] * scaledVector[0]
        + misalignment[7] * scaledVector[1]
        + misalignment[8] * scaledVector[2];
  }

  private static void mapGyroAccelSourceToHead(float[] source, float[] outputHeadAxes) {
    // XREAL Air-family transform=1 gyro/accel report mapping: [-source0,+source2,+source1].
    outputHeadAxes[0] = -source[0];
    outputHeadAxes[1] = source[2];
    outputHeadAxes[2] = source[1];
  }

  private static float[] readVector(JSONObject object, String name, float[] fallback)
      throws JSONException {
    if (!object.has(name) || object.isNull(name)) {
      return fallback;
    }
    JSONArray array = object.getJSONArray(name);
    if (array.length() < 3) {
      throw new JSONException(name + " must contain three values");
    }
    float[] result = new float[3];
    for (int i = 0; i < 3; i++) {
      result[i] = finiteOrDefault((float) array.getDouble(i), fallback[i]);
    }
    return result;
  }

  private static float[] readQuaternion(JSONObject object, String name) throws JSONException {
    if (!object.has(name) || object.isNull(name)) {
      return identityQuaternion();
    }
    JSONArray array = object.getJSONArray(name);
    if (array.length() < 4) {
      throw new JSONException(name + " must contain four values");
    }
    float[] quaternion = new float[4];
    for (int i = 0; i < 4; i++) {
      quaternion[i] = (float) array.getDouble(i);
      if (!Float.isFinite(quaternion[i])) {
        return identityQuaternion();
      }
    }
    normalizeQuaternion(quaternion);
    return quaternion;
  }

  private static void quaternionToMatrix(float[] quaternion, float[] matrix) {
    float x = quaternion[0];
    float y = quaternion[1];
    float z = quaternion[2];
    float w = quaternion[3];
    float xx = x * x;
    float yy = y * y;
    float zz = z * z;
    float xy = x * y;
    float xz = x * z;
    float yz = y * z;
    float wx = w * x;
    float wy = w * y;
    float wz = w * z;

    matrix[0] = 1.0f - 2.0f * (yy + zz);
    matrix[1] = 2.0f * (xy - wz);
    matrix[2] = 2.0f * (xz + wy);
    matrix[3] = 2.0f * (xy + wz);
    matrix[4] = 1.0f - 2.0f * (xx + zz);
    matrix[5] = 2.0f * (yz - wx);
    matrix[6] = 2.0f * (xz - wy);
    matrix[7] = 2.0f * (yz + wx);
    matrix[8] = 1.0f - 2.0f * (xx + yy);
  }

  private static void normalizeQuaternion(float[] quaternion) {
    float length = (float) Math.sqrt(
        quaternion[0] * quaternion[0]
            + quaternion[1] * quaternion[1]
            + quaternion[2] * quaternion[2]
            + quaternion[3] * quaternion[3]);
    if (!Float.isFinite(length) || length < 1.0e-6f) {
      float[] identity = identityQuaternion();
      System.arraycopy(identity, 0, quaternion, 0, 4);
      return;
    }
    for (int i = 0; i < 4; i++) {
      quaternion[i] /= length;
    }
  }

  private static void copyValidatedSensitivity(float[] source, float[] destination) {
    for (int i = 0; i < 3; i++) {
      float value = source[i];
      destination[i] = Float.isFinite(value) && Math.abs(value) > 1.0e-9f ? value : 1.0f;
    }
  }

  private static float finiteOrDefault(float value, float fallback) {
    return Float.isFinite(value) ? value : fallback;
  }

  private static float[] zeroVector() {
    return new float[] {0.0f, 0.0f, 0.0f};
  }

  private static float[] onesVector() {
    return new float[] {1.0f, 1.0f, 1.0f};
  }

  private static float[] identityQuaternion() {
    return new float[] {0.0f, 0.0f, 0.0f, 1.0f};
  }

  private static float[] identityMatrix() {
    return new float[] {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
  }

  private static void setIdentity(float[] matrix) {
    for (int i = 0; i < 9; i++) {
      matrix[i] = 0.0f;
    }
    matrix[0] = 1.0f;
    matrix[4] = 1.0f;
    matrix[8] = 1.0f;
  }
}
