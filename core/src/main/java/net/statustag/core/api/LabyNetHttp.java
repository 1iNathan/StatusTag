package net.statustag.core.api;

import net.labymod.api.util.io.web.request.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LabyNetHttp {

  private LabyNetHttp() {
  }

  public static int statusCode(@NotNull Response<?> response) {
    if (!response.hasException()) {
      return response.getResponseCode();
    }

    Integer parsed = parseCodeFromMessage(response.exception() == null ? null : response.exception().getMessage());
    return parsed == null ? -1 : parsed;
  }

  public static boolean isRateLimited(@NotNull Response<?> response) {
    return statusCode(response) == 429;
  }

  public static boolean isForbidden(@NotNull Response<?> response) {
    int code = statusCode(response);
    if (code == 403) {
      return true;
    }

    String message = response.hasException() && response.exception() != null
        ? response.exception().getMessage()
        : null;
    return message != null && message.contains("Forbidden");
  }

  public static @Nullable String errorMessage(@NotNull Response<?> response) {
    if (!response.hasException() || response.exception() == null) {
      return null;
    }
    return response.exception().getMessage();
  }

  private static @Nullable Integer parseCodeFromMessage(@Nullable String message) {
    if (message == null || message.isBlank()) {
      return null;
    }

    if (message.contains("Forbidden")) {
      return 403;
    }

    if (message.contains("Too Many Requests")) {
      return 429;
    }

    return null;
  }
}
