package net.statustag.core.api;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.statustag.core.service.StatusKind;
import net.statustag.core.service.StatusResolver;
import net.labymod.api.labyconnect.protocol.model.UserStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LabyNetStatusInterpreter {

  private static final long RECENT_ACTIVITY_MS = 30L * 60_000L;

  private LabyNetStatusInterpreter() {
  }

  public static @Nullable StatusKind fromOnlineStatus(
      @NotNull UUID uniqueId,
      @NotNull StatusResolver resolver,
      @Nullable String statusName,
      boolean online
  ) {
    if (statusName == null) {
      return null;
    }

    UserStatus userStatus;
    try {
      userStatus = UserStatus.valueOf(statusName);
    } catch (IllegalArgumentException ignored) {
      return null;
    }

    if (userStatus == UserStatus.OFFLINE && !online && resolver.isPlayerOnTab(uniqueId)) {
      return StatusKind.INVISIBLE;
    }

    return StatusKind.fromUserStatus(userStatus);
  }

  public static @NotNull StatusKind fromGameStats(
      @NotNull UUID uniqueId,
      @NotNull StatusResolver resolver,
      @Nullable GameStatsResponse stats
  ) {
    if (stats == null || stats.getLastOnline() == null || stats.getLastOnline().isBlank()) {
      if (resolver.isPlayerOnTab(uniqueId)) {
        return StatusKind.INVISIBLE;
      }
      return StatusKind.OFFLINE;
    }

    try {
      long lastOnlineMs = Instant.parse(stats.getLastOnline()).toEpochMilli();
      long ageMs = System.currentTimeMillis() - lastOnlineMs;

      if (resolver.isPlayerOnTab(uniqueId) && ageMs <= RECENT_ACTIVITY_MS) {
        return StatusKind.INVISIBLE;
      }

      if (ageMs <= RECENT_ACTIVITY_MS) {
        return StatusKind.AWAY;
      }
    } catch (Exception ignored) {
    }

    return StatusKind.OFFLINE;
  }

  public static boolean shouldRefineWithProfile(@NotNull StatusKind kind) {
    return kind == StatusKind.INVISIBLE
        || kind == StatusKind.OFFLINE
        || kind == StatusKind.UNKNOWN
        || kind == StatusKind.AWAY;
  }

  public static @Nullable StatusKind fromProfileText(@Nullable String text) {
    if (text == null || text.isBlank()) {
      return null;
    }

    String normalized = text.toLowerCase(Locale.ROOT);
    if (containsAny(normalized, "busy", "dnd", "do not disturb", "مشغول")) {
      return StatusKind.BUSY;
    }

    if (containsAny(normalized, "afk", "away", "idle", "بعيد", "أفك")) {
      return StatusKind.AWAY;
    }

    if (containsAny(normalized, "offline", "sleeping")) {
      return StatusKind.OFFLINE;
    }

    if (containsAny(normalized, "online", "active", "playing", "متصل")) {
      return StatusKind.ONLINE;
    }

    return null;
  }

  private static boolean containsAny(@NotNull String haystack, @NotNull String... needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
