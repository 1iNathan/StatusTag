package net.statustag.core.service;

import net.statustag.core.internal.StatusTagInternals;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusReprobePolicy {

  private static final long MESH_FRESH_MS = 5L * 60_000L;

  private StatusReprobePolicy() {
  }

  public static boolean shouldFetchFromApi(
      @Nullable StatusEntry entry,
      @NotNull StatusTagInternals internals
  ) {
    if (entry == null) {
      return true;
    }

    if (entry.isAuthoritative() && !isMeshStale(entry)) {
      return false;
    }

    long intervalMs = reprobeIntervalMs(entry.kind(), internals);
    if (intervalMs <= 0L) {
      return false;
    }

    if (entry.lastCheckedAt() <= 0L) {
      return true;
    }

    return System.currentTimeMillis() - entry.lastCheckedAt() >= intervalMs;
  }

  public static long reprobeIntervalMs(@NotNull StatusKind kind, @NotNull StatusTagInternals internals) {
    return switch (kind) {
      case PROFILE_HIDDEN -> hoursToMillis(internals.profileHiddenReprobeHours());
      case INVISIBLE -> minutesToMillis(internals.invisibleReprobeMinutes());
      case NO_LABYMOD -> hoursToMillis(internals.noLabyModReprobeHours());
      case OFFLINE, UNKNOWN -> daysToMillis(internals.softRefreshDays());
      case ONLINE, AWAY, BUSY -> daysToMillis(internals.softRefreshDays());
    };
  }

  public static long tabPresenceReprobeMs(@NotNull StatusTagInternals internals) {
    return minutesToMillis(internals.tabPresenceReprobeMinutes());
  }

  private static boolean isMeshStale(@NotNull StatusEntry entry) {
    return entry.source() == StatusSource.MESH
        && System.currentTimeMillis() - entry.meshTimestamp() > MESH_FRESH_MS;
  }

  public static boolean isReprobeCandidate(@NotNull StatusKind kind) {
    return kind == StatusKind.PROFILE_HIDDEN
        || kind == StatusKind.INVISIBLE
        || kind == StatusKind.NO_LABYMOD
        || kind == StatusKind.OFFLINE
        || kind == StatusKind.UNKNOWN;
  }

  private static long hoursToMillis(int hours) {
    if (hours <= 0) {
      return 0L;
    }
    return hours * 3_600_000L;
  }

  private static long minutesToMillis(int minutes) {
    if (minutes <= 0) {
      return 0L;
    }
    return minutes * 60_000L;
  }

  private static long daysToMillis(int days) {
    if (days <= 0) {
      return 0L;
    }
    return days * 86_400_000L;
  }
}
