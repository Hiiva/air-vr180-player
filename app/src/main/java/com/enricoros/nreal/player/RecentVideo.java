package com.enricoros.nreal.player;

import android.net.Uri;

public final class RecentVideo {
  public final Uri uri;
  public final String title;
  public long lastPositionMs;
  public long durationMs;

  public RecentVideo(Uri uri, String title, long lastPositionMs, long durationMs) {
    this.uri = uri;
    this.title = title;
    this.lastPositionMs = lastPositionMs;
    this.durationMs = durationMs;
  }
}
