package com.enricoros.nreal;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.content.res.AssetFileDescriptor;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.enricoros.nreal.databinding.ActivityMainBinding;
import com.enricoros.nreal.driver.ImuDataRaw;
import com.enricoros.nreal.driver.NrealManager;
import com.enricoros.nreal.player.HeadTracker;
import com.enricoros.nreal.player.RecentVideo;
import com.enricoros.nreal.player.RecentVideoStore;
import com.enricoros.nreal.player.Vr180Renderer;
import com.enricoros.nreal.player.VrPlayerPresentation;
import com.google.android.material.button.MaterialButton;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "AirVrPlayer";
  private static final int SEEK_BAR_MAX = 1000;
  private static final int SCALE_SLIDER_MIN = 60;
  private static final int SCALE_SLIDER_MAX = 180;
  private static final int SCALE_SLIDER_STEP = 5;
  private static final int SCENE_CENTER_MIN_DEGREES = -45;
  private static final int SCENE_CENTER_MAX_DEGREES = 45;
  private static final int HORIZON_MIN_DEGREES = -30;
  private static final int HORIZON_MAX_DEGREES = 30;
  private static final String PREFS_NAME = "player_settings";
  private static final String PREF_DARK_MODE = "dark_mode";
  private static final String PREF_AUDIO_MUTED = "audio_muted";
  private static final String PREF_VIEW_SCALE = "view_scale";
  private static final String PREF_SCENE_CENTER_DEGREES = "scene_center_degrees";
  private static final String PREF_HORIZON_DEGREES = "horizon_degrees";
  private static final long SKIP_MS = 10_000L;
  private static final long PROGRESS_UPDATE_MS = 500L;
  private static final long MIN_TRACKING_RENDER_INTERVAL_MS = 16L;

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final HeadTracker headTracker = new HeadTracker();
  private final List<RecentVideo> recentVideos = new ArrayList<>();

  private NrealManager nrealManager;
  private ActivityMainBinding binding;
  private ExoPlayer player;
  private DisplayManager displayManager;
  private SharedPreferences settingsPreferences;
  private VrPlayerPresentation presentation;
  private Surface activeVideoSurface;
  private RecentVideo currentVideo;
  private ImuDataRaw latestImuData;
  private boolean isUserSeeking = false;
  private float viewScale = 1.0f;
  private float sceneCenterDegrees = 0.0f;
  private float horizonDegrees = 0.0f;
  private boolean darkMode = false;
  private boolean audioMuted = false;
  private long loopStartMs = C.TIME_UNSET;
  private long loopEndMs = C.TIME_UNSET;
  private long lastImuElapsedMs = 0L;
  private long lastStatusElapsedMs = 0L;
  private long lastTrackingRenderElapsedMs = 0L;
  private String lastDeviceMessage = "";
  private String playbackMessage = "";
  private boolean progressUpdatesScheduled = false;

  private final ActivityResultLauncher<String[]> openVideoLauncher =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onVideoSelected);

  private final Runnable progressUpdater = new Runnable() {
    @Override
    public void run() {
      progressUpdatesScheduled = false;
      updatePlaybackProgress();
      scheduleProgressUpdates();
    }
  };

  private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
    @Override
    public void onDisplayAdded(int displayId) {
      updatePresentation();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
      updatePresentation();
    }

    @Override
    public void onDisplayChanged(int displayId) {
      updatePresentation();
    }
  };

  private final Vr180Renderer.SurfaceCallback videoSurfaceCallback = new Vr180Renderer.SurfaceCallback() {
    @Override
    public void onVideoSurfaceCreated(Surface surface) {
      activeVideoSurface = surface;
      if (player != null) {
        player.setVideoSurface(surface);
      }
      updateStatus();
      updateKeepScreenOn();
    }

    @Override
    public void onVideoSurfaceDestroyed(Surface surface) {
      if (activeVideoSurface == surface) {
        if (player != null) {
          player.clearVideoSurface();
        }
        activeVideoSurface = null;
      }
      updateStatus();
      updateKeepScreenOn();
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    settingsPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    darkMode = settingsPreferences.getBoolean(PREF_DARK_MODE, false);
    audioMuted = settingsPreferences.getBoolean(PREF_AUDIO_MUTED, false);
    viewScale = settingsPreferences.getFloat(PREF_VIEW_SCALE, 1.0f);
    sceneCenterDegrees = settingsPreferences.getFloat(PREF_SCENE_CENTER_DEGREES, 0.0f);
    horizonDegrees = settingsPreferences.getFloat(PREF_HORIZON_DEGREES, 0.0f);
    AppCompatDelegate.setDefaultNightMode(darkMode
        ? AppCompatDelegate.MODE_NIGHT_YES
        : AppCompatDelegate.MODE_NIGHT_NO);

    super.onCreate(savedInstanceState);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    player = new ExoPlayer.Builder(this).build();
    applyAudioMuted();
    nrealManager = new NrealManager(getApplicationContext(), nrealListener);
    recentVideos.addAll(RecentVideoStore.load(this));
    pruneMissingRecentVideos();

    configurePlayer();
    configureControls();
    renderRecentVideos();
    updatePresentation();
    updateStatus();
    updateKeepScreenOn();
    scheduleProgressUpdates();
  }

  @Override
  protected void onStart() {
    super.onStart();
    displayManager.registerDisplayListener(displayListener, uiHandler);
    nrealManager.connectToNrealUsbDevice();
    updatePresentation();
    scheduleProgressUpdates();
  }

  @Override
  protected void onStop() {
    super.onStop();
    saveCurrentVideoProgress();
    displayManager.unregisterDisplayListener(displayListener);
    uiHandler.removeCallbacks(progressUpdater);
    progressUpdatesScheduled = false;
    updateKeepScreenOn();
  }

  @Override
  protected void onDestroy() {
    uiHandler.removeCallbacks(progressUpdater);
    saveCurrentVideoProgress();
    dismissPresentation();
    if (player != null) {
      player.release();
      player = null;
    }
    if (nrealManager != null) {
      nrealManager.destroy();
      nrealManager = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    UsbDevice device = getUsbDeviceExtra(intent);
    if (device != null) {
      nrealManager.connectToNrealUsbDevice();
    }
  }

  private void configurePlayer() {
    player.addListener(new Player.Listener() {
      @Override
      public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_ENDED && isLoopReady()) {
          player.seekTo(loopStartMs);
          player.play();
        }
        updatePlaybackUi();
        updateKeepScreenOn();
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        updatePlaybackUi();
        updateKeepScreenOn();
        scheduleProgressUpdates();
      }

      @Override
      public void onPlayerError(PlaybackException error) {
        playbackMessage = error.getErrorCodeName();
        updatePlaybackUi();
        updateKeepScreenOn();
      }

      @Override
      public void onVideoSizeChanged(VideoSize videoSize) {
        updatePlaybackUi();
      }
    });
  }

  private void configureControls() {
    binding.selectVideoButton.setOnClickListener(v -> openVideoLauncher.launch(new String[]{"video/*"}));
    binding.playPauseButton.setOnClickListener(v -> {
      if (currentVideo == null) {
        openVideoLauncher.launch(new String[]{"video/*"});
      } else if (player.isPlaying()) {
        player.pause();
      } else {
        player.play();
      }
      updatePlaybackUi();
    });
    binding.rewindButton.setOnClickListener(v -> seekRelative(-SKIP_MS));
    binding.forwardButton.setOnClickListener(v -> seekRelative(SKIP_MS));
    binding.abLoopButton.setOnClickListener(v -> handleAbLoopButton());
    binding.recenterButton.setOnClickListener(v -> {
      headTracker.recenter();
      if (presentation != null) {
        presentation.setHeadRotationMatrix(headTracker.getRotationMatrix());
      }
      lastDeviceMessage = "View recentered";
      updateStatus();
    });

    binding.darkModeSwitch.setChecked(darkMode);
    binding.darkModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      darkMode = isChecked;
      settingsPreferences.edit().putBoolean(PREF_DARK_MODE, darkMode).apply();
      AppCompatDelegate.setDefaultNightMode(darkMode
          ? AppCompatDelegate.MODE_NIGHT_YES
          : AppCompatDelegate.MODE_NIGHT_NO);
    });
    binding.muteAudioSwitch.setChecked(audioMuted);
    binding.muteAudioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      audioMuted = isChecked;
      settingsPreferences.edit().putBoolean(PREF_AUDIO_MUTED, audioMuted).apply();
      applyAudioMuted();
    });
    binding.clearRecentVideosButton.setOnClickListener(v -> {
      RecentVideoStore.clear(this, recentVideos);
      currentVideo = null;
      clearLoop();
      if (player != null) {
        player.stop();
        player.clearMediaItems();
      }
      renderRecentVideos();
      updatePlaybackUi();
    });

    binding.progressSeekBar.setMax(SEEK_BAR_MAX);
    binding.progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
          long duration = getKnownDuration();
          if (duration > 0) {
            long position = duration * progress / SEEK_BAR_MAX;
            binding.positionText.setText(formatPosition(position, duration));
          }
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
        isUserSeeking = true;
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        long duration = getKnownDuration();
        if (duration > 0) {
          player.seekTo(duration * seekBar.getProgress() / SEEK_BAR_MAX);
        }
        isUserSeeking = false;
      }
    });

    binding.viewScaleSlider.setValueFrom(SCALE_SLIDER_MIN);
    binding.viewScaleSlider.setValueTo(SCALE_SLIDER_MAX);
    binding.viewScaleSlider.setStepSize(SCALE_SLIDER_STEP);
    binding.viewScaleSlider.setValue(clamp(viewScale * 100.0f, SCALE_SLIDER_MIN, SCALE_SLIDER_MAX));
    binding.viewScaleSlider.setLabelFormatter(value -> String.format(Locale.US, "%.2fx", value / 100.0f));
    binding.viewScaleSlider.addOnChangeListener((slider, value, fromUser) -> {
      viewScale = value / 100.0f;
      binding.viewScaleValue.setText(String.format(Locale.US, "%.2fx", viewScale));
      settingsPreferences.edit().putFloat(PREF_VIEW_SCALE, viewScale).apply();
      if (presentation != null) {
        presentation.setZoom(viewScale);
      }
    });
    updateViewScaleText();

    binding.sceneCenterSlider.setValueFrom(SCENE_CENTER_MIN_DEGREES);
    binding.sceneCenterSlider.setValueTo(SCENE_CENTER_MAX_DEGREES);
    binding.sceneCenterSlider.setStepSize(1.0f);
    binding.sceneCenterSlider.setValue(clamp(sceneCenterDegrees, SCENE_CENTER_MIN_DEGREES, SCENE_CENTER_MAX_DEGREES));
    binding.sceneCenterSlider.setLabelFormatter(value -> formatDegrees(value));
    binding.sceneCenterSlider.addOnChangeListener((slider, value, fromUser) -> {
      sceneCenterDegrees = value;
      updateViewPositionControls();
      applyViewPosition();
      saveProjectionSettings();
    });

    binding.horizonSlider.setValueFrom(HORIZON_MIN_DEGREES);
    binding.horizonSlider.setValueTo(HORIZON_MAX_DEGREES);
    binding.horizonSlider.setStepSize(1.0f);
    binding.horizonSlider.setValue(clamp(horizonDegrees, HORIZON_MIN_DEGREES, HORIZON_MAX_DEGREES));
    binding.horizonSlider.setLabelFormatter(value -> formatDegrees(value));
    binding.horizonSlider.addOnChangeListener((slider, value, fromUser) -> {
      horizonDegrees = value;
      updateViewPositionControls();
      applyViewPosition();
      saveProjectionSettings();
    });

    binding.resetViewPositionButton.setOnClickListener(v -> {
      sceneCenterDegrees = 0.0f;
      horizonDegrees = 0.0f;
      binding.sceneCenterSlider.setValue(0.0f);
      binding.horizonSlider.setValue(0.0f);
      updateViewPositionControls();
      applyViewPosition();
      saveProjectionSettings();
    });
    updateViewPositionControls();

    updatePlaybackUi();
  }

  private void onVideoSelected(Uri uri) {
    if (uri == null) {
      return;
    }
    try {
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException ignored) {
      // Some providers grant a one-session URI. Playback still works for this launch.
    }

    RecentVideo video = findRecentVideo(uri);
    if (video == null) {
      video = new RecentVideo(uri, queryDisplayName(uri), 0L, 0L);
    }
    RecentVideoStore.upsert(this, recentVideos, video);
    renderRecentVideos();
    playVideo(video);
  }

  private void playVideo(RecentVideo video) {
    if (!canOpenVideo(video.uri)) {
      removeRecentVideo(video);
      playbackMessage = "Video file missing";
      updatePlaybackUi();
      return;
    }
    saveCurrentVideoProgress();
    currentVideo = video;
    playbackMessage = "";
    resetLoop();
    binding.currentVideoTitle.setText(video.title);
    player.setMediaItem(MediaItem.fromUri(video.uri), Math.max(0L, video.lastPositionMs));
    player.prepare();
    player.play();
    headTracker.recenter();
    updatePlaybackUi();
    renderRecentVideos();
    scheduleProgressUpdates();
  }

  private void seekRelative(long deltaMs) {
    if (currentVideo == null) {
      return;
    }
    long duration = getKnownDuration();
    long target = Math.max(0L, player.getCurrentPosition() + deltaMs);
    if (duration > 0) {
      target = Math.min(duration, target);
    }
    player.seekTo(target);
    updatePlaybackProgress();
    scheduleProgressUpdates();
  }

  private void handleAbLoopButton() {
    if (currentVideo == null || player == null) {
      return;
    }

    if (isLoopReady()) {
      resetLoop();
      updateLoopControls();
      return;
    }

    long position = Math.max(0L, player.getCurrentPosition());
    if (loopStartMs == C.TIME_UNSET) {
      loopStartMs = position;
      loopEndMs = C.TIME_UNSET;
      updateLoopControls();
      return;
    }

    if (position <= loopStartMs) {
      loopStartMs = position;
      loopEndMs = C.TIME_UNSET;
    } else {
      loopEndMs = position;
      player.seekTo(loopStartMs);
      player.play();
    }
    updateLoopControls();
  }

  private void clearLoop() {
    resetLoop();
    updateLoopControls();
  }

  private void resetLoop() {
    loopStartMs = C.TIME_UNSET;
    loopEndMs = C.TIME_UNSET;
  }

  private void updatePresentation() {
    Display display = findBestExternalDisplay();
    if (display == null) {
      dismissPresentation();
      updateStatus();
      return;
    }
    if (presentation != null && presentation.getDisplay().getDisplayId() == display.getDisplayId()) {
      updateStatus();
      return;
    }

    dismissPresentation();
    presentation = new VrPlayerPresentation(this, display, videoSurfaceCallback);
    presentation.setOnDismissListener(dialog -> {
      if (presentation == dialog) {
        presentation = null;
        updateStatus();
      }
    });
    try {
      presentation.show();
    } catch (WindowManager.InvalidDisplayException e) {
      handlePresentationFailure("External display unavailable", e);
      return;
    } catch (RuntimeException e) {
      handlePresentationFailure("External display failed: " + e.getClass().getSimpleName(), e);
      return;
    }
    presentation.setZoom(viewScale);
    presentation.setViewOffsetDegrees(sceneCenterDegrees, horizonDegrees);
    presentation.setHeadRotationMatrix(headTracker.getRotationMatrix());
    updateStatus();
  }

  private void handlePresentationFailure(String message, RuntimeException error) {
    Log.e(TAG, "Could not create external VR presentation", error);
    VrPlayerPresentation failedPresentation = presentation;
    presentation = null;
    if (failedPresentation != null) {
      failedPresentation.setOnDismissListener(null);
      try {
        failedPresentation.releaseRenderer();
        failedPresentation.dismiss();
      } catch (RuntimeException ignored) {
        // The presentation window may never have finished attaching.
      }
    }
    lastDeviceMessage = message;
    updateStatus();
  }

  private void dismissPresentation() {
    if (presentation == null) {
      return;
    }
    VrPlayerPresentation oldPresentation = presentation;
    presentation = null;
    oldPresentation.setOnDismissListener(null);
    oldPresentation.releaseRenderer();
    oldPresentation.dismiss();
    if (player != null) {
      player.clearVideoSurface();
    }
    activeVideoSurface = null;
    updateKeepScreenOn();
  }

  private Display findBestExternalDisplay() {
    if (displayManager == null) {
      return null;
    }

    Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
    if (displays.length == 0) {
      displays = displayManager.getDisplays();
    }

    Display defaultDisplay = getDisplay();
    int defaultDisplayId = defaultDisplay == null ? 0 : defaultDisplay.getDisplayId();
    Display bestDisplay = null;
    int bestScore = Integer.MIN_VALUE;
    for (Display display : displays) {
      if (display.getDisplayId() == defaultDisplayId) {
        continue;
      }

      Display.Mode mode = display.getMode();
      String name = display.getName() == null ? "" : display.getName().toLowerCase(Locale.US);
      int score = 0;
      if (name.contains("nreal") || name.contains("xreal") || name.contains("air")) {
        score += 1000;
      }
      if ((display.getFlags() & Display.FLAG_PRESENTATION) != 0) {
        score += 100;
      }
      if (mode != null && mode.getPhysicalWidth() >= 3000) {
        score += 80;
      }
      if (mode != null) {
        score += mode.getPhysicalWidth() / 100;
      }

      if (bestDisplay == null || score > bestScore) {
        bestDisplay = display;
        bestScore = score;
      }
    }
    return bestDisplay;
  }

  private final NrealManager.Listener nrealListener = new NrealManager.Listener() {
    @Override
    public void onDeviceConnected() {
      headTracker.reset();
      lastDeviceMessage = "USB connected";
      updateStatus();
    }

    @Override
    public void onDeviceDisconnected() {
      lastDeviceMessage = "USB disconnected";
      lastImuElapsedMs = 0L;
      updateStatus();
    }

    @Override
    public void onPermissionDenied() {
      lastDeviceMessage = "USB permission denied";
      updateStatus();
    }

    @Override
    public void onConnectionError(String error) {
      lastDeviceMessage = error;
      updateStatus();
    }

    @Override
    public void onMessage(String message) {
      lastDeviceMessage = message;
      updateStatus();
    }

    @Override
    public void onNewDataTemp(ImuDataRaw imuDataRawCopy) {
      latestImuData = imuDataRawCopy;
      lastImuElapsedMs = SystemClock.elapsedRealtime();
      float[] rotationMatrix = headTracker.update(imuDataRawCopy);
      long now = SystemClock.elapsedRealtime();
      if (presentation != null && now - lastTrackingRenderElapsedMs >= MIN_TRACKING_RENDER_INTERVAL_MS) {
        lastTrackingRenderElapsedMs = now;
        presentation.setHeadRotationMatrix(rotationMatrix);
      }
      if (now - lastStatusElapsedMs > 500L) {
        updateStatus();
      }
    }

    @Override
    public void onButtonPressedTemp(int buttonId, int relatedValue) {
      lastDeviceMessage = "Button " + buttonId + " value " + relatedValue;
      updateStatus();
    }
  };

  private void updatePlaybackUi() {
    updatePlaybackProgress();
    boolean hasVideo = currentVideo != null;
    binding.rewindButton.setEnabled(hasVideo);
    binding.forwardButton.setEnabled(hasVideo);
    binding.abLoopButton.setEnabled(hasVideo);
    binding.progressSeekBar.setEnabled(hasVideo);
    binding.playPauseButton.setText(player != null && player.isPlaying() ? "Pause" : "Play");
    binding.playPauseButton.setIconResource(player != null && player.isPlaying()
        ? R.drawable.ic_pause_24
        : R.drawable.ic_play_arrow_24);
    if (!hasVideo) {
      binding.currentVideoTitle.setText("No video selected");
    }
    updateLoopControls();
    updateStatus();
    scheduleProgressUpdates();
  }

  private void updatePlaybackProgress() {
    if (binding == null || player == null) {
      return;
    }
    long duration = getKnownDuration();
    long position = currentVideo == null ? 0L : Math.max(0L, player.getCurrentPosition());
    if (!isUserSeeking) {
      if (duration > 0) {
        binding.progressSeekBar.setProgress((int) Math.min(SEEK_BAR_MAX, position * SEEK_BAR_MAX / duration));
      } else {
        binding.progressSeekBar.setProgress(0);
      }
      binding.positionText.setText(formatPosition(position, duration));
    }
    if (isLoopReady() && position >= loopEndMs) {
      player.seekTo(loopStartMs);
    }
  }

  private void scheduleProgressUpdates() {
    if (progressUpdatesScheduled || player == null || binding == null || currentVideo == null) {
      return;
    }
    if (!player.isPlaying()) {
      return;
    }
    progressUpdatesScheduled = true;
    uiHandler.postDelayed(progressUpdater, PROGRESS_UPDATE_MS);
  }

  private void updateKeepScreenOn() {
    if (binding == null || player == null) {
      return;
    }
    boolean keepAwake = player.isPlaying() && (presentation == null || activeVideoSurface == null);
    if (keepAwake) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private void applyAudioMuted() {
    if (player != null) {
      player.setVolume(audioMuted ? 0.0f : 1.0f);
    }
  }

  private void updateLoopControls() {
    if (binding == null) {
      return;
    }
    boolean hasVideo = currentVideo != null;
    binding.abLoopButton.setEnabled(hasVideo);
    if (loopStartMs == C.TIME_UNSET) {
      binding.abLoopButton.setText("Set loop A");
    } else if (loopEndMs == C.TIME_UNSET) {
      binding.abLoopButton.setText("Set loop B (" + formatTime(loopStartMs) + ")");
    } else {
      binding.abLoopButton.setText("Clear A-B loop");
    }
  }

  private boolean isLoopReady() {
    return currentVideo != null
        && loopStartMs != C.TIME_UNSET
        && loopEndMs != C.TIME_UNSET
        && loopEndMs > loopStartMs;
  }

  private void updateStatus() {
    if (binding == null) {
      return;
    }
    lastStatusElapsedMs = SystemClock.elapsedRealtime();
    binding.glassesStatusText.setText(buildGlassesStatus());
    binding.outputStatusText.setText(buildOutputStatus());
    binding.trackingStatusText.setText(buildTrackingStatus());
    binding.playbackStatusText.setText(buildPlaybackStatus());
  }

  private String buildGlassesStatus() {
    if (nrealManager == null) {
      return "Glasses: initializing";
    }
    String status = nrealManager.isDeviceConnected()
        ? (nrealManager.isDeviceStreaming() ? "USB connected, IMU streaming" : "USB connected")
        : "USB disconnected";
    if (lastDeviceMessage.length() > 0) {
      status += " - " + lastDeviceMessage;
    }
    return "Glasses: " + status;
  }

  private String buildOutputStatus() {
    if (presentation == null) {
      return "Output: no external Air display";
    }
    Display display = presentation.getDisplay();
    Display.Mode mode = display.getMode();
    String modeText = mode == null
        ? display.getName()
        : String.format(Locale.US, "%s, %dx%d @ %.0fHz",
        display.getName(),
        mode.getPhysicalWidth(),
        mode.getPhysicalHeight(),
        mode.getRefreshRate());
    return "Output: " + modeText + (presentation.isStereoOutput() ? ", SBS stereo" : ", 2D mirror mode");
  }

  private String buildTrackingStatus() {
    if (lastImuElapsedMs <= 0L) {
      return "Tracking: waiting for Nreal Air IMU";
    }
    long ageMs = SystemClock.elapsedRealtime() - lastImuElapsedMs;
    String status = ageMs < 1000L ? "live" : "stale";
    if (latestImuData == null) {
      return "Tracking: " + status;
    }
    float[] gyro = latestImuData.getGyroscopeRadiansPerSecond();
    return String.format(Locale.US, "Tracking: %s, gyro %.2f %.2f %.2f rad/s, center %s, horizon %s",
        status,
        gyro[0],
        gyro[1],
        gyro[2],
        formatDegrees(sceneCenterDegrees),
        formatDegrees(horizonDegrees));
  }

  private String buildPlaybackStatus() {
    if (currentVideo == null || player == null) {
      return "Idle";
    }
    if (playbackMessage.length() > 0) {
      return "Playback error: " + playbackMessage;
    }

    String state;
    switch (player.getPlaybackState()) {
      case Player.STATE_BUFFERING:
        state = "Buffering";
        break;
      case Player.STATE_READY:
        state = player.isPlaying() ? "Playing" : "Paused";
        break;
      case Player.STATE_ENDED:
        state = "Ended";
        break;
      case Player.STATE_IDLE:
      default:
        state = "Preparing";
        break;
    }
    VideoSize videoSize = player.getVideoSize();
    if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
      state += String.format(Locale.US, " - %dx%d", videoSize.width, videoSize.height);
    }
    return state;
  }

  private void renderRecentVideos() {
    pruneMissingRecentVideos();
    binding.recentVideosContainer.removeAllViews();
    if (recentVideos.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText("No recent videos");
      empty.setTextColor(getColor(R.color.muted));
      empty.setTextSize(14);
      empty.setPadding(0, dp(8), 0, dp(8));
      binding.recentVideosContainer.addView(empty);
      return;
    }

    for (RecentVideo video : recentVideos) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      row.setPadding(dp(10), dp(10), dp(10), dp(10));
      if (currentVideo != null && currentVideo.uri.equals(video.uri)) {
        row.setBackgroundColor(getColor(R.color.panel_alt));
      }

      LinearLayout textColumn = new LinearLayout(this);
      textColumn.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
      textColumn.setLayoutParams(textParams);

      TextView title = new TextView(this);
      title.setText(video.title);
      title.setTextColor(getColor(R.color.ink));
      title.setTextSize(15);
      title.setMaxLines(2);
      textColumn.addView(title);

      TextView meta = new TextView(this);
      meta.setText(formatRecentMeta(video));
      meta.setTextColor(getColor(R.color.muted));
      meta.setTextSize(13);
      textColumn.addView(meta);

      MaterialButton playButton = new MaterialButton(this);
      playButton.setText("Play");
      playButton.setIconResource(R.drawable.ic_play_arrow_24);
      playButton.setMinWidth(0);
      playButton.setOnClickListener(v -> playVideo(video));
      LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
      buttonParams.setMargins(dp(10), 0, 0, 0);

      row.addView(textColumn);
      row.addView(playButton, buttonParams);
      binding.recentVideosContainer.addView(row);
    }
  }

  private RecentVideo findRecentVideo(Uri uri) {
    for (RecentVideo video : recentVideos) {
      if (video.uri.equals(uri)) {
        return video;
      }
    }
    return null;
  }

  private void removeRecentVideo(RecentVideo video) {
    recentVideos.remove(video);
    if (currentVideo != null && currentVideo.uri.equals(video.uri)) {
      currentVideo = null;
      resetLoop();
      if (player != null) {
        player.stop();
        player.clearMediaItems();
      }
    }
    RecentVideoStore.save(this, recentVideos);
    renderRecentVideos();
  }

  private void pruneMissingRecentVideos() {
    boolean changed = false;
    boolean removedCurrentVideo = false;
    for (Iterator<RecentVideo> iterator = recentVideos.iterator(); iterator.hasNext(); ) {
      RecentVideo video = iterator.next();
      if (!canOpenVideo(video.uri)) {
        if (currentVideo != null && currentVideo.uri.equals(video.uri)) {
          currentVideo = null;
          resetLoop();
          removedCurrentVideo = true;
        }
        iterator.remove();
        changed = true;
      }
    }
    if (changed) {
      RecentVideoStore.save(this, recentVideos);
    }
    if (removedCurrentVideo && player != null) {
      player.stop();
      player.clearMediaItems();
    }
  }

  private boolean canOpenVideo(Uri uri) {
    ContentResolver resolver = getContentResolver();
    try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
      return descriptor != null;
    } catch (FileNotFoundException | SecurityException e) {
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  @SuppressWarnings("deprecation")
  private UsbDevice getUsbDeviceExtra(Intent intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
    }
    return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
  }

  private String queryDisplayName(Uri uri) {
    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (index >= 0) {
          String displayName = cursor.getString(index);
          if (displayName != null && displayName.length() > 0) {
            return displayName;
          }
        }
      }
    }
    String path = uri.getLastPathSegment();
    return path == null || path.length() == 0 ? "Selected video" : path;
  }

  private void saveCurrentVideoProgress() {
    if (player == null || currentVideo == null) {
      return;
    }
    long duration = getKnownDuration();
    long position = Math.max(0L, player.getCurrentPosition());
    if (duration > 0 && duration - position < 2500L) {
      position = 0L;
    }
    currentVideo.lastPositionMs = position;
    currentVideo.durationMs = duration;
    RecentVideoStore.save(this, recentVideos);
  }

  private void applyViewPosition() {
    if (presentation != null) {
      presentation.setViewOffsetDegrees(sceneCenterDegrees, horizonDegrees);
    }
  }

  private void updateViewScaleText() {
    binding.viewScaleValue.setText(String.format(Locale.US, "%.2fx", viewScale));
  }

  private void updateViewPositionControls() {
    binding.sceneCenterValue.setText(formatDegrees(sceneCenterDegrees));
    binding.horizonValue.setText(formatDegrees(horizonDegrees));
  }

  private void saveProjectionSettings() {
    settingsPreferences.edit()
        .putFloat(PREF_SCENE_CENTER_DEGREES, sceneCenterDegrees)
        .putFloat(PREF_HORIZON_DEGREES, horizonDegrees)
        .apply();
  }

  private long getKnownDuration() {
    if (player == null) {
      return 0L;
    }
    long duration = player.getDuration();
    return duration == C.TIME_UNSET || duration < 0L ? 0L : duration;
  }

  private String formatRecentMeta(RecentVideo video) {
    if (video.durationMs <= 0L) {
      return "Ready";
    }
    if (video.lastPositionMs <= 0L) {
      return formatTime(video.durationMs);
    }
    return formatTime(video.lastPositionMs) + " / " + formatTime(video.durationMs);
  }

  private String formatPosition(long positionMs, long durationMs) {
    return formatTime(positionMs) + " / " + (durationMs > 0L ? formatTime(durationMs) : "--:--");
  }

  @SuppressLint("DefaultLocale")
  private static String formatTime(long millis) {
    long totalSeconds = Math.max(0L, millis / 1000L);
    long seconds = totalSeconds % 60L;
    long minutes = (totalSeconds / 60L) % 60L;
    long hours = totalSeconds / 3600L;
    if (hours > 0L) {
      return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
    }
    return String.format(Locale.US, "%02d:%02d", minutes, seconds);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }

  private static String formatDegrees(float value) {
    return String.format(Locale.US, "%+.0f deg", value);
  }
}
