package com.enricoros.nreal.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;

import androidx.media3.decoder.CryptoInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;

import com.enricoros.nreal.AppLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Alternates closed HEVC GOPs between two decoders, without changing compressed samples. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ParallelHevcAdapter implements MediaCodecAdapter {
  private static final int INPUT_BUDGET_BYTES = 64 * 1024 * 1024;
  private static final int POOL_BUDGET_BYTES = 16 * 1024 * 1024;
  private static final int PREROLL_FRAMES = 12;
  private static final int EXTREME_PREROLL_FRAMES = 18;
  private static final int MAX_PUMP_OUTPUTS_PER_LANE = 8;
  private static final int MAX_PUMP_INPUTS_PER_LANE = 8;
  private static final float STARTUP_CODEC_OPERATING_RATE = 120f;
  private static final int EXTREME_BITRATE_BPS = 140_000_000;
  private static final long DROP_NON_REFERENCE_HALF_LAG_US = 200_000L;
  private static final long DROP_NON_REFERENCE_ALL_LAG_US = 500_000L;
  private static final long PRE_IRAP_HOLE_GRACE_NS = 50_000_000L;
  private static final long PRE_IRAP_MAX_TAIL_US = 100_000L;
  private static final int PRE_IRAP_RETIRE_POST_BOUNDARY_OUTPUTS = 4;
  private static final int EOS_INDEX = Integer.MAX_VALUE;

  private static final class Packet {
    final byte[] data;
    final int size;
    final long timeUs;
    final long gopBoundaryUs;
    final int flags;
    final boolean terminal;

    Packet(
        byte[] data,
        int size,
        long timeUs,
        long gopBoundaryUs,
        int flags,
        boolean terminal) {
      this.data = data;
      this.size = size;
      this.timeUs = timeUs;
      this.gopBoundaryUs = gopBoundaryUs;
      this.flags = flags;
      this.terminal = terminal;
    }
  }

  private static final class Output {
    final int index;
    final MediaCodec.BufferInfo info;

    Output(int index, MediaCodec.BufferInfo info) {
      this.index = index;
      this.info = info;
    }
  }

  private static final class Lane {
    volatile MediaCodecAdapter codec;
    final ArrayDeque<Packet> packets = new ArrayDeque<>();
    volatile boolean eosQueued;
    volatile boolean eosReceived;
    boolean terminal;
    boolean used;
    int held;
    volatile boolean flushing;
    volatile RuntimeException flushError;
    volatile Future<?> flushFuture;
    volatile long drainBoundaryUs = Long.MIN_VALUE;

    Lane(MediaCodecAdapter codec) {
      this.codec = codec;
    }
  }

  private final Lane[] lanes;
  private final boolean continuousLaneMode;
  private final boolean dedicatedLowLatencyCodec;
  private final float sourceFrameRate;
  private final int sourceBitrateBps;
  private final boolean extremeBitrate;
  private final ByteBuffer input;
  private final ArrayDeque<byte[]> pool = new ArrayDeque<>();
  private final PriorityQueue<Output> outputs = new PriorityQueue<>(
      Comparator.comparingLong(output -> output.info.presentationTimeUs));
  private final TreeMap<Long, Integer> expected = new TreeMap<>();
  private final java.util.HashMap<Long, Integer> expectedLane = new java.util.HashMap<>();
  private final java.util.HashMap<Long, Long> expectedHandoffBoundaryUs = new java.util.HashMap<>();
  private final java.util.HashSet<Long> retiredExpected = new java.util.HashSet<>();
  private final ExecutorService flushExecutor = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "ParallelHevcFlush");
    thread.setDaemon(true);
    return thread;
  });
  private MediaFormat outputFormat;
  private boolean formatPending;
  private boolean inputAcquired;
  private boolean inputEnded;
  private boolean eosReturned;
  private boolean started;
  private boolean prerolled;
  private boolean dropLeadingRasl;
  private int inputLane;
  private long currentInputGopBoundaryUs = Long.MIN_VALUE;
  private int queuedBytes;
  private int pooledBytes;
  private long lastRenderTimeNs;
  private long lastMergedOutputNs;
  private long blockedExpectedUs = Long.MIN_VALUE;
  private long blockedExpectedSinceNs;
  private volatile long playbackPositionUs = Long.MIN_VALUE;
  private volatile long rendererOutputTimeUs = Long.MIN_VALUE;
  private volatile boolean acceleratedPlayback;
  private volatile float playbackSpeed = 1f;
  private int nonReferenceDropPhase;

  public ParallelHevcAdapter(
      Configuration configuration,
      Surface[] additionalSurfaces,
      MediaCodecAdapter.Factory factory) throws IOException {
    lanes = new Lane[1 + Math.min(2, additionalSurfaces.length)];
    dedicatedLowLatencyCodec = configuration.codecInfo.name.contains("low_latency");
    // The standard Qualcomm decoder was stable at 1.0x with per-GOP drain/flush. Continuous
    // alternating lanes are reserved for the dedicated low-latency component used at >1.0x.
    continuousLaneMode = dedicatedLowLatencyCodec;
    sourceFrameRate = configuration.format.frameRate > 0f ? configuration.format.frameRate : 60f;
    int formatBitrate = Math.max(configuration.format.averageBitrate, configuration.format.peakBitrate);
    int mediaFormatBitrate = configuration.mediaFormat.containsKey(MediaFormat.KEY_BIT_RATE)
        ? configuration.mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        : -1;
    sourceBitrateBps = Math.max(formatBitrate, mediaFormatBitrate);
    extremeBitrate = sourceBitrateBps >= EXTREME_BITRATE_BPS;
    float configuredOperatingRate = configuration.mediaFormat.containsKey(MediaFormat.KEY_OPERATING_RATE)
        ? configuration.mediaFormat.getFloat(MediaFormat.KEY_OPERATING_RATE)
        : sourceFrameRate;
    playbackSpeed = Math.max(0.01f, configuredOperatingRate / sourceFrameRate);
    acceleratedPlayback = playbackSpeed > 1.05f;
    if (acceleratedPlayback) {
      // The parallel lanes need burst headroom even though their average GOP workload is split.
      // Qualcomm also only admits two simultaneous 8K low-latency instances with this profile.
      configuration.mediaFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, STARTUP_CODEC_OPERATING_RATE);
    }
    int inputSize = configuration.mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024);
    input = ByteBuffer.allocateDirect(inputSize);
    try {
      lanes[0] = new Lane(factory.createAdapter(configuration));
      for (int i = 1; i < lanes.length; i++) {
        lanes[i] = new Lane(factory.createAdapter(Configuration.createForVideoDecoding(
            configuration.codecInfo,
            configuration.mediaFormat,
            configuration.format,
            additionalSurfaces[i - 1],
            null)));
      }
    } catch (IOException | RuntimeException error) {
      for (Lane lane : lanes) {
        if (lane != null) {
          lane.codec.release();
        }
      }
      throw error;
    }
  }

  public boolean hasPrerolled(long positionUs) {
    // Media3 can hold both a virtual input buffer and an early output while buffering.
    // Keep the real codecs moving even when neither dequeue method is called again yet.
    pump();
    if (!prerolled) {
      int prerollFrames = extremeBitrate && acceleratedPlayback
          ? EXTREME_PREROLL_FRAMES
          : PREROLL_FRAMES;
      TreeMap<Long, Integer> ready = new TreeMap<>();
      for (Output output : outputs) ready.merge(output.info.presentationTimeUs, 1, Integer::sum);
      int count = 0;
      for (java.util.Map.Entry<Long, Integer> sample : expected.tailMap(positionUs).entrySet()) {
        if (ready.getOrDefault(sample.getKey(), 0) < sample.getValue()) break;
        count += sample.getValue();
        if (count >= prerollFrames) break;
      }
      // Count consecutive frames at the seek target, not frames from the other GOP or
      // decode-only pictures before the target. Those cannot keep the playback clock fed.
      boolean lanesReady = true;
      int heldFrames = 0;
      for (Lane lane : lanes) {
        heldFrames += lane.held;
        lanesReady &= !lane.used || lane.eosReceived || lane.held >= prerollFrames;
      }
      // One merged output may already be dequeued and held by MediaCodecVideoRenderer while it
      // asks isReady(). Use the real codec-held frame count for preroll instead of requiring every
      // earliest timestamp to remain in our priority queue.
      prerolled = (heldFrames >= prerollFrames && lanesReady) || (inputEnded
          && count == expected.tailMap(positionUs).values().stream().mapToInt(Integer::intValue).sum());
    }
    return prerolled;
  }

  public boolean hasPendingSamples() {
    // isReady() continues polling this method while ExoPlayer is BUFFERING. Run the same
    // handoff-hole recovery here as in dequeueOutputBufferIndex(), otherwise a missing
    // pre-IRAP tail frame can deadlock forever once Media3 stops dequeuing outputs.
    retireBlockedPreIrapExpected();
    discardRetiredLateOutputs();
    if (!inputEnded) {
      Output next = outputs.peek();
      if (next != null && !expected.isEmpty()
          && next.info.presentationTimeUs == expected.firstKey()) {
        return true;
      }
      // A lane switch/flush can briefly leave no merged output even though decoding is healthy.
      // Keep a short grace period, but do not claim readiness forever if the merge actually
      // deadlocks. That prevents the player clock from running seconds ahead of a frozen frame.
      return lastMergedOutputNs != 0L
          && System.nanoTime() - lastMergedOutputNs < 500_000_000L;
    }
    if (!expected.isEmpty() || !outputs.isEmpty() || queuedBytes > 0 || inputAcquired) {
      return true;
    }
    for (Lane lane : lanes) {
      if (!lane.packets.isEmpty() || lane.held > 0 || lane.flushing || lane.eosQueued) {
        return true;
      }
    }
    return false;
  }

  public int laneForOutputIndex(int index) {
    return index == EOS_INDEX ? -1 : index % lanes.length;
  }

  @Override
  public int dequeueInputBufferIndex() {
    pump();
    // Keep enough compressed lookahead to cross an IDR boundary and feed both decoder lanes.
    // A sub-GOP time/byte cap turns the parallel path back into one-lane decoding because the
    // next lane cannot receive its GOP until the current lane has already drained.
    if (inputAcquired || inputEnded || queuedBytes >= INPUT_BUDGET_BYTES) {
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }
    inputAcquired = true;
    input.clear();
    return 0;
  }

  @Override
  public ByteBuffer getInputBuffer(int index) {
    return input;
  }

  @Override
  public void queueInputBuffer(int index, int offset, int size, long timeUs, int flags) {
    inputAcquired = false;
    if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
      inputEnded = true;
      for (Lane lane : lanes) {
        lane.packets.add(endPacket(true, Long.MAX_VALUE));
      }
    } else {
      if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
        throw new IllegalStateException("Parallel HEVC requires codec recreation for new initialization data");
      }
      int vclType = firstVclNalType(input, offset, size);
      // HEVC IRAP pictures are valid random-access entry points. IDR/BLA can start a fresh
      // decoder directly. CRA can too, provided we discard the following RASL leading pictures,
      // which are the only pictures allowed to depend on references from before the CRA.
      if (isIndependentIrap(vclType)) {
        if (started) {
          markHandoffBoundary(inputLane, timeUs);
          // The dedicated low-latency Qualcomm component can remain live across alternating
          // random-access GOPs. Avoid per-GOP EOS/flush there; the merge layer handles the small
          // pre-IRAP reorder tail explicitly. Non-continuous fallback codecs still drain.
          if (!continuousLaneMode) {
            lanes[inputLane].packets.add(endPacket(false, timeUs));
          }
          inputLane = (inputLane + 1) % lanes.length;
        }
        started = true;
        currentInputGopBoundaryUs = timeUs;
        dropLeadingRasl = vclType == 21;
      } else if (dropLeadingRasl && (vclType == 8 || vclType == 9)) {
        pump();
        return;
      } else if (vclType >= 0 && vclType <= 31) {
        dropLeadingRasl = false;
      }
      if (acceleratedPlayback
          && isNonReferenceVclSample(input, offset, size)
          && shouldDropNonReference()) {
        pump();
        return;
      }
      byte[] data = copyInputSample(offset, size);
      lanes[inputLane].packets.add(
          new Packet(data, size, timeUs, currentInputGopBoundaryUs, flags, false));
      lanes[inputLane].used = true;
      queuedBytes += size;
      expected.merge(timeUs, 1, Integer::sum);
      expectedLane.put(timeUs, inputLane);
    }
    pump();
  }

  private static Packet endPacket(boolean terminal, long drainBoundaryUs) {
    return new Packet(
        null,
        0,
        drainBoundaryUs,
        drainBoundaryUs,
        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        terminal);
  }

  private void pump() {
    for (int laneIndex = 0; laneIndex < lanes.length; laneIndex++) {
      Lane lane = lanes[laneIndex];
      if (lane.flushError != null) {
        throw lane.flushError;
      }
      if (lane.flushing) {
        continue;
      }
      if (!lane.eosReceived) {
        int outputBudget = MAX_PUMP_OUTPUTS_PER_LANE;
        while (outputBudget-- > 0) {
          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
          int index = lane.codec.dequeueOutputBufferIndex(info);
          if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputFormat == null) {
              outputFormat = lane.codec.getOutputFormat();
              formatPending = true;
            }
          } else if (index < 0) {
            break;
          } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && info.size == 0) {
            lane.codec.releaseOutputBuffer(index, false);
            lane.eosReceived = true;
            retireDecoderOmittedExpected(laneIndex);
            break;
          } else {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              lane.eosReceived = true;
              info.flags &= ~MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            }
            lane.held++;
            outputs.add(new Output(index * lanes.length + laneIndex, info));
            if (lane.eosReceived) {
              retireDecoderOmittedExpected(laneIndex);
            }
            if (lane.eosReceived) break;
          }
        }
      }
      if (lane.eosReceived && lane.held == 0 && !lane.terminal) {
        startAsyncFlush(lane);
        continue;
      }
      int inputBudget = MAX_PUMP_INPUTS_PER_LANE;
      while (inputBudget-- > 0 && !lane.eosQueued && !lane.packets.isEmpty()) {
        Packet nextPacket = lane.packets.peek();
        int index = lane.codec.dequeueInputBufferIndex();
        if (index < 0) break;
        Packet packet = lane.packets.remove();
        if (packet.size > 0) {
          ByteBuffer target = lane.codec.getInputBuffer(index);
          target.clear();
          target.put(packet.data, 0, packet.size);
        }
        lane.codec.queueInputBuffer(index, 0, packet.size, packet.timeUs, packet.flags);
        queuedBytes -= packet.size;
        recycle(packet.data);
        if ((packet.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          lane.eosQueued = true;
          lane.terminal = packet.terminal;
          lane.drainBoundaryUs = packet.timeUs;
        }
      }
    }
  }

  @Override
  public int dequeueOutputBufferIndex(MediaCodec.BufferInfo info) {
    pump();
    if (formatPending) {
      formatPending = false;
      return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
    }
    discardRetiredLateOutputs();
    retireBlockedPreIrapExpected();
    Output next = outputs.peek();
    if (next != null && !expected.isEmpty()
        && next.info.presentationTimeUs == expected.firstKey()) {
      outputs.remove();
      long timeUs = next.info.presentationTimeUs;
      if (expected.get(timeUs) == 1) expected.remove(timeUs);
      else expected.put(timeUs, expected.get(timeUs) - 1);
      if (!expected.containsKey(timeUs)) {
        expectedLane.remove(timeUs);
        expectedHandoffBoundaryUs.remove(timeUs);
      }
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      info.set(next.info.offset, next.info.size, timeUs, next.info.flags);
      lastMergedOutputNs = System.nanoTime();
      return next.index;
    }
    boolean allTerminal = true;
    for (Lane lane : lanes) {
      allTerminal &= lane.eosReceived && lane.terminal;
    }
    if (inputEnded && expected.isEmpty() && !eosReturned && allTerminal) {
      eosReturned = true;
      info.set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      return EOS_INDEX;
    }
    return MediaCodec.INFO_TRY_AGAIN_LATER;
  }

  @Override public MediaFormat getOutputFormat() { return outputFormat; }
  @Override public ByteBuffer getOutputBuffer(int index) { return null; }

  @Override
  public void releaseOutputBuffer(int index, boolean render) {
    if (render) releaseOutputBuffer(index, System.nanoTime());
    else if (index != EOS_INDEX) {
      Lane lane = lanes[laneForOutputIndex(index)];
      lane.codec.releaseOutputBuffer(index / lanes.length, false);
      lane.held--;
      pump();
    }
  }

  @Override
  public void releaseOutputBuffer(int index, long timestampNs) {
    if (index == EOS_INDEX) return;
    Lane lane = lanes[laneForOutputIndex(index)];
    lastRenderTimeNs = Math.max(lastRenderTimeNs + 1, timestampNs);
    lane.codec.releaseOutputBuffer(index / lanes.length, lastRenderTimeNs);
    lane.held--;
    pump();
  }

  @Override
  public void flush() {
    for (Lane lane : lanes) {
      waitForLaneFlush(lane);
      lane.codec.flush();
      for (Packet packet : lane.packets) recycle(packet.data);
      lane.packets.clear();
      lane.flushError = null;
      lane.flushing = false;
      lane.flushFuture = null;
      lane.eosQueued = lane.eosReceived = lane.terminal = lane.used = false;
      lane.drainBoundaryUs = Long.MIN_VALUE;
      lane.held = 0;
    }
    outputs.clear();
    expected.clear();
    expectedLane.clear();
    expectedHandoffBoundaryUs.clear();
    retiredExpected.clear();
    queuedBytes = inputLane = 0;
    inputAcquired = inputEnded = eosReturned = started = prerolled = dropLeadingRasl = false;
    currentInputGopBoundaryUs = Long.MIN_VALUE;
    playbackPositionUs = rendererOutputTimeUs = Long.MIN_VALUE;
    lastMergedOutputNs = 0L;
    blockedExpectedUs = Long.MIN_VALUE;
    blockedExpectedSinceNs = 0L;
    nonReferenceDropPhase = 0;
  }

  @Override
  public void release() {
    for (Lane lane : lanes) {
      waitForLaneFlush(lane);
    }
    flushExecutor.shutdownNow();
    try {
      for (Lane lane : lanes) {
        lane.codec.release();
      }
    } finally {
      outputs.clear();
      expected.clear();
      expectedLane.clear();
      expectedHandoffBoundaryUs.clear();
      retiredExpected.clear();
      pool.clear();
      for (Lane lane : lanes) lane.packets.clear();
    }
  }

  @Override
  public void queueSecureInputBuffer(int index, int offset, CryptoInfo info, long timeUs, int flags) {
    throw new UnsupportedOperationException("Parallel HEVC is only used for unencrypted media");
  }

  @Override
  public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
    for (Lane lane : lanes) lane.codec.setOnFrameRenderedListener(
        (ignored, timeUs, nanoTime) -> listener.onFrameRendered(this, timeUs, nanoTime), handler);
  }

  @Override
  public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener) {
    boolean registered = true;
    for (Lane lane : lanes) {
      registered &= lane.codec.registerOnBufferAvailableListener(listener);
    }
    return registered;
  }

  @Override public void setOutputSurface(Surface surface) {
    throw new UnsupportedOperationException("Recreate both decoders when the presentation surface changes");
  }
  @Override public void detachOutputSurface() {
    throw new UnsupportedOperationException("Recreate both decoders when the presentation surface changes");
  }
  @Override
  public void setParameters(Bundle params) {
    Bundle effectiveParams = params;
    if (params.containsKey(MediaFormat.KEY_OPERATING_RATE)) {
      float requestedRate = params.getFloat(MediaFormat.KEY_OPERATING_RATE);
      playbackSpeed = Math.max(0.01f, requestedRate / sourceFrameRate);
      acceleratedPlayback = playbackSpeed > 1.05f;
      if (acceleratedPlayback) {
        effectiveParams = new Bundle(params);
        effectiveParams.putFloat(MediaFormat.KEY_OPERATING_RATE, STARTUP_CODEC_OPERATING_RATE);
      }
    }
    for (Lane lane : lanes) {
      lane.codec.setParameters(effectiveParams);
    }
  }
  @Override public void setVideoScalingMode(int mode) { for (Lane lane : lanes) lane.codec.setVideoScalingMode(mode); }
  @Override public boolean needsReconfiguration() { return false; }
  @Override public PersistableBundle getMetrics() { return lanes[0].codec.getMetrics(); }
  @androidx.annotation.RequiresApi(31)
  @Override public void subscribeToVendorParameters(List<String> names) { for (Lane lane : lanes) lane.codec.subscribeToVendorParameters(names); }
  @androidx.annotation.RequiresApi(31)
  @Override public void unsubscribeFromVendorParameters(List<String> names) { for (Lane lane : lanes) lane.codec.unsubscribeFromVendorParameters(names); }

  private byte[] acquireArray(int size) {
    for (java.util.Iterator<byte[]> iterator = pool.iterator(); iterator.hasNext();) {
      byte[] candidate = iterator.next();
      if (candidate.length >= size) {
        iterator.remove();
        pooledBytes -= candidate.length;
        return candidate;
      }
    }
    return new byte[(size + 65535) & ~65535];
  }

  private byte[] copyInputSample(int offset, int size) {
    byte[] data = acquireArray(size);
    ByteBuffer source = input.duplicate();
    source.position(offset).limit(offset + size);
    source.get(data, 0, size);
    return data;
  }

  private void startAsyncFlush(Lane lane) {
    lane.flushing = true;
    lane.flushFuture = flushExecutor.submit(() -> {
      try {
        lane.codec.flush();
        lane.eosReceived = false;
        lane.eosQueued = false;
        lane.drainBoundaryUs = Long.MIN_VALUE;
      } catch (RuntimeException error) {
        lane.flushError = error;
      } finally {
        lane.flushing = false;
      }
    });
  }

  private static void waitForLaneFlush(Lane lane) {
    Future<?> future = lane.flushFuture;
    if (future != null) {
      try {
        future.get();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for HEVC decoder flush", error);
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        }
        throw new IllegalStateException("HEVC decoder flush failed", cause);
      } finally {
        lane.flushFuture = null;
      }
    }
    if (lane.flushError != null) {
      throw lane.flushError;
    }
  }

  private void markHandoffBoundary(int laneIndex, long boundaryUs) {
    for (java.util.Map.Entry<Long, Integer> entry : expectedLane.entrySet()) {
      long timeUs = entry.getKey();
      if (entry.getValue() == laneIndex && timeUs < boundaryUs) {
        expectedHandoffBoundaryUs.putIfAbsent(timeUs, boundaryUs);
      }
    }
  }

  private boolean hasExpectedBeforeBoundary(int laneIndex, long boundaryUs) {
    for (java.util.Map.Entry<Long, Integer> entry : expectedLane.entrySet()) {
      if (entry.getValue() == laneIndex && entry.getKey() < boundaryUs) {
        return true;
      }
    }
    return false;
  }

  private void relieveBlockedContinuousTail() {
    if (!continuousLaneMode || expected.isEmpty() || outputs.isEmpty()) {
      return;
    }
    long missingUs = expected.firstKey();
    Long boundaryValue = expectedHandoffBoundaryUs.get(missingUs);
    if (boundaryValue == null) {
      return;
    }
    long boundaryUs = boundaryValue;
    // Only pressure-relieve the final few display frames immediately before a closed GOP
    // boundary. Earlier missing frames are ordinary decoder latency and must be preserved.
    if (boundaryUs - missingUs <= 0L || boundaryUs - missingUs > PRE_IRAP_MAX_TAIL_US) {
      return;
    }
    Output next = outputs.peek();
    if (next.info.presentationTimeUs < boundaryUs) {
      return;
    }
    Integer laneIndexValue = expectedLane.get(missingUs);
    if (laneIndexValue == null) {
      return;
    }
    int laneIndex = laneIndexValue;
    Lane lane = lanes[laneIndex];
    if (lane.held < PREROLL_FRAMES) {
      return;
    }

    Output candidate = null;
    for (Output output : outputs) {
      if (laneForOutputIndex(output.index) != laneIndex
          || output.info.presentationTimeUs < boundaryUs) {
        continue;
      }
      if (candidate == null
          || output.info.presentationTimeUs > candidate.info.presentationTimeUs) {
        candidate = output;
      }
    }
    if (candidate == null) {
      return;
    }

    outputs.remove(candidate);
    long droppedUs = candidate.info.presentationTimeUs;
    lane.codec.releaseOutputBuffer(candidate.index / lanes.length, false);
    lane.held--;
    removeExpectedTimestamp(droppedUs);
    AppLog.w("ParallelHevc", "Pressure-relief drop: lane=" + laneIndex
        + ", blockedPtsUs=" + missingUs
        + ", boundaryUs=" + boundaryUs
        + ", droppedFuturePtsUs=" + droppedUs);
    pump();
  }

  private void removeExpectedTimestamp(long timeUs) {
    Integer count = expected.get(timeUs);
    if (count == null) {
      expectedLane.remove(timeUs);
      expectedHandoffBoundaryUs.remove(timeUs);
      return;
    }
    if (count <= 1) {
      expected.remove(timeUs);
      expectedLane.remove(timeUs);
      expectedHandoffBoundaryUs.remove(timeUs);
    } else {
      expected.put(timeUs, count - 1);
    }
  }

  private void retireDecoderOmittedExpected(int laneIndex) {
    long boundaryUs = lanes[laneIndex].drainBoundaryUs;
    if (boundaryUs == Long.MIN_VALUE) {
      return;
    }
    java.util.HashSet<Long> emitted = new java.util.HashSet<>();
    for (Output output : outputs) {
      if (laneForOutputIndex(output.index) == laneIndex) {
        emitted.add(output.info.presentationTimeUs);
      }
    }
    java.util.ArrayList<Long> omitted = new java.util.ArrayList<>();
    for (java.util.Map.Entry<Long, Integer> entry : expectedLane.entrySet()) {
      if (entry.getValue() == laneIndex
          && entry.getKey() < boundaryUs
          && !emitted.contains(entry.getKey())) {
        omitted.add(entry.getKey());
      }
    }
    for (Long timeUs : omitted) {
      Integer count = expected.get(timeUs);
      if (count == null) {
        expectedLane.remove(timeUs);
        continue;
      }
      if (count <= 1) {
        expected.remove(timeUs);
        expectedLane.remove(timeUs);
        expectedHandoffBoundaryUs.remove(timeUs);
      } else {
        expected.put(timeUs, count - 1);
      }
      retiredExpected.add(timeUs);
      AppLog.w("ParallelHevc", "Retiring codec-omitted frame at EOS: lane="
          + laneIndex + ", ptsUs=" + timeUs);
    }
  }

  private void retireBlockedPreIrapExpected() {
    if (expected.isEmpty() || outputs.isEmpty()) {
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    long missingUs = expected.firstKey();
    Output next = outputs.peek();
    if (next.info.presentationTimeUs <= missingUs) {
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    Integer laneIndex = expectedLane.get(missingUs);
    if (laneIndex == null) {
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    Long boundaryValue = expectedHandoffBoundaryUs.get(missingUs);
    if (boundaryValue == null) {
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    long boundaryUs = boundaryValue;
    long boundaryGapUs = boundaryUs - missingUs;
    if (boundaryGapUs <= 0L
        || boundaryGapUs > PRE_IRAP_MAX_TAIL_US
        || next.info.presentationTimeUs < boundaryUs) {
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    // Do not retire an ordinary decode-late frame. Only retire once the post-IRAP timeline has
    // demonstrably advanced by a full reorder window. After a lane handoff the new GOP belongs
    // to the other decoder, so requiring later output from the old lane can deadlock until that
    // lane is reused a whole GOP later.
    int laterOutputsAfterBoundary = 0;
    for (Output output : outputs) {
      if (output.info.presentationTimeUs >= boundaryUs) {
        laterOutputsAfterBoundary++;
      }
    }
    if (laterOutputsAfterBoundary < PRE_IRAP_RETIRE_POST_BOUNDARY_OUTPUTS) {
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    long nowNs = System.nanoTime();
    if (blockedExpectedUs != missingUs) {
      blockedExpectedUs = missingUs;
      blockedExpectedSinceNs = nowNs;
      return;
    }
    if (nowNs - blockedExpectedSinceNs < PRE_IRAP_HOLE_GRACE_NS) {
      return;
    }
    Integer count = expected.get(missingUs);
    if (count == null) {
      expectedLane.remove(missingUs);
      blockedExpectedUs = Long.MIN_VALUE;
      blockedExpectedSinceNs = 0L;
      return;
    }
    if (count <= 1) {
      expected.remove(missingUs);
      expectedLane.remove(missingUs);
      expectedHandoffBoundaryUs.remove(missingUs);
    } else {
      expected.put(missingUs, count - 1);
    }
    retiredExpected.add(missingUs);
    blockedExpectedUs = Long.MIN_VALUE;
    blockedExpectedSinceNs = 0L;
    AppLog.w("ParallelHevc", "Retiring blocked pre-IRAP frame after grace: lane="
        + laneIndex + ", ptsUs=" + missingUs + ", boundaryUs=" + boundaryUs);
  }

  private void discardRetiredLateOutputs() {
    while (!outputs.isEmpty() && !expected.isEmpty()) {
      Output next = outputs.peek();
      long nextUs = next.info.presentationTimeUs;
      if (nextUs >= expected.firstKey() || !retiredExpected.remove(nextUs)) {
        return;
      }
      outputs.remove();
      int laneIndex = laneForOutputIndex(next.index);
      Lane lane = lanes[laneIndex];
      lane.codec.releaseOutputBuffer(next.index / lanes.length, false);
      lane.held--;
      AppLog.w("ParallelHevc", "Discarding late retired output: lane="
          + laneIndex + ", ptsUs=" + nextUs);
      pump();
    }
  }

  private void recycle(byte[] data) {
    if (data != null && pooledBytes + data.length <= POOL_BUDGET_BYTES) {
      pool.add(data);
      pooledBytes += data.length;
    }
  }

  public void updatePlaybackPositionUs(long positionUs) {
    playbackPositionUs = positionUs;
  }

  public void updateRendererOutputTimeUs(long outputTimeUs) {
    rendererOutputTimeUs = outputTimeUs;
  }

  private long currentVideoLagUs() {
    return playbackPositionUs == Long.MIN_VALUE || rendererOutputTimeUs == Long.MIN_VALUE
        ? 0L
        : Math.max(0L, playbackPositionUs - rendererOutputTimeUs);
  }

  private boolean shouldDropNonReference() {
    long lagUs = currentVideoLagUs();
    if (lagUs >= DROP_NON_REFERENCE_ALL_LAG_US) {
      nonReferenceDropPhase++;
      return true;
    }
    if (lagUs >= DROP_NON_REFERENCE_HALF_LAG_US) {
      if (playbackSpeed >= 1.45f) {
        return nonReferenceDropPhase++ % 4 != 0;
      }
      return (nonReferenceDropPhase++ & 1) == 0;
    }
    // At 1.50x an 8K60 source asks the decoder pair for ~90 source frames/s. Evenly shedding
    // all explicitly non-reference pictures on truly extreme streams removes the maximum decode
    // work that HEVC marks as reference-safe to omit. Reference pictures are never discarded.
    // This trades source-frame cadence for a steady synchronized presentation instead of letting
    // decoder lag grow until nearly every already-decoded frame has to be dropped at presentation.
    if (extremeBitrate && playbackSpeed >= 1.45f) {
      nonReferenceDropPhase++;
      return true;
    }
    // Use a gentler cadence for intermediate accelerated rates.
    if (extremeBitrate && playbackSpeed >= 1.20f) {
      return nonReferenceDropPhase++ % 3 == 0;
    }
    nonReferenceDropPhase++;
    return false;
  }

  private static int firstVclNalType(ByteBuffer data, int offset, int size) {
    // Media3's MP4 extractor delivers Annex B NAL units to MediaCodec.
    for (int i = offset; i + 4 < offset + size; i++) {
      if (data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1) {
        int type = (data.get(i + 3) & 0x7e) >> 1;
        if (type <= 31) return type;
      }
    }
    return -1;
  }

  private static boolean isIndependentIrap(int type) {
    return type >= 16 && type <= 21;
  }

  private static boolean isNonReferenceVclSample(ByteBuffer data, int offset, int size) {
    boolean sawVcl = false;
    int end = offset + size;
    for (int i = offset; i + 4 < end; i++) {
      if (data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1) {
        int type = (data.get(i + 3) & 0x7e) >> 1;
        if (type <= 31) {
          sawVcl = true;
          // HEVC *_N pictures are explicitly not used as references.
          if (type != 0 && type != 2 && type != 4 && type != 6 && type != 8) {
            return false;
          }
        }
      }
    }
    return sawVcl;
  }
}


