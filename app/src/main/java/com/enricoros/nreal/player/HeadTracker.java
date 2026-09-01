package com.enricoros.nreal.player;

import com.enricoros.nreal.driver.ImuDataRaw;

/**
 * Drift-bounded 9-axis head tracker with automatic startup centering, online gyro bias
 * estimation, magnetic disturbance rejection, and seamless magnetic re-locking.
 */
public final class HeadTracker {
  private static final float NS_TO_SECONDS = 1.0e-9f;
  private static final float MIN_DT_SECONDS = 0.0001f;
  private static final float MAX_DT_SECONDS = 0.050f;

  private static final float MIN_ACCEL_G = 0.70f;
  private static final float MAX_ACCEL_G = 1.30f;
  private static final float ACCEL_CORRECTION_GAIN = 2.0f;
  private static final float ACCEL_REJECTION_COS = 0.81915206f; // cos(35 degrees)

  private static final float STATIONARY_MIN_ACCEL_G = 0.94f;
  private static final float STATIONARY_MAX_ACCEL_G = 1.06f;
  private static final float STATIONARY_GYRO_WITH_MAG_RAD_PER_SECOND = 0.060f;
  private static final float STATIONARY_GYRO_WITHOUT_MAG_RAD_PER_SECOND = 0.015f;
  private static final float STATIONARY_HOLD_SECONDS = 0.75f;
  private static final float STATIONARY_VECTOR_COS = 0.99994516f; // cos(0.6 degrees)
  private static final float STATIONARY_DEADBAND_RAD_PER_SECOND = 0.010f;
  private static final float GYRO_BIAS_TIME_CONSTANT_SECONDS = 6.0f;
  private static final float MAX_GYRO_BIAS_RAD_PER_SECOND = 0.25f;

  private static final float MAGNETIC_CORRECTION_GAIN = 1.0f;
  private static final float MAGNETIC_STATIONARY_GAIN = 1.8f;
  private static final float UNCALIBRATED_MAGNETIC_GAIN_SCALE = 0.30f;
  private static final float MAX_MAGNETIC_CORRECTION_RAD_PER_SECOND = 0.45f;
  private static final float MAGNETIC_HEADING_DEADBAND_RAD = 0.00035f;
  private static final float MAGNETIC_REJECTION_RAD = 0.34906584f; // 20 degrees
  private static final float MAGNETIC_HEADING_JUMP_RAD = 0.08726646f; // 5 degrees
  private static final float MIN_CALIBRATED_FIELD_RATIO = 0.55f;
  private static final float MAX_CALIBRATED_FIELD_RATIO = 1.80f;
  private static final float MIN_UNCALIBRATED_FIELD_RATIO = 0.35f;
  private static final float MAX_UNCALIBRATED_FIELD_RATIO = 2.85f;
  private static final float MAGNETIC_REFERENCE_TIME_CONSTANT_SECONDS = 30.0f;
  private static final float MAGNETIC_RELOCK_SECONDS = 3.0f;
  private static final float MIN_HORIZONTAL_MAGNITUDE = 0.08f;

  private final Quaternion orientation = new Quaternion();
  private final Quaternion centerCorrection = new Quaternion();
  private final Quaternion delta = new Quaternion();
  private final Quaternion centeredOrientation = new Quaternion();

  private final float[] gyroBias = new float[3];
  private final float[] gyro = new float[3];
  private final float[] correctedGyro = new float[3];
  private final float[] accel = new float[3];
  private final float[] accelUnit = new float[3];
  private final float[] magnetometer = new float[3];
  private final float[] magnetometerUnit = new float[3];
  private final float[] expectedUpInHead = new float[3];
  private final float[] worldMagnetometer = new float[3];
  private final float[] magneticReferenceWorld = new float[3];
  private final float[] stationaryAccelReference = new float[3];
  private final float[] stationaryMagReference = new float[3];
  private final float[] stationaryGyroSum = new float[3];
  private final float[] rotationMatrix = new float[9];

  private long lastTimestampNs;
  private boolean initialized;
  private boolean pendingAutomaticCenter;
  private boolean gyroBiasInitialized;
  private boolean magneticReferenceInitialized;
  private boolean stationaryReferencesInitialized;
  private float stationaryCandidateSeconds;
  private int stationaryGyroSampleCount;
  private float magneticFieldReference;
  private float magneticRejectedSeconds;
  private float filteredHeadingError;
  private float lastAcceptedHeadingError;
  private boolean magneticContinuityLost;

  public synchronized float[] update(ImuDataRaw sample) {
    sample.getGyroscopeRadiansPerSecond(gyro);
    sample.getAccelerationGs(accel);
    sample.getMagnetometerDirection(magnetometer);
    return updateLocked(
        sample.getUptimeNs(),
        gyro,
        accel,
        magnetometer,
        sample.hasMagnetometer(),
        sample.getMagnetometerFieldMagnitude(),
        sample.isMagnetometerCalibrated());
  }

  /** Automatically makes the current pose the viewing origin without resetting fusion state. */
  public synchronized void startViewingSession() {
    if (initialized) {
      centerCorrection.setConjugated(orientation);
      pendingAutomaticCenter = false;
    } else {
      pendingAutomaticCenter = true;
    }
  }

  /** Makes the current fused pose the viewing origin without resetting fusion state. */
  public synchronized void recenter() {
    startViewingSession();
  }

  public synchronized void reset() {
    orientation.setIdentity();
    centerCorrection.setIdentity();
    delta.setIdentity();
    centeredOrientation.setIdentity();
    zero(gyroBias);
    zero(stationaryGyroSum);
    zero(magneticReferenceWorld);
    gyroBiasInitialized = false;
    magneticReferenceInitialized = false;
    stationaryReferencesInitialized = false;
    initialized = false;
    pendingAutomaticCenter = true;
    stationaryCandidateSeconds = 0.0f;
    stationaryGyroSampleCount = 0;
    magneticFieldReference = 0.0f;
    magneticRejectedSeconds = 0.0f;
    filteredHeadingError = 0.0f;
    lastAcceptedHeadingError = 0.0f;
    magneticContinuityLost = true;
    lastTimestampNs = 0L;
  }

  public synchronized float[] getRotationMatrix() {
    return getRotationMatrixLocked();
  }

  synchronized float[] updateForTest(
      long timestampNs,
      float[] gyroscopeRadiansPerSecond,
      float[] accelerationGs,
      float[] magnetometerDirection,
      float magnetometerFieldMagnitude,
      boolean magnetometerCalibrated) {
    System.arraycopy(gyroscopeRadiansPerSecond, 0, gyro, 0, 3);
    System.arraycopy(accelerationGs, 0, accel, 0, 3);
    boolean hasMagnetometer = magnetometerDirection != null && magnetometerDirection.length >= 3;
    if (hasMagnetometer) {
      System.arraycopy(magnetometerDirection, 0, magnetometer, 0, 3);
    } else {
      zero(magnetometer);
    }
    return updateLocked(
        timestampNs,
        gyro,
        accel,
        magnetometer,
        hasMagnetometer,
        magnetometerFieldMagnitude,
        magnetometerCalibrated);
  }

  private float[] updateLocked(
      long timestampNs,
      float[] gyroscope,
      float[] acceleration,
      float[] magneticDirection,
      boolean hasMagnetometer,
      float magneticFieldMagnitude,
      boolean magnetometerCalibrated) {
    sanitizeVector(gyroscope);
    sanitizeVector(acceleration);

    float accelMagnitude = normalizeInto(acceleration, accelUnit);
    boolean accelValid = accelMagnitude >= MIN_ACCEL_G && accelMagnitude <= MAX_ACCEL_G;
    boolean magValid = hasMagnetometer
        && normalizeInto(magneticDirection, magnetometerUnit) > 0.0f
        && Float.isFinite(magneticFieldMagnitude)
        && magneticFieldMagnitude > 0.0f;

    if (!initialized) {
      if (accelValid) {
        orientation.setFromUnitVectors(
            accelUnit[0], accelUnit[1], accelUnit[2],
            0.0f, 1.0f, 0.0f);
      } else {
        orientation.setIdentity();
      }
      orientation.normalize();
      if (magValid) {
        initializeMagneticReference(magnetometerUnit, magneticFieldMagnitude);
      }
      centerCorrection.setConjugated(orientation);
      pendingAutomaticCenter = false;
      initialized = true;
      lastTimestampNs = timestampNs;
      return getRotationMatrixLocked();
    }

    float dt = (timestampNs - lastTimestampNs) * NS_TO_SECONDS;
    lastTimestampNs = timestampNs;
    if (!Float.isFinite(dt) || dt < MIN_DT_SECONDS || dt > MAX_DT_SECONDS) {
      applyPendingAutomaticCenter();
      return getRotationMatrixLocked();
    }

    boolean stationary = updateStationaryState(
        gyroscope, accelMagnitude, accelValid, magValid, dt);
    updateGyroBias(gyroscope, stationary, dt);
    for (int i = 0; i < 3; i++) {
      correctedGyro[i] = gyroscope[i] - (gyroBiasInitialized ? gyroBias[i] : 0.0f);
    }
    if (stationary
        && magnitude(correctedGyro[0], correctedGyro[1], correctedGyro[2])
        < STATIONARY_DEADBAND_RAD_PER_SECOND) {
      zero(correctedGyro);
    }

    applyAccelerometerCorrection(correctedGyro, accelUnit, accelMagnitude, accelValid);
    applyMagnetometerCorrection(
        correctedGyro,
        magnetometerUnit,
        magValid,
        magneticFieldMagnitude,
        magnetometerCalibrated,
        stationary,
        dt);

    delta.setFromAngularVelocity(
        correctedGyro[0], correctedGyro[1], correctedGyro[2], dt);
    orientation.multiplyRight(delta);
    orientation.normalize();

    applyPendingAutomaticCenter();
    return getRotationMatrixLocked();
  }

  private boolean updateStationaryState(
      float[] gyroscope,
      float accelMagnitude,
      boolean accelValid,
      boolean magValid,
      float dt) {
    float gyroThreshold = magValid
        ? STATIONARY_GYRO_WITH_MAG_RAD_PER_SECOND
        : STATIONARY_GYRO_WITHOUT_MAG_RAD_PER_SECOND;
    boolean candidate = accelValid
        && accelMagnitude >= STATIONARY_MIN_ACCEL_G
        && accelMagnitude <= STATIONARY_MAX_ACCEL_G
        && magnitude(
            gyroscope[0] - (gyroBiasInitialized ? gyroBias[0] : 0.0f),
            gyroscope[1] - (gyroBiasInitialized ? gyroBias[1] : 0.0f),
            gyroscope[2] - (gyroBiasInitialized ? gyroBias[2] : 0.0f)) < gyroThreshold;

    if (!candidate) {
      resetStationaryCandidate();
      return false;
    }

    if (!stationaryReferencesInitialized) {
      System.arraycopy(accelUnit, 0, stationaryAccelReference, 0, 3);
      if (magValid) {
        System.arraycopy(magnetometerUnit, 0, stationaryMagReference, 0, 3);
      } else {
        zero(stationaryMagReference);
      }
      stationaryReferencesInitialized = true;
    } else {
      boolean accelMoved = dot(accelUnit, stationaryAccelReference) < STATIONARY_VECTOR_COS;
      boolean magMoved = magValid
          && magnitude(stationaryMagReference[0], stationaryMagReference[1], stationaryMagReference[2]) > 0.0f
          && dot(magnetometerUnit, stationaryMagReference) < STATIONARY_VECTOR_COS;
      if (accelMoved || magMoved) {
        resetStationaryCandidate();
        return false;
      }
    }

    stationaryCandidateSeconds += dt;
    stationaryGyroSum[0] += gyroscope[0];
    stationaryGyroSum[1] += gyroscope[1];
    stationaryGyroSum[2] += gyroscope[2];
    stationaryGyroSampleCount++;
    return stationaryCandidateSeconds >= STATIONARY_HOLD_SECONDS;
  }

  private void resetStationaryCandidate() {
    stationaryCandidateSeconds = 0.0f;
    stationaryGyroSampleCount = 0;
    stationaryReferencesInitialized = false;
    zero(stationaryGyroSum);
  }

  private void updateGyroBias(float[] gyroscope, boolean stationary, float dt) {
    if (!stationary) {
      return;
    }

    if (!gyroBiasInitialized && stationaryGyroSampleCount > 0) {
      for (int i = 0; i < 3; i++) {
        gyroBias[i] = clamp(
            stationaryGyroSum[i] / stationaryGyroSampleCount,
            -MAX_GYRO_BIAS_RAD_PER_SECOND,
            MAX_GYRO_BIAS_RAD_PER_SECOND);
      }
      gyroBiasInitialized = true;
      return;
    }

    float alpha = 1.0f - (float) Math.exp(-dt / GYRO_BIAS_TIME_CONSTANT_SECONDS);
    for (int i = 0; i < 3; i++) {
      gyroBias[i] += (gyroscope[i] - gyroBias[i]) * alpha;
      gyroBias[i] = clamp(
          gyroBias[i], -MAX_GYRO_BIAS_RAD_PER_SECOND, MAX_GYRO_BIAS_RAD_PER_SECOND);
    }
  }

  private void applyAccelerometerCorrection(
      float[] gyroscope,
      float[] measuredUp,
      float accelMagnitude,
      boolean accelValid) {
    if (!accelValid) {
      return;
    }

    orientation.inverseRotate(0.0f, 1.0f, 0.0f, expectedUpInHead);
    float alignment = dot(measuredUp, expectedUpInHead);
    if (alignment < ACCEL_REJECTION_COS) {
      return;
    }

    float magnitudeWeight = 1.0f - clamp(Math.abs(accelMagnitude - 1.0f) / 0.30f, 0.0f, 1.0f);
    // Measured cross expected gives the body-frame angular velocity that removes tilt error.
    float errorX = measuredUp[1] * expectedUpInHead[2]
        - measuredUp[2] * expectedUpInHead[1];
    float errorY = measuredUp[2] * expectedUpInHead[0]
        - measuredUp[0] * expectedUpInHead[2];
    float errorZ = measuredUp[0] * expectedUpInHead[1]
        - measuredUp[1] * expectedUpInHead[0];
    float gain = ACCEL_CORRECTION_GAIN * magnitudeWeight;
    gyroscope[0] += errorX * gain;
    gyroscope[1] += errorY * gain;
    gyroscope[2] += errorZ * gain;
  }

  private void applyMagnetometerCorrection(
      float[] gyroscope,
      float[] measuredMagnetometer,
      boolean magValid,
      float fieldMagnitude,
      boolean calibrated,
      boolean stationary,
      float dt) {
    if (!magValid) {
      magneticRejectedSeconds += dt;
      filteredHeadingError = 0.0f;
      magneticContinuityLost = true;
      return;
    }

    if (!magneticReferenceInitialized) {
      initializeMagneticReference(measuredMagnetometer, fieldMagnitude);
      return;
    }

    if (!calculateWorldHorizontalMagnetometer(measuredMagnetometer, worldMagnetometer)) {
      magneticRejectedSeconds += dt;
      filteredHeadingError = 0.0f;
      magneticContinuityLost = true;
      return;
    }

    float dot = clamp(
        worldMagnetometer[0] * magneticReferenceWorld[0]
            + worldMagnetometer[2] * magneticReferenceWorld[2],
        -1.0f,
        1.0f);
    float crossY = worldMagnetometer[2] * magneticReferenceWorld[0]
        - worldMagnetometer[0] * magneticReferenceWorld[2];
    float headingError = (float) Math.atan2(crossY, dot);

    float fieldRatio = fieldMagnitude / Math.max(magneticFieldReference, 1.0e-6f);
    float minFieldRatio = calibrated
        ? MIN_CALIBRATED_FIELD_RATIO
        : MIN_UNCALIBRATED_FIELD_RATIO;
    float maxFieldRatio = calibrated
        ? MAX_CALIBRATED_FIELD_RATIO
        : MAX_UNCALIBRATED_FIELD_RATIO;
    float headingJump = normalizeAngle(headingError - lastAcceptedHeadingError);
    boolean accepted = fieldRatio >= minFieldRatio
        && fieldRatio <= maxFieldRatio
        && Math.abs(headingError) <= MAGNETIC_REJECTION_RAD
        && (magneticContinuityLost || Math.abs(headingJump) <= MAGNETIC_HEADING_JUMP_RAD);

    if (!accepted) {
      magneticRejectedSeconds += dt;
      filteredHeadingError = 0.0f;
      if (stationary && magneticRejectedSeconds >= MAGNETIC_RELOCK_SECONDS) {
        // Adopt the stable local field without rotating the current view.
        System.arraycopy(worldMagnetometer, 0, magneticReferenceWorld, 0, 3);
        magneticFieldReference = fieldMagnitude;
        magneticRejectedSeconds = 0.0f;
        lastAcceptedHeadingError = 0.0f;
        magneticContinuityLost = false;
      }
      return;
    }

    magneticRejectedSeconds = 0.0f;
    lastAcceptedHeadingError = headingError;
    magneticContinuityLost = false;
    float fieldAlpha = 1.0f - (float) Math.exp(
        -dt / MAGNETIC_REFERENCE_TIME_CONSTANT_SECONDS);
    magneticFieldReference += (fieldMagnitude - magneticFieldReference) * fieldAlpha;

    float errorAlpha = 1.0f - (float) Math.exp(-dt / 0.12f);
    filteredHeadingError += (headingError - filteredHeadingError) * errorAlpha;
    if (Math.abs(filteredHeadingError) < MAGNETIC_HEADING_DEADBAND_RAD) {
      return;
    }

    orientation.inverseRotate(0.0f, 1.0f, 0.0f, expectedUpInHead);
    float gain = stationary ? MAGNETIC_STATIONARY_GAIN : MAGNETIC_CORRECTION_GAIN;
    if (!calibrated) {
      gain *= UNCALIBRATED_MAGNETIC_GAIN_SCALE;
    }
    float correctionRate = clamp(
        filteredHeadingError * gain,
        -MAX_MAGNETIC_CORRECTION_RAD_PER_SECOND,
        MAX_MAGNETIC_CORRECTION_RAD_PER_SECOND);
    gyroscope[0] += expectedUpInHead[0] * correctionRate;
    gyroscope[1] += expectedUpInHead[1] * correctionRate;
    gyroscope[2] += expectedUpInHead[2] * correctionRate;
  }

  private void initializeMagneticReference(float[] measuredMagnetometer, float fieldMagnitude) {
    if (!calculateWorldHorizontalMagnetometer(measuredMagnetometer, magneticReferenceWorld)) {
      return;
    }
    magneticFieldReference = fieldMagnitude;
    magneticReferenceInitialized = true;
    magneticRejectedSeconds = 0.0f;
    filteredHeadingError = 0.0f;
    lastAcceptedHeadingError = 0.0f;
    magneticContinuityLost = false;
  }

  private boolean calculateWorldHorizontalMagnetometer(float[] measured, float[] output) {
    orientation.rotate(measured[0], measured[1], measured[2], output);
    output[1] = 0.0f;
    float horizontalMagnitude = magnitude(output[0], 0.0f, output[2]);
    if (!Float.isFinite(horizontalMagnitude) || horizontalMagnitude < MIN_HORIZONTAL_MAGNITUDE) {
      return false;
    }
    output[0] /= horizontalMagnitude;
    output[2] /= horizontalMagnitude;
    return true;
  }

  private void applyPendingAutomaticCenter() {
    if (pendingAutomaticCenter) {
      centerCorrection.setConjugated(orientation);
      pendingAutomaticCenter = false;
    }
  }

  private float[] getRotationMatrixLocked() {
    centeredOrientation.set(centerCorrection);
    centeredOrientation.multiplyRight(orientation);
    centeredOrientation.normalize();
    centeredOrientation.toColumnMajorMatrix3(rotationMatrix);
    return rotationMatrix;
  }

  private static float normalizeInto(float[] input, float[] output) {
    if (input == null || input.length < 3) {
      zero(output);
      return 0.0f;
    }
    float magnitude = magnitude(input[0], input[1], input[2]);
    if (!Float.isFinite(magnitude) || magnitude < 1.0e-6f) {
      zero(output);
      return 0.0f;
    }
    output[0] = input[0] / magnitude;
    output[1] = input[1] / magnitude;
    output[2] = input[2] / magnitude;
    return magnitude;
  }

  private static void sanitizeVector(float[] vector) {
    for (int i = 0; i < 3; i++) {
      if (!Float.isFinite(vector[i])) {
        vector[i] = 0.0f;
      }
    }
  }

  private static float dot(float[] a, float[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static float magnitude(float x, float y, float z) {
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  private static void zero(float[] values) {
    values[0] = 0.0f;
    values[1] = 0.0f;
    values[2] = 0.0f;
  }

  private static float normalizeAngle(float angle) {
    while (angle > Math.PI) {
      angle -= (float) (2.0 * Math.PI);
    }
    while (angle < -Math.PI) {
      angle += (float) (2.0 * Math.PI);
    }
    return angle;
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
