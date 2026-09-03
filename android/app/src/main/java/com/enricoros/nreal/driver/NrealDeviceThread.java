package com.enricoros.nreal.driver;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Pair;

import com.enricoros.nreal.AppLog;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Implements communication with the device and decoding of the data.
 * Using insights from:
 * - https://github.com/edwatt/real-air/blob/sensor_fusion/src/tracking.c
 * - https://github.com/abls/imu-inspector/blob/master/inspector.c
 *
 * @noinspection SameParameterValue, JavadocLinkAsPlainText
 */
class NrealDeviceThread extends Thread {

  private static final String TAG = "NrealDeviceThread";
  private static final boolean DEBUG_IMU_TEXT = false;
  private static final boolean DEBUG_OTHER_COMMANDS = false;
  private static final int IMU_COMMAND_TIMEOUT_MS = 500;
  private static final int MAX_FACTORY_CONFIG_BYTES = 128 * 1024;

  // Constants from the datasheets, retained for the optional debug display.
  private static final float TICK_SCALE_S = 1f / 1E9f;
  private static final float DEFAULT_SAMPLE_PERIOD_S = 0.001f;

  private final UsbDeviceConnection connection;
  private final UsbEndpoint imuIn;
  private final UsbEndpoint imuOut;
  private final UsbEndpoint otherIn;
  private final UsbEndpoint otherOut;
  private final ThreadCallbacks threadCallbacks;
  private final byte[] imuData = new byte[64];
  private final byte[] otherData = new byte[64];
  private final ImuDataRaw imuDataRaw = new ImuDataRaw();
  private final FactoryImuCalibration factoryImuCalibration = new FactoryImuCalibration();
  private final float[] calibratedAccelerationGs = new float[3];
  private final float[] calibratedGyroscopeRadiansPerSecond = new float[3];
  private final float[] freshMagnetometerDirection = new float[3];

  private volatile boolean mQuit = false;

  private long lastUptimeNs;

  public static final int BUTTON_POWER = 1;
  public static final int BUTTON_BRIGHTNESS_UP = 2;
  public static final int BUTTON_BRIGHTNESS_DOWN = 3;

  public interface ThreadCallbacks {
    void onConnectionError(String s);

    void onNewData(ImuDataRaw data);

    void onButtonPressedTemp(int button, int value);

    void onMessage(String message);
  }


  public NrealDeviceThread(UsbDeviceConnection deviceConnection, Pair<UsbEndpoint, UsbEndpoint> imuEndpoints, Pair<UsbEndpoint, UsbEndpoint> otherEndpoints, ThreadCallbacks callbacks) {
    connection = deviceConnection;
    imuIn = imuEndpoints.first;
    imuOut = imuEndpoints.second;
    otherIn = otherEndpoints.first;
    otherOut = otherEndpoints.second;
    threadCallbacks = callbacks;
  }

  public void quit() {
    AppLog.i(TAG, "Reader thread quit requested");
    mQuit = true;
    try {
      join(2000);
    } catch (InterruptedException e) {
      AppLog.w(TAG, "Interrupted while waiting for reader thread to stop", e);
      threadCallbacks.onConnectionError("Could not stop reading the IMU");
    }
  }


  public void saveState(SharedPreferences preferences) {
    // Magnetic heading is intentionally not part of pose fusion. Remove calibration state from
    // older builds so it cannot be mistaken for an active anti-drift input.
    preferences.edit().remove("magnetometer_calibration_v2").apply();
  }

  public boolean restoreState(SharedPreferences preferences) {
    if (preferences.contains("magnetometer_calibration_v2")) {
      preferences.edit().remove("magnetometer_calibration_v2").apply();
    }
    return false;
  }


  @Override
  public void run() {
    AppLog.i(TAG, "Reader thread starting");
    loadFactoryCalibration();
    if (!t_startImu()) {
      AppLog.w(TAG, "Could not start IMU stream");
      threadCallbacks.onConnectionError("Could not start reading the IMU");
      return;
    }
    AppLog.i(TAG, "IMU stream started");
    if (!t_startOther()) {
      AppLog.w(TAG, "Could not start other HID stream");
      threadCallbacks.onConnectionError("Could not start reading the Others");
      return;
    }
    AppLog.i(TAG, "Other HID stream started");
    if (t_setDisplayModeStereo()) {
      AppLog.i(TAG, "Requested Nreal Air SBS stereo display mode");
      threadCallbacks.onMessage("Requested Nreal Air SBS stereo display mode");
    } else {
      AppLog.w(TAG, "Could not switch Nreal Air to SBS stereo display mode");
      threadCallbacks.onMessage("Could not switch Nreal Air to SBS stereo display mode");
    }

    lastUptimeNs = 0;

    // The button endpoint is mostly idle. Even a 1 ms wait here costs a full IMU sample
    // period and can build a USB backlog; it must never share the sensor read loop.
    Thread buttons = new Thread(() -> {
      while (!mQuit) {
        int received = connection.bulkTransfer(otherIn, otherData, otherData.length, 200);
        if (received == otherData.length && !mQuit) processOtherData();
      }
    }, "Nreal-buttons");
    buttons.start();
    try {
      while (!mQuit) {
        int received = connection.bulkTransfer(imuIn, imuData, imuData.length, 200);
        if (received < 0) {
          if (!mQuit) threadCallbacks.onConnectionError("Could not read the IMU");
          break;
        }
        // Never decode a short transfer using bytes left over from the preceding packet.
        if (received == imuData.length && !mQuit) processIMUData();
      }
    } finally {
      mQuit = true;
      try {
        buttons.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    AppLog.i(TAG, () -> "Reader thread finished: quitRequested=" + mQuit);
  }


  @SuppressLint("DefaultLocale")
  private void processIMUData() {
    // validity checks
    if (imuData[0] != 1 || imuData[1] != 2 || imuData[12] != (byte) 0xA0 || imuData[13] != 0x0F || imuData[27] != 0x20 || imuData[42] != 0x00) {
      printHex(imuData, 0, 64, "Unexpected IMU data (1): ");
      return;
    }

    // Packet decode
    // [0  ...  1] = 01 02
    // int counter1 = (imuData[2] & 0xFF) | ((imuData[3] & 0xFF) << 8); // seems like some sort of delta / resource usage, averaging ~500
    long uptimeNs = ((long) imuData[4] & 0xFF) | (((long) imuData[5] & 0xFF) << 8) | (((long) imuData[6] & 0xFF) << 16) | (((long) imuData[7] & 0xFF) << 24) |
        (((long) imuData[8] & 0xFF) << 32) | (((long) imuData[9] & 0xFF) << 40) | (((long) imuData[10] & 0xFF) << 48) | (((long) imuData[11] & 0xFF) << 56);
    // [12 ... 17] = A0 0F 00 00 00 01
    int angVelX = (imuData[18] & 0xFF) | ((imuData[19] & 0xFF) << 8) | ((imuData[20] & 0xFF) << 16) | ((imuData[20] & 0x80) != 0 ? (0xFF << 24) : 0);
    int angVelY = (imuData[21] & 0xFF) | ((imuData[22] & 0xFF) << 8) | ((imuData[23] & 0xFF) << 16) | ((imuData[23] & 0x80) != 0 ? (0xFF << 24) : 0);
    int angVelZ = (imuData[24] & 0xFF) | ((imuData[25] & 0xFF) << 8) | ((imuData[26] & 0xFF) << 16) | ((imuData[26] & 0x80) != 0 ? (0xFF << 24) : 0);
    // [27 ... 32] = 20 00 00 00 00 01
    int accelX = (imuData[33] & 0xFF) | ((imuData[34] & 0xFF) << 8) | ((imuData[35] & 0xFF) << 16) | ((imuData[35] & 0x80) != 0 ? (0xFF << 24) : 0);
    int accelY = (imuData[36] & 0xFF) | ((imuData[37] & 0xFF) << 8) | ((imuData[38] & 0xFF) << 16) | ((imuData[38] & 0x80) != 0 ? (0xFF << 24) : 0);
    int accelZ = (imuData[39] & 0xFF) | ((imuData[40] & 0xFF) << 8) | ((imuData[41] & 0xFF) << 16) | ((imuData[41] & 0x80) != 0 ? (0xFF << 24) : 0);
    // [42 ... 47] = 00 80 00 04 00 00
    int magX = readLeInt16(imuData, 48);
    int magY = readLeInt16(imuData, 50);
    int magZ = readLeInt16(imuData, 52);
    //int counter2 = (imuData[54] & 0xFF) | ((imuData[55] & 0xFF) << 8) | ((imuData[56] & 0xFF) << 16) | ((imuData[57] & 0xFF) << 24);
    // [58 ... 63] = 00 00 00 00 (00 | 01) 00
    if (imuData[58] != 0 || imuData[59] != 0 || imuData[60] != 0 || imuData[61] != 0 || (imuData[62] != 0 && imuData[62] != 1) || imuData[63] != 0)
      printHex(imuData, 58, 6, "Unexpected IMU data (2): ");

    float dT = lastUptimeNs > 0L
        ? (uptimeNs - lastUptimeNs) * TICK_SCALE_S
        : DEFAULT_SAMPLE_PERIOD_S;
    lastUptimeNs = uptimeNs;
    if (!Float.isFinite(dT) || dT <= 0.0f || dT > 0.1f) {
      dT = DEFAULT_SAMPLE_PERIOD_S;
    }

    factoryImuCalibration.calibrateAccelerometer(
        accelX, accelY, accelZ, calibratedAccelerationGs);
    factoryImuCalibration.calibrateGyroscope(
        angVelX, angVelY, angVelZ, calibratedGyroscopeRadiansPerSecond);

    // Byte 62 is the v2 report's magnetic-observation freshness flag. The XYZ values remain
    // cached between observations, so never present cached values to the stationary detector as
    // fresh measurements. Magnetic data is used only as a motion veto, never as a yaw reference.
    boolean freshMagnetometer = imuData[62] == 1;
    float freshMagnetometerMagnitude = 0.0f;
    if (freshMagnetometer) {
      freshMagnetometerDirection[0] = magY;
      freshMagnetometerDirection[1] = magZ;
      freshMagnetometerDirection[2] = magX;
      freshMagnetometerMagnitude = (float) Math.sqrt(
          freshMagnetometerDirection[0] * freshMagnetometerDirection[0]
              + freshMagnetometerDirection[1] * freshMagnetometerDirection[1]
              + freshMagnetometerDirection[2] * freshMagnetometerDirection[2]);
      if (Float.isFinite(freshMagnetometerMagnitude) && freshMagnetometerMagnitude > 1.0e-6f) {
        freshMagnetometerDirection[0] /= freshMagnetometerMagnitude;
        freshMagnetometerDirection[1] /= freshMagnetometerMagnitude;
        freshMagnetometerDirection[2] /= freshMagnetometerMagnitude;
      } else {
        freshMagnetometer = false;
        freshMagnetometerMagnitude = 0.0f;
      }
    }

    imuDataRaw.update(
        accelX,
        accelY,
        accelZ,
        angVelX,
        angVelY,
        angVelZ,
        magX,
        magY,
        magZ,
        uptimeNs,
        calibratedAccelerationGs,
        calibratedGyroscopeRadiansPerSecond,
        freshMagnetometer ? freshMagnetometerDirection : null,
        freshMagnetometerMagnitude,
        false);

    if (DEBUG_IMU_TEXT) {
      imuDataRaw.update(String.format("\n\nGyro (dps):  %+,.1f  %+,.1f  %+,.1f\n\nAcc    (G):  %+,.1f  %+,.1f  %+,.1f\n\nMag (raw):   %d  %d  %d\n\ndT (ms):  %3.0f",
          Math.toDegrees(calibratedGyroscopeRadiansPerSecond[0]),
          Math.toDegrees(calibratedGyroscopeRadiansPerSecond[1]),
          Math.toDegrees(calibratedGyroscopeRadiansPerSecond[2]),
          calibratedAccelerationGs[0],
          calibratedAccelerationGs[1],
          calibratedAccelerationGs[2],
          magX,
          magY,
          magZ,
          dT * 1000));
    }
    threadCallbacks.onNewData(imuDataRaw);
  }

  private void processOtherData() {
    byte btnIndex = otherData[22];
    byte btnValue = otherData[30];

    // we have a partial understanding of the data
    if (btnIndex == 1) {
      // Power button press
      if (btnValue == 1) {
        // Clicked power - screen is ON
        threadCallbacks.onButtonPressedTemp(BUTTON_POWER, 1);
      } else if (btnValue == 0) {
        // Clicked power - screen is OFF
        threadCallbacks.onButtonPressedTemp(BUTTON_POWER, 0);
      } else
        AppLog.e(TAG, "Unknown screen state: " + btnValue);
    } else if (btnIndex == 2) {
      // Brightness up press
      threadCallbacks.onButtonPressedTemp(BUTTON_BRIGHTNESS_UP, btnValue);
      //mBrightness = btnValue;
    } else if (btnIndex == 3) {
      // Brightness down press
      threadCallbacks.onButtonPressedTemp(BUTTON_BRIGHTNESS_DOWN, btnValue);
      //mBrightness = btnValue;
    } else if (DEBUG_OTHER_COMMANDS)
      AppLog.e(TAG, "Read Other bytes: 22: " + btnIndex + ", 15: " + otherData[15] + ", 30: " + otherData[30] + ", 23: " + otherData[23] + " - " + Arrays.toString(otherData));
  }

  private void loadFactoryCalibration() {
    try {
      if (readFactoryImuCalibration()) {
        AppLog.i(TAG, "Loaded factory IMU calibration");
        threadCallbacks.onMessage("Loaded factory IMU calibration");
      } else {
        AppLog.d(TAG, "Factory IMU calibration was unavailable");
      }
    } catch (RuntimeException e) {
      AppLog.w(TAG, "Could not load factory IMU calibration", e);
    }
  }

  private boolean readFactoryImuCalibration() {
    // Pause the IMU stream while asking the glasses for their JSON calibration blob.
    AppLog.d(TAG, "Reading factory IMU calibration");
    t_sendImuCommand(0x19, new byte[]{0x00}, IMU_COMMAND_TIMEOUT_MS);

    byte[] lengthBytes = t_sendImuCommand(0x14, new byte[0], IMU_COMMAND_TIMEOUT_MS);
    if (lengthBytes == null || lengthBytes.length < 4) {
      AppLog.d(TAG, "Factory IMU calibration length response missing");
      return false;
    }

    int configLength = readLe32(lengthBytes, 0);
    if (configLength <= 0 || configLength > MAX_FACTORY_CONFIG_BYTES) {
      AppLog.w(TAG, "Factory IMU calibration length out of range: " + configLength);
      return false;
    }
    AppLog.d(TAG, () -> "Factory IMU calibration length=" + configLength);

    ByteArrayOutputStream configBytes = new ByteArrayOutputStream(configLength);
    while (configBytes.size() < configLength) {
      byte[] chunk = t_sendImuCommand(0x15, new byte[0], IMU_COMMAND_TIMEOUT_MS);
      if (chunk == null || chunk.length == 0) {
        AppLog.d(TAG, () -> "Factory IMU calibration chunk missing: bytesRead=" + configBytes.size()
            + ", expected=" + configLength);
        return false;
      }
      int bytesToWrite = Math.min(chunk.length, configLength - configBytes.size());
      configBytes.write(chunk, 0, bytesToWrite);
    }

    return parseFactoryImuCalibration(new String(configBytes.toByteArray(), StandardCharsets.UTF_8));
  }

  private boolean parseFactoryImuCalibration(String configJson) {
    try {
      return factoryImuCalibration.load(configJson);
    } catch (JSONException e) {
      AppLog.w(TAG, "Could not parse factory IMU calibration", e);
      return false;
    }
  }

  private byte[] t_sendImuCommand(int commandId, byte[] data, int timeoutMs) {
    int length = data.length + 3;
    byte[] packet = new byte[8 + data.length];
    packet[0] = (byte) 0xAA;
    putLe16(packet, 5, length);
    packet[7] = (byte) (commandId & 0xFF);
    System.arraycopy(data, 0, packet, 8, data.length);

    CRC32 crc32 = new CRC32();
    crc32.update(packet, 5, length);
    putLe32(packet, 1, (int) crc32.getValue());

    int sent = connection.bulkTransfer(imuOut, packet, packet.length, timeoutMs);
    if (sent != packet.length) {
      AppLog.d(TAG, () -> "Could not write IMU command " + commandId
          + ", sent=" + sent
          + ", expected=" + packet.length);
      return null;
    }

    for (int attempt = 0; attempt < 8; attempt++) {
      byte[] response = new byte[64];
      int received = connection.bulkTransfer(imuIn, response, response.length, timeoutMs);
      if (received <= 0) {
        final int attemptIndex = attempt;
        AppLog.d(TAG, () -> "No IMU command response: commandId=" + commandId
            + ", attempt=" + attemptIndex);
        return null;
      }
      if (received < 8 || response[0] != (byte) 0xAA || (response[7] & 0xFF) != (commandId & 0xFF)) {
        continue;
      }
      int responseLength = readLe16(response, 5);
      int responseDataLength = Math.max(0, Math.min(received - 8, responseLength - 3));
      return Arrays.copyOfRange(response, 8, 8 + responseDataLength);
    }
    AppLog.d(TAG, () -> "No matching IMU command response: commandId=" + commandId);
    return null;
  }

  private boolean t_startImu() {
    // Issues the start reading magic command to the IMU
    // NOTE: compared to the hid_write implementations, this is missing the first byte as it's an internal command for the hid library
    byte[] magicPayload = {(byte) 0xaa, (byte) 0xc5, (byte) 0xd1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01};
    return connection.bulkTransfer(imuOut, magicPayload, magicPayload.length, 200) >= 0;
  }

  private boolean t_startOther() {
    // The magic command is to read brightness
    // NOTE: doesn't seem to work now - commented out
    // magicPayload to retrieve brightness = {(byte) 0xfd, 0x1e, (byte) 0xb9, (byte) 0xf0, 0x68, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};
    return true;
  }

  private boolean t_setDisplayModeStereo() {
    // Nreal Air MCU command 0x08 selects display mode. Mode 3 is full SBS,
    // where the external display becomes 3840x1080 with one 1920x1080 half per eye.
    AppLog.d(TAG, "Sending Nreal display mode stereo command");
    return t_sendMcuCommand(0x08, new byte[]{0x03});
  }

  private boolean t_sendMcuCommand(int commandId, byte[] data) {
    if (data.length > 42)
      throw new IllegalArgumentException("Nreal MCU command data too long");

    byte[] packet = new byte[64];
    packet[0] = (byte) 0xFD;
    int length = data.length + 17;
    putLe16(packet, 5, length);
    putLe32(packet, 7, 0x1337);
    putLe32(packet, 11, 0);
    putLe16(packet, 15, commandId);
    System.arraycopy(data, 0, packet, 22, data.length);

    CRC32 crc32 = new CRC32();
    crc32.update(packet, 5, length);
    putLe32(packet, 1, (int) crc32.getValue());

    int sent = connection.bulkTransfer(otherOut, packet, packet.length, 500);
    if (sent != packet.length) {
      AppLog.e(TAG, "Could not write MCU command " + commandId + ", sent=" + sent);
      return false;
    }

    byte[] response = new byte[64];
    int received = connection.bulkTransfer(otherIn, response, response.length, 500);
    if (received <= 0) {
      // Some firmware revisions apply the mode switch without returning a response.
      AppLog.d(TAG, () -> "MCU command " + commandId + " had no response; assuming success");
      return true;
    }
    if (response[0] != (byte) 0xFD || readLe16(response, 15) != commandId) {
      AppLog.d(TAG, () -> "MCU command " + commandId + " returned unrelated response; assuming success");
      return true;
    }
    int dataLength = Math.max(0, readLe16(response, 5) - 17);
    AppLog.d(TAG, () -> "MCU command " + commandId + " response dataLength=" + dataLength
        + ", status=" + (dataLength > 0 ? response[22] : 0));
    return dataLength == 0 || response[22] == 0;
  }

  private static void putLe16(byte[] target, int offset, int value) {
    target[offset] = (byte) (value & 0xFF);
    target[offset + 1] = (byte) ((value >> 8) & 0xFF);
  }

  private static int readLe16(byte[] source, int offset) {
    return (source[offset] & 0xFF) | ((source[offset + 1] & 0xFF) << 8);
  }

  static int readLeInt16(byte[] source, int offset) {
    return (short) readLe16(source, offset);
  }

  private static int readLe32(byte[] source, int offset) {
    return (source[offset] & 0xFF)
        | ((source[offset + 1] & 0xFF) << 8)
        | ((source[offset + 2] & 0xFF) << 16)
        | ((source[offset + 3] & 0xFF) << 24);
  }

  private static void putLe32(byte[] target, int offset, int value) {
    target[offset] = (byte) (value & 0xFF);
    target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    target[offset + 2] = (byte) ((value >> 16) & 0xFF);
    target[offset + 3] = (byte) ((value >> 24) & 0xFF);
  }

  private void printHex(byte[] data, int from, int count, String prefix) {
    StringBuilder sb = new StringBuilder().append(prefix).append(from).append(": ");
    for (int i = from; i < from + count; i++)
      sb.append(String.format("%02X ", data[i] & 0xFF));
    AppLog.e(TAG, sb.toString());
  }

}
