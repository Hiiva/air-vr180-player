package com.enricoros.nreal.driver;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;
import android.util.Pair;

import com.enricoros.nreal.driver.data.MagnetometerPreprocessor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
  private static final boolean DEBUG_10HZ = false;
  private static final boolean DEBUG_IMU_TEXT = false;
  private static final boolean DEBUG_OTHER_COMMANDS = false;
  private static final int IMU_COMMAND_TIMEOUT_MS = 500;
  private static final int MAX_FACTORY_CONFIG_BYTES = 128 * 1024;

  // constants from the datasheets
  private static final float TICK_SCALE_S = 1f / 1E9f;
  private static final float GYRO_SCALE_DPS = 2000f / 8388608f; // based on 24bit signed int w/ FSR = +/-2000 dps, datasheet option
  private static final float ACCEL_SCALE_G = 16f / 8388608f;    // based on 24bit signed int w/ FSR = +/-16 g, datasheet option

  private final UsbDeviceConnection connection;
  private final UsbEndpoint imuIn;
  private final UsbEndpoint imuOut;
  private final UsbEndpoint otherIn;
  private final UsbEndpoint otherOut;
  private final ThreadCallbacks threadCallbacks;
  private final byte[] imuData = new byte[64];
  private final byte[] otherData = new byte[64];
  private final ImuDataRaw imuDataRaw = new ImuDataRaw();
  private final MagnetometerPreprocessor magnetometerPreprocessor = new MagnetometerPreprocessor(100.f, 200);

  private final float[] gyroCalibrationRadiansPerSecond = new float[3];

  private boolean mQuit = false;

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
    mQuit = true;
    try {
      join(2000);
    } catch (InterruptedException e) {
      threadCallbacks.onConnectionError("Could not stop reading the IMU");
    }
  }


  public void saveState(SharedPreferences preferences) {
    int[] calibration = magnetometerPreprocessor.saveCalibration();
    if (calibration != null)
      preferences.edit().putString("magnetometer_calibration", Arrays.toString(calibration)).apply();
  }

  public boolean restoreState(SharedPreferences preferences) {
    String calibration = preferences.getString("magnetometer_calibration", null);
    if (calibration != null) {
      String[] split = calibration.substring(1, calibration.length() - 1).split(", ");
      int[] ints = new int[split.length];
      for (int i = 0; i < split.length; i++)
        ints[i] = Integer.parseInt(split[i]);
      magnetometerPreprocessor.restoreCalibration(ints);
      return true;
    }
    return false;
  }


  @Override
  public void run() {
    loadFactoryCalibration();
    if (!t_startImu()) {
      threadCallbacks.onConnectionError("Could not start reading the IMU");
      return;
    }
    if (!t_startOther()) {
      threadCallbacks.onConnectionError("Could not start reading the Others");
      return;
    }
    if (t_setDisplayModeStereo()) {
      threadCallbacks.onMessage("Requested Nreal Air SBS stereo display mode");
    } else {
      threadCallbacks.onMessage("Could not switch Nreal Air to SBS stereo display mode");
    }

    lastUptimeNs = 0;

    // Infinite read until we request to quit or the device is disconnected (mDeviceConnection can be nullified, not the local copy)
    while (!mQuit /*&& mDeviceConnection != null*/) {

      // read the IMU data - must be coming within 200ms (as it's periodic)
      int res = connection.bulkTransfer(imuIn, imuData, 64, 200);
      if (res < 0) {
        threadCallbacks.onConnectionError("Could not read the IMU");
        break;
      }

      // process the IMU data as soon as it comes
      processIMUData();

      // read the other data - if it's there (timeout of 1 second, non blocking)
      res = connection.bulkTransfer(otherIn, otherData, 64, DEBUG_10HZ ? 100 : 1);
      if (res > 0)
        processOtherData();
    }
    Log.e(TAG, "Reader thread finished");
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
    int magX = (imuData[48] & 0xFF) | ((imuData[49] & 0xFF) << 8);
    int magY = (imuData[50] & 0xFF) | ((imuData[51] & 0xFF) << 8);
    int magZ = (imuData[52] & 0xFF) | ((imuData[53] & 0xFF) << 8);
    //int counter2 = (imuData[54] & 0xFF) | ((imuData[55] & 0xFF) << 8) | ((imuData[56] & 0xFF) << 16) | ((imuData[57] & 0xFF) << 24);
    // [58 ... 63] = 00 00 00 00 (00 | 01) 00
    if (imuData[58] != 0 || imuData[59] != 0 || imuData[60] != 0 || imuData[61] != 0 || (imuData[62] != 0 && imuData[62] != 1) || imuData[63] != 0)
      printHex(imuData, 58, 6, "Unexpected IMU data (2): ");

    // call the callback
    imuDataRaw.update(accelX, accelY, accelZ, angVelX, angVelY, angVelZ, magX, magY, magZ, uptimeNs, gyroCalibrationRadiansPerSecond);

    // DATA PROCESSING

    // Integrate information, if we have a previous time
    if (lastUptimeNs < 1) {
      lastUptimeNs = uptimeNs;
      return;
    }
    float dT = (uptimeNs - lastUptimeNs) * TICK_SCALE_S;
    lastUptimeNs = uptimeNs;

    // Normalize the data for the 3DoF
    float dRoll = (float) (angVelX) * GYRO_SCALE_DPS;
    float dPitch = (float) (angVelY) * GYRO_SCALE_DPS;
    float dYaw = (float) (angVelZ) * GYRO_SCALE_DPS;
    float aX = (float) (accelX) * ACCEL_SCALE_G;
    float aY = (float) (accelY) * ACCEL_SCALE_G;
    float aZ = (float) (accelZ) * ACCEL_SCALE_G;
    float[] mag = magnetometerPreprocessor.process(new int[]{magX, magY, magZ}, dT);

    if (DEBUG_IMU_TEXT) {
      // convert dRoll to string with 2 decimal places
      imuDataRaw.update(String.format("\n\nGyro (dps):  %+,.1f  %+,.1f  %+,.1f\n\nAcc    (G):  %+,.1f  %+,.1f  %+,.1f\n\nMag (norm):  %.3f  %.3f  %.3f\n\ndT (ms):  %3.0f",
          dRoll, dPitch, dYaw, aX, aY, aZ, mag[0], mag[1], mag[2], dT * 1000));
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
        Log.e(TAG, "Unknown screen state: " + btnValue);
    } else if (btnIndex == 2) {
      // Brightness up press
      threadCallbacks.onButtonPressedTemp(BUTTON_BRIGHTNESS_UP, btnValue);
      //mBrightness = btnValue;
    } else if (btnIndex == 3) {
      // Brightness down press
      threadCallbacks.onButtonPressedTemp(BUTTON_BRIGHTNESS_DOWN, btnValue);
      //mBrightness = btnValue;
    } else if (DEBUG_OTHER_COMMANDS)
      Log.e(TAG, "Read Other bytes: 22: " + btnIndex + ", 15: " + otherData[15] + ", 30: " + otherData[30] + ", 23: " + otherData[23] + " - " + Arrays.toString(otherData));
  }

  private void loadFactoryCalibration() {
    try {
      if (readFactoryGyroCalibration()) {
        threadCallbacks.onMessage("Loaded factory IMU gyro calibration");
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not load factory IMU calibration", e);
    }
  }

  private boolean readFactoryGyroCalibration() {
    // Pause the IMU stream while asking the glasses for their JSON calibration blob.
    t_sendImuCommand(0x19, new byte[]{0x00}, IMU_COMMAND_TIMEOUT_MS);

    byte[] lengthBytes = t_sendImuCommand(0x14, new byte[0], IMU_COMMAND_TIMEOUT_MS);
    if (lengthBytes == null || lengthBytes.length < 4) {
      return false;
    }

    int configLength = readLe32(lengthBytes, 0);
    if (configLength <= 0 || configLength > MAX_FACTORY_CONFIG_BYTES) {
      return false;
    }

    ByteArrayOutputStream configBytes = new ByteArrayOutputStream(configLength);
    while (configBytes.size() < configLength) {
      byte[] chunk = t_sendImuCommand(0x15, new byte[0], IMU_COMMAND_TIMEOUT_MS);
      if (chunk == null || chunk.length == 0) {
        return false;
      }
      int bytesToWrite = Math.min(chunk.length, configLength - configBytes.size());
      configBytes.write(chunk, 0, bytesToWrite);
    }

    return parseFactoryGyroCalibration(new String(configBytes.toByteArray(), StandardCharsets.UTF_8));
  }

  private boolean parseFactoryGyroCalibration(String configJson) {
    try {
      JSONObject config = new JSONObject(configJson);
      JSONObject imuDevice = config.getJSONObject("IMU").getJSONObject("device_1");
      JSONArray gyroBias = imuDevice.getJSONArray("gyro_bias");
      if (gyroBias.length() < 3) {
        return false;
      }

      // Match the axis/sign convention used by getGyroscopeRadiansPerSecond().
      gyroCalibrationRadiansPerSecond[0] = -(float) gyroBias.getDouble(0);
      gyroCalibrationRadiansPerSecond[1] = (float) gyroBias.getDouble(1);
      gyroCalibrationRadiansPerSecond[2] = (float) gyroBias.getDouble(2);
      return true;
    } catch (JSONException e) {
      Log.w(TAG, "Could not parse factory IMU calibration", e);
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
      return null;
    }

    for (int attempt = 0; attempt < 8; attempt++) {
      byte[] response = new byte[64];
      int received = connection.bulkTransfer(imuIn, response, response.length, timeoutMs);
      if (received <= 0) {
        return null;
      }
      if (received < 8 || response[0] != (byte) 0xAA || (response[7] & 0xFF) != (commandId & 0xFF)) {
        continue;
      }
      int responseLength = readLe16(response, 5);
      int responseDataLength = Math.max(0, Math.min(received - 8, responseLength - 3));
      return Arrays.copyOfRange(response, 8, 8 + responseDataLength);
    }
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
      Log.e(TAG, "Could not write MCU command " + commandId + ", sent=" + sent);
      return false;
    }

    byte[] response = new byte[64];
    int received = connection.bulkTransfer(otherIn, response, response.length, 500);
    if (received <= 0) {
      // Some firmware revisions apply the mode switch without returning a response.
      return true;
    }
    if (response[0] != (byte) 0xFD || readLe16(response, 15) != commandId) {
      return true;
    }
    int dataLength = Math.max(0, readLe16(response, 5) - 17);
    return dataLength == 0 || response[22] == 0;
  }

  private static void putLe16(byte[] target, int offset, int value) {
    target[offset] = (byte) (value & 0xFF);
    target[offset + 1] = (byte) ((value >> 8) & 0xFF);
  }

  private static int readLe16(byte[] source, int offset) {
    return (source[offset] & 0xFF) | ((source[offset + 1] & 0xFF) << 8);
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
    Log.e(TAG, sb.toString());
  }

}
