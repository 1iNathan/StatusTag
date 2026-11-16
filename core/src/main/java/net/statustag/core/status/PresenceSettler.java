package net.statustag.core.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

public final class PresenceSettler<T> {

  private final long quietMs;
  private final Map<UUID, Burst<T>> bursts = new HashMap<>();

  public PresenceSettler(long quietMs) {
    this.quietMs = quietMs;
  }

  public long quietMs() {
    return this.quietMs;
  }

  public synchronized void record(
      @NotNull UUID id,
      @NotNull T subject,
      @NotNull StatusKind before,
      @NotNull StatusKind after,
      long now
  ) {
    Burst<T> burst = this.bursts.get(id);
    if (burst == null) {
      burst = new Burst<>(before, now);
      this.bursts.put(id, burst);
    }

    burst.subject = subject;
    burst.latest = after;
    burst.lastAt = now;
    burst.changes++;
  }

  public synchronized @NotNull List<Settled<T>> due(long now) {
    List<Settled<T>> settled = new ArrayList<>();
    Iterator<Map.Entry<UUID, Burst<T>>> iterator = this.bursts.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Burst<T>> entry = iterator.next();
      Burst<T> burst = entry.getValue();
      if (now - burst.lastAt < this.quietMs) {
        continue;
      }

      iterator.remove();
      settled.add(new Settled<>(
          entry.getKey(),
          burst.subject,
          burst.before,
          burst.latest,
          classify(burst.before, burst.latest),
          burst.startedAt,
          burst.lastAt,
          burst.changes
      ));
    }
    return settled;
  }

  public synchronized int pending() {
    return this.bursts.size();
  }

  public static @NotNull Change classify(@NotNull StatusKind before, @NotNull StatusKind after) {
    if (before == after || !isFriendState(before) || !isFriendState(after)) {
      return Change.NONE;
    }

    boolean wasOnline = before.isLabyPresence();
    boolean isOnline = after.isLabyPresence();
    if (wasOnline && isOnline) {
      return Change.STATUS;
    }
    if (isOnline) {
      return Change.CAME_ONLINE;
    }
    return wasOnline ? Change.WENT_OFFLINE : Change.NONE;
  }

  private static boolean isFriendState(@NotNull StatusKind kind) {
    return kind.isLabyPresence() || kind == StatusKind.OFFLINE;
  }

  public enum Change {
    NONE,
    CAME_ONLINE,
    WENT_OFFLINE,
    STATUS
  }

  public record Settled<T>(
      @NotNull UUID id,
      @NotNull T subject,
      @NotNull StatusKind before,
      @NotNull StatusKind after,
      @NotNull Change change,
      long startedAt,
      long lastAt,
      int changes
  ) {
  }

  private static final class Burst<T> {

    private final StatusKind before;
    private final long startedAt;
    private T subject;
    private StatusKind latest;
    private long lastAt;
    private int changes;

    private Burst(StatusKind before, long startedAt) {
      this.before = before;
      this.startedAt = startedAt;
    }
  }
}
