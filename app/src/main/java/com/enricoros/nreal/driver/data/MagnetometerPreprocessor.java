package com.enricoros.nreal.driver.data;

import java.util.Arrays;

/**
 * Continuously calibrates, filters, and normalizes the glasses magnetometer.
 * Factory calibration is applied before samples reach this class.
 */
public final class MagnetometerPreprocessor {
  private static final float MIN_VECTOR_MAGNITUDE = 1.0e-6f;
  private static final float MIN_EXTREMA_FIELD_RATIO = 0.35f;
  private static final float MAX_EXTREMA_FIELD_RATIO = 2.85f;
  private static final float CALIBRATION_BLEND_SECONDS = 5.0f;
  private static final float ACTIVE_CALIBRATION_TIME_CONSTANT_SECONDS = 15.0f;

  private final float cutoffFrequencyHz;
  private final float minimumAxisRange;
  private final float[] minValues = new float[3];
  private final float[] maxValues = new float[3];
  private final float[] activeCenters = new float[3];
  private final float[] activeHalfRanges = new float[3];
  private final float[] rawDirection = new float[3];
  private final float[] calibratedDirection = new float[3];
  private final float[] filteredDirection = new float[3];
  private final float[] outputDirection = new float[3];

  private boolean hasExtrema;
  private boolean activeCalibrationInitialized;
  private boolean filterInitialized;
  private boolean fieldReferenceInitialized;
  private boolean calibrationReady;
  private float calibrationBlend;
  private float typicalFieldMagnitude;
  private float lastFieldMagnitude;

  public MagnetometerPreprocessor(float cutoffFrequencyHz, float minimumAxisRange) {
    this.cutoffFrequencyHz = Math.max(0.0f, cutoffFrequencyHz);
    this.minimumAxisRange = Math.max(0.0f, minimumAxisRange);
    resetCalibration();
  }

  public float[] process(float rawX, float rawY, float rawZ, float deltaTimeSeconds) {
    return process(rawX, rawY, rawZ, deltaTimeSeconds, true);
  }

  public float[] process(
      float rawX,
      float rawY,
      float rawZ,
      float deltaTimeSeconds,
      boolean enableOnlineCalibration) {
    lastFieldMagnitude = magnitude(rawX, rawY, rawZ);
    if (!isFiniteVector(rawX, rawY, rawZ) || lastFieldMagnitude < MIN_VECTOR_MAGNITUDE) {
      Arrays.fill(outputDirection, 0.0f);
      return outputDirection;
    }

    float dt = Float.isFinite(deltaTimeSeconds) ? Math.max(0.0f, deltaTimeSeconds) : 0.0f;
    if (!fieldReferenceInitialized) {
      typicalFieldMagnitude = lastFieldMagnitude;
      fieldReferenceInitialized = true;
    }
    float fieldRatio = lastFieldMagnitude / Math.max(typicalFieldMagnitude, MIN_VECTOR_MAGNITUDE);

    if (enableOnlineCalibration) {
      if (!hasExtrema) {
        setExtrema(rawX, rawY, rawZ);
        hasExtrema = true;
      } else if (fieldRatio >= MIN_EXTREMA_FIELD_RATIO
          && fieldRatio <= MAX_EXTREMA_FIELD_RATIO) {
        updateExtrema(rawX, rawY, rawZ);
      }

      calibrationReady = hasSufficientRange();
      if (calibrationReady) {
        updateActiveCalibration(dt);
        calibrationBlend = Math.min(
            1.0f,
            calibrationBlend + dt / CALIBRATION_BLEND_SECONDS);
      } else {
        calibrationBlend = 0.0f;
      }
    } else {
      calibrationReady = false;
      calibrationBlend = 0.0f;
    }

    if (fieldRatio >= 0.5f && fieldRatio <= 2.0f) {
      float fieldAlpha = clamp(dt * 0.5f, 0.0f, 0.01f);
      typicalFieldMagnitude += (lastFieldMagnitude - typicalFieldMagnitude) * fieldAlpha;
    }

    if (!normalize(rawX, rawY, rawZ, rawDirection)) {
      Arrays.fill(outputDirection, 0.0f);
      return outputDirection;
    }

    float x = rawDirection[0];
    float y = rawDirection[1];
    float z = rawDirection[2];
    if (activeCalibrationInitialized && calibrationBlend > 0.0f) {
      float calibratedX = (rawX - activeCenters[0]) / activeHalfRanges[0];
      float calibratedY = (rawY - activeCenters[1]) / activeHalfRanges[1];
      float calibratedZ = (rawZ - activeCenters[2]) / activeHalfRanges[2];
      if (normalize(calibratedX, calibratedY, calibratedZ, calibratedDirection)) {
        float rawWeight = 1.0f - calibrationBlend;
        x = rawWeight * rawDirection[0] + calibrationBlend * calibratedDirection[0];
        y = rawWeight * rawDirection[1] + calibrationBlend * calibratedDirection[1];
        z = rawWeight * rawDirection[2] + calibrationBlend * calibratedDirection[2];
        float blendedMagnitude = magnitude(x, y, z);
        if (blendedMagnitude >= MIN_VECTOR_MAGNITUDE && Float.isFinite(blendedMagnitude)) {
          x /= blendedMagnitude;
          y /= blendedMagnitude;
          z /= blendedMagnitude;
        } else {
          x = rawDirection[0];
          y = rawDirection[1];
          z = rawDirection[2];
        }
      }
    }

    if (!filterInitialized) {
      filteredDirection[0] = x;
      filteredDirection[1] = y;
      filteredDirection[2] = z;
      filterInitialized = true;
    } else {
      float previousWeight = cutoffFrequencyHz <= 0.0f
          ? 0.0f
          : (float) Math.exp(-2.0f * Math.PI * cutoffFrequencyHz * dt);
      float sampleWeight = 1.0f - previousWeight;
      filteredDirection[0] = previousWeight * filteredDirection[0] + sampleWeight * x;
      filteredDirection[1] = previousWeight * filteredDirection[1] + sampleWeight * y;
      filteredDirection[2] = previousWeight * filteredDirection[2] + sampleWeight * z;
    }

    if (!normalize(
        filteredDirection[0],
        filteredDirection[1],
        filteredDirection[2],
        outputDirection)) {
      Arrays.fill(outputDirection, 0.0f);
    }
    return outputDirection;
  }

  public float getLastFieldMagnitude() {
    return lastFieldMagnitude;
  }

  public boolean isCalibrationReady() {
    return calibrationReady;
  }

  public void resetCalibration() {
    Arrays.fill(minValues, Float.POSITIVE_INFINITY);
    Arrays.fill(maxValues, Float.NEGATIVE_INFINITY);
    Arrays.fill(activeCenters, 0.0f);
    Arrays.fill(activeHalfRanges, 1.0f);
    Arrays.fill(rawDirection, 0.0f);
    Arrays.fill(calibratedDirection, 0.0f);
    Arrays.fill(filteredDirection, 0.0f);
    Arrays.fill(outputDirection, 0.0f);
    typicalFieldMagnitude = 0.0f;
    lastFieldMagnitude = 0.0f;
    calibrationBlend = 0.0f;
    calibrationReady = false;
    hasExtrema = false;
    activeCalibrationInitialized = false;
    filterInitialized = false;
    fieldReferenceInitialized = false;
  }

  public float[] saveCalibration() {
    if (!hasExtrema || !allFinite(minValues) || !allFinite(maxValues)) {
      return null;
    }
    float[] calibrationData = new float[6];
    System.arraycopy(minValues, 0, calibrationData, 0, 3);
    System.arraycopy(maxValues, 0, calibrationData, 3, 3);
    return calibrationData;
  }

  public void restoreCalibration(float[] calibrationData) {
    if (calibrationData == null || calibrationData.length != 6) {
      throw new IllegalArgumentException("Calibration data must have exactly 6 elements");
    }
    for (int i = 0; i < 3; i++) {
      float min = calibrationData[i];
      float max = calibrationData[i + 3];
      if (!Float.isFinite(min) || !Float.isFinite(max) || max < min) {
        throw new IllegalArgumentException("Calibration data contains an invalid range");
      }
      minValues[i] = min;
      maxValues[i] = max;
    }
    Arrays.fill(filteredDirection, 0.0f);
    Arrays.fill(outputDirection, 0.0f);
    hasExtrema = true;
    calibrationReady = hasSufficientRange();
    activeCalibrationInitialized = false;
    if (calibrationReady) {
      initializeActiveCalibration();
      calibrationBlend = 1.0f;
    } else {
      calibrationBlend = 0.0f;
    }
    filterInitialized = false;
    fieldReferenceInitialized = false;
  }

  private void setExtrema(float x, float y, float z) {
    minValues[0] = maxValues[0] = x;
    minValues[1] = maxValues[1] = y;
    minValues[2] = maxValues[2] = z;
  }

  private void updateExtrema(float x, float y, float z) {
    minValues[0] = Math.min(minValues[0], x);
    minValues[1] = Math.min(minValues[1], y);
    minValues[2] = Math.min(minValues[2], z);
    maxValues[0] = Math.max(maxValues[0], x);
    maxValues[1] = Math.max(maxValues[1], y);
    maxValues[2] = Math.max(maxValues[2], z);
  }

  private boolean hasSufficientRange() {
    for (int i = 0; i < 3; i++) {
      if (!Float.isFinite(minValues[i])
          || !Float.isFinite(maxValues[i])
          || maxValues[i] - minValues[i] < minimumAxisRange) {
        return false;
      }
    }
    return true;
  }

  private void initializeActiveCalibration() {
    for (int i = 0; i < 3; i++) {
      activeCenters[i] = 0.5f * (maxValues[i] + minValues[i]);
      activeHalfRanges[i] = Math.max(
          MIN_VECTOR_MAGNITUDE,
          0.5f * (maxValues[i] - minValues[i]));
    }
    activeCalibrationInitialized = true;
  }

  private void updateActiveCalibration(float dt) {
    if (!activeCalibrationInitialized) {
      initializeActiveCalibration();
      return;
    }
    float alpha = 1.0f - (float) Math.exp(
        -dt / ACTIVE_CALIBRATION_TIME_CONSTANT_SECONDS);
    for (int i = 0; i < 3; i++) {
      float targetCenter = 0.5f * (maxValues[i] + minValues[i]);
      float targetHalfRange = Math.max(
          MIN_VECTOR_MAGNITUDE,
          0.5f * (maxValues[i] - minValues[i]));
      activeCenters[i] += (targetCenter - activeCenters[i]) * alpha;
      activeHalfRanges[i] += (targetHalfRange - activeHalfRanges[i]) * alpha;
    }
  }

  private static boolean normalize(float x, float y, float z, float[] output) {
    float vectorMagnitude = magnitude(x, y, z);
    if (!Float.isFinite(vectorMagnitude) || vectorMagnitude < MIN_VECTOR_MAGNITUDE) {
      return false;
    }
    output[0] = x / vectorMagnitude;
    output[1] = y / vectorMagnitude;
    output[2] = z / vectorMagnitude;
    return true;
  }

  private static boolean isFiniteVector(float x, float y, float z) {
    return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
  }

  private static boolean allFinite(float[] values) {
    for (float value : values) {
      if (!Float.isFinite(value)) {
        return false;
      }
    }
    return true;
  }

  private static float magnitude(float x, float y, float z) {
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  private static float clamp(float value, float minimum, float maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}
