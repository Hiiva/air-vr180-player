package com.enricoros.nreal.player;

import com.enricoros.nreal.driver.ImuDataRaw;

/**
 * Six-axis head tracker using the glasses gyroscope for angular motion and gravity for
 * pitch/roll stabilization.
 *
 * <p>Yaw is deliberately not corrected from the magnetometer. The XREAL Air family magnetic
 * stream is sensitive to hard/soft-iron disturbances and its report-axis convention differs from
 * gyro/accelerometer. Feeding that signal back into yaw made a changing magnetic heading look like
 * real head motion. Long-term yaw stability is instead handled conservatively by learning residual
 * gyroscope bias only while the glasses have been demonstrably still for a sustained period.</p>
 */
public final class HeadTracker {
  private static final float NS_TO_SECONDS = 1.0e-9f;
  private static final float MIN_DT_SECONDS = 0.0001f;
  private static final float MAX_DT_SECONDS = 0.050f;

  private static final float MIN_ACCEL_G = 0.70f;
  private static final float MAX_ACCEL_G = 1.30f;
  private static final float ACCEL_CORRECTION_GAIN = 2.0f;
  private static final float ACCEL_REJECTION_COS = 0.81915206f; // cos(35 degrees)

  // Bias learning is deliberately conservative. It is safer to tolerate a tiny amount of
  // genuine gyro drift than to classify a slow intentional head turn as sensor bias.
  private static final float STATIONARY_MIN_ACCEL_G = 0.98f;
  private static final float STATIONARY_MAX_ACCEL_G = 1.02f;
  private static final float INITIAL_STATIONARY_GYRO_RAD_PER_SECOND = 0.0040f;
  private static final float STATIONARY_GYRO_RAD_PER_SECOND = 0.0030f;
  private static final float STATIONARY_HOLD_SECONDS = 2.0f;
  private static final float STATIONARY_VECTOR_COS = 0.99999391f; // cos(0.20 degrees)
  private static final float STATIONARY_MAG_VECTOR_COS = 0.99999848f; // cos(0.10 degrees)
  private static final int STATIONARY_MIN_FRESH_MAG_SAMPLES = 2;
  private static final float GYRO_BIAS_TIME_CONSTANT_SECONDS = 60.0f;
  private static final float MAX_GYRO_BIAS_RAD_PER_SECOND = 0.035f;

  private final Quaternion orientation = new Quaternion();
  private final Quaternion centerCorrection = new Quaternion();
  private final Quaternion delta = new Quaternion();
  private final Quaternion centeredOrientation = new Quaternion();

  private final float[] gyroBias = new float[3];
  private final float[] gyro = new float[3];
  private final float[] correctedGyro = new float[3];
  private final float[] accel = new float[3];
  private final float[] accelUnit = new float[3];
  private final float[] expectedUpInHead = new float[3];
  private final float[] stationaryAccelReference = new float[3];
  private final float[] stationaryMagReference = new float[3];
  private final float[] magnetometer = new float[3];
  private final float[] stationaryGyroSum = new float[3];
  private final float[] rotationMatrix = new float[9];

  private long lastTimestampNs;
  private boolean initialized;
  private boolean pendingAutomaticCenter;
  private boolean gyroBiasInitialized;
  private boolean stationaryReferenceInitialized;
  private boolean stationaryMagReferenceInitialized;
  private float stationaryCandidateSeconds;
  private int stationaryGyroSampleCount;
  private int stationaryFreshMagSampleCount;

  public synchronized float[] update(ImuDataRaw sample) {
    sample.getGyroscopeRadiansPerSecond(gyro);
    sample.getAccelerationGs(accel);
    boolean hasFreshMagnetometer = sample.hasMagnetometer();
    if (hasFreshMagnetometer) {
      sample.getMagnetometerDirection(magnetometer);
    } else {
      zero(magnetometer);
    }
    return updateLocked(
        sample.getUptimeNs(), gyro, accel, magnetometer, hasFreshMagnetometer);
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
    gyroBiasInitialized = false;
    stationaryReferenceInitialized = false;
    stationaryMagReferenceInitialized = false;
    initialized = false;
    pendingAutomaticCenter = true;
    stationaryCandidateSeconds = 0.0f;
    stationaryGyroSampleCount = 0;
    stationaryFreshMagSampleCount = 0;
    zero(stationaryMagReference);
    lastTimestampNs = 0L;
  }

  public synchronized float[] getRotationMatrix() {
    return getRotationMatrixLocked();
  }

  /**
   * Test hook. Magnetic heading is permitted only as a stationary-motion veto for bias learning;
   * it is never fed back into orientation and therefore cannot steer yaw.
   */
  synchronized float[] updateForTest(
      long timestampNs,
      float[] gyroscopeRadiansPerSecond,
      float[] accelerationGs,
      float[] magnetometerDirection,
      float magnetometerFieldMagnitude,
      boolean magnetometerCalibrated) {
    System.arraycopy(gyroscopeRadiansPerSecond, 0, gyro, 0, 3);
    System.arraycopy(accelerationGs, 0, accel, 0, 3);
    boolean hasFreshMagnetometer = magnetometerDirection != null
        && magnetometerDirection.length >= 3
        && normalizeInto(magnetometerDirection, magnetometer) > 0.0f;
    if (!hasFreshMagnetometer) {
      zero(magnetometer);
    }
    return updateLocked(
        timestampNs, gyro, accel, magnetometer, hasFreshMagnetometer);
  }

  private float[] updateLocked(
      long timestampNs,
      float[] gyroscope,
      float[] acceleration,
      float[] magneticDirection,
      boolean hasFreshMagnetometer) {
    sanitizeVector(gyroscope);
    sanitizeVector(acceleration);

    float accelMagnitude = normalizeInto(acceleration, accelUnit);
    boolean accelValid = accelMagnitude >= MIN_ACCEL_G && accelMagnitude <= MAX_ACCEL_G;

    if (!initialized) {
      if (accelValid) {
        orientation.setFromUnitVectors(
            accelUnit[0], accelUnit[1], accelUnit[2],
            0.0f, 1.0f, 0.0f);
      } else {
        orientation.setIdentity();
      }
      orientation.normalize();
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
        gyroscope, accelMagnitude, accelValid, magneticDirection, hasFreshMagnetometer, dt);
    updateGyroBias(gyroscope, stationary, dt);

    for (int i = 0; i < 3; i++) {
      correctedGyro[i] = gyroscope[i] - (gyroBiasInitialized ? gyroBias[i] : 0.0f);
    }


    applyAccelerometerCorrection(correctedGyro, accelUnit, accelMagnitude, accelValid);

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
      float[] magneticDirection,
      boolean hasFreshMagnetometer,
      float dt) {
    float biasX = gyroBiasInitialized ? gyroBias[0] : 0.0f;
    float biasY = gyroBiasInitialized ? gyroBias[1] : 0.0f;
    float biasZ = gyroBiasInitialized ? gyroBias[2] : 0.0f;
    float gyroThreshold = gyroBiasInitialized
        ? STATIONARY_GYRO_RAD_PER_SECOND
        : INITIAL_STATIONARY_GYRO_RAD_PER_SECOND;

    boolean candidate = accelValid
        && accelMagnitude >= STATIONARY_MIN_ACCEL_G
        && accelMagnitude <= STATIONARY_MAX_ACCEL_G
        && magnitude(
            gyroscope[0] - biasX,
            gyroscope[1] - biasY,
            gyroscope[2] - biasZ) < gyroThreshold;

    if (!candidate) {
      resetStationaryCandidate();
      return false;
    }

    if (!stationaryReferenceInitialized) {
      System.arraycopy(accelUnit, 0, stationaryAccelReference, 0, 3);
      stationaryReferenceInitialized = true;
    } else if (dot(accelUnit, stationaryAccelReference) < STATIONARY_VECTOR_COS) {
      resetStationaryCandidate();
      return false;
    }

    // The magnetometer is only a veto. A changing field/direction proves the glasses are not a
    // trustworthy zero-rate sample, but the magnetic heading is never used to rotate the view.
    // NrealDeviceThread supplies this vector only on the report's fresh-magnetic-observation flag.
    if (hasFreshMagnetometer) {
      if (!stationaryMagReferenceInitialized) {
        System.arraycopy(magneticDirection, 0, stationaryMagReference, 0, 3);
        stationaryMagReferenceInitialized = true;
      } else if (dot(magneticDirection, stationaryMagReference) < STATIONARY_MAG_VECTOR_COS) {
        resetStationaryCandidate();
        return false;
      }
      stationaryFreshMagSampleCount++;
    }

    stationaryCandidateSeconds += dt;
    stationaryGyroSum[0] += gyroscope[0];
    stationaryGyroSum[1] += gyroscope[1];
    stationaryGyroSum[2] += gyroscope[2];
    stationaryGyroSampleCount++;
    return stationaryCandidateSeconds >= STATIONARY_HOLD_SECONDS
        && stationaryFreshMagSampleCount >= STATIONARY_MIN_FRESH_MAG_SAMPLES;
  }

  private void resetStationaryCandidate() {
    stationaryCandidateSeconds = 0.0f;
    stationaryGyroSampleCount = 0;
    stationaryReferenceInitialized = false;
    stationaryMagReferenceInitialized = false;
    stationaryFreshMagSampleCount = 0;
    zero(stationaryGyroSum);
    zero(stationaryMagReference);
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

    // Adapt very slowly. This is for residual temperature/bias drift, not for following head motion.
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

    float magnitudeWeight = 1.0f
        - clamp(Math.abs(accelMagnitude - 1.0f) / 0.30f, 0.0f, 1.0f);
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
    float vectorMagnitude = magnitude(input[0], input[1], input[2]);
    if (!Float.isFinite(vectorMagnitude) || vectorMagnitude < 1.0e-6f) {
      zero(output);
      return 0.0f;
    }
    output[0] = input[0] / vectorMagnitude;
    output[1] = input[1] / vectorMagnitude;
    output[2] = input[2] / vectorMagnitude;
    return vectorMagnitude;
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

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
