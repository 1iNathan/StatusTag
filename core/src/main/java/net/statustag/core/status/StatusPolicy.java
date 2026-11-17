package net.statustag.core.status;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusPolicy {

  public static final long MINUTE_MS = 60_000L;
  public static final long HOUR_MS = 60L * MINUTE_MS;
  public static final long DAY_MS = 24L * HOUR_MS;
  public static final long SYNC_FRESH_MS = 8L * MINUTE_MS;
  public static final long PRESENCE_HOLD_MS = HOUR_MS;
  public static final long MAX_CACHE_AGE_MS = 30L * DAY_MS;

  private StatusPolicy() {
  }

  public static long refreshAfterMs(@NotNull StatusKind kind) {
    return switch (kind) {
      case ONLINE, AWAY, BUSY, INVISIBLE -> 2L * MINUTE_MS;
      case OFFLINE -> 10L * MINUTE_MS;
      case PROFILE_HIDDEN, NO_LABYMOD -> DAY_MS;
      case UNKNOWN -> 2L * MINUTE_MS;
    };
  }

  public static boolean needsFetch(@Nullable StatusEntry entry, long now) {
    if (entry == null) {
      return true;
    }

    return switch (entry.source()) {
      case SELF, FRIEND -> false;
      case SYNC -> now - entry.updatedAt() >= SYNC_FRESH_MS;
      case API, CACHE -> entry.checkedAt() <= 0L || now - entry.checkedAt() >= refreshAfterMs(entry.kind());
    };
  }

  public static boolean holdsPresence(@Nullable StatusEntry current, @NotNull StatusKind incoming, long now) {
    return current != null
        && incoming == StatusKind.INVISIBLE
        && (current.source() == StatusSource.API || current.source() == StatusSource.CACHE)
        && current.kind().isLabyPresence()
        && now - current.updatedAt() < PRESENCE_HOLD_MS;
  }

  static boolean refreshesWithin(@Nullable StatusEntry entry, long now, long aheadMs) {
    return entry != null
        && (entry.source() == StatusSource.API || entry.source() == StatusSource.CACHE)
        && entry.checkedAt() > 0L
        && !needsFetch(entry, now)
        && needsFetch(entry, now + aheadMs);
  }

  public static boolean isLookupCandidate(@NotNull UUID uniqueId) {
    return uniqueId.version() == 4;
  }

  public static boolean isExpired(@NotNull StatusEntry entry, long now) {
    return entry.updatedAt() <= 0L || now - entry.updatedAt() > MAX_CACHE_AGE_MS;
  }
}
