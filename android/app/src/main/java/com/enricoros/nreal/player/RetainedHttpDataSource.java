package com.enricoros.nreal.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps two forward HTTP responses open across extractor seeks so split audio/video MP4 layouts
 * do not reconnect to the server every time the extractor switches tracks.
 */
@UnstableApi
public final class RetainedHttpDataSource implements DataSource {
  private static final int SESSION_COUNT = 2;
  private static final long MAX_FORWARD_REUSE_BYTES = 1024 * 1024L;
  private static final long IDLE_CLOSE_MS = 5000L;
  private static final int SKIP_BUFFER_BYTES = 64 * 1024;

  private static final ScheduledExecutorService IDLE_CLOSER =
      Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AirVrHttpIdleClose");
        thread.setDaemon(true);
        return thread;
      });

  public static final class Factory implements DataSource.Factory {
    private final DataSource.Factory upstreamFactory;

    public Factory(DataSource.Factory upstreamFactory) {
      this.upstreamFactory = upstreamFactory;
    }

    @Override
    public DataSource createDataSource() {
      return new RetainedHttpDataSource(upstreamFactory);
    }
  }

  private static final class Session {
    DataSource source;
    Uri uri;
    long position;
    long remaining = C.LENGTH_UNSET;
    long lastUsed;
  }

  private final DataSource.Factory upstreamFactory;
  private final Object lock = new Object();
  private final ArrayList<TransferListener> transferListeners = new ArrayList<>();
  private final Session[] sessions = new Session[SESSION_COUNT];
  private final byte[] skipBuffer = new byte[SKIP_BUFFER_BYTES];

  private Session activeSession;
  private long activeRemaining = C.LENGTH_UNSET;
  private long generation;

  private RetainedHttpDataSource(DataSource.Factory upstreamFactory) {
    this.upstreamFactory = upstreamFactory;
    for (int i = 0; i < sessions.length; i++) {
      sessions[i] = new Session();
    }
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    synchronized (lock) {
      transferListeners.add(transferListener);
      for (Session session : sessions) {
        if (session.source != null) {
          session.source.addTransferListener(transferListener);
        }
      }
    }
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    synchronized (lock) {
      generation++;
      if (activeSession != null) {
        throw new IOException("HTTP data source reopened before close");
      }

      Session session = findReusableSession(dataSpec);
      if (session != null) {
        try {
          skipForward(session, dataSpec.position - session.position);
          activeSession = session;
          session.lastUsed = System.nanoTime();
          activeRemaining = requestedLength(dataSpec.length, session.remaining);
          return activeRemaining;
        } catch (IOException error) {
          closeSession(session);
        }
      }

      session = selectReplacementSession();
      closeSession(session);
      DataSource source = createUpstream();
      long length;
      try {
        length = source.open(dataSpec);
      } catch (IOException | RuntimeException error) {
        closeQuietly(source);
        throw error;
      }

      session.source = source;
      session.uri = dataSpec.uri;
      session.position = dataSpec.position;
      session.remaining = length;
      session.lastUsed = System.nanoTime();
      activeSession = session;
      activeRemaining = requestedLength(dataSpec.length, length);
      return activeRemaining;
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }

    synchronized (lock) {
      Session session = activeSession;
      if (session == null || session.source == null) {
        throw new IOException("HTTP data source is not open");
      }
      if (activeRemaining == 0L) {
        return C.RESULT_END_OF_INPUT;
      }

      int requestLength = length;
      if (activeRemaining != C.LENGTH_UNSET) {
        requestLength = (int) Math.min((long) requestLength, activeRemaining);
      }

      final int count;
      try {
        count = session.source.read(buffer, offset, requestLength);
      } catch (IOException error) {
        closeSession(session);
        throw error;
      }
      if (count == C.RESULT_END_OF_INPUT) {
        session.remaining = 0L;
        activeRemaining = 0L;
        return count;
      }

      session.position += count;
      session.lastUsed = System.nanoTime();
      if (session.remaining != C.LENGTH_UNSET) {
        session.remaining -= count;
      }
      if (activeRemaining != C.LENGTH_UNSET) {
        activeRemaining -= count;
      }
      return count;
    }
  }

  @Override
  public Uri getUri() {
    synchronized (lock) {
      return activeSession == null || activeSession.source == null
          ? null
          : activeSession.source.getUri();
    }
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    synchronized (lock) {
      return activeSession == null || activeSession.source == null
          ? Collections.emptyMap()
          : activeSession.source.getResponseHeaders();
    }
  }

  @Override
  public void close() {
    final long closeGeneration;
    synchronized (lock) {
      activeSession = null;
      activeRemaining = C.LENGTH_UNSET;
      closeGeneration = ++generation;
    }
    IDLE_CLOSER.schedule(
        () -> closeIfStillIdle(closeGeneration), IDLE_CLOSE_MS, TimeUnit.MILLISECONDS);
  }

  private Session findReusableSession(DataSpec dataSpec) {
    if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
      return null;
    }
    Session best = null;
    long bestGap = Long.MAX_VALUE;
    for (Session session : sessions) {
      if (session.source == null || !dataSpec.uri.equals(session.uri)) {
        continue;
      }
      long gap = dataSpec.position - session.position;
      if (gap < 0L || gap > MAX_FORWARD_REUSE_BYTES) {
        continue;
      }
      if (session.remaining != C.LENGTH_UNSET && gap > session.remaining) {
        continue;
      }
      if (gap < bestGap) {
        best = session;
        bestGap = gap;
      }
    }
    return best;
  }

  private Session selectReplacementSession() {
    Session oldest = sessions[0];
    for (Session session : sessions) {
      if (session.source == null) {
        return session;
      }
      if (session.lastUsed < oldest.lastUsed) {
        oldest = session;
      }
    }
    return oldest;
  }

  private void skipForward(Session session, long bytes) throws IOException {
    long remainingToSkip = bytes;
    while (remainingToSkip > 0L) {
      int request = (int) Math.min((long) skipBuffer.length, remainingToSkip);
      int count = session.source.read(skipBuffer, 0, request);
      if (count == C.RESULT_END_OF_INPUT) {
        throw new IOException("Retained HTTP response ended before the requested position");
      }
      session.position += count;
      if (session.remaining != C.LENGTH_UNSET) {
        session.remaining -= count;
      }
      remainingToSkip -= count;
    }
  }

  private DataSource createUpstream() {
    DataSource source = upstreamFactory.createDataSource();
    for (TransferListener listener : transferListeners) {
      source.addTransferListener(listener);
    }
    return source;
  }

  private void closeIfStillIdle(long closeGeneration) {
    synchronized (lock) {
      if (generation != closeGeneration || activeSession != null) {
        return;
      }
      for (Session session : sessions) {
        closeSession(session);
      }
    }
  }

  private void closeSession(Session session) {
    if (session.source != null) {
      closeQuietly(session.source);
    }
    session.source = null;
    session.uri = null;
    session.position = 0L;
    session.remaining = C.LENGTH_UNSET;
    session.lastUsed = 0L;
  }

  private static long requestedLength(long requestedLength, long availableLength) {
    if (requestedLength == C.LENGTH_UNSET) {
      return availableLength;
    }
    return availableLength == C.LENGTH_UNSET
        ? requestedLength
        : Math.min(requestedLength, availableLength);
  }

  private static void closeQuietly(DataSource source) {
    try {
      source.close();
    } catch (IOException | RuntimeException ignored) {
    }
  }
}
