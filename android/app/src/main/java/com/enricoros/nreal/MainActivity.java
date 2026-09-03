package com.enricoros.nreal;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.enricoros.nreal.databinding.ActivityMainBinding;
import com.enricoros.nreal.driver.ImuDataRaw;
import com.enricoros.nreal.driver.NrealManager;
import com.enricoros.nreal.player.HeadTracker;
import com.enricoros.nreal.player.ProjectionModeGuesser;
import com.enricoros.nreal.player.RecentVideo;
import com.enricoros.nreal.player.RecentVideoStore;
import com.enricoros.nreal.player.SavedScene;
import com.enricoros.nreal.player.SavedSceneStore;
import com.enricoros.nreal.player.Vr180Renderer;
import com.enricoros.nreal.player.VrPlayerPresentation;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "AirVrPlayer";
  private static final int SEEK_BAR_MAX = 1000;
  private static final int SCALE_SLIDER_MIN = 60;
  private static final int SCALE_SLIDER_MAX = 180;
  private static final int SCALE_SLIDER_STEP = 5;
  private static final float DEFAULT_VIEW_SCALE = 0.80f;
  private static final int SCENE_CENTER_MIN_DEGREES = -45;
  private static final int SCENE_CENTER_MAX_DEGREES = 45;
  private static final int HORIZON_MIN_DEGREES = -30;
  private static final int HORIZON_MAX_DEGREES = 30;
  private static final String PREFS_NAME = "player_settings";
  private static final String PREF_AUDIO_MUTED = "audio_muted";
  private static final String PREF_VIEW_SCALE = "view_scale";
  private static final String PREF_SCENE_CENTER_DEGREES = "scene_center_degrees";
  private static final String PREF_HORIZON_DEGREES = "horizon_degrees";
  private static final String PREF_SERVER_URL = "server_url";
  private static final String PREF_SERVER_API_KEY = "server_api_key";
  private static final String PREF_SERVER_SORT = "server_sort";
  private static final String PREF_SERVER_JUMP_LAST = "server_jump_last";
  private static final String PREF_SERVER_HISTORY_PENDING = "server_history_pending";
  private static final String PREF_WINDOWS_REMOTE_ENABLED = "windows_remote_enabled";
  private static final String PREF_WINDOWS_REMOTE_URL = "windows_remote_url";
  private static final long WINDOWS_REMOTE_POLL_MS = 500L;
  private static final long SERVER_HISTORY_SYNC_INTERVAL_MS = 15_000L;
  private static final int SERVER_REFRESH_WAIT_MS = 1200;
  private static final int SERVER_REFRESH_POLL_DELAY_MS = 2500;
  private static final int SERVER_REFRESH_POLL_MAX_ATTEMPTS = 8;
  private static final long SERVER_UNSEEN_WATCH_LIMIT_MS = 120_000L;
  private static final String SORT_NAME_ASC = "Name A-Z";
  private static final String SORT_NAME_DESC = "Name Z-A";
  private static final String SORT_NEWEST = "Newest";
  private static final String SORT_OLDEST = "Oldest";
  private static final String SORT_LONGEST = "Longest";
  private static final String SORT_SHORTEST = "Shortest";
  private static final long SKIP_MS = 10_000L;
  private static final long PROGRESS_UPDATE_MS = 500L;
  private static final long LOOP_UPDATE_MS = 16L;
  private static final long MIN_TRACKING_RENDER_INTERVAL_MS = 16L;
  private static final long VERBOSE_STATS_INTERVAL_MS = 5000L;
  private static final int SERVER_THUMBNAIL_PREFETCH_COUNT = 24;
  private static final int PLAYER_MIN_BUFFER_MS = 15_000;
  private static final int PLAYER_MAX_BUFFER_MS = 60_000;
  private static final int PLAYER_CONSTRAINED_MIN_BUFFER_MS = 2_500;
  private static final int PLAYER_CONSTRAINED_MAX_BUFFER_MS = 8_000;
  private static final int PLAYER_BUFFER_FOR_PLAYBACK_MS = 250;
  private static final int PLAYER_BUFFER_FOR_REBUFFER_MS = 1_000;
  private static final int PLAYER_BACK_BUFFER_MS = 30_000;
  private static final int PLAYER_CONSTRAINED_BACK_BUFFER_MS = 0;
  private static final int PLAYER_CONSTRAINED_TARGET_BUFFER_BYTES = 48 * 1024 * 1024;
  private static final long CONSTRAINED_BUFFER_MIN_BITRATE_BPS = 70_000_000L;
  private static final String[] PROJECTION_MODE_LABELS = {
      "VR180 equirectangular",
      "VR190 fisheye",
      "VR200 fisheye"
  };
  private static final int[] PROJECTION_MODE_VALUES = {
      Vr180Renderer.PROJECTION_EQUIRECT_VR180,
      Vr180Renderer.PROJECTION_FISHEYE_VR190,
      Vr180Renderer.PROJECTION_FISHEYE_VR200
  };
  private static final int ACTIVE_VIDEO_SURFACE_INDEX = 0;
  private static final boolean LOG_VERBOSE_STATS = AppLog.isVerboseEnabled(TAG);

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final HeadTracker headTracker = new HeadTracker();
  private final List<RecentVideo> recentVideos = new ArrayList<>();
  private final List<ServerVideo> serverVideos = new ArrayList<>();
  private final List<ServerVideo> serverPlayedVideos = new ArrayList<>();
  private final PriorityTaskManager playbackPriorityTaskManager = new PriorityTaskManager();
  private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(8);
  private final ExecutorService serverHistoryExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService windowsRemoteExecutor = Executors.newSingleThreadExecutor();
  private final Object serverHistoryPendingLock = new Object();
  private final Set<String> thumbnailsInFlight = Collections.synchronizedSet(new HashSet<>());
  private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(64 * 1024) {
    @Override
    protected int sizeOf(String key, Bitmap value) {
      return value.getByteCount() / 1024;
    }
  };

  private NrealManager nrealManager;
  private ActivityMainBinding binding;
  private ExoPlayer player;
  private boolean playerUsesConstrainedBuffers = false;
  private String playerServerApiKey = "";
  private volatile int thumbnailLoadGeneration = 0;
  private final Surface[] videoSurfaces = new Surface[Vr180Renderer.VIDEO_SURFACE_COUNT];
  private final Surface[] videoSurfaceAttachedSurfaces = new Surface[Vr180Renderer.VIDEO_SURFACE_COUNT];
  private PlayerMessage loopBoundaryMessage;
  private DisplayManager displayManager;
  private SharedPreferences settingsPreferences;
  private VrPlayerPresentation presentation;
  private RecentVideo currentVideo;
  private SavedScenesController savedScenes;
  private boolean currentVideoFromServer = false;
  private ServerVideo currentServerVideo;
  private Dialog serverDialog;
  private RecyclerView serverVideoRecyclerView;
  private ServerVideoAdapter serverVideoAdapter;
  private TextView serverDialogStatusText;
  private TextView serverDialogTabLibrary;
  private TextView serverDialogTabUnseen;
  private LinearLayout serverSearchRow;
  private EditText serverSearchEditText;
  private String serverDialogTab = "library";
  private String serverSearchQuery = "";
  private boolean serverVideosLoadedThisSession = false;
  private String serverLoadedUrlThisSession = "";
  private int serverRefreshPollToken = 0;
  private ImuDataRaw latestImuData;
  private boolean isUserSeeking = false;
  private float viewScale = DEFAULT_VIEW_SCALE;
  private int projectionMode = Vr180Renderer.PROJECTION_EQUIRECT_VR180;
  private float sceneCenterDegrees = 0.0f;
  private float horizonDegrees = 0.0f;
  private boolean audioMuted = false;
  private long loopStartMs = C.TIME_UNSET;
  private long loopEndMs = C.TIME_UNSET;
  private boolean loopPaused = false;
  private long lastImuElapsedMs = 0L;
  private long lastStatusElapsedMs = 0L;
  private long lastTrackingRenderElapsedMs = 0L;
  private long lastTrackingLogElapsedMs = 0L;
  private long lastVideoFrameLogElapsedMs = 0L;
  private int loopBoundaryGeneration = 0;
  private int trackingSamplesSinceLastLog = 0;
  private int videoFramesSinceLastLog = 0;
  private String lastDeviceMessage = "";
  private String playbackMessage = "";
  private boolean progressUpdatesScheduled = false;
  private boolean loopUpdatesScheduled = false;
  private boolean loopRestartPending = false;
  private boolean watchTimeTrackingActive = false;
  private long lastWatchTimeElapsedMs = 0L;
  private long lastServerHistorySyncElapsedMs = 0L;
  private boolean windowsRemoteEnabled = false;
  private boolean activityStarted = false;
  private boolean windowsRemotePollScheduled = false;
  private boolean updatingWindowsRemoteUi = false;
  private WindowsRemoteClient.State windowsRemoteState;
  private String windowsRemoteError = "";
  private boolean localPlaybackSuspendedForWindowsRemote = false;
  private RecentVideo suspendedLocalVideo;
  private boolean suspendedLocalVideoFromServer = false;
  private ServerVideo suspendedLocalServerVideo;
  private long suspendedLocalLoopStartMs = C.TIME_UNSET;
  private long suspendedLocalLoopEndMs = C.TIME_UNSET;
  private boolean suspendedLocalLoopPaused = false;
  private int suspendedLocalProjectionMode = Vr180Renderer.PROJECTION_EQUIRECT_VR180;

  private final ActivityResultLauncher<String[]> openVideoLauncher =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onVideoSelected);

  private final Runnable progressUpdater = new Runnable() {
    @Override
    public void run() {
      progressUpdatesScheduled = false;
      updatePlaybackProgress();
      syncCurrentServerProgressIfDue();
      scheduleProgressUpdates();
    }
  };

  private final Runnable loopUpdater = new Runnable() {
    @Override
    public void run() {
      loopUpdatesScheduled = false;
      enforceLoopBoundary();
      scheduleLoopUpdates();
    }
  };

  private final Runnable windowsRemotePoller = new Runnable() {
    @Override
    public void run() {
      windowsRemotePollScheduled = false;
      if (!windowsRemoteEnabled || !activityStarted || binding == null) {
        return;
      }
      final String remoteUrl = resolveWindowsRemoteUrl();
      final String apiKey = getServerApiKey();
      if (remoteUrl.length() == 0 || apiKey.length() == 0) {
        windowsRemoteError = remoteUrl.length() == 0
            ? "Set Windows remote URL"
            : "Set server API key";
        applyWindowsRemoteUi();
        scheduleWindowsRemotePoll(WINDOWS_REMOTE_POLL_MS);
        return;
      }
      windowsRemoteExecutor.execute(() -> {
        try {
          WindowsRemoteClient.State state = WindowsRemoteClient.fetchState(remoteUrl, apiKey);
          uiHandler.post(() -> {
            if (!windowsRemoteEnabled || binding == null) {
              return;
            }
            windowsRemoteState = state;
            windowsRemoteError = "";
            applyWindowsRemoteUi();
            scheduleWindowsRemotePoll(WINDOWS_REMOTE_POLL_MS);
          });
        } catch (Exception error) {
          uiHandler.post(() -> {
            if (!windowsRemoteEnabled || binding == null) {
              return;
            }
            windowsRemoteError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            applyWindowsRemoteUi();
            scheduleWindowsRemotePoll(1200L);
          });
        }
      });
    }
  };

  private final Player.Listener activePlayerListener = new Player.Listener() {
    @Override
    public void onPlaybackStateChanged(int playbackState) {
      AppLog.d(TAG, () -> "Playback state changed: " + playbackStateName(playbackState)
          + ", current=" + currentVideoTitleForLog()
          + ", positionMs=" + (player == null ? 0L : Math.max(0L, player.getCurrentPosition()))
          + ", durationMs=" + getKnownDuration());
      if (playbackState == Player.STATE_ENDED && isLoopActive()) {
        seekToLoopStart();
      }
      updatePlaybackUi();
      updateKeepScreenOn();
      scheduleLoopUpdates();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      AppLog.d(TAG, () -> "Playback isPlaying=" + isPlaying
          + ", current=" + currentVideoTitleForLog()
          + ", positionMs=" + (player == null ? 0L : Math.max(0L, player.getCurrentPosition())));
      updateWatchTimeTracking();
      updatePlaybackUi();
      updateKeepScreenOn();
      scheduleProgressUpdates();
      scheduleLoopUpdates();
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      AppLog.d(TAG, () -> "Playback request changed: playWhenReady=" + playWhenReady
          + ", reason=" + playWhenReadyReasonName(reason));
      updatePlaybackUi();
      updateKeepScreenOn();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      AppLog.e(TAG, "Playback failed: " + error.getErrorCodeName(), error);
      playbackMessage = error.getErrorCodeName();
      updatePlaybackUi();
      updateKeepScreenOn();
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      AppLog.i(TAG, () -> "Video size changed: " + videoSize.width + "x" + videoSize.height
          + ", unappliedRotation=" + videoSize.unappliedRotationDegrees
          + ", pixelRatio=" + videoSize.pixelWidthHeightRatio);
      updatePlaybackUi();
    }
  };

  private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
    @Override
    public void onDisplayAdded(int displayId) {
      AppLog.i(TAG, "Display added: id=" + displayId);
      updatePresentation();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
      AppLog.i(TAG, "Display removed: id=" + displayId);
      updatePresentation();
    }

    @Override
    public void onDisplayChanged(int displayId) {
      AppLog.d(TAG, "Display changed: id=" + displayId);
      updatePresentation();
    }
  };

  private final Vr180Renderer.SurfaceCallback videoSurfaceCallback = new Vr180Renderer.SurfaceCallback() {
    @Override
    public void onVideoSurfaceCreated(int surfaceIndex, Surface surface) {
      if (!isValidSurfaceIndex(surfaceIndex)) {
        AppLog.w(TAG, "Ignoring created video surface with invalid index " + surfaceIndex);
        return;
      }
      AppLog.i(TAG, "Video surface created: index=" + surfaceIndex);
      videoSurfaces[surfaceIndex] = surface;
      if (surfaceIndex == ACTIVE_VIDEO_SURFACE_INDEX) {
        attachSlotSurface(player, surfaceIndex);
      }
      if (presentation != null) {
        presentation.setActiveVideoSurfaceIndex(ACTIVE_VIDEO_SURFACE_INDEX);
      }
      updateStatus();
      updateKeepScreenOn();
    }

    @Override
    public void onVideoSurfaceDestroyed(int surfaceIndex, Surface surface) {
      if (!isValidSurfaceIndex(surfaceIndex) || videoSurfaces[surfaceIndex] != surface) {
        AppLog.w(TAG, "Ignoring destroyed video surface with invalid/stale index " + surfaceIndex);
        return;
      }
      AppLog.i(TAG, "Video surface destroyed: index=" + surfaceIndex);
      if (surfaceIndex == ACTIVE_VIDEO_SURFACE_INDEX && player != null) {
        player.clearVideoSurface(surface);
      }
      videoSurfaceAttachedSurfaces[surfaceIndex] = null;
      videoSurfaces[surfaceIndex] = null;
      updateStatus();
      updateKeepScreenOn();
    }

    @Override
    public void onVideoFrameAvailable(int surfaceIndex) {
      if (!isValidSurfaceIndex(surfaceIndex)) {
        AppLog.w(TAG, "Video frame received for invalid surface index " + surfaceIndex);
        return;
      }
      if (LOG_VERBOSE_STATS) {
        videoFramesSinceLastLog++;
        long now = SystemClock.elapsedRealtime();
        if (now - lastVideoFrameLogElapsedMs >= VERBOSE_STATS_INTERVAL_MS) {
          long windowMs = lastVideoFrameLogElapsedMs == 0L ? 0L : now - lastVideoFrameLogElapsedMs;
          int frames = videoFramesSinceLastLog;
          videoFramesSinceLastLog = 0;
          lastVideoFrameLogElapsedMs = now;
          AppLog.v(TAG, "Video frames: surface=" + surfaceIndex
              + ", frames=" + frames
              + ", windowMs=" + windowMs);
        }
      }
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    AppLog.i(TAG, () -> "onCreate: savedState=" + (savedInstanceState != null));
    settingsPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    installDevelopmentHttpsTrust();
    audioMuted = settingsPreferences.getBoolean(PREF_AUDIO_MUTED, false);
    viewScale = settingsPreferences.getFloat(PREF_VIEW_SCALE, DEFAULT_VIEW_SCALE);
    projectionMode = Vr180Renderer.PROJECTION_EQUIRECT_VR180;
    sceneCenterDegrees = settingsPreferences.getFloat(PREF_SCENE_CENTER_DEGREES, 0.0f);
    horizonDegrees = settingsPreferences.getFloat(PREF_HORIZON_DEGREES, 0.0f);
    windowsRemoteEnabled = settingsPreferences.getBoolean(PREF_WINDOWS_REMOTE_ENABLED, false);
    super.onCreate(savedInstanceState);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    player = createPlayer();
    playerServerApiKey = getServerApiKey();
    attachSlotSurface(player, ACTIVE_VIDEO_SURFACE_INDEX);
    applyAudioMuted();
    nrealManager = new NrealManager(getApplicationContext(), nrealListener);
    recentVideos.addAll(RecentVideoStore.load(this));
    flushPendingServerHistorySyncs();
    AppLog.i(TAG, () -> "Loaded startup state: recentVideos=" + recentVideos.size()
        + ", serverPlayedVideos=" + serverPlayedVideos.size()
        + ", audioMuted=" + audioMuted
        + ", viewScale=" + viewScale
        + ", sceneCenter=" + sceneCenterDegrees
        + ", horizon=" + horizonDegrees);

    savedScenes = new SavedScenesController(this, binding.savedScenesButton, new SavedScenesController.Host() {
      @Override public SavedSceneStore.Target currentTarget() { return currentSceneTarget(); }
      @Override public SavedScene captureScene() { return captureSavedScene(); }
      @Override public void restoreScene(SavedSceneStore.Target target, SavedScene scene,
          Runnable success, java.util.function.Consumer<String> failure) {
        restoreSavedScene(target, scene, success, failure);
      }
    });
    configurePlayer();
    configureControls();
    renderRecentVideos();
    updatePresentation();
    updateStatus();
    updateKeepScreenOn();
    scheduleProgressUpdates();
    scheduleLoopUpdates();
    scheduleWindowsRemotePoll(0L);
  }

  @Override
  protected void onStart() {
    AppLog.i(TAG, "onStart");
    super.onStart();
    activityStarted = true;
    displayManager.registerDisplayListener(displayListener, uiHandler);
    if (!windowsRemoteEnabled) {
      nrealManager.connectToNrealUsbDevice();
    }
    updatePresentation();
    scheduleLoopBoundaryMessage();
    scheduleProgressUpdates();
    scheduleLoopUpdates();
    scheduleWindowsRemotePoll(0L);
  }

  @Override
  protected void onStop() {
    AppLog.i(TAG, "onStop");
    super.onStop();
    activityStarted = false;
    saveCurrentVideoProgress();
    watchTimeTrackingActive = false;
    displayManager.unregisterDisplayListener(displayListener);
    uiHandler.removeCallbacks(progressUpdater);
    uiHandler.removeCallbacks(loopUpdater);
    uiHandler.removeCallbacks(windowsRemotePoller);
    windowsRemotePollScheduled = false;
    progressUpdatesScheduled = false;
    loopUpdatesScheduled = false;
    clearLoopBoundaryMessage();
    updateKeepScreenOn();
  }

  @Override
  protected void onDestroy() {
    AppLog.i(TAG, "onDestroy");
    if (savedScenes != null) savedScenes.close();
    uiHandler.removeCallbacks(progressUpdater);
    uiHandler.removeCallbacks(loopUpdater);
    saveCurrentVideoProgress();
    watchTimeTrackingActive = false;
    dismissPresentation();
    clearLoopBoundaryMessage();
    if (player != null) {
      player.release();
      player = null;
    }
    if (nrealManager != null) {
      nrealManager.destroy();
      nrealManager = null;
    }
    headTracker.close();
    thumbnailExecutor.shutdownNow();
    serverHistoryExecutor.shutdown();
    windowsRemoteExecutor.shutdownNow();
    super.onDestroy();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    AppLog.i(TAG, () -> "onNewIntent: action=" + (intent == null ? "null" : intent.getAction()));
    super.onNewIntent(intent);
    UsbDevice device = getUsbDeviceExtra(intent);
    if (device != null) {
      AppLog.i(TAG, () -> "USB device received from intent: vendorId=" + device.getVendorId()
          + ", productId=" + device.getProductId()
          + ", id=" + device.getDeviceId());
      if (!windowsRemoteEnabled) {
        nrealManager.connectToNrealUsbDevice();
      }
    }
  }

  private void configurePlayer() {
    if (player == null) {
      AppLog.w(TAG, "Cannot configure player listener: player is null");
      return;
    }
    AppLog.d(TAG, "Configuring player listener");
    player.removeListener(activePlayerListener);
    player.addListener(activePlayerListener);
  }

  private void configureControls() {
    AppLog.d(TAG, "Configuring controls");

    binding.windowsRemoteSwitch.setChecked(windowsRemoteEnabled);
    binding.windowsRemoteSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      windowsRemoteEnabled = isChecked;
      settingsPreferences.edit().putBoolean(PREF_WINDOWS_REMOTE_ENABLED, isChecked).apply();
      if (isChecked) {
        enterWindowsRemoteMode();
      } else {
        leaveWindowsRemoteMode();
      }
    });
    String storedRemoteUrl = settingsPreferences.getString(PREF_WINDOWS_REMOTE_URL, "");
    binding.windowsRemoteUrlEditText.setText(storedRemoteUrl);
    binding.windowsRemoteUrlEditText.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
      @Override public void afterTextChanged(Editable s) { }
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        settingsPreferences.edit().putString(PREF_WINDOWS_REMOTE_URL, normalizeServerUrl(String.valueOf(s))).apply();
        if (windowsRemoteEnabled) {
          windowsRemoteState = null;
          windowsRemoteError = "";
          scheduleWindowsRemotePoll(0L);
        }
      }
    });

    binding.serverLibraryButton.setOnClickListener(v -> {
      AppLog.i(TAG, "Opening server library dialog");
      openServerLibraryDialog();
    });
    binding.selectVideoButton.setOnClickListener(v -> {
      if (windowsRemoteEnabled) {
        openServerLibraryDialog();
        return;
      }
      AppLog.i(TAG, "Opening local video picker");
      openVideoLauncher.launch(new String[]{"video/*"});
    });
    binding.playPauseButton.setOnClickListener(v -> {
      if (windowsRemoteEnabled) {
        sendWindowsRemoteCommand(commandJson("playPause"));
        return;
      }
      if (currentVideo == null) {
        AppLog.i(TAG, "Play pressed without current video; opening picker");
        openVideoLauncher.launch(new String[]{"video/*"});
      } else if (player.getPlayWhenReady()) {
        AppLog.i(TAG, () -> "Pause requested: " + currentVideoTitleForLog()
            + ", positionMs=" + Math.max(0L, player.getCurrentPosition()));
        player.pause();
      } else {
        AppLog.i(TAG, () -> "Play requested: " + currentVideoTitleForLog()
            + ", positionMs=" + Math.max(0L, player.getCurrentPosition()));
        player.play();
      }
      updatePlaybackUi();
    });
    binding.rewindButton.setOnClickListener(v -> {
      AppLog.d(TAG, "Rewind requested");
      seekRelative(-SKIP_MS);
    });
    binding.forwardButton.setOnClickListener(v -> {
      AppLog.d(TAG, "Forward requested");
      seekRelative(SKIP_MS);
    });
    binding.abLoopAButton.setOnClickListener(v -> setLoopStartToCurrentPosition());
    binding.abLoopAButton.setOnLongClickListener(v -> {
      clearLoopStart();
      return true;
    });
    binding.abLoopBButton.setOnClickListener(v -> setLoopEndToCurrentPosition());
    binding.abLoopBButton.setOnLongClickListener(v -> {
      clearLoopEnd();
      return true;
    });
    binding.abLoopClearButton.setOnClickListener(v -> clearLoop());
    binding.abLoopClearButton.setOnLongClickListener(v -> {
      toggleLoopPaused();
      return true;
    });
    binding.recenterButton.setOnClickListener(v -> {
      AppLog.i(TAG, "View recenter requested");
      if (windowsRemoteEnabled) {
        sendWindowsRemoteCommand(commandJson("recenter"));
        return;
      }
      headTracker.recenter();
      if (presentation != null) {
        presentation.setHeadRotationMatrix(headTracker.getRotationMatrix());
      }
      lastDeviceMessage = "View recentered";
      updateStatus();
    });
    binding.windowsFullscreenButton.setOnClickListener(v ->
        sendWindowsRemoteCommand(commandJson("fullscreenGlasses")));

    binding.muteAudioSwitch.setChecked(audioMuted);
    binding.muteAudioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (updatingWindowsRemoteUi) {
        return;
      }
      AppLog.i(TAG, "Audio mute changed: muted=" + isChecked);
      if (windowsRemoteEnabled) {
        sendWindowsRemoteCommand(commandJson("muted", isChecked));
        return;
      }
      audioMuted = isChecked;
      settingsPreferences.edit().putBoolean(PREF_AUDIO_MUTED, audioMuted).apply();
      applyAudioMuted();
    });
    binding.serverUrlEditText.setText(settingsPreferences.getString(PREF_SERVER_URL, ""));
    binding.serverUrlEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        String normalizedUrl = normalizeServerUrl(String.valueOf(s));
        AppLog.d(TAG, () -> "Server URL edited: " + normalizedUrl);
        settingsPreferences.edit().putString(PREF_SERVER_URL, normalizedUrl).apply();
        if (windowsRemoteEnabled && settingsPreferences.getString(PREF_WINDOWS_REMOTE_URL, "").trim().isEmpty()) {
          windowsRemoteState = null;
          scheduleWindowsRemotePoll(0L);
        }
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });
    binding.serverApiKeyEditText.setText(settingsPreferences.getString(PREF_SERVER_API_KEY, ""));
    binding.serverApiKeyEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        AppLog.d(TAG, "Server API key edited");
        settingsPreferences.edit().putString(PREF_SERVER_API_KEY, String.valueOf(s).trim()).apply();
        if (windowsRemoteEnabled) {
          windowsRemoteState = null;
          scheduleWindowsRemotePoll(0L);
        }
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });
    binding.clearLocalRecentVideosButton.setOnClickListener(v -> confirmClearRecentVideos(
        "Clear local recent videos?",
        "This removes the local recent video list and saved local playback positions.",
        this::clearLocalRecentVideos));
    binding.clearServerRecentVideosButton.setOnClickListener(v -> confirmClearRecentVideos(
        "Clear server recent videos?",
        "This removes the server played history and saved server playback positions.",
        this::clearServerRecentVideos));

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
        AppLog.d(TAG, () -> "Playback seek started: progress=" + seekBar.getProgress());
        isUserSeeking = true;
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        long duration = getKnownDuration();
        if (duration > 0) {
          long targetMs = duration * seekBar.getProgress() / SEEK_BAR_MAX;
          AppLog.i(TAG, () -> "Playback seek completed: targetMs=" + targetMs
              + ", durationMs=" + duration
              + ", current=" + currentVideoTitleForLog());
          seekToFast(targetMs);
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
      if (windowsRemoteEnabled) {
        binding.viewScaleValue.setText(String.format(Locale.US, "%.2fx", viewScale));
        if (fromUser && !updatingWindowsRemoteUi) {
          sendWindowsRemoteCommand(commandJson("zoom", viewScale));
        }
        return;
      }
      if (fromUser) {
        AppLog.d(TAG, () -> "View scale changed: " + viewScale);
      }
      binding.viewScaleValue.setText(String.format(Locale.US, "%.2fx", viewScale));
      settingsPreferences.edit().putFloat(PREF_VIEW_SCALE, viewScale).apply();
      if (presentation != null) {
        presentation.setZoom(viewScale);
      }
    });
    updateViewScaleText();

    ArrayAdapter<String> projectionAdapter = new ArrayAdapter<>(
        this,
        android.R.layout.simple_spinner_item,
        PROJECTION_MODE_LABELS);
    projectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    binding.projectionModeSpinner.setAdapter(projectionAdapter);
    binding.projectionModeSpinner.setSelection(projectionModeIndex(projectionMode));
    binding.projectionModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        projectionMode = PROJECTION_MODE_VALUES[Math.max(0, Math.min(PROJECTION_MODE_VALUES.length - 1, position))];
        AppLog.i(TAG, () -> "Projection mode selected: " + projectionModeName(projectionMode));
        if (windowsRemoteEnabled) {
          if (!updatingWindowsRemoteUi && windowsRemoteState != null) {
            sendWindowsRemoteCommand(commandJson("projection", projectionMode));
          }
          return;
        }
        saveCurrentProjectionMode();
        applyProjectionMode();
      }

      @Override
      public void onNothingSelected(android.widget.AdapterView<?> parent) {
      }
    });

    binding.sceneCenterSlider.setValueFrom(SCENE_CENTER_MIN_DEGREES);
    binding.sceneCenterSlider.setValueTo(SCENE_CENTER_MAX_DEGREES);
    binding.sceneCenterSlider.setStepSize(1.0f);
    binding.sceneCenterSlider.setValue(clamp(sceneCenterDegrees, SCENE_CENTER_MIN_DEGREES, SCENE_CENTER_MAX_DEGREES));
    binding.sceneCenterSlider.setLabelFormatter(value -> formatDegrees(value));
    binding.sceneCenterSlider.addOnChangeListener((slider, value, fromUser) -> {
      sceneCenterDegrees = value;
      if (windowsRemoteEnabled) {
        updateViewPositionControls();
        if (fromUser && !updatingWindowsRemoteUi) {
          sendWindowsRemoteCommand(commandJson("yaw", sceneCenterDegrees));
        }
        return;
      }
      if (fromUser) {
        AppLog.d(TAG, () -> "Scene center changed: " + sceneCenterDegrees);
      }
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
      if (windowsRemoteEnabled) {
        updateViewPositionControls();
        if (fromUser && !updatingWindowsRemoteUi) {
          sendWindowsRemoteCommand(commandJson("horizon", horizonDegrees));
        }
        return;
      }
      if (fromUser) {
        AppLog.d(TAG, () -> "Horizon changed: " + horizonDegrees);
      }
      updateViewPositionControls();
      applyViewPosition();
      saveProjectionSettings();
    });

    binding.resetViewPositionButton.setOnClickListener(v -> {
      AppLog.i(TAG, "View position reset requested");
      if (windowsRemoteEnabled) {
        sendWindowsRemoteCommand(commandJson("resetView"));
        return;
      }
      sceneCenterDegrees = 0.0f;
      horizonDegrees = 0.0f;
      binding.sceneCenterSlider.setValue(0.0f);
      binding.horizonSlider.setValue(0.0f);
      updateViewPositionControls();
      applyViewPosition();
      saveProjectionSettings();
    });
    updateViewPositionControls();
    updateWindowsRemoteModeControls();

    updatePlaybackUi();
  }

  private void enterWindowsRemoteMode() {
    AppLog.i(TAG, "Entering Windows remote mode");
    saveCurrentVideoProgress();

    // Remote control is a temporary UI/playback mode. Keep the local MediaItem loaded so
    // toggling back to Android playback is reversible and does not require reopening it.
    if (!localPlaybackSuspendedForWindowsRemote) {
      suspendedLocalVideo = currentVideo;
      suspendedLocalVideoFromServer = currentVideoFromServer;
      suspendedLocalServerVideo = currentServerVideo;
      suspendedLocalLoopStartMs = loopStartMs;
      suspendedLocalLoopEndMs = loopEndMs;
      suspendedLocalLoopPaused = loopPaused;
      suspendedLocalProjectionMode = projectionMode;
      localPlaybackSuspendedForWindowsRemote = true;
    }
    if (player != null) {
      player.pause();
    }

    // Hide the suspended local session from all remote-mode UI/state paths. The ExoPlayer
    // item itself intentionally stays loaded and paused.
    currentVideo = null;
    currentVideoFromServer = false;
    currentServerVideo = null;
    resetLoop();
    dismissPresentation();
    if (nrealManager != null) {
      nrealManager.closeNrealUsbDevice();
    }

    windowsRemoteState = null;
    windowsRemoteError = "";
    updateWindowsRemoteModeControls();
    renderRecentVideos();
    applyWindowsRemoteUi();
    scheduleWindowsRemotePoll(0L);
  }

  private void leaveWindowsRemoteMode() {
    AppLog.i(TAG, "Leaving Windows remote mode");
    uiHandler.removeCallbacks(windowsRemotePoller);
    windowsRemotePollScheduled = false;
    windowsRemoteState = null;
    windowsRemoteError = "";

    // Restore the exact Android playback session that was suspended when remote mode was
    // entered. It remains paused; the user explicitly chooses when to resume it.
    if (localPlaybackSuspendedForWindowsRemote) {
      currentVideo = suspendedLocalVideo;
      currentVideoFromServer = suspendedLocalVideoFromServer;
      currentServerVideo = suspendedLocalServerVideo;
      loopStartMs = suspendedLocalLoopStartMs;
      loopEndMs = suspendedLocalLoopEndMs;
      loopPaused = suspendedLocalLoopPaused;
      projectionMode = sanitizeProjectionMode(suspendedLocalProjectionMode);
    }
    suspendedLocalVideo = null;
    suspendedLocalVideoFromServer = false;
    suspendedLocalServerVideo = null;
    suspendedLocalLoopStartMs = C.TIME_UNSET;
    suspendedLocalLoopEndMs = C.TIME_UNSET;
    suspendedLocalLoopPaused = false;
    suspendedLocalProjectionMode = Vr180Renderer.PROJECTION_EQUIRECT_VR180;
    localPlaybackSuspendedForWindowsRemote = false;

    // Remote state temporarily owns these shared controls. Restore every local value, not
    // just their labels, so no Windows state leaks back into Android playback.
    viewScale = settingsPreferences.getFloat(PREF_VIEW_SCALE, DEFAULT_VIEW_SCALE);
    sceneCenterDegrees = settingsPreferences.getFloat(PREF_SCENE_CENTER_DEGREES, 0.0f);
    horizonDegrees = settingsPreferences.getFloat(PREF_HORIZON_DEGREES, 0.0f);
    audioMuted = settingsPreferences.getBoolean(PREF_AUDIO_MUTED, false);
    updatingWindowsRemoteUi = true;
    try {
      binding.viewScaleSlider.setValue(clamp(viewScale * 100.0f, SCALE_SLIDER_MIN, SCALE_SLIDER_MAX));
      binding.sceneCenterSlider.setValue(clamp(sceneCenterDegrees, SCENE_CENTER_MIN_DEGREES, SCENE_CENTER_MAX_DEGREES));
      binding.horizonSlider.setValue(clamp(horizonDegrees, HORIZON_MIN_DEGREES, HORIZON_MAX_DEGREES));
      binding.projectionModeSpinner.setSelection(projectionModeIndex(projectionMode), false);
      binding.muteAudioSwitch.setChecked(audioMuted);
    } finally {
      updatingWindowsRemoteUi = false;
    }

    applyAudioMuted();
    updateWindowsRemoteModeControls();
    renderRecentVideos();
    updateViewScaleText();
    updateViewPositionControls();
    updatePresentation();
    if (activityStarted && nrealManager != null && !nrealManager.isDeviceConnected()) {
      nrealManager.connectToNrealUsbDevice();
    }
    updatePlaybackUi();
  }

  private void updateWindowsRemoteModeControls() {
    if (binding == null) {
      return;
    }
    binding.selectVideoButton.setText(windowsRemoteEnabled ? "Library" : "Select");
    binding.windowsFullscreenButton.setVisibility(windowsRemoteEnabled ? View.VISIBLE : View.GONE);
    if (!windowsRemoteEnabled) {
      binding.windowsRemoteStatusText.setText("Windows remote: off");
    } else if (windowsRemoteError.length() > 0) {
      binding.windowsRemoteStatusText.setText("Windows remote: " + windowsRemoteError);
    } else if (windowsRemoteState == null) {
      binding.windowsRemoteStatusText.setText("Windows remote: connecting to " + resolveWindowsRemoteUrl());
    } else {
      binding.windowsRemoteStatusText.setText("Windows remote: connected to " + resolveWindowsRemoteUrl());
    }
  }

  private void scheduleWindowsRemotePoll(long delayMs) {
    if (!windowsRemoteEnabled || !activityStarted || binding == null) {
      return;
    }
    if (delayMs <= 0L) {
      uiHandler.removeCallbacks(windowsRemotePoller);
      windowsRemotePollScheduled = false;
    }
    if (windowsRemotePollScheduled) {
      return;
    }
    windowsRemotePollScheduled = true;
    uiHandler.postDelayed(windowsRemotePoller, Math.max(0L, delayMs));
  }

  private String resolveWindowsRemoteUrl() {
    String configured = normalizeServerUrl(settingsPreferences == null
        ? ""
        : settingsPreferences.getString(PREF_WINDOWS_REMOTE_URL, ""));
    if (configured.length() > 0) {
      return configured;
    }
    String serverUrl = normalizeServerUrl(settingsPreferences == null
        ? ""
        : settingsPreferences.getString(PREF_SERVER_URL, ""));
    if (serverUrl.length() == 0) {
      return "";
    }
    Uri uri = Uri.parse(serverUrl);
    String host = uri.getHost();
    if (host == null || host.length() == 0) {
      return "";
    }
    if (host.contains(":")) {
      host = "[" + host + "]";
    }
    return "http://" + host + ":57050";
  }

  private String resolveWindowsSceneServerUrl() {
    return normalizeServerUrl(settingsPreferences == null
        ? ""
        : settingsPreferences.getString(PREF_SERVER_URL, ""));
  }

  private void sendWindowsRemoteCommand(JSONObject command) {
    if (!windowsRemoteEnabled || command == null) {
      return;
    }
    final String remoteUrl = resolveWindowsRemoteUrl();
    final String apiKey = getServerApiKey();
    if (remoteUrl.length() == 0 || apiKey.length() == 0) {
      windowsRemoteError = remoteUrl.length() == 0 ? "Set Windows remote URL" : "Set server API key";
      applyWindowsRemoteUi();
      return;
    }
    windowsRemoteExecutor.execute(() -> {
      try {
        WindowsRemoteClient.sendCommand(remoteUrl, apiKey, command);
        uiHandler.post(() -> {
          windowsRemoteError = "";
          scheduleWindowsRemotePoll(0L);
        });
      } catch (Exception error) {
        uiHandler.post(() -> {
          windowsRemoteError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
          applyWindowsRemoteUi();
          scheduleWindowsRemotePoll(800L);
        });
      }
    });
  }

  private static JSONObject commandJson(String action) {
    return commandJson(action, (Object) null);
  }

  private static JSONObject commandJson(String action, double value) {
    return commandJson(action, Double.valueOf(value));
  }

  private static JSONObject commandJson(String action, boolean value) {
    return commandJson(action, Boolean.valueOf(value));
  }

  private static JSONObject commandJson(String action, Object value) {
    JSONObject command = new JSONObject();
    try {
      command.put("action", action);
      if (value != null) {
        command.put("value", value);
      }
    } catch (JSONException ignored) {
      return null;
    }
    return command;
  }

  private static JSONObject commandJson(String action, String key, String value) {
    JSONObject command = commandJson(action);
    if (command == null) {
      return null;
    }
    try {
      command.put(key, value);
    } catch (JSONException ignored) {
      return null;
    }
    return command;
  }

  private void applyWindowsRemoteUi() {
    if (!windowsRemoteEnabled || binding == null) {
      return;
    }
    updateWindowsRemoteModeControls();
    if (savedScenes != null) savedScenes.update();
    WindowsRemoteClient.State state = windowsRemoteState;
    boolean hasMedia = state != null && state.hasMedia();
    binding.rewindButton.setEnabled(hasMedia);
    binding.forwardButton.setEnabled(hasMedia);
    binding.progressSeekBar.setEnabled(hasMedia);
    binding.playPauseButton.setEnabled(state != null);
    binding.playPauseButton.setText(state != null && state.playing ? "Pause" : "Play");
    binding.playPauseButton.setIconResource(state != null && state.playing
        ? R.drawable.ic_pause_24
        : R.drawable.ic_play_arrow_24);
    binding.currentVideoTitle.setText(hasMedia ? state.media : "Windows player");

    if (state != null) {
      ServerVideo selected = findServerVideo(serverVideos, state.currentServerId);
      if (selected == null) {
        selected = findServerVideo(serverPlayedVideos, state.currentServerId);
      }
      currentServerVideo = selected;
      updatingWindowsRemoteUi = true;
      try {
        viewScale = clamp(state.zoom, 0.60f, 1.80f);
        float scalePercent = Math.round((viewScale * 100.0f) / SCALE_SLIDER_STEP) * SCALE_SLIDER_STEP;
        binding.viewScaleSlider.setValue(clamp(scalePercent, SCALE_SLIDER_MIN, SCALE_SLIDER_MAX));
        binding.viewScaleValue.setText(String.format(Locale.US, "%.2fx", viewScale));
        sceneCenterDegrees = clamp(state.yawDegrees, SCENE_CENTER_MIN_DEGREES, SCENE_CENTER_MAX_DEGREES);
        horizonDegrees = clamp(state.horizonDegrees, HORIZON_MIN_DEGREES, HORIZON_MAX_DEGREES);
        binding.sceneCenterSlider.setValue(Math.round(sceneCenterDegrees));
        binding.horizonSlider.setValue(Math.round(horizonDegrees));
        projectionMode = sanitizeProjectionMode(state.projectionMode);
        binding.projectionModeSpinner.setSelection(projectionModeIndex(projectionMode));
        binding.muteAudioSwitch.setChecked(state.muted);
      } finally {
        updatingWindowsRemoteUi = false;
      }
      updateViewPositionControls();
    }
    applyWindowsRemoteProgress();
    updateLoopControls();
    applyWindowsRemoteStatus();
    updateKeepScreenOn();
    renderServerDialogRows();
  }

  private void applyWindowsRemoteProgress() {
    if (!windowsRemoteEnabled || binding == null) {
      return;
    }
    WindowsRemoteClient.State state = windowsRemoteState;
    long duration = state == null ? 0L : state.durationMs;
    long position = state == null ? 0L : state.positionMs;
    if (!isUserSeeking) {
      if (duration > 0L) {
        binding.progressSeekBar.setProgress((int) Math.min(SEEK_BAR_MAX, position * SEEK_BAR_MAX / duration));
      } else {
        binding.progressSeekBar.setProgress(0);
      }
      binding.positionText.setText(formatPosition(position, duration));
    }
    updateAbLoopTimeline(duration);
  }

  private void applyWindowsRemoteStatus() {
    if (!windowsRemoteEnabled || binding == null) {
      return;
    }
    WindowsRemoteClient.State state = windowsRemoteState;
    if (state == null) {
      String message = windowsRemoteError.length() == 0 ? "Connecting" : windowsRemoteError;
      binding.playbackStatusText.setText("Windows: " + message);
      binding.glassesStatusText.setText("Glasses: connected to Windows PC");
      binding.outputStatusText.setText("Output: Windows DisplayPort");
      binding.trackingStatusText.setText("Tracking: waiting for Windows state");
      return;
    }
    String playback = state.hasMedia() ? (state.playing ? "Playing" : "Paused") : "Idle";
    if (state.outputWidth > 0 && state.outputHeight > 0) {
      playback += String.format(Locale.US, " - %dx%d", state.outputWidth, state.outputHeight);
    }
    if (state.renderFps > 0.0f) {
      playback += String.format(Locale.US, " - %.0f FPS", state.renderFps);
    }
    binding.playbackStatusText.setText("Windows: " + playback);
    binding.glassesStatusText.setText("Glasses: driven directly by Windows/XREAL USB + DisplayPort");
    binding.outputStatusText.setText(state.outputWidth > 0 && state.outputHeight > 0
        ? String.format(Locale.US, "Output: Windows %dx%d, %s", state.outputWidth, state.outputHeight, state.stereo ? "SBS stereo" : "mono")
        : "Output: Windows DisplayPort");
    binding.trackingStatusText.setText("Tracking: " + (state.tracking.length() == 0 ? "Windows" : state.tracking));
  }

  private void onVideoSelected(Uri uri) {
    if (uri == null) {
      AppLog.d(TAG, "Video picker returned no URI");
      return;
    }
    AppLog.i(TAG, () -> "Video selected: uri=" + uri);
    try {
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      AppLog.d(TAG, "Persisted read permission for selected video");
    } catch (SecurityException ignored) {
      AppLog.w(TAG, () -> "Could not persist read permission for selected video: uri=" + uri);
      // Some providers grant a one-session URI. Playback still works for this launch.
    }

    RecentVideo video = findRecentVideo(uri);
    if (video == null) {
      String title = queryDisplayName(uri);
      video = new RecentVideo(uri, title, querySize(uri), 0L, queryDuration(uri), ProjectionModeGuesser.guess(title));
      final RecentVideo createdVideo = video;
      AppLog.i(TAG, () -> "Created recent video entry: title=" + title
          + ", size=" + createdVideo.size
          + ", durationMs=" + createdVideo.durationMs
          + ", projection=" + projectionModeName(createdVideo.projectionMode));
    } else {
      final RecentVideo existingVideo = video;
      AppLog.i(TAG, () -> "Reusing recent video entry: title=" + existingVideo.title
          + ", size=" + existingVideo.size
          + ", durationMs=" + existingVideo.durationMs
          + ", lastPositionMs=" + existingVideo.lastPositionMs);
      if (video.size <= 0L) {
        video.size = querySize(uri);
      }
      if (video.durationMs <= 0L) {
        video.durationMs = queryDuration(uri);
      }
    }
    RecentVideoStore.upsert(this, recentVideos, video);
    renderRecentVideos();
    playLocalVideo(video);
  }

  private ExoPlayer createPlayer() {
    AppLog.d(TAG, "Creating default player");
    return createPlayer(false);
  }

  private ExoPlayer createPlayer(boolean constrainedBuffers) {
    int minBufferMs = constrainedBuffers ? PLAYER_CONSTRAINED_MIN_BUFFER_MS : PLAYER_MIN_BUFFER_MS;
    int maxBufferMs = constrainedBuffers ? PLAYER_CONSTRAINED_MAX_BUFFER_MS : PLAYER_MAX_BUFFER_MS;
    int backBufferMs = constrainedBuffers ? PLAYER_CONSTRAINED_BACK_BUFFER_MS : PLAYER_BACK_BUFFER_MS;
    AppLog.i(TAG, () -> "Creating player: constrainedBuffers=" + constrainedBuffers
        + ", minBufferMs=" + minBufferMs
        + ", maxBufferMs=" + maxBufferMs
        + ", backBufferMs=" + backBufferMs);
    DefaultLoadControl.Builder loadControlBuilder = new DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            PLAYER_BUFFER_FOR_PLAYBACK_MS,
            PLAYER_BUFFER_FOR_REBUFFER_MS)
        .setBackBuffer(backBufferMs, !constrainedBuffers)
        .setPrioritizeTimeOverSizeThresholds(!constrainedBuffers);
    if (constrainedBuffers) {
      loadControlBuilder.setTargetBufferBytes(PLAYER_CONSTRAINED_TARGET_BUFFER_BYTES);
    }
    DefaultLoadControl loadControl = loadControlBuilder.build();
    return new ExoPlayer.Builder(this, createRenderersFactory())
        .setLoadControl(loadControl)
        .setPriority(C.PRIORITY_PLAYBACK)
        .setPriorityTaskManager(playbackPriorityTaskManager)
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
        .setDataSourceFactory(createDataSourceFactory()))
        .build();
  }


  private DefaultRenderersFactory createRenderersFactory() {
    AppLog.d(TAG, "Creating renderers factory with async codec queueing");
    return new DefaultRenderersFactory(this).forceEnableMediaCodecAsynchronousQueueing();
  }

  private DataSource.Factory createDataSourceFactory() {
    AppLog.d(TAG, "Creating data source factory");
    DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
        .setUserAgent("AirVrPlayer/1.0")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8000)
        .setReadTimeoutMs(30000);
    String apiKey = getServerApiKey();
    if (apiKey.length() > 0) {
      httpDataSourceFactory.setDefaultRequestProperties(Collections.singletonMap("X-API-Key", apiKey));
    }
    return new DefaultDataSource.Factory(this, httpDataSourceFactory);
  }

  private void playLocalVideo(RecentVideo video) {
    AppLog.i(TAG, () -> "Local playback requested: " + video.title);
    saveCurrentVideoProgress();
    currentVideo = null;
    currentVideoFromServer = false;
    currentServerVideo = null;
    playVideo(video);
  }

  private void playVideo(RecentVideo video) {
    AppLog.i(TAG, () -> "Starting playback: title=" + video.title
        + ", uri=" + video.uri
        + ", startPositionMs=" + video.lastPositionMs
        + ", durationMs=" + video.durationMs
        + ", size=" + video.size
        + ", projection=" + projectionModeName(video.projectionMode)
        + ", fromServer=" + currentVideoFromServer);
    currentVideo = video;
    resetWatchTimeTracking();
    projectionMode = sanitizeProjectionMode(video.projectionMode);
    updateProjectionModeControl();
    playbackMessage = "";
    resetLoop();
    binding.currentVideoTitle.setText(video.title);
    preparePlayerForVideoPlayback(video);
    player.setMediaItem(MediaItem.fromUri(video.uri), Math.max(0L, video.lastPositionMs));
    player.prepare();
    player.play();
    if (currentVideoFromServer && currentServerVideo != null) {
      loopStartMs = currentServerVideo.loopStartMs;
      loopEndMs = currentServerVideo.loopEndMs;
      onLoopPointsChanged();
    }
    headTracker.startViewingSession();
    updatePlaybackUi();
    if (!currentVideoFromServer) {
      renderRecentVideos();
    }
    scheduleProgressUpdates();
    scheduleLoopUpdates();
  }

  private void playServerVideo(ServerVideo serverVideo) {
    AppLog.i(TAG, () -> "Server playback requested: id=" + serverVideo.id
        + ", title=" + serverVideo.displayTitle()
        + ", uri=" + serverVideo.uri
        + ", lastPositionMs=" + serverVideo.lastPositionMs
        + ", windowsRemote=" + windowsRemoteEnabled);
    if (windowsRemoteEnabled) {
      currentServerVideo = serverVideo;
      upsertServerPlayedVideo(serverVideo);
      closeServerDialog();
      renderServerDialogRows();
      sendWindowsRemoteCommand(commandJson("playServerId", "id", serverVideo.id));
      return;
    }
    saveCurrentVideoProgress();
    currentVideo = null;
    currentVideoFromServer = true;
    currentServerVideo = serverVideo;
    upsertServerPlayedVideo(serverVideo);
    queueServerHistorySync(serverVideo, true);
    lastServerHistorySyncElapsedMs = SystemClock.elapsedRealtime();
    RecentVideo video = new RecentVideo(
        serverVideo.uri,
        serverVideo.displayTitle(),
        serverVideo.size,
        serverVideo.lastPositionMs,
        serverVideo.watchedTimeMs,
        serverVideo.durationMs,
        serverVideo.projectionMode);
    playVideo(video);
    closeServerDialog();
    renderServerDialogRows();
  }

  private void preparePlayerForVideoPlayback(RecentVideo video) {
    releaseThumbnailMemoryForPlayback();
    boolean constrainedBuffers = shouldConstrainPlayerBuffers(video);
    String serverApiKey = getServerApiKey();
    if (player != null && playerUsesConstrainedBuffers == constrainedBuffers && playerServerApiKey.equals(serverApiKey)) {
      AppLog.d(TAG, () -> "Keeping current player buffer profile: constrained=" + playerUsesConstrainedBuffers);
      attachSlotSurface(player, ACTIVE_VIDEO_SURFACE_INDEX);
      return;
    }
    ExoPlayer oldPlayer = player;
    if (oldPlayer != null) {
      AppLog.i(TAG, () -> "Recreating player for buffer profile: constrained=" + constrainedBuffers);
      oldPlayer.removeListener(activePlayerListener);
      clearSlotSurface(oldPlayer, ACTIVE_VIDEO_SURFACE_INDEX);
      oldPlayer.release();
    }
    player = createPlayer(constrainedBuffers);
    playerUsesConstrainedBuffers = constrainedBuffers;
    playerServerApiKey = serverApiKey;
    attachSlotSurface(player, ACTIVE_VIDEO_SURFACE_INDEX);
    applyAudioMuted();
    configurePlayer();
  }

  private boolean shouldConstrainPlayerBuffers(RecentVideo video) {
    if (video == null || video.size <= 0L || video.durationMs <= 0L) {
      return false;
    }
    long bitrateBps = video.size * 8_000L / video.durationMs;
    boolean constrained = bitrateBps >= CONSTRAINED_BUFFER_MIN_BITRATE_BPS;
    AppLog.d(TAG, () -> "Estimated bitrate: title=" + video.title
        + ", bitrateBps=" + bitrateBps
        + ", constrainedBuffers=" + constrained);
    return constrained;
  }

  private void releaseThumbnailMemoryForPlayback() {
    thumbnailLoadGeneration++;
    thumbnailsInFlight.clear();
    synchronized (thumbnailCache) {
      thumbnailCache.evictAll();
    }
    AppLog.d(TAG, () -> "Released thumbnail memory for playback: generation=" + thumbnailLoadGeneration);
  }

  private void closeServerDialog() {
    if (serverDialog != null && serverDialog.isShowing()) {
      AppLog.d(TAG, "Closing server dialog");
      serverDialog.dismiss();
    }
  }

  private void loadServerVideos(MaterialButton refreshButton, boolean manualRefresh) {
    serverRefreshPollToken++;
    AppLog.i(TAG, () -> "Server video load requested: manualRefresh=" + manualRefresh
        + ", token=" + serverRefreshPollToken);
    loadServerVideos(refreshButton, manualRefresh, 0, serverRefreshPollToken);
  }

  private void loadServerVideos(MaterialButton refreshButton, boolean manualRefresh, int pollAttempt, int pollToken) {
    String rawServerUrl = binding.serverUrlEditText == null ? "" : String.valueOf(binding.serverUrlEditText.getText());
    String serverUrl = normalizeServerUrl(rawServerUrl);
    if (serverUrl.length() == 0) {
      AppLog.w(TAG, "Server video load skipped: missing server URL");
      setServerDialogStatus("Set a server URL in Settings");
      return;
    }
    AppLog.i(TAG, () -> "Loading server videos: url=" + serverUrl
        + ", manualRefresh=" + manualRefresh
        + ", pollAttempt=" + pollAttempt
        + ", token=" + pollToken);
    settingsPreferences.edit().putString(PREF_SERVER_URL, serverUrl).apply();
    binding.serverUrlEditText.setText(serverUrl);
    if (refreshButton != null && pollAttempt == 0) {
      refreshButton.setEnabled(false);
    }
    if (pollAttempt == 0) {
      setServerDialogStatus(manualRefresh ? "Refreshing server videos..." : "Loading server videos...");
    }

    new Thread(() -> {
      try {
        ServerVideoLoadResult result = fetchServerVideosWithFallback(serverUrl, manualRefresh, pollAttempt);
        List<ServerVideo> videos = result.videos;
        String loadedServerUrl = result.serverUrl;
        uiHandler.post(() -> {
          if (pollToken != serverRefreshPollToken) {
            AppLog.d(TAG, () -> "Ignoring stale server video load result: token=" + pollToken
                + ", activeToken=" + serverRefreshPollToken);
            return;
          }
          if (!loadedServerUrl.equals(serverUrl)) {
            settingsPreferences.edit().putString(PREF_SERVER_URL, loadedServerUrl).apply();
            binding.serverUrlEditText.setText(loadedServerUrl);
          }
          serverVideos.clear();
          serverVideos.addAll(videos);
          serverPlayedVideos.clear();
          serverPlayedVideos.addAll(result.playedVideos);
          serverVideosLoadedThisSession = true;
          serverLoadedUrlThisSession = loadedServerUrl;
          AppLog.i(TAG, () -> "Server videos loaded: count=" + videos.size()
              + ", url=" + loadedServerUrl
              + ", refreshing=" + result.refreshing
              + ", cacheAgeSeconds=" + result.cacheAgeSeconds
              + ", pollAttempt=" + pollAttempt);
          setServerDialogStatus(videos.size() + " server videos" + (result.refreshing ? " (updating)" : ""));
          if (refreshButton != null) {
            refreshButton.setEnabled(true);
          }
          renderServerDialogRows();
          jumpToLastServerVideo();
          if (result.refreshing && pollAttempt < SERVER_REFRESH_POLL_MAX_ATTEMPTS) {
            scheduleServerRefreshPoll(refreshButton, pollToken, pollAttempt + 1);
          }
        });
      } catch (IOException | JSONException e) {
        AppLog.w(TAG, "Server video load failed: url=" + serverUrl
            + ", manualRefresh=" + manualRefresh
            + ", pollAttempt=" + pollAttempt, e);
        uiHandler.post(() -> {
          if (pollToken != serverRefreshPollToken) {
            AppLog.d(TAG, () -> "Ignoring stale server video load failure: token=" + pollToken
                + ", activeToken=" + serverRefreshPollToken);
            return;
          }
          if (pollAttempt == 0) {
            setServerDialogStatus("Server load failed: " + e.getMessage());
          }
          if (refreshButton != null) {
            refreshButton.setEnabled(true);
          }
          if (pollAttempt == 0) {
            renderServerDialogRows();
          }
        });
      }
    }, "server-video-loader").start();
  }

  private void scheduleServerRefreshPoll(MaterialButton refreshButton, int pollToken, int pollAttempt) {
    AppLog.d(TAG, () -> "Scheduling server refresh poll: token=" + pollToken
        + ", pollAttempt=" + pollAttempt
        + ", delayMs=" + SERVER_REFRESH_POLL_DELAY_MS);
    uiHandler.postDelayed(() -> {
      if (pollToken != serverRefreshPollToken || serverDialog == null || !serverDialog.isShowing()) {
        AppLog.d(TAG, () -> "Skipping server refresh poll: token=" + pollToken
            + ", activeToken=" + serverRefreshPollToken
            + ", dialogOpen=" + (serverDialog != null && serverDialog.isShowing()));
        return;
      }
      loadServerVideos(refreshButton, false, pollAttempt, pollToken);
    }, SERVER_REFRESH_POLL_DELAY_MS);
  }

  private ServerVideoLoadResult fetchServerVideosWithFallback(String serverUrl, boolean manualRefresh, int pollAttempt) throws IOException, JSONException {
    try {
        ServerVideoLoadResult result = fetchServerVideos(serverUrl, manualRefresh, pollAttempt);
      return new ServerVideoLoadResult(serverUrl, result.videos, result.playedVideos, result.refreshing, result.cacheAgeSeconds);
    } catch (IOException firstError) {
      String fallbackUrl = localHttpFallbackUrl(serverUrl);
      if (fallbackUrl.length() == 0 || fallbackUrl.equals(serverUrl)) {
        throw firstError;
      }
      AppLog.w(TAG, "Primary server video fetch failed; trying HTTP fallback: primary=" + serverUrl
          + ", fallback=" + fallbackUrl, firstError);
      try {
        ServerVideoLoadResult result = fetchServerVideos(fallbackUrl, manualRefresh, pollAttempt);
        AppLog.i(TAG, () -> "HTTP fallback server fetch succeeded: fallback=" + fallbackUrl);
        return new ServerVideoLoadResult(fallbackUrl, result.videos, result.playedVideos, result.refreshing, result.cacheAgeSeconds);
      } catch (IOException | JSONException fallbackError) {
        firstError.addSuppressed(fallbackError);
        throw firstError;
      }
    }
  }

  private ServerVideoLoadResult fetchServerVideos(String serverUrl, boolean manualRefresh, int pollAttempt) throws IOException, JSONException {
    String refreshQuery = "/videos?refresh=background";
    if (manualRefresh && pollAttempt == 0) {
      refreshQuery += "&force=1&wait_ms=" + SERVER_REFRESH_WAIT_MS;
    }
    URL url = new URL(serverUrl + refreshQuery);
    long startedMs = SystemClock.elapsedRealtime();
    AppLog.d(TAG, () -> "Fetching server videos: " + url);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(15000);
    setServerApiKeyHeader(connection);
    int statusCode = connection.getResponseCode();
    InputStream stream = statusCode >= 200 && statusCode < 300
        ? connection.getInputStream()
        : connection.getErrorStream();
    String body = readFully(stream);
    connection.disconnect();
    long elapsedMs = SystemClock.elapsedRealtime() - startedMs;
    AppLog.d(TAG, () -> "Server videos response: status=" + statusCode
        + ", bytes=" + body.length()
        + ", elapsedMs=" + elapsedMs
        + ", url=" + url);
    if (statusCode < 200 || statusCode >= 300) {
      throw new IOException("HTTP " + statusCode + (body.length() > 0 ? ": " + body : ""));
    }

    JSONObject root = new JSONObject(body);
    JSONArray items = root.optJSONArray("videos");
    List<ServerVideo> videos = new ArrayList<>();
    if (items == null) {
      AppLog.w(TAG, "Server video response did not include a videos array");
      return new ServerVideoLoadResult(serverUrl, videos, new ArrayList<>(), root.optBoolean("refreshing", false), root.optLong("cache_age_seconds", -1L));
    }
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null) {
        continue;
      }
      ServerVideo video = parseServerVideo(item);
      if (video != null) {
        videos.add(video);
      }
    }
    List<ServerVideo> playedVideos = new ArrayList<>();
    JSONArray historyItems = root.optJSONArray("history");
    if (historyItems != null) {
      for (int i = 0; i < historyItems.length(); i++) {
        ServerVideo video = parseServerVideo(historyItems.optJSONObject(i));
        if (video != null) {
          playedVideos.add(video);
        }
      }
    }
    for (ServerVideo playedVideo : playedVideos) {
      ServerVideo video = findServerVideo(videos, playedVideo.id);
      if (video != null) {
        video.lastPositionMs = playedVideo.lastPositionMs;
        video.watchedTimeMs = playedVideo.watchedTimeMs;
        video.projectionMode = playedVideo.projectionMode;
        video.loopStartMs = playedVideo.loopStartMs;
        video.loopEndMs = playedVideo.loopEndMs;
      }
    }
    return new ServerVideoLoadResult(serverUrl, videos, playedVideos, root.optBoolean("refreshing", false), root.optLong("cache_age_seconds", -1L));
  }

  private ServerVideo parseServerVideo(JSONObject item) {
    if (item == null) {
      return null;
    }
    String title = item.optString("title", "Server video");
    String streamUrl = item.optString("stream_url", "");
    if (streamUrl.length() == 0) {
      AppLog.w(TAG, () -> "Skipping server video without stream URL: title=" + title);
      return null;
    }
    return new ServerVideo(
        item.optString("id", streamUrl),
        title,
        item.optString("file_name", title),
        item.optString("subfolder", ""),
        Uri.parse(streamUrl),
        item.optString("thumbnail_url", ""),
        item.optLong("size", 0L),
        item.optLong("duration_ms", 0L),
        parseModifiedMillis(item.optString("modified", "")),
        item.optString("file_date", ""),
        item.optBoolean("flagged_for_deletion", false),
        item.optLong("position_ms", 0L),
        item.optLong("watched_ms", 0L),
        item.optInt("projection_mode", ProjectionModeGuesser.guess(joinParts(java.util.Arrays.asList(
            item.optString("file_name", title),
            item.optString("subfolder", ""),
            title)))),
        optionalJsonLong(item, "loop_start_ms"),
        optionalJsonLong(item, "loop_end_ms")
    );
  }

  private static long optionalJsonLong(JSONObject item, String key) {
    return !item.has(key) || item.isNull(key) ? C.TIME_UNSET : Math.max(0L, item.optLong(key, 0L));
  }

  private void seekRelative(long deltaMs) {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("seekRelative", deltaMs / 1000.0));
      return;
    }
    if (currentVideo == null) {
      AppLog.d(TAG, () -> "Relative seek ignored: no current video, deltaMs=" + deltaMs);
      return;
    }
    long duration = getKnownDuration();
    long target = Math.max(0L, player.getCurrentPosition() + deltaMs);
    if (duration > 0) {
      target = Math.min(duration, target);
    }
    final long targetMs = target;
    AppLog.i(TAG, () -> "Relative seek: deltaMs=" + deltaMs
        + ", targetMs=" + targetMs
        + ", durationMs=" + duration
        + ", current=" + currentVideoTitleForLog());
    seekToFast(target);
    updatePlaybackProgress();
    scheduleProgressUpdates();
  }

  private void seekToFast(long positionMs) {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("seekAbsolute", positionMs / 1000.0));
      return;
    }
    if (player == null) {
      AppLog.w(TAG, () -> "Seek ignored: player is null, positionMs=" + positionMs);
      return;
    }
    AppLog.d(TAG, () -> "Seeking player: positionMs=" + positionMs
        + ", loopReady=" + isLoopReady()
        + ", current=" + currentVideoTitleForLog());
    updateWatchTimeTracking();
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
    player.seekTo(positionMs);
    resetWatchTimeTrackingAnchor();
    if (isLoopActive()) {
      clearLoopBoundaryMessage();
      scheduleLoopBoundaryMessage();
    }
  }

  private void seekToLoopStart() {
    performLoopRestart();
  }

  private void performLoopRestart() {
    if (!isLoopActive() || player == null || loopRestartPending) {
      AppLog.d(TAG, () -> "Loop restart skipped: loopActive=" + isLoopActive()
          + ", playerPresent=" + (player != null)
          + ", pending=" + loopRestartPending);
      return;
    }
    AppLog.d(TAG, () -> "Loop restart: startMs=" + loopStartMs
        + ", endMs=" + loopEndMs
        + ", current=" + currentVideoTitleForLog());
    updateWatchTimeTracking();
    loopRestartPending = true;
    clearLoopBoundaryMessage();
    // A nearby keyframe before A would immediately trigger another restart, even while paused.
    player.setSeekParameters(SeekParameters.EXACT);
    player.seekTo(loopStartMs);
    resetWatchTimeTrackingAnchor();
    final int generation = loopBoundaryGeneration;
    uiHandler.post(() -> {
      if (generation != loopBoundaryGeneration) {
        loopRestartPending = false;
        return;
      }
      loopRestartPending = false;
      if (!isLoopActive() || player == null) {
        return;
      }
      scheduleLoopBoundaryMessage();
      updatePlaybackProgress();
      scheduleProgressUpdates();
      scheduleLoopUpdates();
    });
  }

  private void scheduleLoopBoundaryMessage() {
    if (loopRestartPending || !isLoopActive() || player == null || player.getCurrentMediaItem() == null) {
      AppLog.v(TAG, () -> "Loop boundary message not scheduled: pending=" + loopRestartPending
          + ", loopActive=" + isLoopActive()
          + ", playerPresent=" + (player != null)
          + ", hasMediaItem=" + (player != null && player.getCurrentMediaItem() != null));
      return;
    }
    clearLoopBoundaryMessage();
    long positionMs = Math.max(0L, player.getCurrentPosition());
    if (positionMs < loopStartMs || positionMs >= loopEndMs) {
      AppLog.d(TAG, () -> "Loop boundary already passed; posting restart: positionMs=" + positionMs
          + ", endMs=" + loopEndMs);
      uiHandler.post(this::performLoopRestart);
      return;
    }
    final int generation = ++loopBoundaryGeneration;
    AppLog.d(TAG, () -> "Scheduling loop boundary message: generation=" + generation
        + ", positionMs=" + positionMs
        + ", endMs=" + loopEndMs);
    loopBoundaryMessage = player.createMessage((messageType, payload) -> {
      if (!(payload instanceof Integer) || ((Integer) payload) != loopBoundaryGeneration) {
        return;
      }
      loopBoundaryMessage = null;
      performLoopRestart();
    })
        .setLooper(Looper.getMainLooper())
        .setPosition(player.getCurrentMediaItemIndex(), loopEndMs)
        .setPayload(generation)
        .setDeleteAfterDelivery(true)
        .send();
  }

  private void clearLoopBoundaryMessage() {
    loopBoundaryGeneration++;
    if (loopBoundaryMessage != null) {
      AppLog.d(TAG, () -> "Clearing loop boundary message: generation=" + loopBoundaryGeneration);
      loopBoundaryMessage.cancel();
      loopBoundaryMessage = null;
    }
  }

  private boolean isValidSurfaceIndex(int surfaceIndex) {
    return surfaceIndex >= 0 && surfaceIndex < videoSurfaces.length;
  }

  private void attachSlotSurface(ExoPlayer slotPlayer, int slotIndex) {
    if (slotPlayer == null || !isValidSurfaceIndex(slotIndex)) {
      return;
    }
    Surface surface = videoSurfaces[slotIndex];
    if (surface != null && videoSurfaceAttachedSurfaces[slotIndex] != surface) {
      AppLog.i(TAG, () -> "Attaching video surface: slot=" + slotIndex);
      slotPlayer.setVideoSurface(surface);
      videoSurfaceAttachedSurfaces[slotIndex] = surface;
    }
  }

  private void clearSlotSurface(ExoPlayer slotPlayer, int slotIndex) {
    if (slotPlayer == null || !isValidSurfaceIndex(slotIndex)) {
      return;
    }
    Surface attachedSurface = videoSurfaceAttachedSurfaces[slotIndex];
    if (attachedSurface != null) {
      AppLog.i(TAG, () -> "Clearing attached video surface: slot=" + slotIndex);
      slotPlayer.clearVideoSurface(attachedSurface);
      videoSurfaceAttachedSurfaces[slotIndex] = null;
      return;
    }
    Surface surface = videoSurfaces[slotIndex];
    if (surface != null) {
      AppLog.d(TAG, () -> "Clearing fallback video surface: slot=" + slotIndex);
      slotPlayer.clearVideoSurface(surface);
    } else {
      AppLog.d(TAG, () -> "Clearing player video surface without a stored surface: slot=" + slotIndex);
      slotPlayer.clearVideoSurface();
    }
  }

  private SavedSceneStore.Target currentSceneTarget() {
    if (windowsRemoteEnabled) {
      if (windowsRemoteState == null || !windowsRemoteState.hasMedia()
          || windowsRemoteState.currentServerId.isEmpty()) return null;
      String sceneServerUrl = resolveWindowsSceneServerUrl();
      if (sceneServerUrl.isEmpty()) return null;
      return new SavedSceneStore.Target("", sceneServerUrl,
          windowsRemoteState.currentServerId, getServerApiKey(), windowsRemoteState.media,
          resolveWindowsRemoteUrl());
    }
    if (currentVideo == null) return null;
    if (currentVideoFromServer && currentServerVideo != null) {
      String stream = currentServerVideo.uri.toString();
      int videoPath = stream.indexOf("/videos/");
      if (videoPath < 0) return null;
      return new SavedSceneStore.Target("", stream.substring(0, videoPath), currentServerVideo.id,
          getServerApiKey(), currentVideo.title, "android");
    }
    return new SavedSceneStore.Target(currentVideo.uri.toString(), "", "", "", currentVideo.title, "android");
  }

  private SavedScene captureSavedScene() {
    if (currentSceneTarget() == null) return null;
    if (windowsRemoteEnabled) {
      return SavedScene.create(windowsRemoteState.positionMs, windowsRemoteState.loopStartMs, windowsRemoteState.loopEndMs);
    }
    return player == null ? null : SavedScene.create(player.getCurrentPosition(), loopStartMs, loopEndMs);
  }

  @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
  private void restoreSavedScene(SavedSceneStore.Target target, SavedScene scene, Runnable success,
      java.util.function.Consumer<String> failure) {
    if (!target.samePlayback(currentSceneTarget())) {
      failure.accept("The current video changed. Reopen Scenes.");
      return;
    }
    long duration = getKnownDuration();
    if (duration > 0 && (scene.startMs >= duration || (scene.isLoop() && scene.endMs > duration))) {
      failure.accept("This scene is outside the video's duration.");
      return;
    }
    if (windowsRemoteEnabled) {
      try {
        JSONObject command = commandJson("loadScene");
        command.put("video_id", target.videoId);
        command.put("start_ms", scene.startMs);
        command.put("end_ms", scene.isLoop() ? scene.endMs : JSONObject.NULL);
        windowsRemoteExecutor.execute(() -> {
          try {
            WindowsRemoteClient.sendCommand(target.playbackOwner, target.apiKey, command);
            uiHandler.post(() -> { success.run(); scheduleWindowsRemotePoll(0L); });
          } catch (Exception e) {
            uiHandler.post(() -> failure.accept("Could not restore scene: " + e.getMessage()));
          }
        });
      } catch (JSONException e) {
        failure.accept("Could not restore scene: " + e.getMessage());
      }
      return;
    }
    if (player == null) { failure.accept("No video loaded"); return; }
    boolean playRequested = player.getPlayWhenReady();
    resetLoop();
    loopStartMs = scene.isLoop() ? scene.startMs : C.TIME_UNSET;
    loopEndMs = scene.endMs;
    updateWatchTimeTracking();
    // A saved range must start at A, not a nearby keyframe before A.
    player.setSeekParameters(SeekParameters.EXACT);
    player.seekTo(scene.startMs);
    player.setPlayWhenReady(playRequested);
    resetWatchTimeTrackingAnchor();
    persistCurrentServerLoopPoints();
    scheduleLoopBoundaryMessage();
    updatePlaybackUi();
    success.run();
  }

  private void setLoopStartToCurrentPosition() {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("loopStart"));
      return;
    }
    if (currentVideo == null || player == null) {
      return;
    }

    loopStartMs = Math.max(0L, player.getCurrentPosition());
    AppLog.i(TAG, () -> "Loop start set: startMs=" + loopStartMs
        + ", endMs=" + loopEndMs
        + ", current=" + currentVideoTitleForLog());
    onLoopPointsChanged();
    updateLoopControls();
    persistCurrentServerLoopPoints();
  }

  private void setLoopEndToCurrentPosition() {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("loopEnd"));
      return;
    }
    if (currentVideo == null || player == null) {
      return;
    }

    loopEndMs = Math.max(0L, player.getCurrentPosition());
    AppLog.i(TAG, () -> "Loop end set: startMs=" + loopStartMs
        + ", endMs=" + loopEndMs
        + ", current=" + currentVideoTitleForLog());
    onLoopPointsChanged();
    updateLoopControls();
    persistCurrentServerLoopPoints();
  }

  private void onLoopPointsChanged() {
    AppLog.d(TAG, () -> "Loop points changed: startMs=" + loopStartMs
        + ", endMs=" + loopEndMs
        + ", ready=" + isLoopReady()
        + ", paused=" + loopPaused);
    clearLoopBoundaryMessage();
    if (isLoopActive()) {
      scheduleLoopBoundaryMessage();
      enforceLoopBoundary();
      if (player != null) {
        player.play();
      }
      scheduleLoopUpdates();
    } else {
      uiHandler.removeCallbacks(loopUpdater);
      loopUpdatesScheduled = false;
    }
  }

  private void clearLoop() {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("clearLoop"));
      return;
    }
    AppLog.i(TAG, () -> "Loop cleared: current=" + currentVideoTitleForLog());
    resetLoop();
    updateLoopControls();
    persistCurrentServerLoopPoints();
  }

  private void clearLoopStart() {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("clearLoopStart"));
      return;
    }
    if (currentVideo == null) {
      return;
    }
    AppLog.i(TAG, () -> "Loop start cleared: current=" + currentVideoTitleForLog());
    loopStartMs = C.TIME_UNSET;
    onLoopPointsChanged();
    updateLoopControls();
    persistCurrentServerLoopPoints();
  }

  private void clearLoopEnd() {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("clearLoopEnd"));
      return;
    }
    if (currentVideo == null) {
      return;
    }
    AppLog.i(TAG, () -> "Loop end cleared: current=" + currentVideoTitleForLog());
    loopEndMs = C.TIME_UNSET;
    onLoopPointsChanged();
    updateLoopControls();
    persistCurrentServerLoopPoints();
  }

  private void toggleLoopPaused() {
    if (windowsRemoteEnabled) {
      sendWindowsRemoteCommand(commandJson("toggleLoopPaused"));
      return;
    }
    setLoopPaused(!loopPaused);
  }

  private void setLoopPaused(boolean paused) {
    if (loopPaused == paused) {
      return;
    }
    loopPaused = paused;
    AppLog.i(TAG, () -> "AB loop pause changed: paused=" + loopPaused
        + ", startMs=" + loopStartMs
        + ", endMs=" + loopEndMs
        + ", current=" + currentVideoTitleForLog());
    if (loopPaused) {
      loopRestartPending = false;
      clearLoopBoundaryMessage();
      uiHandler.removeCallbacks(loopUpdater);
      loopUpdatesScheduled = false;
    } else {
      clearLoopBoundaryMessage();
      if (isLoopActive()) {
        enforceLoopBoundary();
        scheduleLoopBoundaryMessage();
        scheduleLoopUpdates();
      }
    }
    updateLoopControls();
  }

  private void persistCurrentServerLoopPoints() {
    if (!currentVideoFromServer || currentVideo == null || currentServerVideo == null) {
      return;
    }
    if (player != null) {
      updateWatchTimeTracking();
      long duration = getKnownDuration();
      long position = Math.max(0L, player.getCurrentPosition());
      if (duration > 0L && duration - position < 2500L) {
        position = 0L;
      }
      currentVideo.lastPositionMs = position;
      currentVideo.durationMs = duration;
      currentServerVideo.lastPositionMs = position;
      currentServerVideo.watchedTimeMs = currentVideo.watchedTimeMs;
      if (duration > 0L) {
        currentServerVideo.durationMs = duration;
      }
    }
    currentServerVideo.loopStartMs = loopStartMs;
    currentServerVideo.loopEndMs = loopEndMs;
    upsertServerPlayedVideo(currentServerVideo);
    queueServerHistorySync(currentServerVideo, false);
  }

  private void resetLoop() {
    AppLog.d(TAG, () -> "Resetting loop state: previousStartMs=" + loopStartMs
        + ", previousEndMs=" + loopEndMs);
    loopStartMs = C.TIME_UNSET;
    loopEndMs = C.TIME_UNSET;
    loopPaused = false;
    loopRestartPending = false;
    clearLoopBoundaryMessage();
    uiHandler.removeCallbacks(loopUpdater);
    loopUpdatesScheduled = false;
  }

  private void updatePresentation() {
    if (windowsRemoteEnabled) {
      dismissPresentation();
      return;
    }
    Display display = findBestExternalDisplay();
    if (display == null) {
      AppLog.i(TAG, "No external presentation display found");
      dismissPresentation();
      updateStatus();
      return;
    }
    if (presentation != null && presentation.getDisplay().getDisplayId() == display.getDisplayId()) {
      AppLog.d(TAG, () -> "Keeping existing presentation display: " + displaySummary(display));
      updateStatus();
      return;
    }

    dismissPresentation();
    AppLog.i(TAG, () -> "Creating VR presentation on display: " + displaySummary(display));
    presentation = new VrPlayerPresentation(this, display, videoSurfaceCallback);
    presentation.setOnDismissListener(dialog -> {
      if (presentation == dialog) {
        AppLog.i(TAG, "VR presentation dismissed");
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
    presentation.setActiveVideoSurfaceIndex(ACTIVE_VIDEO_SURFACE_INDEX);
    presentation.setZoom(viewScale);
    presentation.setProjectionMode(projectionMode);
    presentation.setViewOffsetDegrees(sceneCenterDegrees, horizonDegrees);
    presentation.setHeadRotationMatrix(headTracker.getRotationMatrix());
    AppLog.i(TAG, () -> "VR presentation ready: display=" + displaySummary(display)
        + ", stereoOutput=" + presentation.isStereoOutput()
        + ", viewScale=" + viewScale
        + ", projection=" + projectionModeName(projectionMode));
    updateStatus();
  }

  private void handlePresentationFailure(String message, RuntimeException error) {
    AppLog.e(TAG, "Could not create external VR presentation", error);
    VrPlayerPresentation failedPresentation = presentation;
    presentation = null;
    if (failedPresentation != null) {
      failedPresentation.setOnDismissListener(null);
      try {
        failedPresentation.releaseRenderer();
        failedPresentation.dismiss();
      } catch (RuntimeException ignored) {
        AppLog.w(TAG, "Could not clean up failed presentation", ignored);
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
    AppLog.i(TAG, () -> "Dismissing VR presentation: display=" + displaySummary(presentation.getDisplay()));
    VrPlayerPresentation oldPresentation = presentation;
    presentation = null;
    oldPresentation.setOnDismissListener(null);
    oldPresentation.releaseRenderer();
    oldPresentation.dismiss();
    for (int i = 0; i < videoSurfaces.length; i++) {
      if (i == ACTIVE_VIDEO_SURFACE_INDEX) {
        clearSlotSurface(player, i);
      }
      videoSurfaceAttachedSurfaces[i] = null;
      videoSurfaces[i] = null;
    }
    updateKeepScreenOn();
  }

  private Display findBestExternalDisplay() {
    if (displayManager == null) {
      AppLog.w(TAG, "Cannot find external display: displayManager is null");
      return null;
    }

    Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
    if (displays.length == 0) {
      displays = displayManager.getDisplays();
    }
    final int displayCount = displays.length;
    AppLog.d(TAG, () -> "Evaluating displays for presentation: count=" + displayCount);

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
      final Display candidateDisplay = display;
      final int candidateScore = score;
      AppLog.d(TAG, () -> "Display candidate: " + displaySummary(candidateDisplay) + ", score=" + candidateScore);
    }
    if (bestDisplay != null) {
      final Display selectedDisplay = bestDisplay;
      final int selectedScore = bestScore;
      AppLog.i(TAG, () -> "Selected external display: " + displaySummary(selectedDisplay)
          + ", score=" + selectedScore);
    }
    return bestDisplay;
  }

  private final NrealManager.Listener nrealListener = new NrealManager.Listener() {
    @Override
    public void onDeviceConnected() {
      AppLog.i(TAG, "Nreal device connected");
      headTracker.reset();
      lastDeviceMessage = "USB connected";
      updateStatus();
    }

    @Override
    public void onDeviceDisconnected() {
      AppLog.i(TAG, "Nreal device disconnected");
      lastDeviceMessage = "USB disconnected";
      lastImuElapsedMs = 0L;
      updateStatus();
    }

    @Override
    public void onPermissionDenied() {
      AppLog.w(TAG, "Nreal USB permission denied");
      lastDeviceMessage = "USB permission denied";
      updateStatus();
    }

    @Override
    public void onConnectionError(String error) {
      AppLog.w(TAG, () -> "Nreal connection error: " + error);
      lastDeviceMessage = error;
      updateStatus();
    }

    @Override
    public void onMessage(String message) {
      AppLog.i(TAG, () -> "Nreal message: " + message);
      lastDeviceMessage = message;
      updateStatus();
    }

    @Override
    public void onImuSample(ImuDataRaw sample) {
      headTracker.update(sample);
    }

    @Override
    public void onNewDataTemp(ImuDataRaw imuDataRawCopy) {
      latestImuData = imuDataRawCopy;
      lastImuElapsedMs = SystemClock.elapsedRealtime();
      long now = SystemClock.elapsedRealtime();
      if (LOG_VERBOSE_STATS) {
        trackingSamplesSinceLastLog++;
        if (now - lastTrackingLogElapsedMs >= VERBOSE_STATS_INTERVAL_MS) {
          long windowMs = lastTrackingLogElapsedMs == 0L ? 0L : now - lastTrackingLogElapsedMs;
          int samples = trackingSamplesSinceLastLog;
          trackingSamplesSinceLastLog = 0;
          lastTrackingLogElapsedMs = now;
          float[] gyro = latestImuData.getGyroscopeRadiansPerSecond();
          AppLog.v(TAG, String.format(Locale.US,
              "IMU samples: samples=%d, windowMs=%d, gyro=%.3f %.3f %.3f rad/s",
              samples,
              windowMs,
              gyro[0],
              gyro[1],
              gyro[2]));
        }
      }
      if (presentation != null && now - lastTrackingRenderElapsedMs >= MIN_TRACKING_RENDER_INTERVAL_MS) {
        lastTrackingRenderElapsedMs = now;
        presentation.setHeadRotationMatrix(headTracker.getRotationMatrix());
      }
      if (now - lastStatusElapsedMs > 500L) {
        updateStatus();
      }
    }

    @Override
    public void onButtonPressedTemp(int buttonId, int relatedValue) {
      AppLog.i(TAG, () -> "Nreal button event: button=" + buttonId + ", value=" + relatedValue);
      lastDeviceMessage = "Button " + buttonId + " value " + relatedValue;
      updateStatus();
    }
  };

  private void updatePlaybackUi() {
    if (savedScenes != null) savedScenes.update();
    if (windowsRemoteEnabled) {
      applyWindowsRemoteUi();
      scheduleWindowsRemotePoll(WINDOWS_REMOTE_POLL_MS);
      return;
    }
    updatePlaybackProgress();
    boolean hasVideo = currentVideo != null;
    binding.rewindButton.setEnabled(hasVideo);
    binding.forwardButton.setEnabled(hasVideo);
    binding.abLoopAButton.setEnabled(hasVideo);
    binding.abLoopBButton.setEnabled(hasVideo);
    binding.abLoopClearButton.setEnabled(hasVideo);
    binding.progressSeekBar.setEnabled(hasVideo);
    // Remote mode is allowed to disable this shared button while its state is unavailable.
    // Local mode must always take ownership back explicitly; with no video the same button
    // intentionally opens the local picker.
    binding.playPauseButton.setEnabled(player != null);
    boolean isPlaybackRequested = player != null && player.getPlayWhenReady();
    binding.playPauseButton.setText(isPlaybackRequested ? "Pause" : "Play");
    binding.playPauseButton.setIconResource(isPlaybackRequested
        ? R.drawable.ic_pause_24
        : R.drawable.ic_play_arrow_24);
    binding.currentVideoTitle.setText(hasVideo ? currentVideo.title : "No video selected");
    updateLoopControls();
    updateStatus();
    scheduleProgressUpdates();
    scheduleLoopUpdates();
  }

  private void updatePlaybackProgress() {
    if (savedScenes != null) savedScenes.update();
    if (windowsRemoteEnabled) {
      applyWindowsRemoteProgress();
      return;
    }
    if (binding == null || player == null) {
      return;
    }
    updateWatchTimeTracking();
    long duration = getKnownDuration();
    long position = currentVideo == null ? 0L : Math.max(0L, player.getCurrentPosition());
    updateAbLoopTimeline(duration);
    if (!isUserSeeking) {
      if (duration > 0) {
        binding.progressSeekBar.setProgress((int) Math.min(SEEK_BAR_MAX, position * SEEK_BAR_MAX / duration));
      } else {
        binding.progressSeekBar.setProgress(0);
      }
      binding.positionText.setText(formatPosition(position, duration));
    }
    enforceLoopBoundary(position);
  }

  private void enforceLoopBoundary() {
    if (player == null || currentVideo == null) {
      return;
    }
    enforceLoopBoundary(Math.max(0L, player.getCurrentPosition()));
  }

  private void enforceLoopBoundary(long positionMs) {
    if (!loopRestartPending && isLoopActive()
        && (positionMs < loopStartMs || positionMs >= loopEndMs)) {
      seekToLoopStart();
    }
  }

  private void scheduleProgressUpdates() {
    if (windowsRemoteEnabled) {
      scheduleWindowsRemotePoll(WINDOWS_REMOTE_POLL_MS);
      return;
    }
    if (progressUpdatesScheduled || player == null || binding == null || currentVideo == null) {
      return;
    }
    if (!player.isPlaying()) {
      return;
    }
    progressUpdatesScheduled = true;
    uiHandler.postDelayed(progressUpdater, PROGRESS_UPDATE_MS);
  }

  private void scheduleLoopUpdates() {
    if (loopUpdatesScheduled || player == null || binding == null || currentVideo == null) {
      return;
    }
    if (!player.isPlaying() || !isLoopActive()) {
      return;
    }
    loopUpdatesScheduled = true;
    uiHandler.postDelayed(loopUpdater, LOOP_UPDATE_MS);
  }

  private void updateKeepScreenOn() {
    if (binding == null) {
      return;
    }
    boolean keepAwake;
    if (windowsRemoteEnabled) {
      keepAwake = windowsRemoteState != null && windowsRemoteState.playing;
    } else {
      if (player == null) {
        return;
      }
      keepAwake = player.isPlaying()
          && (presentation == null || videoSurfaces[ACTIVE_VIDEO_SURFACE_INDEX] == null);
    }
    if (keepAwake) {
      AppLog.d(TAG, () -> "Keep screen on: enabled, presentationPresent=" + (presentation != null)
          + ", activeSurfacePresent=" + (videoSurfaces[ACTIVE_VIDEO_SURFACE_INDEX] != null));
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      AppLog.d(TAG, () -> "Keep screen on: disabled, presentationPresent=" + (presentation != null)
          + ", activeSurfacePresent=" + (videoSurfaces[ACTIVE_VIDEO_SURFACE_INDEX] != null));
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private void applyAudioMuted() {
    if (player != null) {
      AppLog.d(TAG, "Applying audio volume: muted=" + audioMuted);
      player.setVolume(audioMuted ? 0.0f : 1.0f);
    }
  }

  private void updateLoopControls() {
    if (savedScenes != null) savedScenes.update();
    if (binding == null) {
      return;
    }
    if (windowsRemoteEnabled) {
      boolean hasRemoteVideo = windowsRemoteState != null && windowsRemoteState.hasMedia();
      binding.abLoopAButton.setEnabled(hasRemoteVideo);
      binding.abLoopBButton.setEnabled(hasRemoteVideo);
      binding.abLoopClearButton.setEnabled(hasRemoteVideo);
      long remoteStart = windowsRemoteState == null ? C.TIME_UNSET : windowsRemoteState.loopStartMs;
      long remoteEnd = windowsRemoteState == null ? C.TIME_UNSET : windowsRemoteState.loopEndMs;
      binding.abLoopAButton.setText(remoteStart == C.TIME_UNSET ? "A" : "A " + formatTime(remoteStart));
      binding.abLoopBButton.setText(remoteEnd == C.TIME_UNSET ? "B" : "B " + formatTime(remoteEnd));
      updateAbLoopTimeline(getKnownDuration());
      return;
    }
    boolean hasVideo = currentVideo != null;
    binding.abLoopAButton.setEnabled(hasVideo);
    binding.abLoopBButton.setEnabled(hasVideo);
    binding.abLoopClearButton.setEnabled(hasVideo);
    updateAbLoopTimeline(getKnownDuration());
    binding.abLoopAButton.setText(loopStartMs == C.TIME_UNSET ? "A" : "A " + formatTime(loopStartMs));
    binding.abLoopBButton.setText(loopEndMs == C.TIME_UNSET ? "B" : "B " + formatTime(loopEndMs));
  }

  private void updateAbLoopTimeline(long durationMs) {
    if (binding == null) {
      return;
    }
    if (windowsRemoteEnabled) {
      if (windowsRemoteState == null || !windowsRemoteState.hasMedia() || durationMs <= 0L) {
        binding.abLoopTimelineView.setLoopPoints(C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET);
      } else {
        binding.abLoopTimelineView.setLoopPoints(durationMs, windowsRemoteState.loopStartMs, windowsRemoteState.loopEndMs);
      }
      return;
    }
    if (currentVideo == null || durationMs <= 0L) {
      binding.abLoopTimelineView.setLoopPoints(C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET);
      return;
    }
    binding.abLoopTimelineView.setLoopPoints(durationMs, loopStartMs, loopEndMs);
  }

  private boolean isLoopReady() {
    return currentVideo != null
        && loopStartMs != C.TIME_UNSET
        && loopEndMs != C.TIME_UNSET
        && loopEndMs > loopStartMs;
  }

  private boolean isLoopActive() {
    return isLoopReady() && !loopPaused;
  }

  private void updateStatus() {
    if (binding == null) {
      return;
    }
    if (windowsRemoteEnabled) {
      applyWindowsRemoteStatus();
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
    AppLog.d(TAG, () -> "Rendering recent videos: count=" + recentVideos.size());
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
      playButton.setEnabled(!windowsRemoteEnabled);
      playButton.setOnClickListener(v -> {
        if (!windowsRemoteEnabled) {
          playLocalVideo(video);
        }
      });
      LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
      buttonParams.setMargins(dp(10), 0, 0, 0);

      MaterialButton removeButton = new MaterialButton(this);
      removeButton.setText("");
      removeButton.setIconResource(R.drawable.ic_delete_24);
      removeButton.setIconPadding(0);
      removeButton.setMinWidth(0);
      removeButton.setMinimumWidth(0);
      removeButton.setPadding(0, 0, 0, 0);
      removeButton.setContentDescription("Remove " + video.title + " from recent videos");
      removeButton.setOnClickListener(v -> removeRecentVideo(video));
      LinearLayout.LayoutParams removeButtonParams = new LinearLayout.LayoutParams(dp(30), dp(48));
      removeButtonParams.setMargins(dp(6), 0, 0, 0);

      row.addView(textColumn);
      row.addView(playButton, buttonParams);
      row.addView(removeButton, removeButtonParams);
      binding.recentVideosContainer.addView(row);
    }
  }

  private void renderServerDialogRows() {
    if (serverVideoAdapter == null) {
      AppLog.d(TAG, "Skipping server row render: adapter is null");
      return;
    }
    List<ServerVideo> visible = getVisibleServerVideos();
    AppLog.d(TAG, () -> "Rendering server rows: visible=" + visible.size()
        + ", tab=" + serverDialogTab
        + ", search=" + normalizedServerSearchQuery());
    serverVideoAdapter.setVideos(visible);
    String searchQuery = normalizedServerSearchQuery();
    boolean unseenTab = "unseen".equals(serverDialogTab);
    if (visible.isEmpty()) {
      String serverUrl = normalizeServerUrl(binding.serverUrlEditText == null ? "" : String.valueOf(binding.serverUrlEditText.getText()));
      if (searchQuery.length() > 0) {
        setServerDialogStatus("No matches for \"" + serverSearchQuery.trim() + "\"");
      } else {
        setServerDialogStatus(unseenTab
            ? "No unseen server videos"
            : serverUrl.length() == 0 ? "Set a server URL in Settings" : "No server videos loaded");
      }
      return;
    }
    if (searchQuery.length() > 0) {
      int total = serverVideos.size();
      if (unseenTab) {
        total = 0;
        for (ServerVideo video : serverVideos) {
          if (video.watchedTimeMs < SERVER_UNSEEN_WATCH_LIMIT_MS) {
            total++;
          }
        }
      }
      setServerDialogStatus(visible.size() + " of " + total + " server videos");
    } else {
      setServerDialogStatus(visible.size() + (unseenTab ? " unseen videos" : " server videos"));
    }
    prefetchThumbnails(visible, preferredServerPrefetchStart(visible), SERVER_THUMBNAIL_PREFETCH_COUNT);
  }

  private void openServerLibraryDialog() {
    AppLog.i(TAG, () -> "Opening server library dialog: loadedThisSession=" + serverVideosLoadedThisSession
        + ", serverVideos=" + serverVideos.size()
        + ", playedVideos=" + serverPlayedVideos.size()
        + ", tab=" + serverDialogTab);
    Dialog dialog = new Dialog(this);
    serverDialog = dialog;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(14), dp(16), dp(12));
    root.setBackgroundColor(getColor(R.color.panel));

    TextView title = new TextView(this);
    title.setText("Server videos");
    title.setTextColor(getColor(R.color.ink));
    title.setTextSize(20);
    title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    MaterialButton refreshButton = new MaterialButton(this);
    refreshButton.setText("Refresh");
    LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    refreshParams.setMargins(0, dp(10), 0, 0);
    root.addView(refreshButton, refreshParams);

    LinearLayout controlsRow = new LinearLayout(this);
    controlsRow.setOrientation(LinearLayout.HORIZONTAL);
    controlsRow.setGravity(Gravity.CENTER_VERTICAL);
    controlsRow.setPadding(0, dp(8), 0, 0);

    Spinner sortSpinner = new Spinner(this);
    String[] sorts = {SORT_NAME_ASC, SORT_NAME_DESC, SORT_NEWEST, SORT_OLDEST, SORT_LONGEST, SORT_SHORTEST};
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sorts);
    sortSpinner.setAdapter(adapter);
    String selectedSort = settingsPreferences.getString(PREF_SERVER_SORT, SORT_NAME_ASC);
    for (int i = 0; i < sorts.length; i++) {
      if (sorts[i].equals(selectedSort)) {
        sortSpinner.setSelection(i);
        break;
      }
    }
    controlsRow.addView(sortSpinner, new LinearLayout.LayoutParams(0, dp(52), 1.0f));

    CheckBox jumpCheckBox = new CheckBox(this);
    jumpCheckBox.setText("Jump to last");
    jumpCheckBox.setTextColor(getColor(R.color.ink));
    jumpCheckBox.setChecked(settingsPreferences.getBoolean(PREF_SERVER_JUMP_LAST, true));
    controlsRow.addView(jumpCheckBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
    root.addView(controlsRow);

    LinearLayout navRow = new LinearLayout(this);
    navRow.setOrientation(LinearLayout.HORIZONTAL);
    navRow.setGravity(Gravity.CENTER_VERTICAL);

    MaterialButton previousButton = new MaterialButton(this);
    previousButton.setText("Previous");
    navRow.addView(previousButton, new LinearLayout.LayoutParams(0, dp(48), 1.0f));

    MaterialButton nextButton = new MaterialButton(this);
    nextButton.setText("Next");
    LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
    nextParams.setMargins(dp(8), 0, 0, 0);
    navRow.addView(nextButton, nextParams);
    root.addView(navRow);

    LinearLayout tabRow = new LinearLayout(this);
    tabRow.setOrientation(LinearLayout.HORIZONTAL);
    tabRow.setPadding(0, dp(8), 0, 0);
    serverDialogTabLibrary = makeServerTab("Library");
    serverDialogTabUnseen = makeServerTab("Unseen");
    tabRow.addView(serverDialogTabLibrary, new LinearLayout.LayoutParams(0, dp(42), 1.0f));
    tabRow.addView(serverDialogTabUnseen, new LinearLayout.LayoutParams(0, dp(42), 1.0f));
    MaterialButton searchButton = new MaterialButton(this);
    searchButton.setText("");
    searchButton.setIconResource(R.drawable.ic_search_24);
    searchButton.setIconPadding(0);
    searchButton.setMinWidth(0);
    searchButton.setMinimumWidth(0);
    searchButton.setPadding(0, 0, 0, 0);
    searchButton.setContentDescription("Search server videos");
    LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(dp(42), dp(42));
    searchButtonParams.setMargins(dp(8), 0, 0, 0);
    tabRow.addView(searchButton, searchButtonParams);
    root.addView(tabRow);

    serverSearchRow = new LinearLayout(this);
    serverSearchRow.setOrientation(LinearLayout.HORIZONTAL);
    serverSearchRow.setGravity(Gravity.CENTER_VERTICAL);
    serverSearchRow.setPadding(0, dp(8), 0, 0);
    serverSearchRow.setVisibility(serverSearchQuery.length() == 0 ? View.GONE : View.VISIBLE);

    serverSearchEditText = new EditText(this);
    serverSearchEditText.setSingleLine(true);
    serverSearchEditText.setHint("Search server videos");
    serverSearchEditText.setTextColor(getColor(R.color.ink));
    serverSearchEditText.setHintTextColor(getColor(R.color.muted));
    serverSearchEditText.setText(serverSearchQuery);
    serverSearchRow.addView(serverSearchEditText, new LinearLayout.LayoutParams(0, dp(48), 1.0f));

    MaterialButton clearSearchButton = new MaterialButton(this);
    clearSearchButton.setText("");
    clearSearchButton.setIconResource(R.drawable.ic_close_24);
    clearSearchButton.setIconPadding(0);
    clearSearchButton.setMinWidth(0);
    clearSearchButton.setMinimumWidth(0);
    clearSearchButton.setPadding(0, 0, 0, 0);
    clearSearchButton.setContentDescription("Clear server search");
    LinearLayout.LayoutParams clearSearchParams = new LinearLayout.LayoutParams(dp(44), dp(48));
    clearSearchParams.setMargins(dp(8), 0, 0, 0);
    serverSearchRow.addView(clearSearchButton, clearSearchParams);
    root.addView(serverSearchRow);

    serverDialogStatusText = new TextView(this);
    serverDialogStatusText.setTextColor(getColor(R.color.muted));
    serverDialogStatusText.setTextSize(14);
    serverDialogStatusText.setPadding(0, dp(8), 0, dp(4));
    root.addView(serverDialogStatusText);

    serverVideoRecyclerView = new RecyclerView(this);
    serverVideoRecyclerView.setHasFixedSize(false);
    serverVideoRecyclerView.setItemViewCacheSize(12);
    serverVideoRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    serverVideoAdapter = new ServerVideoAdapter();
    serverVideoRecyclerView.setAdapter(serverVideoAdapter);
    root.addView(serverVideoRecyclerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

    MaterialButton closeButton = new MaterialButton(this);
    closeButton.setText("Close");
    closeButton.setOnClickListener(v -> dialog.dismiss());
    root.addView(closeButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

    refreshButton.setOnClickListener(v -> loadServerVideos(refreshButton, true));
    sortSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        settingsPreferences.edit().putString(PREF_SERVER_SORT, sorts[position]).apply();
        renderServerDialogRows();
        jumpToLastServerVideo();
      }

      @Override
      public void onNothingSelected(android.widget.AdapterView<?> parent) {
      }
    });
    jumpCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
      settingsPreferences.edit().putBoolean(PREF_SERVER_JUMP_LAST, isChecked).apply();
      if (isChecked) {
        jumpToLastServerVideo();
      }
    });
    previousButton.setOnClickListener(v -> playAdjacentServerVideo(-1));
    nextButton.setOnClickListener(v -> playAdjacentServerVideo(1));
    searchButton.setOnClickListener(v -> {
      if (serverSearchRow == null || serverSearchEditText == null) {
        return;
      }
      serverSearchRow.setVisibility(View.VISIBLE);
      serverSearchEditText.requestFocus();
      Window dialogWindow = dialog.getWindow();
      if (dialogWindow != null) {
        dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
      }
    });
    serverSearchEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        serverSearchQuery = String.valueOf(s);
        renderServerDialogRows();
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });
    clearSearchButton.setOnClickListener(v -> clearServerSearch());
    serverDialogTabLibrary.setOnClickListener(v -> {
      serverDialogTab = "library";
      updateServerTabs();
      renderServerDialogRows();
      jumpToLastServerVideo();
    });
    serverDialogTabUnseen.setOnClickListener(v -> {
      serverDialogTab = "unseen";
      updateServerTabs();
      renderServerDialogRows();
      jumpToLastServerVideo();
    });

    dialog.setContentView(root);
    dialog.setOnDismissListener(d -> {
      AppLog.i(TAG, "Server library dialog dismissed");
      serverDialog = null;
      serverVideoRecyclerView = null;
      serverVideoAdapter = null;
      serverDialogStatusText = null;
      serverSearchRow = null;
      serverSearchEditText = null;
    });
    dialog.show();
    Window shownWindow = dialog.getWindow();
    if (shownWindow != null) {
      shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
    updateServerTabs();
    boolean startedAutoLoad = false;
    String currentServerUrl = normalizeServerUrl(binding.serverUrlEditText == null ? "" : String.valueOf(binding.serverUrlEditText.getText()));
    if (!serverVideosLoadedThisSession && currentServerUrl.length() > 0) {
      startedAutoLoad = true;
      AppLog.i(TAG, () -> "Auto-loading server videos for dialog: url=" + currentServerUrl);
      loadServerVideos(refreshButton, false);
    } else if (serverVideosLoadedThisSession && !currentServerUrl.equals(serverLoadedUrlThisSession)) {
      setServerDialogStatus("Refresh to use the Settings server URL");
    } else {
      setServerDialogStatus(serverVideos.isEmpty() ? "Set a server URL in Settings" : serverVideos.size() + " server videos");
    }
    if (!startedAutoLoad) {
      renderServerDialogRows();
    }
  }

  private TextView makeServerTab(String text) {
    TextView tab = new TextView(this);
    tab.setText(text);
    tab.setGravity(Gravity.CENTER);
    tab.setTextSize(15);
    tab.setTextColor(getColor(R.color.ink));
    return tab;
  }

  private void updateServerTabs() {
    if (serverDialogTabLibrary == null || serverDialogTabUnseen == null) {
      return;
    }
    serverDialogTabLibrary.setBackgroundColor("library".equals(serverDialogTab) ? getColor(R.color.panel_alt) : getColor(R.color.panel));
    serverDialogTabUnseen.setBackgroundColor("unseen".equals(serverDialogTab) ? getColor(R.color.panel_alt) : getColor(R.color.panel));
  }

  private void setServerDialogStatus(String status) {
    AppLog.d(TAG, () -> "Server dialog status: " + status);
    if (serverDialogStatusText != null) {
      serverDialogStatusText.setText(status);
    }
  }

  private void clearServerSearch() {
    AppLog.i(TAG, () -> "Clearing server search: previousQuery=" + serverSearchQuery);
    serverSearchQuery = "";
    if (serverSearchEditText != null && serverSearchEditText.length() > 0) {
      serverSearchEditText.setText("");
    }
    if (serverSearchRow != null) {
      serverSearchRow.setVisibility(View.GONE);
    }
    renderServerDialogRows();
  }

  private List<ServerVideo> getVisibleServerVideos() {
    List<ServerVideo> source = serverVideos;
    List<ServerVideo> videos = new ArrayList<>();
    String query = normalizedServerSearchQuery();
    String[] queryTerms = query.length() == 0 ? new String[0] : query.split(" ");
    for (ServerVideo video : source) {
      if ("unseen".equals(serverDialogTab) && video.watchedTimeMs >= SERVER_UNSEEN_WATCH_LIMIT_MS) {
        continue;
      }
      if (query.length() == 0 || matchesServerSearch(video, queryTerms)) {
        videos.add(video);
      }
    }
    String sort = settingsPreferences.getString(PREF_SERVER_SORT, SORT_NAME_ASC);
    videos.sort((left, right) -> compareServerVideos(left, right, sort));
    return videos;
  }

  private String normalizedServerSearchQuery() {
    return SearchMatcher.normalize(serverSearchQuery);
  }

  private boolean matchesServerSearch(ServerVideo video, String[] queryTerms) {
    return SearchMatcher.matches(video.searchText, queryTerms);
  }

  private int compareServerVideos(ServerVideo left, ServerVideo right, String sort) {
    if (SORT_NAME_DESC.equals(sort)) {
      return right.displayTitle().compareToIgnoreCase(left.displayTitle());
    }
    if (SORT_NEWEST.equals(sort)) {
      return Long.compare(right.modifiedMs, left.modifiedMs);
    }
    if (SORT_OLDEST.equals(sort)) {
      return Long.compare(left.modifiedMs, right.modifiedMs);
    }
    if (SORT_LONGEST.equals(sort)) {
      return Long.compare(right.durationMs, left.durationMs);
    }
    if (SORT_SHORTEST.equals(sort)) {
      return Long.compare(left.durationMs, right.durationMs);
    }
    return left.displayTitle().compareToIgnoreCase(right.displayTitle());
  }

  private void playAdjacentServerVideo(int direction) {
    List<ServerVideo> visible = getVisibleServerVideos();
    if (visible.isEmpty()) {
      AppLog.d(TAG, () -> "Adjacent server playback ignored: no visible videos, direction=" + direction);
      return;
    }
    String currentId = windowsRemoteEnabled && windowsRemoteState != null
        && windowsRemoteState.currentServerId.length() > 0
        ? windowsRemoteState.currentServerId
        : (currentServerVideo != null ? currentServerVideo.id : latestServerHistoryId());
    int index = -1;
    for (int i = 0; i < visible.size(); i++) {
      if (visible.get(i).id.equals(currentId)) {
        index = i;
        break;
      }
    }
    int target = index < 0 ? 0 : Math.max(0, Math.min(visible.size() - 1, index + direction));
    final int fromIndex = index;
    final int targetIndex = target;
    final String targetId = visible.get(target).id;
    AppLog.i(TAG, () -> "Adjacent server playback: direction=" + direction
        + ", fromIndex=" + fromIndex
        + ", targetIndex=" + targetIndex
        + ", targetId=" + targetId);
    playServerVideo(visible.get(target));
  }

  private void jumpToLastServerVideo() {
    if (serverVideoRecyclerView == null || !settingsPreferences.getBoolean(PREF_SERVER_JUMP_LAST, true)) {
      return;
    }
    String lastId = latestServerHistoryId();
    if (lastId.length() == 0) {
      return;
    }
    List<ServerVideo> visible = getVisibleServerVideos();
    int index = -1;
    for (int i = 0; i < visible.size(); i++) {
      if (visible.get(i).id.equals(lastId)) {
        index = i;
        break;
      }
    }
    if (index >= 0) {
      int targetIndex = index;
      AppLog.d(TAG, () -> "Jumping server list to last video: id=" + lastId
          + ", index=" + targetIndex);
      serverVideoRecyclerView.post(() -> {
        RecyclerView.LayoutManager manager = serverVideoRecyclerView.getLayoutManager();
        if (manager instanceof LinearLayoutManager) {
          ((LinearLayoutManager) manager).scrollToPositionWithOffset(targetIndex, dp(24));
        } else {
          serverVideoRecyclerView.scrollToPosition(targetIndex);
        }
      });
    }
  }

  private void toggleServerDeleteFlag(ServerVideo video) {
    boolean nextFlag = !video.flaggedForDeletion;
    String serverUrl = settingsPreferences.getString(PREF_SERVER_URL, "");
    if (serverUrl.length() == 0) {
      AppLog.w(TAG, () -> "Delete flag toggle skipped: missing server URL, videoId=" + video.id);
      return;
    }
    AppLog.i(TAG, () -> "Toggling server delete flag: videoId=" + video.id
        + ", nextFlag=" + nextFlag);
    setServerDialogStatus(nextFlag ? "Flagging for deletion..." : "Undoing deletion flag...");
    new Thread(() -> {
      try {
        URL url = new URL(serverUrl + "/videos/" + video.id + "/delete-flag?flagged=" + nextFlag);
        long startedMs = SystemClock.elapsedRealtime();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        setServerApiKeyHeader(connection);
        int statusCode = connection.getResponseCode();
        String body = readFully(statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        long elapsedMs = SystemClock.elapsedRealtime() - startedMs;
        AppLog.d(TAG, () -> "Delete flag response: videoId=" + video.id
            + ", status=" + statusCode
            + ", elapsedMs=" + elapsedMs);
        if (statusCode < 200 || statusCode >= 300) {
          throw new IOException("HTTP " + statusCode + (body.length() > 0 ? ": " + body : ""));
        }
        uiHandler.post(() -> {
          video.flaggedForDeletion = nextFlag;
          ServerVideo played = findServerVideo(serverPlayedVideos, video.id);
          if (played != null) {
            played.flaggedForDeletion = nextFlag;
          }
          setServerDialogStatus(nextFlag ? "Flagged for deletion" : "Deletion flag undone");
          renderServerDialogRows();
        });
      } catch (IOException e) {
        AppLog.w(TAG, "Delete flag update failed: videoId=" + video.id, e);
        uiHandler.post(() -> setServerDialogStatus("Delete flag failed: " + e.getMessage()));
      }
    }, "server-delete-flag").start();
  }

  private void loadThumbnail(ServerVideo video, ImageView imageView) {
    if (video.thumbnailUrl.length() == 0) {
      AppLog.v(TAG, () -> "Thumbnail skipped: no URL for videoId=" + video.id);
      imageView.setImageDrawable(null);
      return;
    }
    Bitmap cached = getCachedThumbnail(video.thumbnailUrl);
    imageView.setTag(video.thumbnailUrl);
    if (cached != null) {
      AppLog.v(TAG, () -> "Thumbnail cache hit: videoId=" + video.id);
      imageView.setImageBitmap(cached);
      return;
    }
    AppLog.v(TAG, () -> "Thumbnail load queued: videoId=" + video.id
        + ", url=" + video.thumbnailUrl);
    imageView.setImageDrawable(null);
    int generation = thumbnailLoadGeneration;
    thumbnailExecutor.execute(() -> {
      if (generation != thumbnailLoadGeneration) {
        return;
      }
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(video.thumbnailUrl).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(true);
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
          AppLog.v(TAG, () -> "Thumbnail load failed with HTTP status: videoId=" + video.id
              + ", status=" + statusCode);
          return;
        }
        Bitmap bitmap;
        try (InputStream stream = connection.getInputStream()) {
          bitmap = BitmapFactory.decodeStream(stream);
        }
        if (bitmap != null && generation == thumbnailLoadGeneration) {
          putCachedThumbnail(video.thumbnailUrl, bitmap);
          AppLog.v(TAG, () -> "Thumbnail loaded: videoId=" + video.id
              + ", width=" + bitmap.getWidth()
              + ", height=" + bitmap.getHeight());
          uiHandler.post(() -> {
            if (generation != thumbnailLoadGeneration) {
              return;
            }
            Object tag = imageView.getTag();
            if (video.thumbnailUrl.equals(tag)) {
              imageView.setImageBitmap(bitmap);
            }
          });
        }
      } catch (IOException ignored) {
        AppLog.v(TAG, () -> "Thumbnail load failed: videoId=" + video.id
            + ", error=" + ignored.getMessage());
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }
    });
  }

  private int preferredServerPrefetchStart(List<ServerVideo> videos) {
    if (!settingsPreferences.getBoolean(PREF_SERVER_JUMP_LAST, true)) {
      return 0;
    }
    String lastId = currentServerVideo != null
        ? currentServerVideo.id
        : latestServerHistoryId();
    if (lastId.length() == 0) {
      return 0;
    }
    for (int i = 0; i < videos.size(); i++) {
      if (videos.get(i).id.equals(lastId)) {
        return Math.max(0, i - 2);
      }
    }
    return 0;
  }

  private void prefetchThumbnails(List<ServerVideo> videos, int start, int count) {
    int limit = Math.min(start + count, videos.size());
    AppLog.d(TAG, () -> "Prefetching thumbnails: start=" + start
        + ", limit=" + limit
        + ", totalVisible=" + videos.size());
    for (int i = Math.max(0, start); i < limit; i++) {
      String thumbnailUrl = videos.get(i).thumbnailUrl;
      if (thumbnailUrl.length() == 0 || getCachedThumbnail(thumbnailUrl) != null) {
        continue;
      }
      int generation = thumbnailLoadGeneration;
      thumbnailExecutor.execute(() -> fetchThumbnailIntoCache(thumbnailUrl, generation));
    }
  }

  private void fetchThumbnailIntoCache(String thumbnailUrl, int generation) {
    if (generation != thumbnailLoadGeneration) {
      return;
    }
    if (getCachedThumbnail(thumbnailUrl) != null) {
      AppLog.v(TAG, () -> "Thumbnail prefetch skipped: already cached, url=" + thumbnailUrl);
      return;
    }
    if (!thumbnailsInFlight.add(thumbnailUrl)) {
      AppLog.v(TAG, () -> "Thumbnail prefetch skipped: already in flight, url=" + thumbnailUrl);
      return;
    }
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(thumbnailUrl).openConnection();
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(15000);
      connection.setUseCaches(true);
      int statusCode = connection.getResponseCode();
      if (statusCode < 200 || statusCode >= 300) {
        AppLog.v(TAG, () -> "Thumbnail prefetch HTTP failure: status=" + statusCode
            + ", url=" + thumbnailUrl);
        return;
      }
      Bitmap bitmap;
      try (InputStream stream = connection.getInputStream()) {
        bitmap = BitmapFactory.decodeStream(stream);
      }
      if (bitmap != null && generation == thumbnailLoadGeneration) {
        putCachedThumbnail(thumbnailUrl, bitmap);
        AppLog.v(TAG, () -> "Thumbnail prefetched: width=" + bitmap.getWidth()
            + ", height=" + bitmap.getHeight()
            + ", url=" + thumbnailUrl);
      }
    } catch (IOException ignored) {
      AppLog.v(TAG, () -> "Thumbnail prefetch failed: url=" + thumbnailUrl
          + ", error=" + ignored.getMessage());
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
      thumbnailsInFlight.remove(thumbnailUrl);
    }
  }

  private String formatServerMeta(ServerVideo video) {
    List<String> parts = new ArrayList<>();
    if (video.fileDate.length() > 0) {
      parts.add(video.fileDate);
    }
    parts.add(formatTime(video.durationMs));
    parts.add(formatBytes(video.size));
    if (video.lastPositionMs > 0L) {
      parts.add("Resume " + formatTime(video.lastPositionMs));
    }
    if (video.watchedTimeMs > 0L) {
      parts.add("Watched " + formatWatchedTime(video.watchedTimeMs));
    }
    return joinParts(parts);
  }

  private Bitmap getCachedThumbnail(String thumbnailUrl) {
    synchronized (thumbnailCache) {
      return thumbnailCache.get(thumbnailUrl);
    }
  }

  private void putCachedThumbnail(String thumbnailUrl, Bitmap bitmap) {
    synchronized (thumbnailCache) {
      thumbnailCache.put(thumbnailUrl, bitmap);
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

  private void confirmClearRecentVideos(String title, String message, Runnable onConfirm) {
    new MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Clear", (dialog, which) -> onConfirm.run())
        .show();
  }

  private void clearLocalRecentVideos() {
    AppLog.i(TAG, () -> "Clearing local recent videos: count=" + recentVideos.size()
        + ", currentFromServer=" + currentVideoFromServer);
    RecentVideoStore.clear(this, recentVideos);
    if (!currentVideoFromServer) {
      currentVideo = null;
      clearLoop();
      if (player != null) {
        player.stop();
        player.clearMediaItems();
      }
      updatePlaybackUi();
    }
    renderRecentVideos();
  }

  private void clearServerRecentVideos() {
    AppLog.i(TAG, () -> "Clearing server recent videos: count=" + serverPlayedVideos.size()
        + ", currentFromServer=" + currentVideoFromServer);
    serverPlayedVideos.clear();
    settingsPreferences.edit()
        .remove(PREF_SERVER_HISTORY_PENDING)
        .commit();
    queueClearServerHistory();
    if (currentVideoFromServer) {
      currentVideo = null;
      currentServerVideo = null;
      currentVideoFromServer = false;
      clearLoop();
      if (player != null) {
        player.stop();
        player.clearMediaItems();
      }
      updatePlaybackUi();
    }
    renderServerDialogRows();
  }

  private void removeRecentVideo(RecentVideo video) {
    AppLog.i(TAG, () -> "Removing recent video: title=" + video.title
        + ", uri=" + video.uri);
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
    String fallback = path == null || path.length() == 0 ? "Selected video" : path;
    AppLog.d(TAG, () -> "Using fallback display name for URI: " + fallback);
    return fallback;
  }

  private long querySize(Uri uri) {
    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int index = cursor.getColumnIndex(OpenableColumns.SIZE);
        if (index >= 0) {
          return Math.max(0L, cursor.getLong(index));
        }
      }
    } catch (RuntimeException ignored) {
      AppLog.w(TAG, "Could not query selected video size", ignored);
      return 0L;
    }
    AppLog.d(TAG, () -> "Selected video size unavailable: uri=" + uri);
    return 0L;
  }

  private long queryDuration(Uri uri) {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(this, uri);
      String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      long durationMs = duration == null ? 0L : Math.max(0L, Long.parseLong(duration));
      AppLog.d(TAG, () -> "Selected video duration: durationMs=" + durationMs);
      return durationMs;
    } catch (RuntimeException ignored) {
      AppLog.w(TAG, "Could not query selected video duration", ignored);
      return 0L;
    } finally {
      try {
        retriever.release();
      } catch (IOException | RuntimeException ignored) {
        AppLog.w(TAG, "Could not release metadata retriever cleanly", ignored);
        // Some platform builds throw from release after failed metadata reads.
      }
    }
  }

  private void saveCurrentVideoProgress() {
    if (player == null || currentVideo == null) {
      AppLog.d(TAG, "Skipping progress save: no player or current video");
      return;
    }
    updateWatchTimeTracking();
    long duration = getKnownDuration();
    long position = Math.max(0L, player.getCurrentPosition());
    if (duration > 0 && duration - position < 2500L) {
      position = 0L;
    }
    currentVideo.lastPositionMs = position;
    currentVideo.durationMs = duration;
    currentVideo.projectionMode = projectionMode;
    final long savedPositionMs = position;
    AppLog.d(TAG, () -> "Saving video progress: current=" + currentVideoTitleForLog()
        + ", positionMs=" + savedPositionMs
        + ", watchedTimeMs=" + currentVideo.watchedTimeMs
        + ", durationMs=" + duration
        + ", projection=" + projectionModeName(projectionMode)
        + ", fromServer=" + currentVideoFromServer);
    if (currentVideoFromServer && currentServerVideo != null) {
      currentServerVideo.lastPositionMs = position;
      currentServerVideo.watchedTimeMs = currentVideo.watchedTimeMs;
      currentServerVideo.projectionMode = projectionMode;
      if (duration > 0L) {
        currentServerVideo.durationMs = duration;
      }
      upsertServerPlayedVideo(currentServerVideo);
      queueServerHistorySync(currentServerVideo, false);
      renderServerDialogRows();
    } else {
      RecentVideoStore.save(this, recentVideos);
    }
  }

  private void updateWatchTimeTracking() {
    boolean shouldTrack = player != null && currentVideo != null && player.isPlaying();
    long now = SystemClock.elapsedRealtime();
    if (watchTimeTrackingActive) {
      long elapsedMs = Math.max(0L, now - lastWatchTimeElapsedMs);
      if (elapsedMs > 0L && currentVideo != null) {
        currentVideo.watchedTimeMs += elapsedMs;
      }
    }
    watchTimeTrackingActive = shouldTrack;
    lastWatchTimeElapsedMs = now;
  }

  private void resetWatchTimeTracking() {
    watchTimeTrackingActive = player != null && currentVideo != null && player.isPlaying();
    lastWatchTimeElapsedMs = SystemClock.elapsedRealtime();
  }

  private void resetWatchTimeTrackingAnchor() {
    if (watchTimeTrackingActive) {
      lastWatchTimeElapsedMs = SystemClock.elapsedRealtime();
    }
  }

  private void applyViewPosition() {
    if (presentation != null) {
      AppLog.d(TAG, () -> "Applying view position: center=" + sceneCenterDegrees
          + ", horizon=" + horizonDegrees);
      presentation.setViewOffsetDegrees(sceneCenterDegrees, horizonDegrees);
    }
  }

  private void applyProjectionMode() {
    if (presentation != null) {
      AppLog.d(TAG, () -> "Applying projection mode: " + projectionModeName(projectionMode));
      presentation.setProjectionMode(projectionMode);
    }
  }

  private void saveCurrentProjectionMode() {
    if (currentVideo == null) {
      return;
    }
    currentVideo.projectionMode = projectionMode;
    AppLog.d(TAG, () -> "Saving current projection mode: current=" + currentVideoTitleForLog()
        + ", projection=" + projectionModeName(projectionMode)
        + ", fromServer=" + currentVideoFromServer);
    if (currentVideoFromServer && currentServerVideo != null) {
      currentServerVideo.projectionMode = projectionMode;
      upsertServerPlayedVideo(currentServerVideo);
      queueServerHistorySync(currentServerVideo, false);
      renderServerDialogRows();
    } else {
      RecentVideoStore.save(this, recentVideos);
      renderRecentVideos();
    }
  }

  private void updateProjectionModeControl() {
    if (binding.projectionModeSpinner != null) {
      binding.projectionModeSpinner.setSelection(projectionModeIndex(projectionMode), false);
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

  private static int sanitizeProjectionMode(int value) {
    for (int mode : PROJECTION_MODE_VALUES) {
      if (mode == value) {
        return value;
      }
    }
    return Vr180Renderer.PROJECTION_EQUIRECT_VR180;
  }

  private static int projectionModeIndex(int value) {
    for (int i = 0; i < PROJECTION_MODE_VALUES.length; i++) {
      if (PROJECTION_MODE_VALUES[i] == value) {
        return i;
      }
    }
    return 0;
  }

  private long getKnownDuration() {
    if (windowsRemoteEnabled) {
      return windowsRemoteState == null ? 0L : windowsRemoteState.durationMs;
    }
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
      return video.watchedTimeMs > 0L
          ? formatTime(video.durationMs) + " | Watched " + formatWatchedTime(video.watchedTimeMs)
          : formatTime(video.durationMs);
    }
    String meta = "Resume " + formatTime(video.lastPositionMs) + " / " + formatTime(video.durationMs);
    if (video.watchedTimeMs > 0L) {
      meta += " | Watched " + formatWatchedTime(video.watchedTimeMs);
    }
    return meta;
  }

  private void upsertServerPlayedVideo(ServerVideo video) {
    AppLog.d(TAG, () -> "Upserting server played video: id=" + video.id
        + ", title=" + video.displayTitle()
        + ", positionMs=" + video.lastPositionMs
        + ", watchedTimeMs=" + video.watchedTimeMs);
    for (int i = 0; i < serverPlayedVideos.size(); i++) {
      if (serverPlayedVideos.get(i).id.equals(video.id)) {
        serverPlayedVideos.remove(i);
        break;
      }
    }
    serverPlayedVideos.add(0, video.copy());
  }

  private void syncCurrentServerProgressIfDue() {
    if (!currentVideoFromServer || currentServerVideo == null || currentVideo == null || player == null) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - lastServerHistorySyncElapsedMs < SERVER_HISTORY_SYNC_INTERVAL_MS) {
      return;
    }
    long duration = getKnownDuration();
    long position = Math.max(0L, player.getCurrentPosition());
    if (duration > 0L && duration - position < 2500L) {
      position = 0L;
    }
    currentServerVideo.lastPositionMs = position;
    currentServerVideo.watchedTimeMs = currentVideo.watchedTimeMs;
    currentServerVideo.projectionMode = projectionMode;
    if (duration > 0L) {
      currentServerVideo.durationMs = duration;
    }
    upsertServerPlayedVideo(currentServerVideo);
    queueServerHistorySync(currentServerVideo, false);
    lastServerHistorySyncElapsedMs = now;
  }

  private void queueServerHistorySync(ServerVideo video, boolean played) {
    String serverUrl = normalizeServerUrl(settingsPreferences.getString(PREF_SERVER_URL, ""));
    if (serverUrl.length() == 0) {
      AppLog.w(TAG, "Cannot queue server history without a server URL");
      return;
    }
    try {
      JSONObject payload = new JSONObject();
      payload.put("position_ms", video.lastPositionMs);
      payload.put("watched_ms", video.watchedTimeMs);
      payload.put("duration_ms", video.durationMs);
      payload.put("projection_mode", video.projectionMode);
      payload.put("loop_start_ms", video.loopStartMs == C.TIME_UNSET ? JSONObject.NULL : video.loopStartMs);
      payload.put("loop_end_ms", video.loopEndMs == C.TIME_UNSET ? JSONObject.NULL : video.loopEndMs);

      JSONObject pendingItem = new JSONObject();
      pendingItem.put("server_url", serverUrl);
      pendingItem.put("video_id", video.id);
      pendingItem.put("played", played);
      pendingItem.put("token", System.currentTimeMillis() + "-" + System.nanoTime());
      pendingItem.put("payload", payload);
      synchronized (serverHistoryPendingLock) {
        JSONObject pending = loadPendingServerHistory();
        pending.put(video.id, pendingItem);
        settingsPreferences.edit().putString(PREF_SERVER_HISTORY_PENDING, pending.toString()).commit();
      }
      submitPendingServerHistorySync(pendingItem);
    } catch (JSONException e) {
      AppLog.w(TAG, "Could not queue server history update: id=" + video.id, e);
    }
  }

  private void flushPendingServerHistorySyncs() {
    JSONObject pending;
    synchronized (serverHistoryPendingLock) {
      pending = loadPendingServerHistory();
    }
    JSONArray names = pending.names();
    if (names == null) {
      return;
    }
    for (int i = 0; i < names.length(); i++) {
      JSONObject pendingItem = pending.optJSONObject(names.optString(i));
      if (pendingItem != null) {
        submitPendingServerHistorySync(pendingItem);
      }
    }
  }

  private JSONObject loadPendingServerHistory() {
    try {
      return new JSONObject(settingsPreferences.getString(PREF_SERVER_HISTORY_PENDING, "{}"));
    } catch (JSONException e) {
      AppLog.w(TAG, "Could not parse pending server history; preserving raw preference", e);
      return new JSONObject();
    }
  }

  private void submitPendingServerHistorySync(JSONObject pendingItem) {
    serverHistoryExecutor.execute(() -> {
      String videoId = pendingItem.optString("video_id", "");
      String token = pendingItem.optString("token", "");
      String serverUrl = pendingItem.optString("server_url", "");
      JSONObject payload = pendingItem.optJSONObject("payload");
      if (videoId.length() == 0 || token.length() == 0 || serverUrl.length() == 0 || payload == null) {
        AppLog.w(TAG, "Skipping invalid pending server history item");
        return;
      }
      try {
        String endpoint = serverUrl + "/videos/" + videoId + "/history?played="
            + pendingItem.optBoolean("played", false);
        executeServerHistoryRequest("PUT", endpoint, payload.toString());
        synchronized (serverHistoryPendingLock) {
          JSONObject pending = loadPendingServerHistory();
          JSONObject current = pending.optJSONObject(videoId);
          if (current != null && token.equals(current.optString("token", ""))) {
            pending.remove(videoId);
            settingsPreferences.edit().putString(PREF_SERVER_HISTORY_PENDING, pending.toString()).commit();
          }
        }
      } catch (IOException e) {
        AppLog.w(TAG, "Server history sync failed; update remains queued: id=" + videoId, e);
      }
    });
  }

  private void queueClearServerHistory() {
    String serverUrl = normalizeServerUrl(settingsPreferences.getString(PREF_SERVER_URL, ""));
    if (serverUrl.length() == 0) {
      return;
    }
    serverHistoryExecutor.execute(() -> {
      try {
        executeServerHistoryRequest("DELETE", serverUrl + "/history", null);
      } catch (IOException e) {
        AppLog.w(TAG, "Could not clear server history", e);
        uiHandler.post(() -> setServerDialogStatus("Could not clear server history"));
      }
    });
  }

  private void executeServerHistoryRequest(String method, String endpoint, String body) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(15000);
    setServerApiKeyHeader(connection);
    if (body != null) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(bytes);
      }
    }
    int statusCode = connection.getResponseCode();
    InputStream stream = statusCode >= 200 && statusCode < 300
        ? connection.getInputStream()
        : connection.getErrorStream();
    String responseBody = readFully(stream);
    connection.disconnect();
    if (statusCode < 200 || statusCode >= 300) {
      throw new IOException("HTTP " + statusCode + (responseBody.length() > 0 ? ": " + responseBody : ""));
    }
  }

  private ServerVideo findServerVideo(List<ServerVideo> videos, String id) {
    for (ServerVideo video : videos) {
      if (video.id.equals(id)) {
        return video;
      }
    }
    return null;
  }

  private boolean hasServerWatchHistory(ServerVideo video) {
    return findServerVideo(serverPlayedVideos, video.id) != null;
  }

  private String latestServerHistoryId() {
    return serverPlayedVideos.isEmpty() ? "" : serverPlayedVideos.get(0).id;
  }

  private static long parseModifiedMillis(String value) {
    if (value == null || value.length() == 0) {
      return 0L;
    }
    try {
      return java.time.Instant.parse(value).toEpochMilli();
    } catch (RuntimeException e) {
      return 0L;
    }
  }

  private static String formatFileDate(long modifiedMs) {
    if (modifiedMs <= 0L) {
      return "";
    }
    try {
      return java.time.Instant.ofEpochMilli(modifiedMs)
          .atZone(java.time.ZoneId.systemDefault())
          .toLocalDate()
          .toString();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static String normalizeServerUrl(String value) {
    String url = value == null ? "" : value.trim();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    if (url.length() == 0) {
      return "";
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      url = "http://" + url;
    }
    return url;
  }

  private static String localHttpFallbackUrl(String value) {
    if (value != null && value.startsWith("https://")) {
      return "http://" + value.substring("https://".length());
    }
    return "";
  }

  private String getServerApiKey() {
    return settingsPreferences == null ? "" : settingsPreferences.getString(PREF_SERVER_API_KEY, "").trim();
  }

  private void setServerApiKeyHeader(HttpURLConnection connection) {
    String apiKey = getServerApiKey();
    if (apiKey.length() > 0) {
      connection.setRequestProperty("X-API-Key", apiKey);
    }
  }

  private static String readFully(InputStream stream) throws IOException {
    if (stream == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      char[] buffer = new char[4096];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
    }
    return builder.toString();
  }

  private static String formatBytes(long bytes) {
    if (bytes <= 0L) {
      return "Server stream";
    }
    double value = bytes;
    String[] units = {"B", "KB", "MB", "GB", "TB"};
    int unit = 0;
    while (value >= 1024.0 && unit < units.length - 1) {
      value /= 1024.0;
      unit++;
    }
    return String.format(Locale.US, unit == 0 ? "%.0f\u00A0%s" : "%.1f\u00A0%s", value, units[unit]);
  }

  private static String formatWatchedTime(long millis) {
    long minutes = Math.max(1L, Math.round(millis / 60000.0));
    if (minutes < 60L) {
      return minutes + "m";
    }
    long hours = minutes / 60L;
    long remainder = minutes % 60L;
    return remainder == 0L ? hours + "h" : hours + "h " + remainder + "m";
  }

  private static String joinParts(List<String> parts) {
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (part == null || part.length() == 0) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(" - ");
      }
      builder.append(part);
    }
    return builder.toString();
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

  private String currentVideoTitleForLog() {
    if (currentVideo != null) {
      return currentVideo.title;
    }
    if (currentServerVideo != null) {
      return currentServerVideo.displayTitle();
    }
    return "none";
  }

  private static String playbackStateName(int state) {
    switch (state) {
      case Player.STATE_BUFFERING:
        return "BUFFERING";
      case Player.STATE_READY:
        return "READY";
      case Player.STATE_ENDED:
        return "ENDED";
      case Player.STATE_IDLE:
      default:
        return "IDLE";
    }
  }

  private static String playWhenReadyReasonName(int reason) {
    switch (reason) {
      case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS:
        return "AUDIO_FOCUS_LOSS";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY:
        return "AUDIO_BECOMING_NOISY";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE:
        return "REMOTE";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM:
        return "END_OF_MEDIA_ITEM";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST:
        return "USER_REQUEST";
      default:
        return "UNKNOWN(" + reason + ")";
    }
  }

  private static String projectionModeName(int mode) {
    switch (mode) {
      case Vr180Renderer.PROJECTION_FISHEYE_VR190:
        return "VR190_FISHEYE";
      case Vr180Renderer.PROJECTION_FISHEYE_VR200:
        return "VR200_FISHEYE";
      case Vr180Renderer.PROJECTION_EQUIRECT_VR180:
      default:
        return "VR180_EQUIRECT";
    }
  }

  private static String displaySummary(Display display) {
    if (display == null) {
      return "none";
    }
    Display.Mode mode = display.getMode();
    if (mode == null) {
      return "id=" + display.getDisplayId() + ", name=" + display.getName();
    }
    return String.format(Locale.US,
        "id=%d, name=%s, mode=%dx%d@%.0fHz, flags=0x%X",
        display.getDisplayId(),
        display.getName(),
        mode.getPhysicalWidth(),
        mode.getPhysicalHeight(),
        mode.getRefreshRate(),
        display.getFlags());
  }

  private static void installDevelopmentHttpsTrust() {
    if (!BuildConfig.TRUST_ALL_SERVER_CERTS) {
      return;
    }
    try {
      TrustManager[] trustManagers = new TrustManager[]{
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
      };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagers, new SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
      HostnameVerifier verifier = (hostname, session) -> true;
      HttpsURLConnection.setDefaultHostnameVerifier(verifier);
    } catch (Exception e) {
      AppLog.w(TAG, "Could not install development HTTPS trust", e);
    }
  }

  private final class ServerVideoAdapter extends RecyclerView.Adapter<ServerVideoAdapter.ServerVideoViewHolder> {
    private final List<ServerVideo> videos = new ArrayList<>();

    ServerVideoAdapter() {
      setHasStableIds(true);
    }

    void setVideos(List<ServerVideo> nextVideos) {
      videos.clear();
      videos.addAll(nextVideos);
      notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
      return videos.get(position).id.hashCode();
    }

    @Override
    public int getItemCount() {
      return videos.size();
    }

    @Override
    public ServerVideoViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
      LinearLayout row = new LinearLayout(MainActivity.this);
      row.setOrientation(LinearLayout.VERTICAL);
      row.setMinimumHeight(dp(132));
      row.setPadding(dp(10), dp(10), dp(10), dp(10));
      row.setLayoutParams(new RecyclerView.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      LinearLayout titleRow = new LinearLayout(MainActivity.this);
      titleRow.setOrientation(LinearLayout.HORIZONTAL);
      titleRow.setGravity(Gravity.CENTER_VERTICAL);
      row.addView(titleRow, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      TextView title = new TextView(MainActivity.this);
      title.setTextColor(getColor(R.color.ink));
      title.setTextSize(16);
      title.setMaxLines(Integer.MAX_VALUE);
      titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

      MaterialButton flagButton = new MaterialButton(MainActivity.this);
      flagButton.setText("");
      flagButton.setIconPadding(0);
      flagButton.setMinWidth(0);
      flagButton.setMinimumWidth(0);
      flagButton.setPadding(0, 0, 0, 0);
      LinearLayout.LayoutParams flagParams = new LinearLayout.LayoutParams(dp(44), dp(44));
      flagParams.setMargins(dp(8), 0, 0, 0);
      titleRow.addView(flagButton, flagParams);

      LinearLayout detailRow = new LinearLayout(MainActivity.this);
      detailRow.setOrientation(LinearLayout.HORIZONTAL);
      detailRow.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
      );
      detailParams.setMargins(0, dp(8), 0, 0);
      row.addView(detailRow, detailParams);

      ImageView thumbnail = new ImageView(MainActivity.this);
      thumbnail.setBackgroundColor(getColor(R.color.panel_alt));
      thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
      LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(132), dp(76));
      imageParams.setMargins(0, 0, dp(12), 0);
      detailRow.addView(thumbnail, imageParams);

      LinearLayout textColumn = new LinearLayout(MainActivity.this);
      textColumn.setOrientation(LinearLayout.VERTICAL);
      detailRow.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

      TextView meta = new TextView(MainActivity.this);
      meta.setTextColor(getColor(R.color.muted));
      meta.setTextSize(13);
      meta.setMaxLines(3);
      textColumn.addView(meta);

      return new ServerVideoViewHolder(row, thumbnail, title, meta, flagButton);
    }

    @Override
    public void onBindViewHolder(ServerVideoViewHolder holder, int position) {
      ServerVideo video = videos.get(position);
      holder.itemView.setBackgroundColor(
          currentServerVideo != null && currentServerVideo.id.equals(video.id)
              ? getColor(R.color.panel_alt)
              : getColor(R.color.panel)
      );
      holder.title.setText(video.displayTitle());
      holder.title.setTextColor(hasServerWatchHistory(video) ? getColor(R.color.primary) : getColor(R.color.ink));
      holder.title.setPaintFlags(video.flaggedForDeletion
          ? holder.title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
          : holder.title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
      holder.meta.setText(formatServerMeta(video));
      holder.flagButton.setIconResource(video.flaggedForDeletion ? R.drawable.ic_replay_10_24 : R.drawable.ic_delete_24);
      holder.flagButton.setContentDescription(video.flaggedForDeletion ? "Undo deletion flag" : "Flag for deletion");
      holder.flagButton.setOnClickListener(v -> toggleServerDeleteFlag(video));
      holder.itemView.setOnClickListener(v -> playServerVideo(video));
      loadThumbnail(video, holder.thumbnail);
    }

    final class ServerVideoViewHolder extends RecyclerView.ViewHolder {
      final ImageView thumbnail;
      final TextView title;
      final TextView meta;
      final MaterialButton flagButton;

      ServerVideoViewHolder(
          View itemView,
          ImageView thumbnail,
          TextView title,
          TextView meta,
          MaterialButton flagButton) {
        super(itemView);
        this.thumbnail = thumbnail;
        this.title = title;
        this.meta = meta;
        this.flagButton = flagButton;
      }
    }
  }

  private static final class ServerVideoLoadResult {
    final String serverUrl;
    final List<ServerVideo> videos;
    final List<ServerVideo> playedVideos;
    final boolean refreshing;
    final long cacheAgeSeconds;

    ServerVideoLoadResult(String serverUrl, List<ServerVideo> videos, List<ServerVideo> playedVideos, boolean refreshing, long cacheAgeSeconds) {
      this.serverUrl = serverUrl;
      this.videos = videos;
      this.playedVideos = playedVideos;
      this.refreshing = refreshing;
      this.cacheAgeSeconds = cacheAgeSeconds;
    }
  }

  private static final class ServerVideo {
    final String id;
    final String title;
    final String fileName;
    final String subfolder;
    final Uri uri;
    final String thumbnailUrl;
    final long size;
    long durationMs;
    final long modifiedMs;
    final String fileDate;
    final String searchText;
    boolean flaggedForDeletion;
    long lastPositionMs;
    long watchedTimeMs;
    int projectionMode;
    long loopStartMs;
    long loopEndMs;

    ServerVideo(
        String id,
        String title,
        String fileName,
        String subfolder,
        Uri uri,
        String thumbnailUrl,
        long size,
        long durationMs,
        long modifiedMs,
        String fileDate,
        boolean flaggedForDeletion,
        long lastPositionMs,
        long watchedTimeMs,
        int projectionMode,
        long loopStartMs,
        long loopEndMs) {
      this.id = id;
      this.title = title;
      this.fileName = fileName;
      this.subfolder = subfolder;
      this.uri = uri;
      this.thumbnailUrl = thumbnailUrl;
      this.size = size;
      this.durationMs = durationMs;
      this.modifiedMs = modifiedMs;
      this.fileDate = fileDate.length() == 0 ? formatFileDate(modifiedMs) : fileDate;
      this.searchText = SearchMatcher.normalize(displayTitle() + " " + this.fileDate);
      this.flaggedForDeletion = flaggedForDeletion;
      this.lastPositionMs = lastPositionMs;
      this.watchedTimeMs = watchedTimeMs;
      this.projectionMode = sanitizeProjectionMode(projectionMode);
      this.loopStartMs = loopStartMs;
      this.loopEndMs = loopEndMs;
    }

    String displayTitle() {
      return subfolder.length() == 0 ? fileName : subfolder + " / " + fileName;
    }

    ServerVideo copy() {
      return new ServerVideo(
          id,
          title,
          fileName,
          subfolder,
          uri,
          thumbnailUrl,
          size,
          durationMs,
          modifiedMs,
          fileDate,
          flaggedForDeletion,
          lastPositionMs,
          watchedTimeMs,
          projectionMode,
          loopStartMs,
          loopEndMs
      );
    }
  }
}
