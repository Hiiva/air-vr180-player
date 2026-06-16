package com.enricoros.nreal.driver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import androidx.preference.PreferenceManager;

import com.enricoros.nreal.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** @noinspection SameParameterValue */
public class NrealManager {

  private static final String TAG = "NrealManager";

  private static final int NREAL_AIR_VENDOR_ID = 0x3318;
  private static final int NREAL_AIR_PRODUCT_ID = 0x0424;

  private static final String CUSTOM_BROADCAST_PERMISSION_ACTION = "ai.enrico.mindlet.NREAL_USB_PERMISSION";

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Object imuDispatchLock = new Object();
  private final ImuDataRaw pendingImuData = new ImuDataRaw();
  private final ImuDataRaw dispatchedImuData = new ImuDataRaw();
  private final Runnable imuDispatchRunnable = new Runnable() {
    @Override
    public void run() {
      dispatchPendingImuData();
    }
  };
  private final Context context;
  private final Listener listener;
  private final UsbManager usbManager;
  private final SharedPreferences preferences;
  private final BroadcastReceiver mUsbPermissionReceiver;

  private UsbDeviceConnection mDeviceConnection;
  private NrealDeviceThread mThread;
  private boolean hasPendingImuData;
  private boolean imuDispatchQueued;


  public interface Listener {
    void onDeviceConnected();

    void onDeviceDisconnected();

    void onPermissionDenied();

    void onConnectionError(String error);

    void onMessage(String message);

    void onNewDataTemp(ImuDataRaw imuDataRawCopy);

    void onButtonPressedTemp(int buttonId, int relatedValue);
  }


  public NrealManager(Context applicationContext, Listener nrealListener) {
    AppLog.d(TAG, "Creating NrealManager");
    context = applicationContext;
    listener = nrealListener;
    usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    preferences = PreferenceManager.getDefaultSharedPreferences(context);

    // Note: moved registration here to be sure we will not double-register this receiver
    mUsbPermissionReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (!Objects.equals(intent.getAction(), CUSTOM_BROADCAST_PERMISSION_ACTION))
          return;
        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
          AppLog.w(TAG, "USB permission denied by system dialog");
          listener.onPermissionDenied();
          return;
        }
        UsbDevice device = getUsbDeviceExtra(intent);
        if (device == null) {
          AppLog.w(TAG, "USB permission callback did not include a device");
          listener.onConnectionError("No permission granted for device");
          return;
        }
        synchronized (this) {
          AppLog.i(TAG, () -> "USB permission granted: vendorId=" + device.getVendorId()
              + ", productId=" + device.getProductId()
              + ", id=" + device.getDeviceId());
          onUsbDevicePermissionGranted(device);
        }
      }
    };

    IntentFilter usbPermissionFilter = new IntentFilter(CUSTOM_BROADCAST_PERMISSION_ACTION);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
      context.registerReceiver(mUsbPermissionReceiver, usbPermissionFilter, Context.RECEIVER_NOT_EXPORTED);
    else
      context.registerReceiver(mUsbPermissionReceiver, usbPermissionFilter);
    AppLog.d(TAG, "Registered USB permission receiver");
  }


  public void connectToNrealUsbDevice() {
    AppLog.d(TAG, () -> "connectToNrealUsbDevice: connected=" + (mDeviceConnection != null)
        + ", streaming=" + isDeviceStreaming());
    if (mDeviceConnection == null) {
      // find the device on the list of USB devices
      UsbDevice nrealDevice = UsbUtils.usbFindConnectedDevice(usbManager, NREAL_AIR_VENDOR_ID, NREAL_AIR_PRODUCT_ID);
      if (nrealDevice == null) {
        AppLog.w(TAG, "No attached Nreal devices found");
        listener.onConnectionError("No attached Nreal devices found");
        return;
      }
      AppLog.i(TAG, () -> "Requesting USB permission: vendorId=" + nrealDevice.getVendorId()
          + ", productId=" + nrealDevice.getProductId()
          + ", id=" + nrealDevice.getDeviceId());

      // ask the user for permissions; may continue right away to -> mUsbPermissionReceiver -> onUsbDevicePermissionGranted
      PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(CUSTOM_BROADCAST_PERMISSION_ACTION), PendingIntent.FLAG_MUTABLE);
      usbManager.requestPermission(nrealDevice, permissionIntent);
    }
  }

  public void closeNrealUsbDevice() {
    AppLog.i(TAG, () -> "Closing Nreal USB device: connected=" + (mDeviceConnection != null)
        + ", streaming=" + isDeviceStreaming());
    stopNrealCommunication();
    if (mDeviceConnection != null) {
      mDeviceConnection.close();
      mDeviceConnection = null;
      mThread = null;
      listener.onDeviceDisconnected();
    }
  }

  public boolean isDeviceConnected() {
    return mDeviceConnection != null;
  }

  public boolean isDeviceStreaming() {
    return mThread != null && mThread.isAlive();
  }

  public void destroy() {
    AppLog.d(TAG, "Destroying NrealManager");
    closeNrealUsbDevice();
    try {
      context.unregisterReceiver(mUsbPermissionReceiver);
      AppLog.d(TAG, "Unregistered USB permission receiver");
    } catch (IllegalArgumentException ignored) {
      AppLog.d(TAG, "USB permission receiver was already unregistered");
      // Receiver was already unregistered.
    }
  }


  private void onUsbDevicePermissionGranted(UsbDevice device) {
    // [DEV] sanity check
    if (mDeviceConnection != null) {
      AppLog.e(TAG, "Device already opened and connected");
      return;
    }
    UsbUtils.logDevice("Nreal Air", device);
    AppLog.i(TAG, () -> "Opening Nreal USB device: interfaces=" + device.getInterfaceCount()
        + ", configurations=" + device.getConfigurationCount());


    // find the interface and endpoints
    List<UsbInterface> usbInterfaces = new ArrayList<>();
    UsbInterface imuInterface = UsbUtils.usbFindHIDInterface(device, 3, 0);
    if (imuInterface == null) {
      AppLog.w(TAG, "Could not find IMU interface");
      listener.onConnectionError("Could not find IMU interface");
      return;
    }
    usbInterfaces.add(imuInterface);
    Pair<UsbEndpoint, UsbEndpoint> imuEndpoints = UsbUtils.usbFindInterfaceEndpoints(imuInterface, UsbConstants.USB_ENDPOINT_XFER_INT, 0x84, 0x05);
    if (imuEndpoints == null || imuEndpoints.first.getMaxPacketSize() != 64) {
      AppLog.w(TAG, "Could not find IMU endpoints");
      listener.onConnectionError("Could not find IMU endpoints");
      return;
    }
    UsbInterface otherInterface = UsbUtils.usbFindHIDInterface(device, 4, 0);
    if (otherInterface == null) {
      AppLog.w(TAG, "Could not find other interface");
      listener.onConnectionError("Could not find other interface");
      return;
    }
    usbInterfaces.add(otherInterface);
    Pair<UsbEndpoint, UsbEndpoint> otherEndpoints = UsbUtils.usbFindInterfaceEndpoints(otherInterface, UsbConstants.USB_ENDPOINT_XFER_INT, 0x86, 0x07);
    if (otherEndpoints == null || otherEndpoints.first.getMaxPacketSize() != 64) {
      AppLog.w(TAG, "Could not find other endpoints");
      listener.onConnectionError("Could not find other endpoints");
      return;
    }
    AppLog.i(TAG, () -> "Found Nreal USB endpoints: imuIn=" + imuEndpoints.first.getAddress()
        + ", imuOut=" + imuEndpoints.second.getAddress()
        + ", otherIn=" + otherEndpoints.first.getAddress()
        + ", otherOut=" + otherEndpoints.second.getAddress());

    // connect to the device, and claim all interfaces
    mDeviceConnection = usbManager.openDevice(device);
    if (mDeviceConnection == null) {
      AppLog.w(TAG, "Could not open Nreal USB device");
      listener.onConnectionError("Could not open device");
      return;
    }
    for (UsbInterface i : usbInterfaces) {
      if (!mDeviceConnection.claimInterface(i, true)) {
        AppLog.w(TAG, () -> "Could not claim USB interface " + i.getId() + ":" + i.getAlternateSetting());
        listener.onConnectionError("Could not claim interface " + i.getId() + ":" + i.getAlternateSetting());
        return;
      }
      AppLog.d(TAG, () -> "Claimed USB interface " + i.getId() + ":" + i.getAlternateSetting());
    }

    // start
    listener.onDeviceConnected();

    if (mThread != null) {
      AppLog.e(TAG, "Reader thread already running");
      return;
    }
    mThread = new NrealDeviceThread(mDeviceConnection, imuEndpoints, otherEndpoints, mReaderCallbacks);
    if (mThread.restoreState(preferences))
      listener.onMessage("Restored Calibration");
    AppLog.i(TAG, "Starting Nreal reader thread");
    mThread.start();
  }

  private final NrealDeviceThread.ThreadCallbacks mReaderCallbacks = new NrealDeviceThread.ThreadCallbacks() {
    @Override
    public void onConnectionError(String error) {
      AppLog.w(TAG, () -> "Reader callback connection error: " + error);
      uiHandler.post(() -> {
        listener.onConnectionError(error);
        closeNrealUsbDevice();
      });
    }

    @Override
    public void onNewData(ImuDataRaw data) {
      queueLatestImuData(data);
    }

    @Override
    public void onButtonPressedTemp(int button, int value) {
      AppLog.d(TAG, () -> "Reader callback button: button=" + button + ", value=" + value);
      uiHandler.post(() -> listener.onButtonPressedTemp(button, value));
    }

    @Override
    public void onMessage(String message) {
      AppLog.d(TAG, () -> "Reader callback message: " + message);
      uiHandler.post(() -> listener.onMessage(message));
    }
  };

  private void queueLatestImuData(ImuDataRaw data) {
    boolean shouldPost;
    synchronized (imuDispatchLock) {
      pendingImuData.copyFrom(data);
      hasPendingImuData = true;
      shouldPost = !imuDispatchQueued;
      if (shouldPost) {
        imuDispatchQueued = true;
      }
    }
    if (shouldPost) {
      uiHandler.post(imuDispatchRunnable);
    }
  }

  private void dispatchPendingImuData() {
    synchronized (imuDispatchLock) {
      if (!hasPendingImuData) {
        imuDispatchQueued = false;
        return;
      }
      dispatchedImuData.copyFrom(pendingImuData);
      hasPendingImuData = false;
      imuDispatchQueued = false;
    }
    listener.onNewDataTemp(dispatchedImuData);
  }

  private void clearPendingImuDispatch() {
    uiHandler.removeCallbacks(imuDispatchRunnable);
    synchronized (imuDispatchLock) {
      hasPendingImuData = false;
      imuDispatchQueued = false;
    }
  }

  private void stopNrealCommunication() {
    if (mThread != null) {
      AppLog.i(TAG, "Stopping Nreal reader thread");
      mThread.quit();
      mThread.saveState(preferences);
      mThread = null;
    }
    clearPendingImuDispatch();
  }

  @SuppressWarnings("deprecation")
  private static UsbDevice getUsbDeviceExtra(Intent intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
      return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
    return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
  }

}
