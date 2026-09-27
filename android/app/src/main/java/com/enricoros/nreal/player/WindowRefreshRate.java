package com.enricoros.nreal.player;

import android.view.WindowManager;

import com.enricoros.nreal.AppLog;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;

/** Window-scoped minimum: released by Android when the window disappears, even on a crash. */
public final class WindowRefreshRate {
  private static final String TAG = "WindowRefreshRate";
  private static final Field MINIMUM_RATE = findMinimumRateField();

  private WindowRefreshRate() {}

  private static Field findMinimumRateField() {
    try {
      // A window minimum can lift an external-composition refresh cap when the
      // system does not honor preferredRefreshRate alone.
      // Obtain just this field; do not enable process-wide hidden-API exemptions.
      for (Field field : HiddenApiBypass.getInstanceFields(WindowManager.LayoutParams.class)) {
        if (field.getName().equals("preferredMinDisplayRefreshRate")) return field;
      }
      AppLog.w(TAG, "Window minimum refresh field is unavailable");
    } catch (RuntimeException | LinkageError error) {
      AppLog.w(TAG, "Could not access window minimum refresh field", error);
    }
    return null;
  }

  /** Updates the caller's own window attributes; returns whether they changed. */
  public static boolean setMinimum(WindowManager.LayoutParams params, float refreshRate) {
    if (MINIMUM_RATE == null) return false;
    try {
      if (MINIMUM_RATE.getFloat(params) == refreshRate) return false;
      MINIMUM_RATE.setFloat(params, refreshRate);
      AppLog.i(TAG, "Window minimum refresh request=" + refreshRate);
      return true;
    } catch (IllegalAccessException | RuntimeException error) {
      AppLog.w(TAG, "Could not set window minimum refresh", error);
      return false;
    }
  }
}
