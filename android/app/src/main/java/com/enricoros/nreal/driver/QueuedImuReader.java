package com.enricoros.nreal.driver;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import com.enricoros.nreal.AppLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/** Keeps USB reads pending even while the Java reader is stalled by GC or scheduling. */
final class QueuedImuReader implements AutoCloseable {
  private static final String TAG = "NrealDeviceThread";
  // One 64-byte report per millisecond: 512 requests cover about half a second.
  // Requests complete individually; the queue does not delay delivery until it fills.
  private static final int REQUEST_COUNT = 512;
  private static final int READ_TIMEOUT_MS = 200;

  private final UsbDeviceConnection connection;
  private final Slot[] slots = new Slot[REQUEST_COUNT];
  private int pending;

  private static final class Slot {
    final UsbRequest request = new UsbRequest();
    final ByteBuffer buffer;
    boolean queued;

    Slot(int packetSize) {
      buffer = ByteBuffer.allocateDirect(packetSize);
    }
  }

  QueuedImuReader(UsbDeviceConnection connection) {
    this.connection = connection;
  }

  void start(UsbEndpoint endpoint, int packetSize) throws IOException {
    // Allocate everything before submitting reads, avoiding allocation in the steady-state loop.
    for (int i = 0; i < slots.length; i++) {
      Slot slot = new Slot(packetSize);
      slots[i] = slot;
      if (!slot.request.initialize(connection, endpoint)) {
        throw new IOException("Could not initialize an IMU USB request");
      }
      slot.request.setClientData(slot);
    }
    for (Slot slot : slots) enqueue(slot);
    AppLog.i(TAG, "Buffered IMU reader started: " + REQUEST_COUNT + " pending reads");
  }

  int read(byte[] packet) throws IOException {
    UsbRequest completed;
    try {
      completed = connection.requestWait(READ_TIMEOUT_MS);
    } catch (TimeoutException e) {
      throw new IOException("Timed out reading the IMU", e);
    }
    if (completed == null) throw new IOException("Could not read the IMU");
    Slot slot = (Slot) completed.getClientData();
    slot.queued = false;
    pending--;
    int received = slot.buffer.position();
    if (received == packet.length) {
      slot.buffer.flip();
      slot.buffer.get(packet);
    }
    // Copy before requeueing: the USB controller may overwrite the buffer immediately.
    // Refill before decoding/fusion so their processing time cannot starve this endpoint.
    enqueue(slot);
    return received;
  }

  private void enqueue(Slot slot) throws IOException {
    slot.buffer.clear();
    if (!slot.request.queue(slot.buffer)) throw new IOException("Could not queue an IMU read");
    slot.queued = true;
    pending++;
  }

  @Override
  public void close() {
    for (Slot slot : slots) {
      if (slot != null && slot.queued) slot.request.cancel();
    }
    // Cancellation still produces completions. Reap them before freeing requests or reusing
    // the connection, including when initialization only submitted part of the queue.
    long deadline = System.nanoTime() + 500_000_000L;
    try {
      while (pending > 0 && System.nanoTime() < deadline) {
        // A timed wait polls first and may return null on USB hangup even with cancelled
        // completions still available. Reap directly after cancellation, without that poll.
        UsbRequest completed = connection.requestWait(0);
        if (completed == null) break;
        Slot slot = (Slot) completed.getClientData();
        slot.queued = false;
        pending--;
      }
    } catch (TimeoutException | RuntimeException e) {
      AppLog.w(TAG, "Could not drain cancelled IMU requests", e);
    }
    if (pending > 0) {
      // A failed/disconnected transport must never be reused with unreaped native requests.
      // The device thread stops the button reader before invoking this cleanup.
      AppLog.w(TAG, "Closing USB connection with " + pending + " unreaped IMU requests");
      connection.close();
    }
    for (int i = 0; i < slots.length; i++) {
      if (slots[i] != null) {
        slots[i].request.close();
        slots[i] = null;
      }
    }
    pending = 0;
    AppLog.i(TAG, "Buffered IMU reader stopped");
  }
}
