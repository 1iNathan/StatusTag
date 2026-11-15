package net.statustag.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.DebugLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FetchSchedulerTest {

  private static final class Host implements FetchHost {
    final Map<UUID, StatusKind> applied = new HashMap<>();
    final Map<UUID, Long> lastOnline = new HashMap<>();
    final Set<UUID> resolved = new HashSet<>();
    final Set<UUID> onTab = new HashSet<>();
    String token = "jwt";
    boolean enabled = true;

    @Override
    public boolean lookupsEnabled() {
      return this.enabled;
    }

    @Override
    public @Nullable String jwt() {
      return this.token;
    }

    @Override
    public boolean needsLookup(@NotNull UUID uniqueId, long now) {
      return !this.resolved.contains(uniqueId);
    }

    @Override
    public boolean isOnTab(@NotNull UUID uniqueId) {
      return this.onTab.contains(uniqueId);
    }

    @Override
    public boolean isKnownLabyModUser(@NotNull UUID uniqueId) {
      return false;
    }

    @Override
    public void recordLastOnline(@NotNull UUID uniqueId, long lastOnline) {
      this.lastOnline.put(uniqueId, lastOnline);
    }

    @Override
    public void applyLookup(@NotNull UUID uniqueId, @NotNull StatusKind kind, long now) {
      this.applied.put(uniqueId, kind);
      this.resolved.add(uniqueId);
    }
  }

  private static final class Lookup implements StatusLookup {
    final List<UUID> requested = new ArrayList<>();
    final List<Consumer<LookupResult>> callbacks = new ArrayList<>();

    @Override
    public void lookup(@NotNull UUID uniqueId, @NotNull String jwt, @NotNull Consumer<LookupResult> callback) {
      this.requested.add(uniqueId);
      this.callbacks.add(callback);
    }

    final List<List<UUID>> bulkRequests = new ArrayList<>();
    final List<Consumer<BulkLookupResult>> bulkCallbacks = new ArrayList<>();

    @Override
    public void lookupBulk(
        @NotNull List<UUID> uniqueIds,
        @NotNull String jwt,
        @NotNull Consumer<BulkLookupResult> callback
    ) {
      this.bulkRequests.add(new ArrayList<>(uniqueIds));
      this.bulkCallbacks.add(callback);
    }

    void answer(int index, int code, @Nullable String status) {
      this.callbacks.get(index).accept(new LookupResult(code, status, true, 0L, -1, -1L));
    }
  }

  private List<UUID> enqueueMany(int count) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      this.scheduler.enqueue(id, false);
    }
    return ids;
  }

  private final long[] clock = {1_000_000L};
  private final Host host = new Host();
  private final Lookup lookup = new Lookup();
  private final FetchScheduler scheduler = new FetchScheduler(
      this.host,
      this.lookup,
      new DebugLog(Path.of("unused.log"), Runnable::run),
      () -> this.clock[0]
  );

  @BeforeEach
  void singleLookupsByDefault() {
    this.scheduler.disableBulkUntil(Long.MAX_VALUE);
  }

  private void advance(long millis) {
    this.clock[0] += millis;
  }

  @Test
  void deduplicatesAndPrioritises() {
    UUID normal = UUID.randomUUID();
    UUID urgent = UUID.randomUUID();
    assertTrue(this.scheduler.enqueue(normal, false));
    assertFalse(this.scheduler.enqueue(normal, false));
    assertTrue(this.scheduler.enqueue(urgent, true));
    assertEquals(2, this.scheduler.size());

    this.scheduler.tick(this.clock[0]);
    assertEquals(List.of(urgent), this.lookup.requested);
  }

  @Test
  void promotesQueuedEntryToPriority() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    this.scheduler.enqueue(first, false);
    this.scheduler.enqueue(second, false);
    this.scheduler.enqueue(second, true);

    this.scheduler.tick(this.clock[0]);
    assertEquals(second, this.lookup.requested.get(0));
  }

  @Test
  void keepsSingleRequestInFlightAndRespectsInterval() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.enqueue(b, false);

    this.scheduler.tick(this.clock[0]);
    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.requested.size());

    this.lookup.answer(0, 200, "ONLINE");
    assertEquals(StatusKind.ONLINE, this.host.applied.get(a));

    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.requested.size());

    this.advance(RateLimiter.DEFAULT_INTERVAL_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(List.of(a, b), this.lookup.requested);
  }

  @Test
  void requeuesAfterRateLimit() {
    UUID a = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.tick(this.clock[0]);
    this.lookup.callbacks.get(0).accept(new LookupResult(429, null, false, 6_000L, 0, -1L));

    assertNull(this.host.applied.get(a));
    assertTrue(this.scheduler.isQueued(a));
    assertEquals(6_000L, this.scheduler.limiter().waitMs(this.clock[0]));
  }

  @Test
  void pausesWithoutToken() {
    UUID a = UUID.randomUUID();
    this.host.token = null;
    this.scheduler.enqueue(a, false);
    this.scheduler.tick(this.clock[0]);

    assertTrue(this.lookup.requested.isEmpty());
    assertTrue(this.scheduler.isQueued(a));
    assertEquals(FetchScheduler.NO_TOKEN_PAUSE_MS, this.scheduler.limiter().waitMs(this.clock[0]));
  }

  @Test
  void skipsEntriesThatNoLongerNeedLookup() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.enqueue(b, false);
    this.host.resolved.add(a);

    this.scheduler.tick(this.clock[0]);
    assertEquals(List.of(b), this.lookup.requested);
  }

  @Test
  void recoversFromStuckRequest() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.tick(this.clock[0]);
    this.scheduler.enqueue(b, false);

    this.advance(FetchScheduler.IN_FLIGHT_TIMEOUT_MS);
    this.scheduler.tick(this.clock[0]);
    this.advance(RateLimiter.MAX_BACKOFF_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(List.of(a, b), this.lookup.requested);
  }

  @Test
  void clearDropsQueueAndIgnoresStaleCallbacks() {
    UUID a = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.tick(this.clock[0]);
    this.scheduler.clear();
    this.lookup.callbacks.get(0).accept(new LookupResult(500, null, false, 0L, -1, -1L));

    assertEquals(0, this.scheduler.size());
  }

  @Test
  void boundsUrgentQueue() {
    for (int i = 0; i < FetchScheduler.MAX_URGENT + 10; i++) {
      this.scheduler.enqueue(UUID.randomUUID(), true);
    }
    UUID normal = UUID.randomUUID();
    assertTrue(this.scheduler.enqueue(normal, false));
    assertEquals(FetchScheduler.MAX_URGENT + 11, this.scheduler.size());
  }

  @Test
  void clearWaitsForRunningRequest() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.tick(this.clock[0]);
    this.scheduler.clear();
    this.scheduler.enqueue(b, false);

    this.advance(RateLimiter.DEFAULT_INTERVAL_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.requested.size());

    this.lookup.answer(0, 200, "AWAY");
    this.scheduler.tick(this.clock[0]);
    assertEquals(List.of(a, b), this.lookup.requested);
  }

  @Test
  void usesBulkEvenForOnePlayerWhenAvailable() {
    this.scheduler.disableBulkUntil(0L);
    this.enqueueMany(1);
    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.bulkRequests.size());
    assertTrue(this.lookup.requested.isEmpty());
  }

  @Test
  void bulkRejectionFallsBackToSingleLookups() {
    this.scheduler.disableBulkUntil(0L);
    this.enqueueMany(3);
    this.scheduler.tick(this.clock[0]);
    this.lookup.bulkCallbacks.get(0).accept(new BulkLookupResult(401, null, 0L, -1, -1L));

    assertFalse(this.scheduler.isBulkActive());
    this.advance(RateLimiter.DEFAULT_INTERVAL_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.requested.size());
    assertEquals(1, this.lookup.bulkRequests.size());
  }

  @Test
  void probesBulkAndFallsBackWhenMissing() {
    this.scheduler.disableBulkUntil(0L);
    List<UUID> ids = this.enqueueMany(10);
    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.bulkRequests.size());
    assertEquals(10, this.lookup.bulkRequests.get(0).size());

    this.lookup.bulkCallbacks.get(0).accept(BulkLookupResult.unsupported());
    assertEquals(10, this.scheduler.size());
    assertFalse(this.scheduler.isBulkActive());

    this.advance(RateLimiter.DEFAULT_INTERVAL_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(1, this.lookup.bulkRequests.size());
    assertEquals(List.of(ids.get(0)), this.lookup.requested);

    this.lookup.answer(0, 200, "ONLINE");
    this.advance(FetchScheduler.BULK_RETRY_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(2, this.lookup.bulkRequests.size());
  }

  @Test
  void appliesBulkResultsAndKeepsUsingBulk() {
    this.scheduler.disableBulkUntil(0L);
    List<UUID> ids = this.enqueueMany(10);
    this.scheduler.tick(this.clock[0]);
    Map<UUID, LookupResult> entries = new HashMap<>();
    entries.put(ids.get(0), new LookupResult(200, "BUSY", true, 0L, -1, -1L, 42L));
    entries.put(ids.get(1), new LookupResult(403, null, false, 0L, -1, -1L));
    for (int i = 2; i < ids.size(); i++) {
      entries.put(ids.get(i), new LookupResult(204, null, false, 0L, -1, -1L));
    }
    this.lookup.bulkCallbacks.get(0).accept(new BulkLookupResult(200, entries, 0L, -1, -1L));

    assertTrue(this.scheduler.isBulkActive());
    assertEquals(StatusKind.BUSY, this.host.applied.get(ids.get(0)));
    assertEquals(42L, this.host.lastOnline.get(ids.get(0)));
    assertFalse(this.host.lastOnline.containsKey(ids.get(1)));
    assertEquals(StatusKind.PROFILE_HIDDEN, this.host.applied.get(ids.get(1)));
    assertEquals(StatusKind.NO_LABYMOD, this.host.applied.get(ids.get(2)));
    assertEquals(0, this.scheduler.size());

    this.enqueueMany(2);
    this.advance(RateLimiter.DEFAULT_INTERVAL_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(2, this.lookup.bulkRequests.size());
    assertEquals(2, this.lookup.bulkRequests.get(1).size());
  }

  @Test
  void repeatedBulkErrorsSwitchToSingleLookupsForAWhile() {
    this.scheduler.disableBulkUntil(0L);
    this.enqueueMany(5);
    for (int attempt = 0; attempt < FetchScheduler.BULK_FAILURE_LIMIT; attempt++) {
      this.scheduler.tick(this.clock[0]);
      this.lookup.bulkCallbacks.get(attempt).accept(new BulkLookupResult(503, null, 0L, -1, -1L));
      this.advance(RateLimiter.MAX_BACKOFF_MS);
    }
    assertEquals(FetchScheduler.BULK_FAILURE_LIMIT, this.lookup.bulkRequests.size());

    this.scheduler.tick(this.clock[0]);
    assertEquals(FetchScheduler.BULK_FAILURE_LIMIT, this.lookup.bulkRequests.size());
    assertEquals(1, this.lookup.requested.size());

    this.lookup.answer(0, 200, "ONLINE");
    this.advance(FetchScheduler.BULK_COOLDOWN_MS);
    this.scheduler.tick(this.clock[0]);
    assertEquals(FetchScheduler.BULK_FAILURE_LIMIT + 1, this.lookup.bulkRequests.size());
  }

  @Test
  void stuckBulkRequestsCountAsFailures() {
    this.scheduler.disableBulkUntil(0L);
    this.enqueueMany(5);
    for (int attempt = 0; attempt < FetchScheduler.BULK_FAILURE_LIMIT; attempt++) {
      this.scheduler.tick(this.clock[0]);
      this.advance(FetchScheduler.IN_FLIGHT_TIMEOUT_MS);
      this.scheduler.tick(this.clock[0]);
      this.advance(RateLimiter.MAX_BACKOFF_MS);
    }
    this.scheduler.tick(this.clock[0]);
    assertFalse(this.scheduler.isBulkActive());
    assertTrue(this.lookup.requested.size() >= 1);
  }

  @Test
  void bulkRateLimitRequeuesEveryone() {
    this.scheduler.disableBulkUntil(0L);
    this.enqueueMany(10);
    this.scheduler.tick(this.clock[0]);
    this.lookup.bulkCallbacks.get(0).accept(new BulkLookupResult(429, null, 9_000L, 0, -1L));

    assertEquals(10, this.scheduler.size());
    assertEquals(9_000L, this.scheduler.limiter().waitMs(this.clock[0]));
  }

  @Test
  void hiddenProfileIsFinal() {
    UUID a = UUID.randomUUID();
    this.scheduler.enqueue(a, false);
    this.scheduler.tick(this.clock[0]);
    this.lookup.answer(0, 403, null);

    assertEquals(StatusKind.PROFILE_HIDDEN, this.host.applied.get(a));
    assertFalse(this.scheduler.isQueued(a));
    assertEquals(1, this.lookup.requested.size());
  }
}
