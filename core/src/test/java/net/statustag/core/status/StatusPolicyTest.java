package net.statustag.core.status;

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
  void onlyMojangUuidsAreLookedUp() {
    assertTrue(StatusPolicy.isLookupCandidate(UUID.randomUUID()));
    assertFalse(StatusPolicy.isLookupCandidate(UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes())));
    assertFalse(StatusPolicy.isLookupCandidate(new UUID(0L, 0x1234L)));
  }
}
