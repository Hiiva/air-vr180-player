package com.enricoros.nreal;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.enricoros.nreal.player.SavedScene;
import com.enricoros.nreal.player.SavedSceneStore;
import com.enricoros.nreal.player.SavedSceneStore.Target;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Owns the small, on-demand scenes panel; storage and HTTP never block playback. */
final class SavedScenesController implements AutoCloseable {
  interface Host {
    Target currentTarget();
    SavedScene captureScene();
    void restoreScene(Target target, SavedScene scene, Runnable success, Consumer<String> failure);
  }

  private interface Work { void run() throws Exception; }

  private final Activity activity;
  private final MaterialButton entryButton;
  private final Host host;
  private final SavedSceneStore store;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<SavedScene> scenes = new ArrayList<>();
  private Target target;
  private int generation;
  private boolean closed;
  private boolean busy;
  private boolean loaded;
  private String error = "";
  private Runnable retry;
  private BottomSheetDialog dialog;
  private LinearLayout sheet;
  private MaterialButton saveButton;
  private MaterialButton retryButton;
  private TextView statusText;
  private RecyclerView list;
  private SceneAdapter adapter;

  SavedScenesController(Activity activity, MaterialButton entryButton, Host host) {
    this.activity = activity;
    this.entryButton = entryButton;
    this.host = host;
    store = new SavedSceneStore(activity.getApplicationContext());
    entryButton.setOnClickListener(v -> show());
    update();
  }

  void update() {
    if (closed) return;
    Target next = host.currentTarget();
    if (target == null ? next != null : !target.samePlayback(next)) {
      dismiss();
      target = next;
      generation++;
      scenes.clear();
      loaded = false;
      busy = false;
      error = "";
      retry = null;
      if (target != null) load();
    }
    entryButton.setEnabled(target != null);
    entryButton.setText(scenes.isEmpty() ? "Scenes" : "Scenes · " + scenes.size());
    updateSaveButton();
  }

  private void load() {
    if (closed || target == null || busy) return;
    Target capturedTarget = target;
    int token = generation;
    busy = true;
    error = "";
    render();
    worker.execute(() -> {
      try {
        List<SavedScene> result = store.load(capturedTarget);
        main.post(() -> {
          if (!isCurrent(token)) return;
          busy = false;
          loaded = true;
          scenes.clear();
          scenes.addAll(result);
          retry = null;
          render();
        });
      } catch (Exception e) {
        main.post(() -> failed(token, "Could not load scenes", e, this::load));
      }
    });
  }

  private boolean isCurrent(int token) {
    return !closed && token == generation;
  }

  private void show() {
    update();
    if (target == null || dialog != null) return;
    dialog = new BottomSheetDialog(activity);
    sheet = new LinearLayout(activity);
    sheet.setOrientation(LinearLayout.VERTICAL);
    sheet.setPadding(dp(16), dp(12), dp(16), dp(16));
    sheet.setBackgroundColor(activity.getColor(R.color.panel));

    LinearLayout heading = new LinearLayout(activity);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout titles = new LinearLayout(activity);
    titles.setOrientation(LinearLayout.VERTICAL);
    TextView title = text("Scenes", 20, R.color.ink);
    title.setTypeface(null, Typeface.BOLD);
    titles.addView(title);
    TextView videoTitle = text(target.title, 13, R.color.muted);
    videoTitle.setMaxLines(2);
    videoTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    titles.addView(videoTitle);
    heading.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    MaterialButton close = iconButton(R.drawable.ic_close_24, "Close scenes");
    close.setOnClickListener(v -> dismiss());
    heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
    sheet.addView(heading);

    saveButton = new MaterialButton(activity);
    saveButton.setIconResource(R.drawable.ic_bookmark_24);
    saveButton.setMaxLines(2);
    saveButton.setOnClickListener(v -> {
      SavedScene scene = host.captureScene();
      if (scene != null && !busy && loaded) put(scene, true);
    });
    LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(64));
    saveParams.topMargin = dp(12);
    sheet.addView(saveButton, saveParams);

    statusText = text("", 13, R.color.muted);
    statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    sheet.addView(statusText);
    retryButton = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
    retryButton.setText("Retry");
    retryButton.setOnClickListener(v -> { if (retry != null && !busy) retry.run(); });
    sheet.addView(retryButton, new LinearLayout.LayoutParams(-2, dp(48)));

    list = new RecyclerView(activity);
    list.setLayoutManager(new LinearLayoutManager(activity));
    adapter = new SceneAdapter();
    list.setAdapter(adapter);
    sheet.addView(list, new LinearLayout.LayoutParams(-1, dp(120)));
    dialog.setContentView(sheet);
    dialog.setOnDismissListener(ignored -> {
      dialog = null;
      sheet = null;
      adapter = null;
      list = null;
      saveButton = null;
      statusText = null;
      retryButton = null;
    });
    dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    dialog.getBehavior().setSkipCollapsed(true);
    dialog.show();
    render();
    // Other controllers may have edited this video's shared server scenes.
    if (target.isServer() && !busy) load();
  }

  private void updateSaveButton() {
    if (saveButton == null) return;
    SavedScene current = host.captureScene();
    boolean duplicate = current != null && scenes.stream().anyMatch(scene -> scene.sameRange(current));
    saveButton.setEnabled(loaded && !busy && current != null && !duplicate);
    saveButton.setText(current == null ? "Save position" :
        (duplicate ? "Already saved" : current.isLoop() ? "Save loop" : "Save position")
            + "\n" + range(current));
  }

  private void render() {
    scenes.sort(Comparator.comparingLong(scene -> scene.startMs));
    entryButton.setText(scenes.isEmpty() ? "Scenes" : "Scenes · " + scenes.size());
    updateSaveButton();
    if (dialog == null) return;
    String message = !error.isEmpty() ? error : busy ? (loaded ? "Updating scenes…" : "Loading scenes…")
        : scenes.isEmpty() ? "No saved scenes in this video" : "";
    statusText.setText(message);
    statusText.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    retryButton.setVisibility(!error.isEmpty() && retry != null ? View.VISIBLE : View.GONE);
    retryButton.setEnabled(!busy);
    int maxListHeight = Math.max(dp(80), activity.getResources().getDisplayMetrics().heightPixels * 3 / 4 - dp(220));
    list.getLayoutParams().height = Math.min(maxListHeight, dp(scenes.size() * 76));
    list.requestLayout();
    adapter.notifyDataSetChanged();
  }

  private void put(SavedScene scene, boolean showUndo) {
    Target capturedTarget = target;
    mutate(() -> store.put(capturedTarget, scene), () -> {
      scenes.removeIf(existing -> existing.id.equals(scene.id));
      scenes.add(scene);
    }, scene.name.isEmpty() ? "Scene saved" : "Name saved",
        showUndo ? () -> delete(scene, false) : null, () -> put(scene, showUndo));
  }

  private void delete(SavedScene scene, boolean showUndo) {
    Target capturedTarget = target;
    mutate(() -> store.delete(capturedTarget, scene),
        () -> scenes.removeIf(existing -> existing.id.equals(scene.id)), "Scene deleted",
        showUndo ? () -> put(scene, false) : null, () -> delete(scene, showUndo));
  }

  private void mutate(Work work, Runnable apply, String message, Runnable undo, Runnable retryAction) {
    if (closed || busy || target == null) return;
    int token = generation;
    busy = true;
    error = "";
    render();
    worker.execute(() -> {
      try {
        work.run();
        main.post(() -> {
          if (!isCurrent(token)) return;
          busy = false;
          retry = null;
          apply.run();
          render();
          if (sheet != null) {
            Snackbar notice = Snackbar.make(sheet, message, Snackbar.LENGTH_LONG);
            if (undo != null) notice.setAction("Undo", v -> { if (isCurrent(token)) undo.run(); });
            notice.show();
          }
        });
      } catch (Exception e) {
        main.post(() -> failed(token, "Could not save changes", e, retryAction));
      }
    });
  }

  private void failed(int token, String message, Exception exception, Runnable retryAction) {
    if (!isCurrent(token)) return;
    busy = false;
    error = message + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    retry = retryAction;
    render();
  }

  private void restore(SavedScene scene) {
    if (busy || target == null) return;
    int token = generation;
    busy = true;
    error = "";
    render();
    host.restoreScene(target, scene, () -> {
      if (!isCurrent(token)) return;
      busy = false;
      dismiss();
    }, message -> {
      if (!isCurrent(token)) return;
      busy = false;
      error = message;
      retry = () -> restore(scene);
      render();
    });
  }

  private void menu(View anchor, SavedScene scene) {
    if (busy) return;
    int token = generation;
    PopupMenu menu = new PopupMenu(activity, anchor);
    menu.getMenu().add("Rename");
    menu.getMenu().add("Delete");
    menu.setOnMenuItemClickListener(item -> {
      if (!isCurrent(token) || busy) return true;
      if ("Delete".contentEquals(item.getTitle())) delete(scene, true);
      else {
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setHint(range(scene));
        name.setText(scene.name);
        LinearLayout container = new LinearLayout(activity);
        container.setPadding(dp(24), 0, dp(24), 0);
        container.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(activity).setTitle("Name (optional)").setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (ignored, which) -> {
              if (isCurrent(token) && !busy) put(scene.renamed(name.getText().toString().trim()), false);
            }).show();
      }
      return true;
    });
    menu.show();
  }

  private final class SceneAdapter extends RecyclerView.Adapter<SceneHolder> {
    @Override public int getItemCount() { return scenes.size(); }

    @Override public SceneHolder onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout row = new LinearLayout(activity);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setMinimumHeight(dp(72));
      row.setBackgroundResource(android.R.drawable.list_selector_background);
      row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
      LinearLayout labels = new LinearLayout(activity);
      labels.setPadding(0, dp(12), dp(8), dp(12));
      labels.setOrientation(LinearLayout.VERTICAL);
      TextView title = text("", 16, R.color.ink);
      title.setMaxLines(2);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      TextView detail = text("", 13, R.color.muted);
      labels.addView(title);
      labels.addView(detail);
      row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      MaterialButton more = iconButton(R.drawable.ic_more_vert_24, "Edit scene");
      row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
      row.setFocusable(true);
      return new SceneHolder(row, title, detail, more);
    }

    @Override public void onBindViewHolder(SceneHolder holder, int position) {
      SavedScene scene = scenes.get(position);
      String title = scene.name.isEmpty() ? range(scene) : scene.name;
      holder.title.setText(title);
      holder.detail.setText((scene.isLoop() ? "Loop" : "Position") + (scene.name.isEmpty() ? "" : " · " + range(scene)));
      holder.itemView.setEnabled(!busy);
      holder.more.setEnabled(!busy);
      holder.more.setContentDescription("Edit scene " + title);
      holder.itemView.setOnClickListener(v -> restore(scene));
      holder.more.setOnClickListener(v -> menu(v, scene));
    }
  }

  private static final class SceneHolder extends RecyclerView.ViewHolder {
    final TextView title;
    final TextView detail;
    final MaterialButton more;
    SceneHolder(View row, TextView title, TextView detail, MaterialButton more) {
      super(row);
      this.title = title;
      this.detail = detail;
      this.more = more;
    }
  }

  private MaterialButton iconButton(int icon, String description) {
    MaterialButton button = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
    button.setText("");
    button.setIconResource(icon);
    button.setIconPadding(0);
    button.setPadding(dp(12), 0, dp(12), 0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setContentDescription(description);
    return button;
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(activity.getColor(color));
    return view;
  }

  private static String range(SavedScene scene) {
    return time(scene.startMs) + (scene.isLoop() ? "–" + time(scene.endMs) : "");
  }

  private static String time(long ms) {
    long seconds = ms / 1000;
    return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

  private void dismiss() { if (dialog != null) dialog.dismiss(); }

  @Override public void close() {
    closed = true;
    generation++;
    dismiss();
    worker.shutdown();
  }
}
