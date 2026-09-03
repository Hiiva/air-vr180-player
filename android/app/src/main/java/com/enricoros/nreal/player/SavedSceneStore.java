package com.enricoros.nreal.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All operations run on the scenes worker, never the playback/UI thread. */
public final class SavedSceneStore {
  public static final class Target {
    public final String localUri;
    public final String serverUrl;
    public final String videoId;
    public final String apiKey;
    public final String title;
    public final String playbackOwner;

    public Target(String localUri, String serverUrl, String videoId, String apiKey,
        String title, String playbackOwner) {
      this.localUri = localUri;
      this.serverUrl = serverUrl;
      this.videoId = videoId;
      this.apiKey = apiKey;
      this.title = title;
      this.playbackOwner = playbackOwner;
    }

    public boolean samePlayback(Target other) {
      return other != null && localUri.equals(other.localUri) && serverUrl.equals(other.serverUrl)
          && videoId.equals(other.videoId) && apiKey.equals(other.apiKey)
          && playbackOwner.equals(other.playbackOwner);
    }

    public boolean isServer() {
      return !videoId.isEmpty();
    }
  }

  private final SharedPreferences preferences;
  private static final Object LOCAL_WRITE_LOCK = new Object();

  public SavedSceneStore(Context context) {
    preferences = context.getSharedPreferences("saved_scenes", Context.MODE_PRIVATE);
  }

  public List<SavedScene> load(Target target) throws IOException, JSONException {
    JSONArray values = target.isServer()
        ? request(target, "GET", "", null).getJSONArray("scenes")
        : new JSONArray(preferences.getString(target.localUri, "[]"));
    List<SavedScene> scenes = new ArrayList<>();
    for (int i = 0; i < values.length(); i++) {
      scenes.add(SavedScene.fromJson(values.getJSONObject(i)));
    }
    scenes.sort(Comparator.comparingLong(scene -> scene.startMs));
    return scenes;
  }

  public void put(Target target, SavedScene scene) throws IOException, JSONException {
    if (target.isServer()) {
      request(target, "PUT", "/" + scene.id, scene.toJson());
      return;
    }
    synchronized (LOCAL_WRITE_LOCK) {
      List<SavedScene> scenes = load(target);
      for (SavedScene existing : scenes) {
        if (!existing.id.equals(scene.id) && existing.sameRange(scene)) {
          throw new IOException("This scene is already saved");
        }
      }
      scenes.removeIf(existing -> existing.id.equals(scene.id));
      scenes.add(scene);
      saveLocal(target, scenes);
    }
  }

  public void delete(Target target, SavedScene scene) throws IOException, JSONException {
    if (target.isServer()) {
      request(target, "DELETE", "/" + scene.id, null);
      return;
    }
    synchronized (LOCAL_WRITE_LOCK) {
      List<SavedScene> scenes = load(target);
      scenes.removeIf(existing -> existing.id.equals(scene.id));
      saveLocal(target, scenes);
    }
  }

  private void saveLocal(Target target, List<SavedScene> scenes) throws JSONException, IOException {
    JSONArray values = new JSONArray();
    for (SavedScene scene : scenes) values.put(scene.toJson());
    if (!preferences.edit().putString(target.localUri, values.toString()).commit()) {
      throw new IOException("Could not save scenes on this device");
    }
  }

  private static JSONObject request(Target target, String method, String suffix, JSONObject body)
      throws IOException, JSONException {
    String endpoint = target.serverUrl + "/videos/" + Uri.encode(target.videoId) + "/scenes" + suffix;
    HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
    try {
      connection.setRequestMethod(method);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(8000);
      connection.setUseCaches(false);
      connection.setRequestProperty("X-API-Key", target.apiKey);
      connection.setRequestProperty("Accept", "application/json");
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        if (status == 409) throw new IOException("This scene is already saved; reopen Scenes to refresh");
        if (status == 401 || status == 403) throw new IOException("Check the server API key");
        throw new IOException("Scenes server returned HTTP " + status);
      }
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
      }
    } finally {
      connection.disconnect();
    }
  }
}
