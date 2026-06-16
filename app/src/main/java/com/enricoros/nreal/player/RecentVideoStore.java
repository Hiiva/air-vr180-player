package com.enricoros.nreal.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.enricoros.nreal.AppLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class RecentVideoStore {
  private static final String TAG = "RecentVideoStore";
  private static final String PREF_NAME = "recent_videos";
  private static final String KEY_ITEMS = "items";
  private static final int MAX_ITEMS = 24;

  private RecentVideoStore() {
  }

  public static List<RecentVideo> load(Context context) {
    SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    String rawJson = preferences.getString(KEY_ITEMS, "[]");
    List<RecentVideo> videos = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(rawJson);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) {
          continue;
        }
        String uriText = item.optString("uri", null);
        String title = item.optString("title", "Untitled video");
        if (uriText == null || uriText.length() == 0) {
          continue;
        }
        videos.add(new RecentVideo(
            Uri.parse(uriText),
            title,
            item.optLong("size", 0L),
            item.optLong("positionMs", 0L),
            item.optLong("watchedTimeMs", 0L),
            item.optLong("durationMs", 0L),
            item.has("projectionMode") ? item.optInt("projectionMode", 0) : ProjectionModeGuesser.guess(title)
        ));
      }
    } catch (JSONException ignored) {
      AppLog.w(TAG, "Could not parse saved recent videos; clearing preference", ignored);
      preferences.edit().remove(KEY_ITEMS).apply();
    }
    AppLog.d(TAG, () -> "Loaded recent videos: count=" + videos.size());
    return videos;
  }

  public static void upsert(Context context, List<RecentVideo> videos, RecentVideo video) {
    AppLog.d(TAG, () -> "Upserting recent video: title=" + video.title
        + ", uri=" + video.uri
        + ", size=" + video.size
        + ", durationMs=" + video.durationMs
        + ", positionMs=" + video.lastPositionMs
        + ", watchedTimeMs=" + video.watchedTimeMs);
    for (Iterator<RecentVideo> iterator = videos.iterator(); iterator.hasNext(); ) {
      RecentVideo existing = iterator.next();
      if (existing.uri.equals(video.uri)) {
        iterator.remove();
        break;
      }
    }
    videos.add(0, video);
    while (videos.size() > MAX_ITEMS) {
      videos.remove(videos.size() - 1);
    }
    save(context, videos);
  }

  public static void save(Context context, List<RecentVideo> videos) {
    AppLog.d(TAG, () -> "Saving recent videos: count=" + videos.size());
    JSONArray array = new JSONArray();
    for (RecentVideo video : videos) {
      JSONObject item = new JSONObject();
      try {
        item.put("uri", video.uri.toString());
        item.put("title", video.title);
        item.put("size", video.size);
        item.put("positionMs", video.lastPositionMs);
        item.put("watchedTimeMs", video.watchedTimeMs);
        item.put("durationMs", video.durationMs);
        item.put("projectionMode", video.projectionMode);
        array.put(item);
      } catch (JSONException ignored) {
        AppLog.w(TAG, "Could not serialize recent video: title=" + video.title, ignored);
        // JSONObject only throws for invalid numbers; these values are app-owned.
      }
    }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ITEMS, array.toString())
        .apply();
  }

  public static void clear(Context context, List<RecentVideo> videos) {
    AppLog.i(TAG, () -> "Clearing recent videos: count=" + videos.size());
    videos.clear();
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_ITEMS)
        .apply();
  }

}
