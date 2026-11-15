package net.statustag.core.net;

import net.statustag.core.status.StatusKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record LookupResult(
    int code,
    @Nullable String status,
    boolean online,
    long retryAfterMs,
    int remaining,
    long resetAt,
    long lastOnline
) {

  public static final int NETWORK_ERROR = -1;
  public static final int NOT_LISTED = -2;

  public LookupResult(int code, @Nullable String status, boolean online, long retryAfterMs, int remaining, long resetAt) {
    this(code, status, online, retryAfterMs, remaining, resetAt, -1L);
  }

  public boolean isRateLimited() {
    return this.code == 429;
  }

  public boolean isUnauthorized() {
    return this.code == 401;
  }

  public @Nullable StatusKind interpret(boolean onTab, boolean knownLabyModUser) {
    return switch (this.code) {
      case 200 -> fromPayload(this.status, this.online, onTab);
      case 204 -> knownLabyModUser ? (onTab ? StatusKind.INVISIBLE : StatusKind.OFFLINE) : StatusKind.NO_LABYMOD;
      case 403 -> StatusKind.PROFILE_HIDDEN;
      case 404 -> StatusKind.NO_LABYMOD;
      case NOT_LISTED -> knownLabyModUser ? StatusKind.PROFILE_HIDDEN : StatusKind.NO_LABYMOD;
      default -> null;
    };
  }

  private static @Nullable StatusKind fromPayload(@Nullable String status, boolean online, boolean onTab) {
    StatusKind kind = StatusKind.parse(status);
    if (kind == null || !isLabyStatus(kind)) {
      return null;
    }

    if (kind == StatusKind.OFFLINE && !online && onTab) {
      return StatusKind.INVISIBLE;
    }
    return kind;
  }

  private static boolean isLabyStatus(@NotNull StatusKind kind) {
    return kind.isLabyPresence() || kind == StatusKind.OFFLINE;
  }
}
