package com.enricoros.nreal.player;

import androidx.media3.common.C;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A durable bookmark or loop, independent of watch history and the active A/B range. */
public final class SavedScene {
  public final String id;
  public final String name;
  public final long startMs;
  public final long endMs;

  public SavedScene(String id, String name, long startMs, long endMs) {
    if (!id.matches("[0-9a-f]{32}") || name.length() > 80 || startMs < 0
        || (endMs != C.TIME_UNSET && endMs <= startMs)) {
      throw new IllegalArgumentException("Invalid saved scene");
    }
    this.id = id;
    this.name = name.trim();
    this.startMs = startMs;
    this.endMs = endMs;
  }

  public static SavedScene create(long positionMs, long loopStartMs, long loopEndMs) {
    boolean loop = loopStartMs >= 0 && loopEndMs > loopStartMs;
    return new SavedScene(UUID.randomUUID().toString().replace("-", ""), "",
        loop ? loopStartMs : Math.max(0L, positionMs), loop ? loopEndMs : C.TIME_UNSET);
  }

  public boolean isLoop() {
    return endMs != C.TIME_UNSET;
  }

  public boolean sameRange(SavedScene other) {
    return other != null && startMs == other.startMs && endMs == other.endMs;
  }

  public SavedScene renamed(String value) {
    return new SavedScene(id, value, startMs, endMs);
  }

  public JSONObject toJson() throws JSONException {
    return new JSONObject().put("id", id).put("name", name).put("start_ms", startMs)
        .put("end_ms", isLoop() ? endMs : JSONObject.NULL);
  }

  public static SavedScene fromJson(JSONObject value) throws JSONException {
    try {
      return new SavedScene(value.getString("id"), value.getString("name"),
          value.getLong("start_ms"), value.isNull("end_ms") ? C.TIME_UNSET : value.getLong("end_ms"));
    } catch (IllegalArgumentException e) {
      throw new JSONException(e.getMessage());
    }
  }
}
