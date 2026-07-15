package net.statustag.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.statustag.core.internal.StatusTagInternals;
import org.junit.jupiter.api.Test;

class StatusReprobePolicyTest {

  private static final long MINUTE = 60_000L;
  private static final long HOUR = 3_600_000L;
  private static final long DAY = 86_400_000L;

  private final StatusTagInternals internals = new StatusTagInternals();

  private static StatusEntry entry(StatusKind kind, StatusSource source, long lastCheckedAt) {
    return new StatusEntry(UUID.randomUUID(), kind, source, lastCheckedAt, lastCheckedAt, false, 0, 0L);
  }

  @Test
  void nullEntryIsAlwaysFetched() {
    assertTrue(StatusReprobePolicy.shouldFetchFromApi(null, this.internals));
  }

  @Test
  void authoritativeEntriesAreNeverFetched() {
    long ancient = System.currentTimeMillis() - 365 * DAY;
    assertFalse(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.ONLINE, StatusSource.FRIEND, ancient), this.internals));
    assertFalse(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.ONLINE, StatusSource.SELF, ancient), this.internals));
  }

  @Test
  void staleMeshEntriesBecomeFetchable() {
    UUID uuid = UUID.randomUUID();
    long old = System.currentTimeMillis() - 10 * MINUTE;
    StatusEntry stale = new StatusEntry(uuid, StatusKind.ONLINE, StatusSource.MESH, 0L, old, false, 0, old);
    assertTrue(StatusReprobePolicy.shouldFetchFromApi(stale, this.internals));

    long fresh = System.currentTimeMillis();
    StatusEntry live = new StatusEntry(uuid, StatusKind.ONLINE, StatusSource.MESH, 0L, fresh, false, 0, fresh);
    assertFalse(StatusReprobePolicy.shouldFetchFromApi(live, this.internals));
  }

  @Test
  void neverCheckedEntryIsFetched() {
    assertTrue(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.PROFILE_HIDDEN, StatusSource.API, 0L), this.internals));
  }

  @Test
  void profileHiddenRefetchesAfterSixHours() {
    long now = System.currentTimeMillis();
    assertFalse(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.PROFILE_HIDDEN, StatusSource.API, now - 5 * HOUR), this.internals));
    assertTrue(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.PROFILE_HIDDEN, StatusSource.API, now - 7 * HOUR), this.internals));
  }

  @Test
  void invisibleRefetchesAfterThirtyMinutes() {
    long now = System.currentTimeMillis();
    assertFalse(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.INVISIBLE, StatusSource.API, now - 29 * MINUTE), this.internals));
    assertTrue(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.INVISIBLE, StatusSource.API, now - 31 * MINUTE), this.internals));
  }

  @Test
  void liveKindsUseSoftRefreshInterval() {
    long now = System.currentTimeMillis();
    assertFalse(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.ONLINE, StatusSource.API, now - 29 * DAY), this.internals));
    assertTrue(StatusReprobePolicy.shouldFetchFromApi(entry(StatusKind.ONLINE, StatusSource.API, now - 31 * DAY), this.internals));
  }

  @Test
  void reprobeIntervalsMatchInternals() {
    assertEquals(6 * HOUR, StatusReprobePolicy.reprobeIntervalMs(StatusKind.PROFILE_HIDDEN, this.internals));
    assertEquals(30 * MINUTE, StatusReprobePolicy.reprobeIntervalMs(StatusKind.INVISIBLE, this.internals));
    assertEquals(24 * HOUR, StatusReprobePolicy.reprobeIntervalMs(StatusKind.NO_LABYMOD, this.internals));
    assertEquals(30 * DAY, StatusReprobePolicy.reprobeIntervalMs(StatusKind.OFFLINE, this.internals));
    assertEquals(30 * DAY, StatusReprobePolicy.reprobeIntervalMs(StatusKind.UNKNOWN, this.internals));
    assertEquals(30 * DAY, StatusReprobePolicy.reprobeIntervalMs(StatusKind.ONLINE, this.internals));
    assertEquals(15 * MINUTE, StatusReprobePolicy.tabPresenceReprobeMs(this.internals));
  }

  @Test
  void onlyStaleableKindsAreReprobeCandidates() {
    assertTrue(StatusReprobePolicy.isReprobeCandidate(StatusKind.PROFILE_HIDDEN));
    assertTrue(StatusReprobePolicy.isReprobeCandidate(StatusKind.INVISIBLE));
    assertTrue(StatusReprobePolicy.isReprobeCandidate(StatusKind.NO_LABYMOD));
    assertTrue(StatusReprobePolicy.isReprobeCandidate(StatusKind.OFFLINE));
    assertTrue(StatusReprobePolicy.isReprobeCandidate(StatusKind.UNKNOWN));
    assertFalse(StatusReprobePolicy.isReprobeCandidate(StatusKind.ONLINE));
    assertFalse(StatusReprobePolicy.isReprobeCandidate(StatusKind.AWAY));
    assertFalse(StatusReprobePolicy.isReprobeCandidate(StatusKind.BUSY));
  }
}
