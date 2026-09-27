package com.enricoros.nreal;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import com.enricoros.nreal.player.Vr180Renderer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A temporary comparison session. All positions map to the first file's timeline. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class VideoComparisonController {
  interface Host {
    ExoPlayer player();
    MainActivity.ServerVideo current();
    boolean available();
    void begin();
    void pick();
    int slot();
    long frameTimeUs();
    ExoPlayer create(int slot);
    boolean attach(ExoPlayer player, int slot);
    void release(ExoPlayer player, int slot);
    void select(MainActivity.ServerVideo video, ExoPlayer player, int slot);
    float volume();
    void changed();
    long loopStart();
    long loopEnd();
    boolean loopPaused();
    void loop(long start, long end, boolean paused);
    void finish();
  }

  private static final class Version {
    final MainActivity.ServerVideo video;
    final ExoPlayer player;
    final int slot;
    long offsetMs;
    volatile long frameTimeUs = C.TIME_UNSET;
    volatile int generation;
    boolean hasFrame;
    VideoFrameMetadataListener frameListener;
    Version(MainActivity.ServerVideo video, ExoPlayer player, int slot) {
      this.video = video.copy();
      this.player = player;
      this.slot = slot;
    }
  }

  private final Activity activity;
  private final Host host;
  private final MaterialButton entry;
  private final LinearLayout panel;
  private final List<Version> versions = new ArrayList<>();
  private final List<MaterialButton> transport = new ArrayList<>();
  private TextView status;
  private MaterialButton toggle;
  private MaterialButton offset;
  private MaterialButton retry;
  private MaterialButton playPause;
  private LinearLayout alignmentPanel;
  private final List<MaterialButton> alignmentButtons = new ArrayList<>();
  private int selected;
  private boolean picking;
  private boolean loading;
  private String message = "";
  private boolean requestedPlaying;
  private boolean groupRunning;
  private float groupSpeed = 1f;
  private long logicalTargetMs;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long lastUiMs;
  private final Runnable synchronizer = new Runnable() {
    @Override public void run() {
      if (!active()) return;
      synchronizePlayers();
      if (SystemClock.elapsedRealtime() - lastUiMs >= 200) {
        update();
        lastUiMs = SystemClock.elapsedRealtime();
      }
      handler.postDelayed(this, 40);
    }
  };

  VideoComparisonController(Activity activity, MaterialButton entry, LinearLayout panel, Host host) {
    this.activity = activity;
    this.entry = entry;
    this.panel = panel;
    this.host = host;
    entry.setOnClickListener(v -> {
      if (!active()) {
        host.begin();
        requestedPlaying = host.player().getPlayWhenReady();
        groupSpeed = host.player().getPlaybackParameters().speed;
        versions.add(new Version(host.current(), host.player(), host.slot()));
        selected = 0;
        Version reference = versions.get(0);
        observeFrames(reference);
        reference.frameTimeUs = host.frameTimeUs();
        reference.hasFrame = reference.frameTimeUs != C.TIME_UNSET;
        message = "";
        buildPanel();
        seekTo(host.player().getCurrentPosition());
        handler.post(synchronizer);
      }
      picking = true;
      host.pick();
      update();
    });
    update();
  }

  boolean active() { return !versions.isEmpty(); }
  boolean picking() { return picking; }
  void cancelPick() { picking = false; }

  void add(MainActivity.ServerVideo video) {
    picking = false;
    if (versions.size() >= Vr180Renderer.VIDEO_GROUP_COUNT) return;
    for (Version version : versions) {
      if (version.video.uri.equals(video.uri)) {
        message = "Already in this comparison. Choose another version.";
        update();
        return;
      }
    }
    long logical = logicalPosition();
    int slot = 0;
    while (slotUsed(slot)) slot += 2;
    ExoPlayer addedPlayer = host.create(slot);
    Version added = new Version(video, addedPlayer, slot);
    addedPlayer.setVolume(0);
    addedPlayer.setPlaybackSpeed(host.player().getPlaybackParameters().speed);
    addedPlayer.setSeekParameters(SeekParameters.EXACT);
    addedPlayer.setMediaItem(MediaItem.fromUri(video.uri), Math.max(0, logical));
    versions.add(added);
    host.attach(addedPlayer, slot);
    addedPlayer.prepare();
    // Adding is preparation, never part of the toggle path. Keep showing the reference.
    seekLogical(logical);
    message = "Preparing the added version. Toggle becomes available when every frame is ready.";
    update();
  }

  private boolean slotUsed(int slot) {
    for (Version version : versions) if (version.slot == slot) return true;
    return false;
  }

  boolean playing() { return requestedPlaying; }
  boolean preparing() { return loading; }

  void end() {
    if (!active()) return;
    close();
    host.player().setSeekParameters(SeekParameters.CLOSEST_SYNC);
    host.player().setVolume(host.volume());
    host.player().setPlaybackSpeed(groupSpeed);
    host.player().setPlayWhenReady(requestedPlaying);
    host.finish();
    update();
  }

  void close() {
    handler.removeCallbacks(synchronizer);
    for (Version version : versions) {
      version.generation++;
      if (version.frameListener != null) version.player.clearVideoFrameMetadataListener(version.frameListener);
      if (version.player != host.player()) host.release(version.player, version.slot);
    }
    versions.clear();
    picking = false;
    loading = false;
    groupRunning = false;
    panel.removeAllViews();
  }

  private long logicalPosition() {
    return loading ? logicalTargetMs : host.player().getCurrentPosition() - versions.get(selected).offsetMs;
  }

  private boolean contains(Version version, long position) {
    long duration = version.player.getDuration();
    if (duration <= 0) duration = version.video.durationMs;
    return position >= 0 && (duration <= 0 || position < duration);
  }

  private void switchTo(int next) {
    if (next == selected || !alignedForSwitch()) return;
    Version target = versions.get(next);
    long delta = target.offsetMs - versions.get(selected).offsetMs;
    long start = shifted(host.loopStart(), delta);
    long end = shifted(host.loopEnd(), delta);
    if (!validLoop(target, start, end)) {
      message = "No matching loop in this version. Clear the loop or choose a shared section.";
      update();
      return;
    }
    boolean pausedLoop = host.loopPaused();
    selected = next;
    // No seek, prepare, surface reattachment, media replacement or network operation here.
    host.select(target.video, target.player, target.slot);
    applyVolume();
    host.loop(start, end, pausedLoop);
    AppLog.i("VideoComparison", "Instant texture switch: slot=" + target.slot
        + ", positionMs=" + target.player.getCurrentPosition());
    message = "";
    host.changed();
  }

  private static long shifted(long time, long delta) {
    return time == C.TIME_UNSET ? C.TIME_UNSET : time + delta;
  }

  private boolean validLoop(Version version, long start, long end) {
    long duration = version.player.getDuration();
    if (duration <= 0) duration = version.video.durationMs;
    return (start == C.TIME_UNSET || contains(version, start))
        && (end == C.TIME_UNSET || end > 0 && (duration <= 0 || end <= duration));
  }

  private void observeFrames(Version version) {
    int generation = ++version.generation;
    version.frameTimeUs = C.TIME_UNSET;
    version.hasFrame = false;
    version.frameListener = (pts, release, format, mediaFormat) -> {
      if (generation == version.generation) version.frameTimeUs = pts;
    };
    version.player.setVideoFrameMetadataListener(version.frameListener);
  }

  void surfaceFrameAvailable(int slot) {
    for (Version version : versions) {
      if (slot / 2 == version.slot / 2 && version.frameTimeUs != C.TIME_UNSET) version.hasFrame = true;
    }
  }

  void surfacesChanged() {
    for (Version version : versions) host.attach(version.player, version.slot);
  }

  void surfaceLost(int slot) {
    for (Version version : versions) {
      if (slot / 2 == version.slot / 2) {
        if (!loading) logicalTargetMs = logicalPosition();
        loading = true;
        pauseAll();
        version.player.clearVideoSurface();
        observeFrames(version);
      }
    }
  }

  void seekTo(long position) {
    if (active()) seekLogical(position - versions.get(selected).offsetMs);
  }

  private void seekLogical(long logical) {
    logicalTargetMs = logical;
    loading = true;
    pauseAll();
    for (Version version : versions) {
      long position = logical + version.offsetMs;
      version.player.setSeekParameters(SeekParameters.EXACT);
      if (position == version.player.getCurrentPosition() && version.hasFrame) continue;
      observeFrames(version);
      // Invalid matches remain explicitly unavailable, rather than being shown as aligned.
      version.player.seekTo(Math.max(0, position));
    }
    applyVolume();
    update();
  }

  private void pauseAll() {
    groupRunning = false;
    for (Version version : versions) {
      version.player.pause();
      version.player.setPlaybackSpeed(groupSpeed);
    }
  }

  void setPlaying(boolean playing) {
    if (!active()) return;
    long logical = logicalPosition();
    requestedPlaying = playing;
    // A pause settles every decoder on the same logical timestamp before toggles resume.
    seekLogical(logical);
    host.changed();
  }

  void setSpeed(float speed) {
    groupSpeed = speed;
    for (Version version : versions) version.player.setPlaybackSpeed(speed);
  }

  void applyVolume() {
    for (int i = 0; i < versions.size(); i++) versions.get(i).player.setVolume(i == selected ? host.volume() : 0f);
  }

  private boolean allFramesReady(boolean requireBuffer) {
    for (Version version : versions) {
      ExoPlayer player = version.player;
      if (!version.hasFrame || player.getPlayerError() != null || player.getPlaybackState() != Player.STATE_READY
          || !contains(version, logicalTargetMs + version.offsetMs)) return false;
      // Fill ahead before starting a clip. A size-limited stream may stop loading earlier.
      long duration = player.getDuration();
      long remaining = duration > 0 ? duration - player.getCurrentPosition() : 1500;
      if (requireBuffer && player.getTotalBufferedDuration() < Math.min(1500, remaining)
          && player.isLoading()) return false;
    }
    return true;
  }

  private void synchronizePlayers() {
    if (versions.isEmpty()) return;
    for (Version version : versions) {
      if (version.player.getPlayerError() != null) {
        pauseAll();
        loading = true;
        message = "Could not prepare " + version.video.displayTitle() + ": "
            + version.player.getPlayerError().getErrorCodeName() + ". Retry or remove this version.";
        return;
      }
      if (!contains(version, (loading ? logicalTargetMs : logicalPosition()) + version.offsetMs)) {
        if (!loading) logicalTargetMs = logicalPosition();
        pauseAll();
        loading = true;
        message = "Outside the shared duration. Seek to a shared section or remove this version.";
        return;
      }
    }
    if (loading) {
      if (!allFramesReady(requestedPlaying)) return;
      loading = false;
      message = "";
      groupRunning = requestedPlaying;
      if (requestedPlaying) for (Version version : versions) version.player.play();
      AppLog.i("VideoComparison", "All versions ready: count=" + versions.size()
          + ", logicalMs=" + logicalTargetMs + ", playing=" + requestedPlaying);
      host.changed();
      return;
    }
    if (groupRunning) {
      Version reference = versions.get(0);
      long logical = reference.player.getCurrentPosition();
      for (Version version : versions) {
        long drift = version.player.getCurrentPosition() - version.offsetMs - logical;
        if (version.player.getPlaybackState() != Player.STATE_READY) {
          // A stalled decoder must not silently compare a different moment.
          seekLogical(logical);
          message = "Synchronizing all versions...";
          return;
        }
        if (version != reference) {
          // Audio sinks acquire their clocks at different times. Correct the silent
          // follower gradually instead of repeatedly flushing both decoders on startup.
          float correction = Math.abs(drift) <= 5 ? 0 : Math.max(-0.08f, Math.min(0.08f, -drift / 500f));
          float speed = groupSpeed * (1f + correction);
          if (Math.abs(version.player.getPlaybackParameters().speed - speed) > 0.002f) {
            version.player.setPlaybackSpeed(speed);
          }
        }
      }
    }
  }

  private boolean alignedForSwitch() {
    if (loading || !allFramesReady(false)) return false;
    long logical = versions.get(0).player.getCurrentPosition();
    for (Version version : versions) {
      if (Math.abs(version.player.getCurrentPosition() - version.offsetMs - logical) > 20) return false;
    }
    return true;
  }

  private void seek(long position) { seekTo(position); }

  private long frameStep() {
    Format format = host.player().getVideoFormat();
    return format != null && format.frameRate > 0
        ? Math.max(1L, Math.round(1000.0 / format.frameRate)) : 1L;
  }

  private void step(long delta) {
    Version version = versions.get(selected);
    long pts = version.frameTimeUs;
    long base = version.hasFrame && pts != C.TIME_UNSET ? pts / 1000 : host.player().getCurrentPosition();
    long position = base + delta;
    if (!contains(version, position)) return;
    requestedPlaying = false;
    host.loop(host.loopStart(), host.loopEnd(), true);
    message = "";
    seek(position);
  }

  private void align(long newOffset) {
    if (selected == 0 || loading) return;
    Version version = versions.get(selected);
    long logical = logicalPosition();
    long delta = newOffset - version.offsetMs;
    long start = shifted(host.loopStart(), delta);
    long end = shifted(host.loopEnd(), delta);
    if (!contains(version, logical + newOffset) || !validLoop(version, start, end)) {
      message = "Offset is outside this file or loop. Clear the loop or choose another time.";
      update();
      return;
    }
    requestedPlaying = false;
    version.offsetMs = newOffset;
    host.loop(start, end, true);
    seekLogical(logical);
  }

  private void removeVersion() {
    if (versions.size() < 2) return;
    String[] names = new String[versions.size() - 1];
    for (int i = 1; i < versions.size(); i++) names[i - 1] = versions.get(i).video.displayTitle();
    new MaterialAlertDialogBuilder(activity).setTitle("Remove comparison version")
        .setItems(names, (dialog, which) -> {
          int index = which + 1;
          long logical = logicalPosition();
          Version removed = versions.get(index);
          if (selected == index) {
            long delta = versions.get(0).offsetMs - removed.offsetMs;
            long start = shifted(host.loopStart(), delta);
            long end = shifted(host.loopEnd(), delta);
            boolean paused = host.loopPaused();
            selected = 0;
            host.select(versions.get(0).video, versions.get(0).player, versions.get(0).slot);
            host.loop(start, end, paused);
          } else if (selected > index) selected--;
          removed.generation++;
          host.release(removed.player, removed.slot);
          versions.remove(index);
          message = "";
          seekLogical(logical);
        }).setNegativeButton("Cancel", null).show();
  }

  private void editOffset() {
    EditText input = new EditText(activity);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
    input.setText(Long.toString(versions.get(selected).offsetMs));
    androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
        .setTitle("Version offset in milliseconds")
        .setMessage("Positive = this file's matching frame occurs later. Use a distinctive paused frame to align; check again near the clip you compare.")
        .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
    dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(button -> {
      try {
        long value = Long.parseLong(input.getText().toString().trim());
        if (Math.abs((double) value) > 86_400_000L) throw new NumberFormatException();
        align(value);
        dialog.dismiss();
      } catch (NumberFormatException e) { input.setError("Enter milliseconds between -86400000 and 86400000"); }
    }));
    dialog.show();
  }

  private void clip(long length) {
    long start = host.player().getCurrentPosition();
    long logicalStart = logicalPosition();
    for (Version version : versions) {
      if (!contains(version, logicalStart + version.offsetMs)
          || !validLoop(version, logicalStart + version.offsetMs, logicalStart + version.offsetMs + length)) {
        message = "Choose a clip inside every version's duration.";
        update();
        return;
      }
    }
    host.loop(start, start + length, false);
    setPlaying(true);
    message = "";
    update();
  }

  private void buildPanel() {
    panel.removeAllViews();
    transport.clear();
    alignmentButtons.clear();
    status = new TextView(activity);
    status.setTextColor(activity.getColor(R.color.ink));
    status.setTextSize(14);
    status.setLines(7);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    panel.addView(status);
    toggle = button(panel, "Toggle version", () -> switchTo((selected + 1) % versions.size()));
    button(panel, "Choose version", () -> {
      String[] names = new String[versions.size()];
      for (int i = 0; i < names.length; i++) names[i] = (i == 0 ? "Reference: " : (i + 1) + ": ") + versions.get(i).video.displayTitle();
      new MaterialAlertDialogBuilder(activity).setTitle("Comparison versions")
          .setSingleChoiceItems(names, selected, (dialog, which) -> {
            dialog.dismiss();
            if (which != selected) switchTo(which);
          }).setNegativeButton("Cancel", null).show();
    });
    LinearLayout steps = row();
    button(steps, "−frame", () -> step(-frameStep()));
    playPause = button(steps, "Pause", () -> {
      setPlaying(!requestedPlaying);
      update();
    });
    button(steps, "+frame", () -> step(frameStep()));
    LinearLayout clips = row();
    button(clips, "Loop 3 s", () -> clip(3000));
    button(clips, "Loop 5 s", () -> clip(5000));
    button(panel, "Replay loop", () -> {
      if (host.loopStart() == C.TIME_UNSET || host.loopEnd() == C.TIME_UNSET) return;
      seek(host.loopStart());
      host.loop(host.loopStart(), host.loopEnd(), false);
      setPlaying(true);
    });
    button(panel, "Alignment controls", () -> alignmentPanel.setVisibility(
        alignmentPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    alignmentPanel = new LinearLayout(activity);
    alignmentPanel.setOrientation(LinearLayout.VERTICAL);
    alignmentPanel.setVisibility(View.GONE);
    panel.addView(alignmentPanel);
    TextView help = new TextView(activity);
    help.setText("Match a distinctive paused frame using each version's offset, then toggle. Offsets last for this session. Frame nudges use nominal FPS; use 1 ms for fine alignment or variable frame rate. Different edits may need realignment near each clip.");
    help.setTextColor(activity.getColor(R.color.muted));
    alignmentPanel.addView(help);
    offset = button(alignmentPanel, "Offset", this::editOffset);
    int alignmentStart = transport.size();
    LinearLayout alignment = row(alignmentPanel);
    button(alignment, "Offset −frame", () -> align(versions.get(selected).offsetMs - frameStep()));
    button(alignment, "Offset +frame", () -> align(versions.get(selected).offsetMs + frameStep()));
    LinearLayout fine = row(alignmentPanel);
    button(fine, "Offset −1 ms", () -> align(versions.get(selected).offsetMs - 1));
    button(fine, "Offset +1 ms", () -> align(versions.get(selected).offsetMs + 1));
    alignmentButtons.addAll(transport.subList(alignmentStart, transport.size()));
    retry = button(panel, "Retry streams", () -> {
      for (Version version : versions) {
        if (version.player.getPlayerError() != null) version.player.prepare();
      }
      message = "";
      seekLogical(logicalPosition());
    });
    transport.remove(retry);
    MaterialButton remove = button(panel, "Remove version", this::removeVersion);
    transport.remove(remove);
    MaterialButton done = button(panel, "End comparison", this::end);
    transport.remove(done);
  }

  private LinearLayout row() {
    return row(panel);
  }

  private LinearLayout row(LinearLayout parent) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    parent.addView(row);
    return row;
  }

  private MaterialButton button(LinearLayout parent, String label, Runnable action) {
    MaterialButton button = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
    button.setText(label);
    button.setMinWidth(0);
    button.setMinimumHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
    button.setOnClickListener(v -> { if (active()) action.run(); });
    parent.addView(button, parent.getOrientation() == LinearLayout.VERTICAL ? new LinearLayout.LayoutParams(-1, -2)
        : new LinearLayout.LayoutParams(0, -2, 1));
    transport.add(button);
    return button;
  }

  private void setTextIfChanged(TextView view, String text) {
    if (!text.contentEquals(view.getText())) view.setText(text);
  }

  void update() {
    entry.setVisibility(host.available() || active() ? View.VISIBLE : View.GONE);
    entry.setEnabled(host.available() && versions.size() < Vr180Renderer.VIDEO_GROUP_COUNT);
    setTextIfChanged(entry, versions.size() == Vr180Renderer.VIDEO_GROUP_COUNT ? "3 versions preloaded" : active() ? "Add comparison version" : "Compare server versions");
    panel.setVisibility(active() ? View.VISIBLE : View.GONE);
    if (!active() || status == null) return;
    ExoPlayer player = host.player();
    Version version = versions.get(selected);
    Format format = player.getVideoFormat();
    String details = format == null || loading ? "" : String.format(Locale.US, " · %d × %d", format.width, format.height)
        + (format.frameRate > 0 ? String.format(Locale.US, " · %.3f fps", format.frameRate) : " · FPS unknown");
    long pts = version.frameTimeUs;
    String frame = !version.hasFrame || pts == C.TIME_UNSET ? "Waiting for video frame"
        : String.format(Locale.US, "Frame PTS %.6f s", pts / 1_000_000.0);
    setTextIfChanged(status, String.format(Locale.US, "%s %d/%d: %s\nTime %.3f s · offset %+d ms%s\n%s%s",
        loading ? "Preparing" : selected == 0 ? "Reference" : "Version", selected + 1, versions.size(),
        version.video.displayTitle(), player.getCurrentPosition() / 1000.0, version.offsetMs, details, frame,
        message.isEmpty() ? "" : "\n" + message));
    for (MaterialButton button : transport) button.setEnabled(!loading);
    toggle.setEnabled(versions.size() > 1 && alignedForSwitch());
    int next = (selected + 1) % versions.size();
    setTextIfChanged(toggle, next == 0 ? "Show reference" : "Show version " + (next + 1));
    setTextIfChanged(offset, selected == 0 ? "Reference offset: 0 ms" : "Set offset: " + version.offsetMs + " ms");
    offset.setEnabled(!loading && selected != 0);
    for (MaterialButton button : alignmentButtons) button.setEnabled(!loading && selected != 0);
    setTextIfChanged(playPause, requestedPlaying ? "Pause" : "Play");
    playPause.setEnabled(true);
    boolean failed = false;
    for (Version item : versions) failed |= item.player.getPlayerError() != null;
    retry.setVisibility(failed ? View.VISIBLE : View.GONE);
  }
}
