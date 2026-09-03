package com.enricoros.nreal;

import android.util.Log;

public final class AppLog {
  public interface MessageBuilder {
    String build();
  }

  private static final boolean DEBUG_LOGGING = BuildConfig.DEBUG;

  private AppLog() {
  }

  public static boolean isVerboseEnabled(String tag) {
    return Log.isLoggable(tag, Log.VERBOSE);
  }

  public static boolean isDebugEnabled(String tag) {
    return DEBUG_LOGGING || Log.isLoggable(tag, Log.DEBUG);
  }

  public static boolean isInfoEnabled(String tag) {
    return DEBUG_LOGGING || Log.isLoggable(tag, Log.INFO);
  }

  public static void v(String tag, String message) {
    if (isVerboseEnabled(tag)) {
      Log.v(tag, message);
    }
  }

  public static void v(String tag, MessageBuilder messageBuilder) {
    if (isVerboseEnabled(tag)) {
      Log.v(tag, messageBuilder.build());
    }
  }

  public static void d(String tag, String message) {
    if (isDebugEnabled(tag)) {
      Log.d(tag, message);
    }
  }

  public static void d(String tag, MessageBuilder messageBuilder) {
    if (isDebugEnabled(tag)) {
      Log.d(tag, messageBuilder.build());
    }
  }

  public static void i(String tag, String message) {
    if (isInfoEnabled(tag)) {
      Log.i(tag, message);
    }
  }

  public static void i(String tag, MessageBuilder messageBuilder) {
    if (isInfoEnabled(tag)) {
      Log.i(tag, messageBuilder.build());
    }
  }

  public static void w(String tag, String message) {
    Log.w(tag, message);
  }

  public static void w(String tag, String message, Throwable throwable) {
    Log.w(tag, message, throwable);
  }

  public static void w(String tag, MessageBuilder messageBuilder) {
    Log.w(tag, messageBuilder.build());
  }

  public static void e(String tag, String message) {
    Log.e(tag, message);
  }

  public static void e(String tag, String message, Throwable throwable) {
    Log.e(tag, message, throwable);
  }

  public static void e(String tag, MessageBuilder messageBuilder) {
    Log.e(tag, messageBuilder.build());
  }
}
