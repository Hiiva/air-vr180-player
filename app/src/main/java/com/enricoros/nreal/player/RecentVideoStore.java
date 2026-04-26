package com.enricoros.nreal.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class RecentVideoStore {
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
            item.optLong("positionMs", 0L),
            item.optLong("durationMs", 0L)
        ));
      }
    } catch (JSONException ignored) {
      preferences.edit().remove(KEY_ITEMS).apply();
    }
    return videos;
  }

  public static void upsert(Context context, List<RecentVideo> videos, RecentVideo video) {
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
    JSONArray array = new JSONArray();
    for (RecentVideo video : videos) {
      JSONObject item = new JSONObject();
      try {
        item.put("uri", video.uri.toString());
        item.put("title", video.title);
        item.put("positionMs", video.lastPositionMs);
        item.put("durationMs", video.durationMs);
        array.put(item);
      } catch (JSONException ignored) {
        // JSONObject only throws for invalid numbers; these values are app-owned.
      }
    }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ITEMS, array.toString())
        .apply();
  }

  public static void clear(Context context, List<RecentVideo> videos) {
    videos.clear();
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_ITEMS)
        .apply();
  }
}
