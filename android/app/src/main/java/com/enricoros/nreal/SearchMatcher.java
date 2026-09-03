package com.enricoros.nreal;

import java.text.Normalizer;

final class SearchMatcher {
  private SearchMatcher() {
  }

  static boolean matches(String searchableText, String[] terms) {
    for (String term : terms) {
      if (!searchableText.contains(term)) {
        return false;
      }
    }
    return true;
  }

  static String normalize(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }

    String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
    StringBuilder normalized = new StringBuilder(decomposed.length());
    boolean pendingSeparator = false;
    for (int offset = 0; offset < decomposed.length();) {
      int codePoint = decomposed.codePointAt(offset);
      offset += Character.charCount(codePoint);
      int type = Character.getType(codePoint);
      if (type == Character.NON_SPACING_MARK
          || type == Character.COMBINING_SPACING_MARK
          || type == Character.ENCLOSING_MARK) {
        continue;
      }
      if (Character.isLetterOrDigit(codePoint)) {
        if (pendingSeparator && normalized.length() > 0) {
          normalized.append(' ');
        }
        normalized.appendCodePoint(Character.toLowerCase(codePoint));
        pendingSeparator = false;
      } else {
        pendingSeparator = true;
      }
    }
    return normalized.toString();
  }
}
