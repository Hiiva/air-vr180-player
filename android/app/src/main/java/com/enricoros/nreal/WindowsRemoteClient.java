package com.enricoros.nreal;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class WindowsRemoteClient {
  private WindowsRemoteClient() {
  }

  static State fetchState(String baseUrl, String apiKey) throws IOException, JSONException {
    HttpURLConnection connection = open(baseUrl + "/state", apiKey);
    connection.setRequestMethod("GET");
    int status = connection.getResponseCode();
    String body = readFully(status >= 200 && status < 300
        ? connection.getInputStream()
        : connection.getErrorStream());
    connection.disconnect();
    if (status < 200 || status >= 300) {
      throw new IOException("Windows remote HTTP " + status + (body.isEmpty() ? "" : ": " + body));
    }
    return State.fromJson(new JSONObject(body));
  }

  static void sendCommand(String baseUrl, String apiKey, JSONObject payload) throws IOException {
    HttpURLConnection connection = open(baseUrl + "/command", apiKey);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
    connection.setFixedLengthStreamingMode(bytes.length);
    try (OutputStream output = connection.getOutputStream()) {
      output.write(bytes);
    }
    int status = connection.getResponseCode();
    String body = readFully(status >= 200 && status < 300
        ? connection.getInputStream()
        : connection.getErrorStream());
    connection.disconnect();
    if (status < 200 || status >= 300) {
      throw new IOException("Windows remote HTTP " + status + (body.isEmpty() ? "" : ": " + body));
    }
  }

  private static HttpURLConnection open(String url, String apiKey) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(2500);
    connection.setReadTimeout(3500);
    connection.setUseCaches(false);
    connection.setRequestProperty("Accept", "application/json");
    if (apiKey != null && !apiKey.isEmpty()) {
      connection.setRequestProperty("X-API-Key", apiKey);
    }
    return connection;
  }

  private static String readFully(InputStream input) throws IOException {
    if (input == null) {
      return "";
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      StringBuilder output = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line);
      }
      return output.toString();
    }
  }

  static final class State {
    final String media;
    final String currentServerId;
    final String serverUrl;
    final boolean playing;
    final long positionMs;
    final long durationMs;
    final double speed;
    final long volume;
    final boolean muted;
    final float zoom;
    final float yawDegrees;
    final float horizonDegrees;
    final int projectionMode;
    final boolean stereo;
    final String stereoMode;
    final boolean fullscreenOnGlasses;
    final int outputWidth;
    final int outputHeight;
    final long loopStartMs;
    final long loopEndMs;
    final boolean loopPaused;
    final String tracking;
    final float renderFps;

    State(
        String media,
        String currentServerId,
        String serverUrl,
        boolean playing,
        long positionMs,
        long durationMs,
        double speed,
        long volume,
        boolean muted,
        float zoom,
        float yawDegrees,
        float horizonDegrees,
        int projectionMode,
        boolean stereo,
        String stereoMode,
        boolean fullscreenOnGlasses,
        int outputWidth,
        int outputHeight,
        long loopStartMs,
        long loopEndMs,
        boolean loopPaused,
        String tracking,
        float renderFps) {
      this.media = media;
      this.currentServerId = currentServerId;
      this.serverUrl = serverUrl;
      this.playing = playing;
      this.positionMs = positionMs;
      this.durationMs = durationMs;
      this.speed = speed;
      this.volume = volume;
      this.muted = muted;
      this.zoom = zoom;
      this.yawDegrees = yawDegrees;
      this.horizonDegrees = horizonDegrees;
      this.projectionMode = projectionMode;
      this.stereo = stereo;
      this.stereoMode = stereoMode;
      this.fullscreenOnGlasses = fullscreenOnGlasses;
      this.outputWidth = outputWidth;
      this.outputHeight = outputHeight;
      this.loopStartMs = loopStartMs;
      this.loopEndMs = loopEndMs;
      this.loopPaused = loopPaused;
      this.tracking = tracking;
      this.renderFps = renderFps;
    }

    static State fromJson(JSONObject value) {
      return new State(
          value.optString("media", ""),
          value.optString("current_server_id", ""),
          value.optString("server_url", ""),
          value.optBoolean("playing", false),
          secondsToMs(value.optDouble("position_seconds", 0.0)),
          secondsToMs(value.optDouble("duration_seconds", 0.0)),
          value.optDouble("speed", 1.0),
          value.optLong("volume", 100L),
          value.optBoolean("muted", false),
          (float) value.optDouble("zoom", 0.8),
          (float) value.optDouble("yaw_degrees", 0.0),
          (float) value.optDouble("horizon_degrees", 0.0),
          value.optInt("projection_mode", 0),
          value.optBoolean("stereo", true),
          value.optString("stereo_mode", "auto"),
          value.optBoolean("fullscreen_on_glasses", false),
          value.optInt("output_width", 0),
          value.optInt("output_height", 0),
          optionalSecondsToMs(value, "loop_start_seconds"),
          optionalSecondsToMs(value, "loop_end_seconds"),
          value.optBoolean("loop_paused", false),
          value.optString("tracking", ""),
          (float) value.optDouble("render_fps", 0.0));
    }

    boolean hasMedia() {
      return !media.isEmpty();
    }

    private static long secondsToMs(double seconds) {
      if (!Double.isFinite(seconds) || seconds <= 0.0) {
        return 0L;
      }
      return Math.max(0L, Math.round(seconds * 1000.0));
    }

    private static long optionalSecondsToMs(JSONObject value, String key) {
      return !value.has(key) || value.isNull(key)
          ? androidx.media3.common.C.TIME_UNSET
          : secondsToMs(value.optDouble(key, 0.0));
    }
  }
}
