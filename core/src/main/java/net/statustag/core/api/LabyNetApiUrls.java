package net.statustag.core.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

public final class LabyNetApiUrls {

  private static final String BASE = "https://laby.net/api/v3/user/%s";

  private LabyNetApiUrls() {
  }

  public static @NotNull String onlineStatus(@NotNull UUID uniqueId) {
    return String.format(BASE, uniqueId) + "/online-status";
  }

  public static @NotNull String gameStats(@NotNull UUID uniqueId) {
    return String.format(BASE, uniqueId) + "/game-stats";
  }

  public static @NotNull String profileStatus(@NotNull UUID uniqueId) {
    return String.format(BASE, uniqueId) + "/status";
  }

}
