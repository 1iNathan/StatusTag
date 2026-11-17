package net.statustag.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatusPolicyTest {

  private static final long NOW = 1_794_500_000_000L;

  private static StatusEntry entry(StatusKind kind, StatusSource source, long age) {
    return new StatusEntry(kind, source, NOW - age, NOW - age);
  }

  @Test
  void unknownPlayersNeedLookup() {
    assertTrue(StatusPolicy.needsFetch(null, NOW));
  }

  @Test
  void liveSourcesNeverNeedLookup() {
    assertFalse(StatusPolicy.needsFetch(entry(StatusKind.ONLINE, StatusSource.FRIEND, StatusPolicy.DAY_MS), NOW));
    assertFalse(StatusPolicy.needsFetch(entry(StatusKind.BUSY, StatusSource.SELF, StatusPolicy.DAY_MS), NOW));
  }

  @Test
  void syncEntriesExpireAfterFreshWindow() {
    assertFalse(StatusPolicy.needsFetch(entry(StatusKind.AWAY, StatusSource.SYNC, StatusPolicy.MINUTE_MS), NOW));
    assertTrue(StatusPolicy.needsFetch(entry(StatusKind.AWAY, StatusSource.SYNC, StatusPolicy.SYNC_FRESH_MS), NOW));
  }

  @Test
  void presenceRefreshesWithinMinutes() {
    long refresh = StatusPolicy.refreshAfterMs(StatusKind.ONLINE);
    assertFalse(StatusPolicy.needsFetch(entry(StatusKind.ONLINE, StatusSource.API, refresh - 1L), NOW));
    assertTrue(StatusPolicy.needsFetch(entry(StatusKind.ONLINE, StatusSource.API, refresh), NOW));
    assertTrue(refresh < StatusPolicy.HOUR_MS);
  }

  @Test
  void hiddenProfilesAndMissingAccountsAreRarelyRechecked() {
    assertFalse(StatusPolicy.needsFetch(entry(StatusKind.PROFILE_HIDDEN, StatusSource.API, StatusPolicy.HOUR_MS), NOW));
    assertFalse(StatusPolicy.needsFetch(entry(StatusKind.NO_LABYMOD, StatusSource.CACHE, 12L * StatusPolicy.HOUR_MS), NOW));
    assertTrue(StatusPolicy.needsFetch(entry(StatusKind.NO_LABYMOD, StatusSource.CACHE, StatusPolicy.DAY_MS), NOW));
  }

  @Test
  void uncheckedCacheEntriesNeedLookup() {
    assertTrue(StatusPolicy.needsFetch(new StatusEntry(StatusKind.ONLINE, StatusSource.CACHE, NOW, 0L), NOW));
  }

  @Test
  void asCacheKeepsTheCheckTimeOnlyForLookups() {
    StatusEntry api = entry(StatusKind.OFFLINE, StatusSource.API, StatusPolicy.MINUTE_MS);
    assertEquals(api.checkedAt(), api.asCache().checkedAt());
    assertEquals(StatusSource.CACHE, api.asCache().source());
    assertFalse(StatusPolicy.needsFetch(api.asCache(), NOW));

    StatusEntry friend = entry(StatusKind.BUSY, StatusSource.FRIEND, StatusPolicy.MINUTE_MS);
    assertEquals(0L, friend.asCache().checkedAt());
    assertEquals(friend.updatedAt(), friend.asCache().updatedAt());
    assertTrue(StatusPolicy.needsFetch(friend.asCache(), NOW));

    StatusEntry sync = entry(StatusKind.AWAY, StatusSource.SYNC, StatusPolicy.MINUTE_MS);
    assertEquals(0L, sync.asCache().checkedAt());
    assertTrue(StatusPolicy.needsFetch(sync.asCache(), NOW));
  }

  @Test
  void refreshesWithinOnlyForLookupsAboutToExpire() {
    long ahead = StatusPolicy.MINUTE_MS;
    long refresh = StatusPolicy.refreshAfterMs(StatusKind.OFFLINE);
    assertTrue(StatusPolicy.refreshesWithin(entry(StatusKind.OFFLINE, StatusSource.API, refresh - 59_000L), NOW, ahead));
    assertTrue(StatusPolicy.refreshesWithin(
        entry(StatusKind.INVISIBLE, StatusSource.CACHE, StatusPolicy.refreshAfterMs(StatusKind.INVISIBLE) - 59_000L), NOW, ahead));
    assertFalse(StatusPolicy.refreshesWithin(entry(StatusKind.OFFLINE, StatusSource.API, refresh - 61_000L), NOW, ahead));
    assertFalse(StatusPolicy.refreshesWithin(entry(StatusKind.OFFLINE, StatusSource.API, refresh), NOW, ahead));
    assertFalse(StatusPolicy.refreshesWithin(new StatusEntry(StatusKind.OFFLINE, StatusSource.CACHE, NOW, 0L), NOW, ahead));
    assertFalse(StatusPolicy.refreshesWithin(null, NOW, ahead));
  }

  @Test
  void refreshesWithinIgnoresLiveAndSyncButCoversPresence() {
    long ahead = StatusPolicy.MINUTE_MS;
    assertFalse(StatusPolicy.refreshesWithin(entry(StatusKind.OFFLINE, StatusSource.FRIEND, StatusPolicy.DAY_MS), NOW, ahead));
    assertFalse(StatusPolicy.refreshesWithin(entry(StatusKind.BUSY, StatusSource.SELF, StatusPolicy.DAY_MS), NOW, ahead));
    assertFalse(StatusPolicy.refreshesWithin(
        entry(StatusKind.AWAY, StatusSource.SYNC, StatusPolicy.SYNC_FRESH_MS - 30_000L), NOW, ahead));
    for (StatusKind kind : new StatusKind[] {StatusKind.ONLINE, StatusKind.AWAY, StatusKind.BUSY}) {
      long refresh = StatusPolicy.refreshAfterMs(kind);
      assertTrue(StatusPolicy.refreshesWithin(entry(kind, StatusSource.API, refresh - 30_000L), NOW, ahead));
    }
  }

  @Test
  void seenPresenceIsHeldAgainstAnOfflineAnswer() {
    long hold = StatusPolicy.PRESENCE_HOLD_MS;
    assertTrue(StatusPolicy.holdsPresence(entry(StatusKind.ONLINE, StatusSource.API, 60_000L), StatusKind.INVISIBLE, NOW));
    assertTrue(StatusPolicy.holdsPresence(entry(StatusKind.AWAY, StatusSource.CACHE, hold - 1L), StatusKind.INVISIBLE, NOW));
    assertFalse(StatusPolicy.holdsPresence(entry(StatusKind.BUSY, StatusSource.API, hold), StatusKind.INVISIBLE, NOW));
    assertFalse(StatusPolicy.holdsPresence(entry(StatusKind.ONLINE, StatusSource.API, 60_000L), StatusKind.AWAY, NOW));
    assertFalse(StatusPolicy.holdsPresence(entry(StatusKind.ONLINE, StatusSource.API, 60_000L), StatusKind.PROFILE_HIDDEN, NOW));
    assertFalse(StatusPolicy.holdsPresence(entry(StatusKind.ONLINE, StatusSource.API, 60_000L), StatusKind.OFFLINE, NOW));
    assertFalse(StatusPolicy.holdsPresence(entry(StatusKind.INVISIBLE, StatusSource.API, 60_000L), StatusKind.INVISIBLE, NOW));
    assertFalse(StatusPolicy.holdsPresence(entry(StatusKind.ONLINE, StatusSource.SYNC, 60_000L), StatusKind.INVISIBLE, NOW));
    assertFalse(StatusPolicy.holdsPresence(null, StatusKind.INVISIBLE, NOW));
    assertTrue(StatusPolicy.refreshAfterMs(StatusKind.INVISIBLE) <= 2L * StatusPolicy.MINUTE_MS);
    assertTrue(StatusPolicy.refreshAfterMs(StatusKind.ONLINE) <= 2L * StatusPolicy.MINUTE_MS);
  }

  @Test
  void onlyMojangUuidsAreLookedUp() {
    assertTrue(StatusPolicy.isLookupCandidate(UUID.randomUUID()));
    assertFalse(StatusPolicy.isLookupCandidate(UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes())));
    assertFalse(StatusPolicy.isLookupCandidate(new UUID(0L, 0x1234L)));
  }
}
