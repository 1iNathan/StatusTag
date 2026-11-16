package net.statustag.core.status;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

public final class NoticeGate {

  static final long JOIN_COVER_MS = 10_000L;
  static final long JOIN_REPEAT_MS = 60_000L;
  static final long FOREIGN_BEFORE_MS = 1_600L;
  static final long FOREIGN_AFTER_MS = 750L;
  static final long FORGET_AFTER_MS = 120_000L;

  private final Map<UUID, Long> joinedAt = new HashMap<>();
  private final Map<String, Long> foreignAt = new HashMap<>();

  public synchronized boolean claimJoin(@NotNull UUID id, long now) {
    this.prune(now);
    Long last = this.joinedAt.get(id);
    if (last != null && now - last < JOIN_REPEAT_MS) {
      return false;
    }

    this.joinedAt.put(id, now);
    return true;
  }

  public synchronized void foreignNotice(@NotNull String title, long now) {
    this.prune(now);
    this.foreignAt.put(key(title), now);
  }

  public synchronized @NotNull Outcome decide(
      @NotNull PresenceSettler.Settled<?> settled,
      @NotNull String name,
      @NotNull Toggles toggles
  ) {
    return switch (settled.change()) {
      case NONE -> Outcome.SETTLED;
      case STATUS -> toggles.statusChange() ? Outcome.PUSH : Outcome.OFF;
      case CAME_ONLINE -> {
        if (!toggles.cameOnline()) {
          yield Outcome.OFF;
        }
        Long joined = this.joinedAt.get(settled.id());
        if (joined != null && joined >= settled.startedAt() - JOIN_COVER_MS) {
          yield Outcome.JOINED;
        }
        yield this.shownByOthers(name, settled.lastAt()) ? Outcome.FOREIGN : Outcome.PUSH;
      }
      case WENT_OFFLINE -> {
        this.joinedAt.remove(settled.id());
        if (!toggles.wentOffline()) {
          yield Outcome.OFF;
        }
        yield this.shownByOthers(name, settled.lastAt()) ? Outcome.FOREIGN : Outcome.PUSH;
      }
    };
  }

  private boolean shownByOthers(@NotNull String name, long lastAt) {
    Long at = this.foreignAt.get(key(name));
    return at != null && at >= lastAt - FOREIGN_BEFORE_MS && at <= lastAt + FOREIGN_AFTER_MS;
  }

  private void prune(long now) {
    this.joinedAt.values().removeIf(at -> now - at >= FORGET_AFTER_MS);
    this.foreignAt.values().removeIf(at -> now - at >= FORGET_AFTER_MS);
  }

  private static @NotNull String key(@NotNull String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  public enum Outcome {
    PUSH,
    SETTLED,
    OFF,
    JOINED,
    FOREIGN
  }

  public record Toggles(boolean statusChange, boolean cameOnline, boolean wentOffline) {
  }
}
