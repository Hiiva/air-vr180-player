package com.enricoros.nreal.player;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * Keeps DefaultLoadControl's byte cap for loading while requiring a real time buffer before
 * playback starts or resumes. Media3 normally couples those two choices behind one boolean.
 */
@UnstableApi
public final class TimeGatedLoadControl extends DefaultLoadControl {
  private final long bufferForPlaybackUs;
  private final long bufferForRebufferUs;

  public TimeGatedLoadControl(
      int minBufferMs,
      int maxBufferMs,
      int bufferForPlaybackMs,
      int bufferForRebufferMs,
      int targetBufferBytes,
      int backBufferMs) {
    super(
        new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        minBufferMs,
        DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
        maxBufferMs,
        DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
        bufferForPlaybackMs,
        DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
        bufferForRebufferMs,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
        targetBufferBytes,
        false,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK,
        backBufferMs,
        false);
    bufferForPlaybackUs = bufferForPlaybackMs * 1000L;
    bufferForRebufferUs = bufferForRebufferMs * 1000L;
  }

  @Override
  public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
    long bufferedPlayoutUs = Util.getPlayoutDurationForMediaDuration(
        parameters.bufferedDurationUs, parameters.playbackSpeed);
    long requiredUs = parameters.rebuffering ? bufferForRebufferUs : bufferForPlaybackUs;
    if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
      requiredUs = Math.min(requiredUs, parameters.targetLiveOffsetUs / 2);
    }
    return requiredUs <= 0L || bufferedPlayoutUs >= requiredUs;
  }
}
