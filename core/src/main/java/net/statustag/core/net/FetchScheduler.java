package net.statustag.core.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.DebugLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FetchScheduler {

  static final int MAX_QUEUE = 512;
  static final int MAX_URGENT = 128;
  static final int BULK_SIZE = 100;
  static final long BULK_RETRY_MS = 6L * 3_600_000L;
  static final int BULK_FAILURE_LIMIT = 3;
  static final long BULK_COOLDOWN_MS = 15L * 60_000L;
  static final long IN_FLIGHT_TIMEOUT_MS = 20_000L;
  static final long NO_TOKEN_PAUSE_MS = 10_000L;
  static final long UNAUTHORIZED_PAUSE_MS = 30_000L;

  private final FetchHost host;
  private final StatusLookup lookup;
  private final DebugLog debug;
  private final LongSupplier clock;
  private final RateLimiter limiter = new RateLimiter();
  private final ArrayDeque<UUID> urgent = new ArrayDeque<>();
  private final ArrayDeque<UUID> normal = new ArrayDeque<>();
  private final Set<UUID> queued = new HashSet<>();
  private final Set<UUID> inFlight = new HashSet<>();

  private long activeRequest;
  private long nextRequestId = 1L;
  private long inFlightSince;
  private long generation;
  private long bulkUnsupportedUntil;
  private boolean bulkConfirmed;
  private boolean activeBulk;
  private int bulkFailures;

  public FetchScheduler(
      @NotNull FetchHost host,
      @NotNull StatusLookup lookup,
      @NotNull DebugLog debug,
      @NotNull LongSupplier clock
  ) {
    this.host = host;
    this.lookup = lookup;
    this.debug = debug;
    this.clock = clock;
  }

  public synchronized boolean enqueue(@NotNull UUID uniqueId, boolean priority) {
    if (this.inFlight.contains(uniqueId)) {
      return false;
    }

    if (!this.queued.add(uniqueId)) {
      if (priority && this.urgent.size() < MAX_URGENT && this.normal.remove(uniqueId)) {
        this.urgent.addLast(uniqueId);
      }
      return false;
    }

    if (priority && this.urgent.size() < MAX_URGENT) {
      this.urgent.addLast(uniqueId);
    } else if (this.normal.size() < MAX_QUEUE) {
      this.normal.addLast(uniqueId);
    } else {
      this.queued.remove(uniqueId);
      return false;
    }
    return true;
  }

  public synchronized int size() {
    return this.queued.size();
  }

  public synchronized boolean isQueued(@NotNull UUID uniqueId) {
    return this.queued.contains(uniqueId);
  }

  public synchronized boolean isBulkActive() {
    return this.bulkConfirmed;
  }

  synchronized void disableBulkUntil(long until) {
    this.bulkUnsupportedUntil = until;
    this.bulkConfirmed = false;
  }

  public RateLimiter limiter() {
    return this.limiter;
  }

  public synchronized void clear() {
    this.urgent.clear();
    this.normal.clear();
    this.queued.clear();
    this.generation++;
  }

  public void tick(long now) {
    if (!this.host.lookupsEnabled()) {
      return;
    }

    synchronized (this) {
      if (this.activeRequest != 0L) {
        if (now - this.inFlightSince < IN_FLIGHT_TIMEOUT_MS) {
          return;
        }
        if (this.activeBulk) {
          this.registerBulkFailure(now);
        }
        List<UUID> stuck = new ArrayList<>(this.inFlight);
        this.finishRequest(this.activeRequest);
        for (UUID uniqueId : stuck) {
          if (this.queued.add(uniqueId)) {
            this.normal.addLast(uniqueId);
          }
        }
        this.limiter.onFailure(now);
      }

      if (this.queued.isEmpty() || this.limiter.waitMs(now) > 0L) {
        return;
      }

      boolean bulk = now >= this.bulkUnsupportedUntil;
      List<UUID> batch = this.pollNeeded(now, bulk ? BULK_SIZE : 1);
      if (batch.isEmpty()) {
        return;
      }

      String jwt = this.host.jwt();
      if (jwt == null) {
        for (int index = batch.size() - 1; index >= 0; index--) {
          if (this.queued.add(batch.get(index))) {
            this.urgent.addFirst(batch.get(index));
          }
        }
        this.limiter.pause(now, NO_TOKEN_PAUSE_MS);
        return;
      }

      long requestId = this.nextRequestId++;
      long dispatchGeneration = this.generation;
      this.activeRequest = requestId;
      this.activeBulk = bulk;
      this.inFlightSince = now;
      this.inFlight.addAll(batch);
      this.limiter.onDispatch(now);

      if (!bulk) {
        UUID uniqueId = batch.get(0);
        if (this.debug.enabled()) {
          this.debug.log("lookup " + uniqueId + " queue=" + this.queued.size() + " interval=" + this.limiter.intervalMs());
        }
        this.lookup.lookup(uniqueId, jwt, result -> this.complete(uniqueId, result, requestId, dispatchGeneration));
      } else {
        if (this.debug.enabled()) {
          this.debug.log("bulk lookup size=" + batch.size() + " queue=" + this.queued.size());
        }
        this.lookup.lookupBulk(batch, jwt, result -> this.completeBulk(batch, result, requestId, dispatchGeneration));
      }
    }
  }

  private @NotNull List<UUID> pollNeeded(long now, int limit) {
    List<UUID> batch = new ArrayList<>(Math.min(limit, this.queued.size()));
    UUID candidate;
    while (batch.size() < limit && (candidate = this.poll()) != null) {
      if (this.host.needsLookup(candidate, now)) {
        batch.add(candidate);
      }
    }
    return batch;
  }

  private @Nullable UUID poll() {
    UUID uniqueId = this.urgent.pollFirst();
    if (uniqueId == null) {
      uniqueId = this.normal.pollFirst();
    }
    if (uniqueId != null) {
      this.queued.remove(uniqueId);
    }
    return uniqueId;
  }

  private void requeue(@NotNull UUID uniqueId) {
    if (this.inFlight.contains(uniqueId) || !this.queued.add(uniqueId)) {
      return;
    }
    if (this.urgent.size() < MAX_URGENT) {
      this.urgent.addLast(uniqueId);
    } else {
      this.normal.addLast(uniqueId);
    }
  }

  private void registerBulkFailure(long now) {
    if (++this.bulkFailures < BULK_FAILURE_LIMIT) {
      return;
    }

    this.bulkFailures = 0;
    this.bulkConfirmed = false;
    this.bulkUnsupportedUntil = Math.max(this.bulkUnsupportedUntil, now + BULK_COOLDOWN_MS);
    if (this.debug.enabled()) {
      this.debug.log("bulk failing, using single lookups for " + BULK_COOLDOWN_MS / 60_000L + " min");
    }
  }

  private void finishRequest(long requestId) {
    if (this.activeRequest == requestId) {
      this.activeRequest = 0L;
      this.inFlightSince = 0L;
      this.inFlight.clear();
    }
  }

  private @Nullable StatusKind interpret(@NotNull UUID uniqueId, @NotNull LookupResult result) {
    StatusKind kind = result.interpret(this.host.isOnTab(uniqueId), this.host.isKnownLabyModUser(uniqueId));
    return kind == null && result.code() == 200 ? StatusKind.UNKNOWN : kind;
  }

  void complete(@NotNull UUID uniqueId, @NotNull LookupResult result, long requestId, long dispatchGeneration) {
    long now = this.clock.getAsLong();
    StatusKind kind = null;
    if (result.isRateLimited()) {
      this.limiter.onRateLimited(now, result.retryAfterMs());
    } else if (result.isUnauthorized()) {
      this.limiter.pause(now, UNAUTHORIZED_PAUSE_MS);
    } else {
      kind = this.interpret(uniqueId, result);
      if (kind == null) {
        this.limiter.onFailure(now);
      } else {
        this.limiter.onSuccess();
      }
    }
    this.limiter.onQuota(now, result.remaining(), result.resetAt());

    if (this.debug.enabled()) {
      this.debug.log("result " + uniqueId + " code=" + result.code() + " kind=" + kind
          + " remaining=" + result.remaining() + " retryAfter=" + result.retryAfterMs());
    }

    if (kind != null) {
      this.host.applyLookup(uniqueId, kind, now);
      if (result.lastOnline() > 0L) {
        this.host.recordLastOnline(uniqueId, result.lastOnline());
      }
    }

    synchronized (this) {
      this.finishRequest(requestId);
      if (kind == null && dispatchGeneration == this.generation) {
        this.requeue(uniqueId);
      }
    }
  }

  void completeBulk(
      @NotNull List<UUID> batch,
      @NotNull BulkLookupResult result,
      long requestId,
      long dispatchGeneration
  ) {
    long now = this.clock.getAsLong();
    boolean applied = false;
    if (result.isRateLimited()) {
      this.limiter.onRateLimited(now, result.retryAfterMs());
    } else if (result.isSuccess()) {
      applied = true;
      this.limiter.onSuccess();
      Map<UUID, LookupResult> entries = result.entries();
      for (UUID uniqueId : batch) {
        LookupResult entry = entries.get(uniqueId);
        StatusKind kind = entry == null ? null : this.interpret(uniqueId, entry);
        if (kind != null) {
          this.host.applyLookup(uniqueId, kind, now);
          if (entry.lastOnline() > 0L) {
            this.host.recordLastOnline(uniqueId, entry.lastOnline());
          }
        }
      }
    } else if (!result.isUnsupported()) {
      this.limiter.onFailure(now);
    }
    this.limiter.onQuota(now, result.remaining(), result.resetAt());

    if (this.debug.enabled()) {
      this.debug.log("bulk result code=" + result.code() + " size=" + batch.size() + " applied=" + applied);
    }

    synchronized (this) {
      boolean current = requestId == this.activeRequest;
      if (result.isSuccess()) {
        this.bulkConfirmed = true;
        this.bulkFailures = 0;
      } else if (result.isUnsupported()) {
        this.bulkConfirmed = false;
        this.bulkFailures = 0;
        this.bulkUnsupportedUntil = now + BULK_RETRY_MS;
      } else if (!result.isRateLimited() && current) {
        this.registerBulkFailure(now);
      }

      this.finishRequest(requestId);
      if (!applied && dispatchGeneration == this.generation) {
        for (UUID uniqueId : batch) {
          this.requeue(uniqueId);
        }
      }
    }
  }
}
