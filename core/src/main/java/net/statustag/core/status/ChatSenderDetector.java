package net.statustag.core.status;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ChatSenderDetector {

  static final int SCAN_LIMIT = 64;
  private static final int MIN_NAME = 3;
  private static final int MAX_NAME = 16;
  private static final int MAX_TAG = 14;
  private static final String SEPARATORS = ":\u00BB>\u203A|\u2192";
  private static final String CLOSERS = ")]}";

  private ChatSenderDetector() {
  }

  public static @Nullable UUID detect(@Nullable String text, @NotNull Map<String, UUID> namesByLowerCase) {
    if (text == null || text.isEmpty() || namesByLowerCase.isEmpty()) {
      return null;
    }

    int limit = Math.min(text.length(), SCAN_LIMIT);
    int index = 0;
    while (index < limit) {
      if (!isNameChar(text.charAt(index))) {
        index++;
        continue;
      }

      int start = index;
      while (index < text.length() && isNameChar(text.charAt(index))) {
        index++;
      }

      int length = index - start;
      if (length >= MIN_NAME && length <= MAX_NAME && isFollowedBySeparator(text, index)) {
        UUID uniqueId = namesByLowerCase.get(text.substring(start, index).toLowerCase(Locale.ROOT));
        if (uniqueId != null) {
          return uniqueId;
        }
      }
    }
    return null;
  }

  static boolean isFollowedBySeparator(@NotNull String text, int from) {
    int index = skipSpaces(text, from);
    for (int step = 0; step < 4 && index < text.length(); step++) {
      char current = text.charAt(index);
      if (SEPARATORS.indexOf(current) >= 0) {
        return true;
      }

      if (CLOSERS.indexOf(current) >= 0) {
        index = skipSpaces(text, index + 1);
      } else if (current == '[' || current == '(') {
        int close = text.indexOf(current == '[' ? ']' : ')', index + 1);
        if (close < 0 || close - index > MAX_TAG) {
          return false;
        }
        index = skipSpaces(text, close + 1);
      } else {
        return false;
      }
    }
    return false;
  }

  private static int skipSpaces(@NotNull String text, int from) {
    int index = from;
    while (index < text.length() && text.charAt(index) == ' ') {
      index++;
    }
    return index;
  }

  private static boolean isNameChar(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9'
        || character == '_';
  }
}
