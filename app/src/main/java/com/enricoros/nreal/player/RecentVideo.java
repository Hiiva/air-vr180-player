package com.enricoros.nreal.player;

import android.net.Uri;

public final class RecentVideo {
  public final Uri uri;
  public final String title;
  public long size;
  public long lastPositionMs;
  public long watchedTimeMs;
  public long durationMs;
  public int projectionMode;

  public RecentVideo(Uri uri, String title, long lastPositionMs, long durationMs) {
    this(uri, title, 0L, lastPositionMs, durationMs, 0);
  }

  public RecentVideo(Uri uri, String title, long lastPositionMs, long durationMs, int projectionMode) {
    this(uri, title, 0L, lastPositionMs, durationMs, projectionMode);
  }

  public RecentVideo(Uri uri, String title, long size, long lastPositionMs, long durationMs, int projectionMode) {
    this(uri, title, size, lastPositionMs, 0L, durationMs, projectionMode);
  }

  public RecentVideo(
      Uri uri,
      String title,
      long size,
      long lastPositionMs,
      long watchedTimeMs,
      long durationMs,
      int projectionMode) {
    this.uri = uri;
    this.title = title;
    this.size = size;
    this.lastPositionMs = lastPositionMs;
    this.watchedTimeMs = watchedTimeMs;
    this.durationMs = durationMs;
    this.projectionMode = projectionMode;
  }
}
