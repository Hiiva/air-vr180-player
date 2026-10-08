package com.enricoros.nreal.player;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.enricoros.nreal.AppLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** Uses the second VR texture for HEVC files on supported Qualcomm decoders. */
@androidx.annotation.OptIn(markerClass = {
    androidx.media3.common.util.UnstableApi.class, androidx.media3.common.util.ExperimentalApi.class})
public final class HevcPlaybackRenderersFactory extends DefaultRenderersFactory {
  private static final long ACCELERATED_HEVC_LATE_THRESHOLD_US = -30_000L;

  public static final class Surfaces {
    private static final class ScheduledFrame {
      final int lane;
      final long targetTimeNs;

      ScheduledFrame(int lane, long targetTimeNs) {
        this.lane = lane;
        this.targetTimeNs = targetTimeNs;
      }
    }

    private final Surface[] laneSurfaces = new Surface[3];
    private volatile Runnable frameLatchWakeup;
    private volatile boolean scheduledMode;
    private final ArrayDeque<ScheduledFrame> scheduledFrames = new ArrayDeque<>();

    public synchronized void set(int index, Surface surface) {
      if (index >= 0 && index < laneSurfaces.length) {
        laneSurfaces[index] = surface;
      }
    }

    public synchronized Surface[] additionalFor(Surface surface) {
      if (laneSurfaces[0] != surface) {
        return new Surface[0];
      }
      ArrayList<Surface> additional = new ArrayList<>(2);
      for (int i = 1; i < laneSurfaces.length; i++) {
        Surface candidate = laneSurfaces[i];
        if (candidate != null && candidate.isValid()) {
          additional.add(candidate);
        }
      }
      return additional.toArray(new Surface[0]);
    }

    public void frameLatched() {
      Runnable wakeup = frameLatchWakeup;
      if (wakeup != null) {
        wakeup.run();
      }
    }

    public void setFrameLatchWakeup(Runnable wakeup) {
      frameLatchWakeup = wakeup;
    }

    public void setScheduledMode(boolean scheduledMode) {
      this.scheduledMode = scheduledMode;
      if (!scheduledMode) {
        clearScheduledFrames();
      }
    }

    public boolean isScheduledMode() {
      return scheduledMode;
    }

    public synchronized void scheduleFrame(int lane, long targetTimeNs) {
      scheduledFrames.add(new ScheduledFrame(lane, targetTimeNs));
    }

    public synchronized int peekScheduledLane() {
      ScheduledFrame frame = scheduledFrames.peek();
      return frame == null ? -1 : frame.lane;
    }

    public synchronized long peekScheduledTargetTimeNs() {
      ScheduledFrame frame = scheduledFrames.peek();
      return frame == null ? Long.MIN_VALUE : frame.targetTimeNs;
    }

    public synchronized int scheduledFrameCount() {
      return scheduledFrames.size();
    }

    public synchronized void consumeScheduledLane(int lane) {
      ScheduledFrame next = scheduledFrames.peek();
      if (next != null && next.lane == lane) {
        scheduledFrames.remove();
        notifyAll();
      }
    }

    public synchronized void clearScheduledFrames() {
      scheduledFrames.clear();
      notifyAll();
    }
  }

  private final Surfaces surfaces;
  private final boolean acceleratedPlayback;

  public HevcPlaybackRenderersFactory(
      Context context, Surfaces surfaces, boolean acceleratedPlayback) {
    super(context);
    this.surfaces = surfaces;
    this.acceleratedPlayback = acceleratedPlayback;
    surfaces.setScheduledMode(false);
    // Stable 1.0x playback also relied on asynchronous codec queueing for the extreme 8K path.
    // Keep this enabled for every player; only the dual-decoder/scheduled behavior is gated below.
    forceEnableMediaCodecAsynchronousQueueing();
  }

  private static boolean isDemandingHevc(Format format) {
    return MimeTypes.VIDEO_H265.equals(format.sampleMimeType)
        && format.width > 0
        && format.height > 0
        && format.frameRate >= 50f
        && (long) format.width * format.height >= 24_000_000L;
  }

  private static boolean isExtremeBitrateHevc(Format format) {
    return isDemandingHevc(format)
        && Math.max(format.averageBitrate, format.peakBitrate) > 160_000_000;
  }

  private static boolean isMain10Hevc(Format format) {
    if (!MimeTypes.VIDEO_H265.equals(format.sampleMimeType)) {
      return false;
    }
    if (format.colorInfo != null
        && (format.colorInfo.lumaBitdepth > 8 || format.colorInfo.chromaBitdepth > 8)) {
      return true;
    }
    String codecs = format.codecs;
    return codecs != null && (codecs.startsWith("hvc1.2.") || codecs.startsWith("hev1.2."));
  }

  private boolean needsParallelDecode(Format format) {
    return isExtremeBitrateHevc(format);
  }

  private static MediaCodecInfo parallelCodecInfo(MediaCodecInfo fallback) {
    try {
      for (MediaCodecInfo info : MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)) {
        if ("c2.qti.hevc.decoder.low_latency".equals(info.name)) {
          return info;
        }
      }
    } catch (MediaCodecUtil.DecoderQueryException error) {
      AppLog.w("ParallelHevc", "Could not query low-latency HEVC decoder", error);
    }
    return fallback;
  }

  @Override
  protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
    MediaCodecAdapter.Factory standard = super.getCodecAdapterFactory();
    MediaCodecAdapter.Factory parallel = new SynchronousMediaCodecAdapter.Factory();
    return configuration -> {
      Surface[] additionalSurfaces = surfaces.additionalFor(configuration.surface);
      if (needsParallelDecode(configuration.format) && configuration.crypto == null
          && "c2.qti.hevc.decoder".equals(configuration.codecInfo.name)
          && additionalSurfaces.length > 0) {
        MediaCodecInfo codecInfo = parallelCodecInfo(configuration.codecInfo);
        MediaCodecAdapter.Configuration parallelConfiguration = configuration;
        if (codecInfo != configuration.codecInfo) {
          parallelConfiguration = MediaCodecAdapter.Configuration.createForVideoDecoding(
              codecInfo,
              configuration.mediaFormat,
              configuration.format,
              configuration.surface,
              null);
        }
        for (int additionalCount = additionalSurfaces.length;
            additionalCount >= 1;
            additionalCount--) {
          Surface[] selectedAdditionalSurfaces = new Surface[additionalCount];
          System.arraycopy(
              additionalSurfaces, 0, selectedAdditionalSurfaces, 0, additionalCount);
          try {
            MediaCodecAdapter adapter = new ParallelHevcAdapter(
                parallelConfiguration, selectedAdditionalSurfaces, parallel);
            surfaces.setScheduledMode(acceleratedPlayback);
            AppLog.i("ParallelHevc", "Using " + (additionalCount + 1)
                + " decoders for HEVC samples: " + codecInfo.name);
            return adapter;
          } catch (IOException | RuntimeException error) {
            AppLog.w("ParallelHevc", "Could not create " + (additionalCount + 1)
                + " HEVC decoder lanes; trying fewer", error);
          }
        }
        surfaces.setScheduledMode(false);
      }
      return standard.createAdapter(configuration);
    };
  }

  @Override
  protected void buildVideoRenderers(Context context, int extensionMode,
      MediaCodecSelector selector, boolean fallback, Handler handler,
      VideoRendererEventListener listener, long joiningTimeMs, ArrayList<Renderer> out) {
    MediaCodecVideoRenderer.Builder builder = new MediaCodecVideoRenderer.Builder(context)
        .setCodecAdapterFactory(getCodecAdapterFactory())
        .setMediaCodecSelector(selector)
        .setEnableDecoderFallback(fallback)
        .setAllowedJoiningTimeMs(joiningTimeMs)
        .setEventHandler(handler)
        .setEventListener(listener)
        .setMaxDroppedFramesToNotify(30);
    // Every input timestamp must have an output, including preroll before a precise seek.
    if (Build.VERSION.SDK_INT >= 34) builder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(false);
    out.add(new VideoRenderer(builder, surfaces, acceleratedPlayback));
  }

  @Override
  protected void buildAudioRenderers(
      Context context,
      int extensionMode,
      MediaCodecSelector selector,
      boolean fallback,
      AudioSink audioSink,
      Handler handler,
      AudioRendererEventListener listener,
      ArrayList<Renderer> out) {
    if (!acceleratedPlayback) {
      super.buildAudioRenderers(
          context, extensionMode, selector, fallback, audioSink, handler, listener, out);
      return;
    }
    out.add(new PlaybackAudioRenderer(
        context,
        getCodecAdapterFactory(),
        selector,
        fallback,
        audioSink,
        handler,
        listener));
  }

  private static final class PlaybackAudioRenderer extends MediaCodecAudioRenderer {
    private boolean startedReady;

    PlaybackAudioRenderer(
        Context context,
        MediaCodecAdapter.Factory codecAdapterFactory,
        MediaCodecSelector selector,
        boolean fallback,
        AudioSink audioSink,
        Handler handler,
      AudioRendererEventListener listener) {
      super(context, codecAdapterFactory, selector, fallback, handler, listener, audioSink);
    }

    @Override
    public boolean isReady() {
      boolean ready = super.isReady();
      if (ready) {
        startedReady = true;
      }
      return ready || (startedReady && getState() == Renderer.STATE_STARTED && !isEnded());
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      startedReady = false;
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
    }
  }

  private static final class VideoRenderer extends MediaCodecVideoRenderer {
    private static final int PARALLEL_MAX_SCHEDULED_FRAMES = 1;
    private static final long PARALLEL_MAX_PRESENTATION_LAG_US = 120_000L;

    private long parallelAnchorPtsUs = Long.MIN_VALUE;
    private long parallelAnchorRealtimeNs;
    private long parallelLastPtsUs = Long.MIN_VALUE;
    private float parallelAnchorSpeed = 1f;
    private boolean preserveLateHevcFrames;
    private long dropStatsStartMs;
    private int outputFrames;
    private int outputDropFrames;
    private int keyframeCatchups;
    private int forcedOutputFrames;
    private int lateOutputChecks;
    private long worstLateOutputUs;
    private final Surfaces surfaces;
    private final boolean scheduledParallelPlayback;

    VideoRenderer(Builder builder, Surfaces surfaces, boolean scheduledParallelPlayback) {
      super(builder);
      this.surfaces = surfaces;
      this.scheduledParallelPlayback = scheduledParallelPlayback;
      surfaces.setFrameLatchWakeup(() -> {
        Renderer.WakeupListener wakeup = getWakeupListener();
        if (wakeup != null) {
          wakeup.onWakeup();
        }
      });
    }

    @Override
    protected float getCodecOperatingRateV23(
        float targetPlaybackSpeed, Format format, Format[] streamFormats) {
      float operatingRate =
          super.getCodecOperatingRateV23(targetPlaybackSpeed, format, streamFormats);
      if (targetPlaybackSpeed > 1.05f
          && operatingRate > 0f
          && isDemandingHevc(format)
          && isMain10Hevc(format)
          && !isExtremeBitrateHevc(format)) {
        return Math.min(120f, operatingRate * (4f / 3f));
      }
      return operatingRate;
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs,
        long elapsedRealtimeUs,
        MediaCodecAdapter codec,
        ByteBuffer buffer,
        int bufferIndex,
        int bufferFlags,
        int sampleCount,
        long bufferPresentationTimeUs,
        boolean isDecodeOnlyBuffer,
        boolean isLastBuffer,
        Format format) throws ExoPlaybackException {
      if (!(codec instanceof ParallelHevcAdapter) || !scheduledParallelPlayback) {
        float speed = getPlaybackSpeed();
        preserveLateHevcFrames = scheduledParallelPlayback
            && speed >= 1.45f
            && isDemandingHevc(format)
            && !isExtremeBitrateHevc(format);
        boolean processed = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format);
        if (processed) outputFrames++;
        if (scheduledParallelPlayback && isDemandingHevc(format)) {
          logOutputDropStats();
        }
        return processed;
      }
      if (getState() != Renderer.STATE_STARTED) {
        // Do not consume merged output during preroll. Holding the first output lets the
        // parallel adapter fill its internal ready queue so ExoPlayer can transition to started.
        return false;
      }
      if (isDecodeOnlyBuffer && !isLastBuffer) {
        skipOutputBuffer(codec, bufferIndex, bufferPresentationTimeUs);
        return true;
      }
      if (surfaces.scheduledFrameCount() >= PARALLEL_MAX_SCHEDULED_FRAMES) {
        return false;
      }
      ParallelHevcAdapter parallel = (ParallelHevcAdapter) codec;
      parallel.updatePlaybackPositionUs(positionUs);
      // At 1.50x an 8K60 source consumes essentially every 90 Hz display refresh. If decoding
      // or GL misses even a few refreshes there is no spare presentation bandwidth to catch up,
      // so a fixed A/V offset can accumulate forever. Once a picture has already been decoded,
      // dropping only its presentation is reference-safe and lets Media3 immediately drain later
      // decoded outputs until video is back near the player/audio clock.
      if (!isLastBuffer
          && bufferPresentationTimeUs < positionUs - PARALLEL_MAX_PRESENTATION_LAG_US) {
        skipOutputBuffer(codec, bufferIndex, bufferPresentationTimeUs);
        parallel.updateRendererOutputTimeUs(bufferPresentationTimeUs);
        parallelLastPtsUs = bufferPresentationTimeUs;
        return true;
      }
      // The parallel adapter already merges outputs by media PTS. Schedule the merged frame
      // on one stable PTS-to-wall-clock timeline. Recomputing from "now" for every late frame
      // causes multiple decoded frames to be released in a burst; SurfaceTexture then latches
      // only the newest one and visually skips the intermediate frames.
      float speed = Math.max(0.01f, getPlaybackSpeed());
      long nowNs = System.nanoTime();
      if (parallelAnchorPtsUs == Long.MIN_VALUE
          || bufferPresentationTimeUs <= parallelLastPtsUs
          || Math.abs(speed - parallelAnchorSpeed) > 0.0001f) {
        // Anchor the video timeline to ExoPlayer's current media clock, not to whichever decoded
        // frame happens to arrive first. Otherwise startup decode latency becomes a permanent
        // audio/video offset.
        parallelAnchorPtsUs = positionUs;
        parallelAnchorRealtimeNs = nowNs;
        parallelAnchorSpeed = speed;
        surfaces.clearScheduledFrames();
      }

      long mediaDeltaUs = bufferPresentationTimeUs - parallelAnchorPtsUs;
      long releaseTimeNs = parallelAnchorRealtimeNs
          + (long) (mediaDeltaUs * 1000.0 / parallelAnchorSpeed);

      int lane = parallel.laneForOutputIndex(bufferIndex);
      if (lane >= 0) {
        surfaces.scheduleFrame(lane, releaseTimeNs);
      }
      // Queue the decoded frame to SurfaceTexture immediately. GL owns the actual 90 Hz
      // presentation decision using the media PTS target stored above. This avoids racing two
      // independent future-time schedulers (MediaCodec and GL) against each other.
      renderOutputBuffer(codec, bufferIndex, bufferPresentationTimeUs);
      parallel.updateRendererOutputTimeUs(bufferPresentationTimeUs);
      parallelLastPtsUs = bufferPresentationTimeUs;
      return true;
    }

    @Override
    protected boolean shouldDropOutputBuffer(
        long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
      lateOutputChecks++;
      if (earlyUs < worstLateOutputUs) worstLateOutputUs = earlyUs;
      boolean drop;
      if (preserveLateHevcFrames
          && earlyUs < ACCELERATED_HEVC_LATE_THRESHOLD_US
          && !isLastBuffer) {
        drop = false;
      } else {
        drop = super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer);
      }
      if (drop) outputDropFrames++;
      return drop;
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(
        long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
      boolean drop = super.shouldDropBuffersToKeyframe(
          earlyUs, elapsedRealtimeUs, isLastBuffer);
      if (preserveLateHevcFrames && earlyUs < ACCELERATED_HEVC_LATE_THRESHOLD_US) {
        return false;
      }
      if (drop) keyframeCatchups++;
      return drop;
    }

    @Override
    protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedSinceLastRenderUs) {
      if (preserveLateHevcFrames
          && earlyUs < ACCELERATED_HEVC_LATE_THRESHOLD_US) {
        forcedOutputFrames++;
        return true;
      }
      boolean force = super.shouldForceRenderOutputBuffer(earlyUs, elapsedSinceLastRenderUs);
      if (force) forcedOutputFrames++;
      return force;
    }

    private void logOutputDropStats() {
      long nowMs = SystemClock.elapsedRealtime();
      if (dropStatsStartMs == 0L) dropStatsStartMs = nowMs;
      if (nowMs - dropStatsStartMs < 5_000L) return;
      AppLog.i("HevcDecode", "Output decisions: speed=" + getPlaybackSpeed()
          + ", outputs=" + outputFrames
          + ", drops=" + outputDropFrames
          + ", keyframeCatchups=" + keyframeCatchups
          + ", forced=" + forcedOutputFrames
          + ", lateChecks=" + lateOutputChecks
          + ", worstLateUs=" + worstLateOutputUs);
      dropStatsStartMs = nowMs;
      outputFrames = 0;
      outputDropFrames = 0;
      keyframeCatchups = 0;
      forcedOutputFrames = 0;
      lateOutputChecks = 0;
      worstLateOutputUs = 0;
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
      parallelAnchorPtsUs = Long.MIN_VALUE;
      parallelLastPtsUs = Long.MIN_VALUE;
      preserveLateHevcFrames = false;
      dropStatsStartMs = 0L;
      outputFrames = 0;
      outputDropFrames = 0;
      keyframeCatchups = 0;
      forcedOutputFrames = 0;
      lateOutputChecks = 0;
      worstLateOutputUs = 0L;
      surfaces.clearScheduledFrames();
    }

    @Override public boolean isReady() {
      MediaCodecAdapter codec = getCodec();
      if (codec instanceof ParallelHevcAdapter) {
        ParallelHevcAdapter parallel = (ParallelHevcAdapter) codec;
        // Input already moved into the adapter is still buffered media. Media3 only knows
        // about its source queue and the single output buffer currently held by the renderer.
        // A frame already released into the GL scheduling queue is buffered media too; omitting
        // it makes ExoPlayer oscillate BUFFERING/READY whenever the adapter queue briefly drains.
        boolean prerolled = parallel.hasPrerolled(getLastResetPositionUs());
        int scheduled = surfaces.scheduledFrameCount();
        boolean pending = parallel.hasPendingSamples();
        return prerolled && (scheduledParallelPlayback
            ? (scheduled > 0 || pending)
            : (pending || super.isReady()));
      }
      return super.isReady();
    }

    @Override protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
      // A new presentation owns a new pair of textures. Recreate both codec bindings together.
      return "c2.qti.hevc.decoder".equals(name)
          || "c2.qti.hevc.decoder.low_latency".equals(name)
          || super.codecNeedsSetOutputSurfaceWorkaround(name);
    }

    @Override protected DecoderReuseEvaluation canReuseCodec(MediaCodecInfo info, Format oldFormat, Format newFormat) {
      if (("c2.qti.hevc.decoder".equals(info.name)
              || "c2.qti.hevc.decoder.low_latency".equals(info.name))
          && (isDemandingHevc(oldFormat) || isDemandingHevc(newFormat))) {
        return new DecoderReuseEvaluation(info.name, oldFormat, newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
      }
      return super.canReuseCodec(info, oldFormat, newFormat);
    }

  }
}
