package com.enricoros.nreal.player;

import android.net.Uri;
import android.os.SharedMemory;
import android.system.ErrnoException;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.enricoros.nreal.AppLog;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keeps HTTP read-ahead in Android shared memory so large buffers do not consume the ART heap.
 *
 * <p>Large known-length requests are fetched with several ordered HTTP range workers. Each worker
 * writes directly into its assigned part of the shared-memory ring, while the consumer only sees
 * bytes after all preceding ranges have completed.
 */
@UnstableApi
public final class SharedMemoryPrefetchDataSource implements DataSource {
  private static final String TAG = "SharedMemoryPrefetch";
  private static final int COPY_BUFFER_BYTES = 256 * 1024;
  private static final int PREFETCH_WORKERS = 3;
  private static final int PREFETCH_CHUNK_BYTES = 8 * 1024 * 1024;
  private static final int MIN_PARALLEL_LENGTH_BYTES = 32 * 1024 * 1024;

  public static final class Factory implements DataSource.Factory {
    private final DataSource.Factory upstreamFactory;
    private final int capacityBytes;

    public Factory(DataSource.Factory upstreamFactory, int capacityBytes) {
      this.upstreamFactory = upstreamFactory;
      this.capacityBytes = capacityBytes;
    }

    @Override
    public DataSource createDataSource() {
      return new SharedMemoryPrefetchDataSource(upstreamFactory, capacityBytes);
    }
  }

  private static final class Chunk {
    final long start;
    final int length;

    Chunk(long start, int length) {
      this.start = start;
      this.length = length;
    }
  }

  private final DataSource.Factory upstreamFactory;
  private final int capacityBytes;
  private final Object lock = new Object();
  private final ArrayList<TransferListener> transferListeners = new ArrayList<>();
  private final Thread[] workerThreads = new Thread[PREFETCH_WORKERS];
  private final DataSource[] workerSources = new DataSource[PREFETCH_WORKERS];
  private final TreeMap<Long, Integer> completedChunks = new TreeMap<>();

  private SharedMemory sharedMemory;
  private ByteBuffer mappedBuffer;
  private DataSource passthroughUpstream;
  private DataSpec sourceDataSpec;
  private Uri openedUri;
  private Map<String, List<String>> responseHeaders = Collections.emptyMap();
  private long totalLength;
  private long nextAssignPosition;
  private long writePosition;
  private long readPosition;
  private int finishedWorkers;
  private boolean eof;
  private boolean closed;
  private boolean passthrough;
  private IOException producerError;

  private SharedMemoryPrefetchDataSource(DataSource.Factory upstreamFactory, int capacityBytes) {
    this.upstreamFactory = upstreamFactory;
    this.capacityBytes = capacityBytes;
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    synchronized (lock) {
      transferListeners.add(transferListener);
      if (passthroughUpstream != null) {
        passthroughUpstream.addTransferListener(transferListener);
      }
    }
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    resetForOpen();

    DataSource probe = createUpstream();
    long length;
    try {
      length = probe.open(dataSpec);
      openedUri = probe.getUri();
      responseHeaders = probe.getResponseHeaders();
    } catch (IOException | RuntimeException error) {
      closeQuietly(probe);
      throw error;
    }

    if (length == C.LENGTH_UNSET || length < MIN_PARALLEL_LENGTH_BYTES) {
      passthroughUpstream = probe;
      passthrough = true;
      return length;
    }

    try {
      sharedMemory = SharedMemory.create("air-vr180-stream", capacityBytes);
      mappedBuffer = sharedMemory.mapReadWrite();
    } catch (ErrnoException | RuntimeException error) {
      releaseSharedMemory();
      passthroughUpstream = probe;
      passthrough = true;
      AppLog.w(TAG, "Shared-memory prefetch unavailable; using direct HTTP reads", error);
      return length;
    }

    closeQuietly(probe);
    sourceDataSpec = dataSpec;
    totalLength = length;
    if (length == 0L) {
      eof = true;
      return 0L;
    }

    for (int i = 0; i < PREFETCH_WORKERS; i++) {
      final int workerIndex = i;
      Thread worker = new Thread(
          () -> runWorker(workerIndex), "AirVrSharedPrefetch-" + workerIndex);
      worker.setDaemon(true);
      workerThreads[i] = worker;
      worker.start();
    }
    AppLog.i(TAG, "Started " + (capacityBytes / (1024 * 1024))
        + " MB RAM prefetch buffer with " + PREFETCH_WORKERS + " HTTP workers");
    return length;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (passthrough) {
      return passthroughUpstream.read(buffer, offset, length);
    }

    synchronized (lock) {
      while (writePosition == readPosition && !eof && producerError == null && !closed) {
        try {
          lock.wait();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("Interrupted while waiting for prefetched media");
        }
      }

      if (producerError != null) {
        throw producerError;
      }
      if (writePosition == readPosition) {
        return -1;
      }

      int ringOffset = (int) (readPosition % capacityBytes);
      int available = (int) Math.min((long) length, writePosition - readPosition);
      int count = Math.min(available, capacityBytes - ringOffset);
      ByteBuffer reader = mappedBuffer.duplicate();
      reader.position(ringOffset);
      reader.get(buffer, offset, count);
      readPosition += count;
      lock.notifyAll();
      return count;
    }
  }

  @Override
  public Uri getUri() {
    return passthrough && passthroughUpstream != null ? passthroughUpstream.getUri() : openedUri;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return passthrough && passthroughUpstream != null
        ? passthroughUpstream.getResponseHeaders()
        : responseHeaders;
  }

  @Override
  public void close() throws IOException {
    IOException closeError = null;
    synchronized (lock) {
      closed = true;
      lock.notifyAll();
    }

    if (passthroughUpstream != null) {
      try {
        passthroughUpstream.close();
      } catch (IOException error) {
        closeError = error;
      }
      passthroughUpstream = null;
    }

    for (Thread worker : workerThreads) {
      if (worker != null) {
        worker.interrupt();
      }
    }
    joinWorkers(500L);

    for (int i = 0; i < PREFETCH_WORKERS; i++) {
      Thread worker = workerThreads[i];
      if (worker != null && worker.isAlive()) {
        closeQuietly(workerSources[i]);
      }
    }
    joinWorkers(1500L);

    for (int i = 0; i < PREFETCH_WORKERS; i++) {
      closeQuietly(workerSources[i]);
      workerSources[i] = null;
      workerThreads[i] = null;
    }

    releaseSharedMemory();
    passthrough = false;
    if (closeError != null) {
      throw closeError;
    }
  }

  private void runWorker(int workerIndex) {
    byte[] copyBuffer = new byte[COPY_BUFFER_BYTES];
    try {
      while (true) {
        Chunk chunk = takeChunk();
        if (chunk == null) {
          return;
        }

        DataSource source = createUpstream();
        synchronized (lock) {
          if (closed) {
            closeQuietly(source);
            return;
          }
          workerSources[workerIndex] = source;
        }

        try {
          long openedLength = source.open(sourceDataSpec.subrange(chunk.start, chunk.length));
          if (openedLength != C.LENGTH_UNSET && openedLength < chunk.length) {
            throw new IOException(
                "HTTP range shorter than requested: " + openedLength + " < " + chunk.length);
          }

          int chunkOffset = 0;
          while (chunkOffset < chunk.length) {
            synchronized (lock) {
              if (closed) {
                return;
              }
            }
            int request = Math.min(copyBuffer.length, chunk.length - chunkOffset);
            int count = source.read(copyBuffer, 0, request);
            if (count < 0) {
              throw new IOException(
                  "HTTP range ended early at " + chunkOffset + " / " + chunk.length);
            }
            writeToRing(chunk.start + chunkOffset, copyBuffer, count);
            chunkOffset += count;
          }
        } finally {
          closeQuietly(source);
          synchronized (lock) {
            if (workerSources[workerIndex] == source) {
              workerSources[workerIndex] = null;
            }
          }
        }

        synchronized (lock) {
          completedChunks.put(chunk.start, chunk.length);
          while (true) {
            Integer completedLength = completedChunks.remove(writePosition);
            if (completedLength == null) {
              break;
            }
            writePosition += completedLength;
          }
          if (writePosition >= totalLength) {
            eof = true;
          }
          lock.notifyAll();
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (IOException error) {
      synchronized (lock) {
        if (!closed && producerError == null) {
          producerError = error;
          lock.notifyAll();
        }
      }
    } catch (RuntimeException error) {
      synchronized (lock) {
        if (!closed && producerError == null) {
          producerError = new IOException("Shared-memory prefetch failed", error);
          lock.notifyAll();
        }
      }
    } finally {
      synchronized (lock) {
        finishedWorkers++;
        if (!closed && finishedWorkers == PREFETCH_WORKERS
            && writePosition < totalLength && producerError == null) {
          producerError = new IOException("Parallel prefetch ended before the requested range");
        }
        lock.notifyAll();
      }
    }
  }

  private Chunk takeChunk() throws InterruptedException {
    synchronized (lock) {
      while (!closed && producerError == null) {
        if (nextAssignPosition >= totalLength) {
          return null;
        }
        int length = (int) Math.min(
            (long) PREFETCH_CHUNK_BYTES, totalLength - nextAssignPosition);
        if (nextAssignPosition + length - readPosition <= capacityBytes) {
          long start = nextAssignPosition;
          nextAssignPosition += length;
          return new Chunk(start, length);
        }
        lock.wait();
      }
      return null;
    }
  }

  private void writeToRing(long logicalPosition, byte[] data, int count) {
    int dataOffset = 0;
    long position = logicalPosition;
    while (dataOffset < count) {
      int ringOffset = (int) (position % capacityBytes);
      int part = Math.min(count - dataOffset, capacityBytes - ringOffset);
      ByteBuffer writer = mappedBuffer.duplicate();
      writer.position(ringOffset);
      writer.put(data, dataOffset, part);
      dataOffset += part;
      position += part;
    }
  }

  private DataSource createUpstream() {
    DataSource source = upstreamFactory.createDataSource();
    synchronized (lock) {
      for (TransferListener listener : transferListeners) {
        source.addTransferListener(listener);
      }
    }
    return source;
  }

  private void resetForOpen() {
    synchronized (lock) {
      sourceDataSpec = null;
      openedUri = null;
      responseHeaders = Collections.emptyMap();
      totalLength = 0L;
      nextAssignPosition = 0L;
      writePosition = 0L;
      readPosition = 0L;
      finishedWorkers = 0;
      completedChunks.clear();
      eof = false;
      closed = false;
      passthrough = false;
      producerError = null;
    }
  }

  private void joinWorkers(long timeoutMs) {
    for (Thread worker : workerThreads) {
      if (worker == null || !worker.isAlive()) {
        continue;
      }
      try {
        worker.join(timeoutMs);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void closeQuietly(DataSource source) {
    if (source == null) {
      return;
    }
    try {
      source.close();
    } catch (IOException | RuntimeException ignored) {
    }
  }

  private void releaseSharedMemory() {
    ByteBuffer buffer = mappedBuffer;
    mappedBuffer = null;
    if (buffer != null) {
      try {
        SharedMemory.unmap(buffer);
      } catch (RuntimeException ignored) {
      }
    }
    SharedMemory memory = sharedMemory;
    sharedMemory = null;
    if (memory != null) {
      memory.close();
    }
  }
}
