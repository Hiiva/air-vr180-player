package com.enricoros.nreal.player;

import android.os.SystemClock;

import com.enricoros.nreal.AppLog;
import com.enricoros.nreal.driver.ImuDataRaw;

import java.util.Locale;

/** Full-rate VQF fusion with automatic bias estimation during rest and motion. */
public final class HeadTracker implements AutoCloseable {
  static {
    System.loadLibrary("nreal_tracking");
  }

  private final Quaternion orientation = new Quaternion();
  private final Quaternion centerCorrection = new Quaternion();
  private final Quaternion centeredOrientation = new Quaternion();
  private final double[] fusedQuaternion = new double[4];
  private final double[] diagnostics = new double[6];
  private final float[] gyro = new float[3];
  private final float[] accel = new float[3];
  private final float[] magnetic = new float[3];
  private long nativeHandle = nativeCreate();
  private boolean initialized;
  private long lastDiagnosticMs;

  /** Called synchronously for EVERY USB sample, before any UI coalescing. */
  public synchronized void update(ImuDataRaw sample) {
    if (nativeHandle == 0) return;
    sample.getGyroscopeRadiansPerSecond(gyro);
    sample.getAccelerationGs(accel);
    boolean freshMagnetic = sample.hasMagnetometer();
    if (freshMagnetic) sample.getMagnetometerDirection(magnetic);
    nativeUpdate(nativeHandle, sample.getUptimeNs(), gyro[0], gyro[1], gyro[2],
        accel[0], accel[1], accel[2], freshMagnetic, magnetic[0], magnetic[1], magnetic[2], fusedQuaternion);
    orientation.w = (float) fusedQuaternion[0];
    orientation.x = (float) fusedQuaternion[1];
    orientation.y = (float) fusedQuaternion[2];
    orientation.z = (float) fusedQuaternion[3];
    orientation.normalize();
    if (!initialized) {
      centerCorrection.setConjugated(orientation);
      initialized = true;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - lastDiagnosticMs >= 5000L) {
      lastDiagnosticMs = now;
      nativeDiagnostics(nativeHandle, diagnostics);
      AppLog.i("HeadTracker", String.format(Locale.US,
          "VQF samples=%.0f gaps=%.0f rest=%s biasDps=%.5f %.5f %.5f",
          diagnostics[4], diagnostics[5], diagnostics[3] != 0,
          diagnostics[0], diagnostics[1], diagnostics[2]));
    }
  }

  /** Changes only the viewing origin; retains orientation and learned sensor bias. */
  public synchronized void startViewingSession() {
    if (initialized) centerCorrection.setConjugated(orientation);
  }

  public synchronized void recenter() {
    startViewingSession();
  }

  /** A new USB connection starts a new sensor timeline and bias estimate. */
  public synchronized void reset() {
    if (nativeHandle == 0) return;
    nativeDestroy(nativeHandle);
    nativeHandle = 0;
    nativeHandle = nativeCreate();
    orientation.setIdentity();
    centerCorrection.setIdentity();
    initialized = false;
    lastDiagnosticMs = 0;
  }

  /** Independent snapshot: the sensor thread must never mutate a matrix held by the renderer. */
  public synchronized float[] getRotationMatrix() {
    centeredOrientation.set(centerCorrection);
    centeredOrientation.multiplyRight(orientation);
    centeredOrientation.normalize();
    float[] matrix = new float[9];
    centeredOrientation.toColumnMajorMatrix3(matrix);
    return matrix;
  }

  @Override
  public synchronized void close() {
    nativeDestroy(nativeHandle);
    nativeHandle = 0;
  }

  private static native long nativeCreate();
  private static native void nativeDestroy(long handle);
  private static native void nativeUpdate(long handle, long timestampNs,
      float gx, float gy, float gz, float ax, float ay, float az,
      boolean freshMagnetic, float mx, float my, float mz, double[] output);
  private static native void nativeDiagnostics(long handle, double[] output);
}
