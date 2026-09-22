package com.enricoros.nreal.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;

import androidx.media3.decoder.CryptoInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;

/** Alternates closed HEVC GOPs between two decoders, without changing compressed samples. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ParallelHevcAdapter implements MediaCodecAdapter {
  private static final int INPUT_BUDGET_BYTES = 64 * 1024 * 1024;
  private static final int POOL_BUDGET_BYTES = 16 * 1024 * 1024;
  private static final int PREROLL_FRAMES = 12;
  private static final int EOS_INDEX = Integer.MAX_VALUE;

  private static final class Packet {
    final byte[] data;
    final int size;
    final long timeUs;
    final int flags;
    final boolean terminal;

    Packet(byte[] data, int size, long timeUs, int flags, boolean terminal) {
      this.data = data;
      this.size = size;
      this.timeUs = timeUs;
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
    final MediaCodecAdapter codec;
    final ArrayDeque<Packet> packets = new ArrayDeque<>();
    boolean eosQueued;
    boolean eosReceived;
    boolean terminal;
    boolean used;
    int held;

    Lane(MediaCodecAdapter codec) {
      this.codec = codec;
    }
  }

  private final Lane[] lanes = new Lane[2];
  private final ByteBuffer input;
  private final ArrayDeque<byte[]> pool = new ArrayDeque<>();
  private final PriorityQueue<Output> outputs = new PriorityQueue<>(
      Comparator.comparingLong(output -> output.info.presentationTimeUs));
  private final TreeMap<Long, Integer> expected = new TreeMap<>();
  private MediaFormat outputFormat;
  private boolean formatPending;
  private boolean inputAcquired;
  private boolean inputEnded;
  private boolean eosReturned;
  private boolean started;
  private boolean prerolled;
  private int inputLane;
  private int queuedBytes;
  private int pooledBytes;
  private long lastRenderTimeNs;

  public ParallelHevcAdapter(Configuration configuration, Surface secondSurface, MediaCodecAdapter.Factory factory) throws IOException {
    int inputSize = configuration.mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024);
    input = ByteBuffer.allocateDirect(inputSize);
    try {
      lanes[0] = new Lane(factory.createAdapter(configuration));
      lanes[1] = new Lane(factory.createAdapter(Configuration.createForVideoDecoding(
          configuration.codecInfo, configuration.mediaFormat, configuration.format, secondSurface, null)));
    } catch (IOException | RuntimeException error) {
      if (lanes[0] != null) lanes[0].codec.release();
      throw error;
    }
  }

  public boolean hasPrerolled(long positionUs) {
    // Media3 can hold both a virtual input buffer and an early output while buffering.
    // Keep the real codecs moving even when neither dequeue method is called again yet.
    pump();
    if (!prerolled) {
      TreeMap<Long, Integer> ready = new TreeMap<>();
      for (Output output : outputs) ready.merge(output.info.presentationTimeUs, 1, Integer::sum);
      int count = 0;
      for (java.util.Map.Entry<Long, Integer> sample : expected.tailMap(positionUs).entrySet()) {
        if (ready.getOrDefault(sample.getKey(), 0) < sample.getValue()) break;
        count += sample.getValue();
        if (count >= PREROLL_FRAMES) break;
      }
      // Count consecutive frames at the seek target, not frames from the other GOP or
      // decode-only pictures before the target. Those cannot keep the playback clock fed.
      boolean lanesReady = true;
      for (Lane lane : lanes) {
        lanesReady &= !lane.used || lane.eosReceived || lane.held >= PREROLL_FRAMES;
      }
      prerolled = (count >= PREROLL_FRAMES && lanesReady) || (inputEnded
          && count == expected.tailMap(positionUs).values().stream().mapToInt(Integer::intValue).sum());
    }
    return prerolled;
  }

  public boolean hasPendingSamples() {
    return !expected.isEmpty();
  }

  @Override
  public int dequeueInputBufferIndex() {
    pump();
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
      for (Lane lane : lanes) lane.packets.add(endPacket(true));
    } else {
      if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
        throw new IllegalStateException("Parallel HEVC requires codec recreation for new initialization data");
      }
      // Only IDR pictures reset all references. CRA/open GOP boundaries must stay on the same lane.
      if (isIdr(input, offset, size)) {
        if (started) {
          lanes[inputLane].packets.add(endPacket(false));
          inputLane ^= 1;
        }
        started = true;
      }
      byte[] data = acquireArray(size);
      ByteBuffer source = input.duplicate();
      source.position(offset).limit(offset + size);
      source.get(data, 0, size);
      lanes[inputLane].packets.add(new Packet(data, size, timeUs, flags, false));
      lanes[inputLane].used = true;
      queuedBytes += size;
      expected.merge(timeUs, 1, Integer::sum);
    }
    pump();
  }

  private static Packet endPacket(boolean terminal) {
    return new Packet(null, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM, terminal);
  }

  private void pump() {
    for (int laneIndex = 0; laneIndex < lanes.length; laneIndex++) {
      Lane lane = lanes[laneIndex];
      if (!lane.eosReceived) {
        while (true) {
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
            break;
          } else {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              lane.eosReceived = true;
              info.flags &= ~MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            }
            lane.held++;
            outputs.add(new Output(index * 2 + laneIndex, info));
            if (lane.eosReceived) break;
          }
        }
      }
      // Drain each GOP completely before flushing: a flush invalidates held output buffers.
      if (lane.eosReceived && lane.held == 0 && !lane.terminal) {
        lane.codec.flush();
        lane.eosReceived = false;
        lane.eosQueued = false;
      }
      while (!lane.eosQueued && !lane.packets.isEmpty()) {
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
    Output next = outputs.peek();
    if (next != null && !expected.isEmpty()
        && next.info.presentationTimeUs == expected.firstKey()) {
      outputs.remove();
      long timeUs = next.info.presentationTimeUs;
      if (expected.get(timeUs) == 1) expected.remove(timeUs);
      else expected.put(timeUs, expected.get(timeUs) - 1);
      info.set(next.info.offset, next.info.size, timeUs, next.info.flags);
      return next.index;
    }
    if (inputEnded && expected.isEmpty() && !eosReturned
        && lanes[0].eosReceived && lanes[0].terminal && lanes[1].eosReceived && lanes[1].terminal) {
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
      Lane lane = lanes[index % 2];
      lane.codec.releaseOutputBuffer(index / 2, false);
      lane.held--;
      pump();
    }
  }

  @Override
  public void releaseOutputBuffer(int index, long timestampNs) {
    if (index == EOS_INDEX) return;
    Lane lane = lanes[index % 2];
    lastRenderTimeNs = Math.max(lastRenderTimeNs + 1, timestampNs);
    lane.codec.releaseOutputBuffer(index / 2, lastRenderTimeNs);
    lane.held--;
    pump();
  }

  @Override
  public void flush() {
    for (Lane lane : lanes) {
      lane.codec.flush();
      for (Packet packet : lane.packets) recycle(packet.data);
      lane.packets.clear();
      lane.eosQueued = lane.eosReceived = lane.terminal = lane.used = false;
      lane.held = 0;
    }
    outputs.clear();
    expected.clear();
    queuedBytes = inputLane = 0;
    inputAcquired = inputEnded = eosReturned = started = prerolled = false;
  }

  @Override
  public void release() {
    try { lanes[0].codec.release(); }
    finally {
      lanes[1].codec.release();
      outputs.clear();
      expected.clear();
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
    boolean first = lanes[0].codec.registerOnBufferAvailableListener(listener);
    boolean second = lanes[1].codec.registerOnBufferAvailableListener(listener);
    return first && second;
  }

  @Override public void setOutputSurface(Surface surface) {
    throw new UnsupportedOperationException("Recreate both decoders when the presentation surface changes");
  }
  @Override public void detachOutputSurface() {
    throw new UnsupportedOperationException("Recreate both decoders when the presentation surface changes");
  }
  @Override public void setParameters(Bundle params) { for (Lane lane : lanes) lane.codec.setParameters(params); }
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

  private void recycle(byte[] data) {
    if (data != null && pooledBytes + data.length <= POOL_BUDGET_BYTES) {
      pool.add(data);
      pooledBytes += data.length;
    }
  }

  private static boolean isIdr(ByteBuffer data, int offset, int size) {
    // Media3's MP4 extractor delivers Annex B NAL units to MediaCodec.
    for (int i = offset; i + 4 < offset + size; i++) {
      if (data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1) {
        int type = (data.get(i + 3) & 0x7e) >> 1;
        if (type <= 31) return type == 19 || type == 20;
      }
    }
    return false;
  }
}


