package com.enricoros.nreal.player;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.enricoros.nreal.AppLog;

import java.io.IOException;
import java.util.ArrayList;

/** Uses the second VR texture for demanding HEVC files on supported Qualcomm decoders. */
@androidx.annotation.OptIn(markerClass = {
    androidx.media3.common.util.UnstableApi.class, androidx.media3.common.util.ExperimentalApi.class})
public final class HevcPlaybackRenderersFactory extends DefaultRenderersFactory {
  public static final class Surfaces {
    private Surface primary;
    private Surface secondary;

    public synchronized void set(int index, Surface surface) {
      if (index == 0) primary = surface;
      if (index == 1) secondary = surface;
    }

    public synchronized Surface secondaryFor(Surface surface) {
      return primary == surface && secondary != null && secondary.isValid() ? secondary : null;
    }
  }

  private final Surfaces surfaces;

  public HevcPlaybackRenderersFactory(Context context, Surfaces surfaces) {
    super(context);
    this.surfaces = surfaces;
    forceEnableMediaCodecAsynchronousQueueing();
  }

  private static boolean needsParallelDecode(Format format) {
    return MimeTypes.VIDEO_H265.equals(format.sampleMimeType)
        && (long) format.width * format.height >= 24_000_000L
        && format.frameRate >= 50f
        && Math.max(format.averageBitrate, format.peakBitrate) > 160_000_000;
  }

  @Override
  protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
    MediaCodecAdapter.Factory standard = super.getCodecAdapterFactory();
    return configuration -> {
      Surface secondary = surfaces.secondaryFor(configuration.surface);
      if (needsParallelDecode(configuration.format) && configuration.crypto == null
          && "c2.qti.hevc.decoder".equals(configuration.codecInfo.name) && secondary != null) {
        try {
          MediaCodecAdapter adapter = new ParallelHevcAdapter(configuration, secondary, standard);
          AppLog.i("ParallelHevc", "Using two decoders for original high-bitrate HEVC samples");
          return adapter;
        } catch (IOException | RuntimeException error) {
          AppLog.w("ParallelHevc", "Second decoder unavailable; using one decoder", error);
        }
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
    out.add(new VideoRenderer(builder));
  }

  private static final class VideoRenderer extends MediaCodecVideoRenderer {
    VideoRenderer(Builder builder) { super(builder); }

    @Override public boolean isReady() {
      MediaCodecAdapter codec = getCodec();
      if (codec instanceof ParallelHevcAdapter) {
        ParallelHevcAdapter parallel = (ParallelHevcAdapter) codec;
        // Input already moved into the adapter is still buffered media. Media3 only knows
        // about its source queue and the single output buffer currently held by the renderer.
        return parallel.hasPrerolled(getLastResetPositionUs())
            && (parallel.hasPendingSamples() || super.isReady());
      }
      return super.isReady();
    }

    @Override protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
      // A new presentation owns a new pair of textures. Recreate both codec bindings together.
      return "c2.qti.hevc.decoder".equals(name) || super.codecNeedsSetOutputSurfaceWorkaround(name);
    }

    @Override protected DecoderReuseEvaluation canReuseCodec(MediaCodecInfo info, Format oldFormat, Format newFormat) {
      if ("c2.qti.hevc.decoder".equals(info.name)
          && (needsParallelDecode(oldFormat) || needsParallelDecode(newFormat))) {
        return new DecoderReuseEvaluation(info.name, oldFormat, newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
      }
      return super.canReuseCodec(info, oldFormat, newFormat);
    }

  }
}
