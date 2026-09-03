package com.enricoros.nreal.player;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ProjectionModeGuesser {
  private static final Pattern FOV_200 = Pattern.compile(
      "\\b(?:vr|fov|hfov|vfov|fisheye|fish(?:eye)?|dualfisheye|dual\\s+fisheye)\\s*[-_ ]?200\\b"
          + "|\\b200\\s*[-_ ]?(?:vr|fov|hfov|vfov|deg|degree|degrees|fisheye|fish(?:eye)?)\\b");
  private static final Pattern FOV_190 = Pattern.compile(
      "\\b(?:vr|fov|hfov|vfov|fisheye|fish(?:eye)?|dualfisheye|dual\\s+fisheye)\\s*[-_ ]?190\\b"
          + "|\\b190\\s*[-_ ]?(?:vr|fov|hfov|vfov|deg|degree|degrees|fisheye|fish(?:eye)?)\\b");
  private static final Pattern VR_200_COMPACT = Pattern.compile("\\bvr[-_ ]?200\\b|\\b200[-_ ]?vr\\b");
  private static final Pattern VR_190_COMPACT = Pattern.compile("\\bvr[-_ ]?190\\b|\\b190[-_ ]?vr\\b");
  private static final Pattern MKX_200 = Pattern.compile("\\bmkx[-_ ]?200\\b");

  private ProjectionModeGuesser() {
  }

  public static int guess(String name) {
    String normalized = normalize(name);
    if (normalized.length() == 0) {
      return Vr180Renderer.PROJECTION_EQUIRECT_VR180;
    }

    if (matches(FOV_200, normalized)
        || matches(VR_200_COMPACT, normalized)
        || matches(MKX_200, normalized)) {
      return Vr180Renderer.PROJECTION_FISHEYE_VR200;
    }
    if (matches(FOV_190, normalized) || matches(VR_190_COMPACT, normalized)) {
      return Vr180Renderer.PROJECTION_FISHEYE_VR190;
    }

    return Vr180Renderer.PROJECTION_EQUIRECT_VR180;
  }

  private static boolean matches(Pattern pattern, String value) {
    return pattern.matcher(value).find();
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.toLowerCase(Locale.US);
    int extensionStart = normalized.lastIndexOf('.');
    if (extensionStart > 0) {
      normalized = normalized.substring(0, extensionStart);
    }
    normalized = normalized.replaceAll("[\\[\\](){},;]+", " ");
    normalized = normalized.replaceAll("[._]+", " ");
    normalized = normalized.replaceAll("\\s+", " ").trim();
    return normalized;
  }
}
