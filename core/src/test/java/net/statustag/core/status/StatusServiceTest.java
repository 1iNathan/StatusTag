package net.statustag.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.util.DebugLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusServiceTest {

  @TempDir
  Path directory;

  private StatusStore store;
  private StatusPalette palette;
  private StatusService service;

  @BeforeEach
  void setUp() {
    this.store = new StatusStore(this.directory.resolve("cache.json"), this.directory.resolve("legacy.json"));
    this.palette = new StatusPalette(kind -> null);
    this.service = this.createService(this.store, true);
  }

  private StatusService createService(StatusStore store, boolean labyChat) {
    StatusSettings config = new StatusSettings() {
      @Override
      public boolean isActive() {
        return true;
      }

      @Override
      public boolean isApiEnabled() {
        return true;
      }

      @Override
      public boolean isLabyChatEnabled() {
        return labyChat;
      }

      @Override
      public boolean isSyncEnabled() {
        return true;
      }

      @Override
      public boolean isServerDisabled(String host) {
        return "blocked.example".equals(host);
      }
    };
    return new StatusService(
        config,
        store,
        this.palette,
        new DebugLog(this.directory.resolve("debug.log"), Runnable::run),
        (uniqueId, jwt, callback) -> {
        }
    );
  }

  private static StatusEntry aged(StatusKind kind, StatusSource source, long now, long age) {
    return new StatusEntry(kind, source, now - age, now - age);
  }

  @Test
  void repeatedLookupRefreshesCheckTime() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    long now = System.currentTimeMillis();
    this.service.applyLookup(id, StatusKind.ONLINE, now);
    long firstCheck = this.store.get(id).checkedAt();

    this.store.put(id, this.store.get(id).withCheckedAt(firstCheck - StatusPolicy.HOUR_MS));
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.applyLookup(id, StatusKind.ONLINE, System.currentTimeMillis());
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));
  }

  @Test
  void liveFriendStatusWinsOverLookup() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);
    this.service.applyLookup(id, StatusKind.OFFLINE, System.currentTimeMillis());

    assertEquals(StatusKind.BUSY, this.service.kindOf(id));
    assertEquals(StatusSource.FRIEND, this.store.get(id).source());
  }

  @Test
  void freshSyncWinsOverLookup() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.AWAY, StatusSource.SYNC);
    this.service.applyLookup(id, StatusKind.ONLINE, System.currentTimeMillis());

    assertEquals(StatusKind.AWAY, this.service.kindOf(id));
  }

  @Test
  void hidesPlayersWithoutLabyModWhenRequested() {
    UUID id = UUID.randomUUID();
    this.service.applyLookup(id, StatusKind.NO_LABYMOD, System.currentTimeMillis());
    assertTrue(this.service.shownArgb(id) != 0);

    this.palette.setShowPlayersWithoutLabyMod(false);
    assertEquals(0, this.service.shownArgb(id));
    assertEquals(0, this.service.shownArgb(UUID.randomUUID()));
  }

  @Test
  void labyModDetectionForcesRecheckOfMissingAccount() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.applyLookup(id, StatusKind.NO_LABYMOD, System.currentTimeMillis());
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.onLabyModUser(id);
    assertTrue(this.service.isKnownLabyModUser(id));
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));
  }

  @Test
  void playersWhoLeftAreNotLookedUp() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.onTabRemove(id, null);
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.requestLookup(id);
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));
    assertTrue(this.service.scheduler().isQueued(id));
  }

  @Test
  void syncNeverOverridesLiveFriend() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);
    this.service.commit(id, StatusKind.ONLINE, StatusSource.SYNC);
    assertEquals(StatusKind.BUSY, this.service.kindOf(id));

    this.service.demoteLive();
    this.service.commit(id, StatusKind.ONLINE, StatusSource.SYNC);
    assertEquals(StatusKind.ONLINE, this.service.kindOf(id));
  }

  @Test
  void findsChatSendersAmongTabPlayers() {
    UUID steve = UUID.randomUUID();
    this.service.onTabAdd(steve, "Steve");
    assertEquals(steve, this.service.findChatSender("[VIP] Steve: hi"));
    assertEquals(steve, this.service.findTabPlayer("STEVE"));
    assertEquals(List.of("Steve"), this.service.completeTabName("st"));
    assertEquals("Steve", this.service.tabName(steve));

    this.service.onTabRemove(steve, "Steve");
    assertEquals(null, this.service.findChatSender("[VIP] Steve: hi"));
    assertEquals(null, this.service.tabName(steve));
  }

  @Test
  void completesNamesWithTheirCapitalisationInOrder() {
    this.service.onTabAdd(UUID.randomUUID(), "stone_Miner");
    this.service.onTabAdd(UUID.randomUUID(), "Steve");
    this.service.onTabAdd(UUID.randomUUID(), "Alex");

    assertEquals(List.of("Steve", "stone_Miner"), this.service.completeTabName("ST"));
  }

  @Test
  void recentlyUnlistedPlayerBecomesHiddenWithoutSecondLookup() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.applyUnlisted(id, System.currentTimeMillis());
    assertEquals(StatusKind.NO_LABYMOD, this.service.kindOf(id));

    this.service.onLabyModUser(id);
    assertEquals(StatusKind.PROFILE_HIDDEN, this.service.kindOf(id));
    assertEquals(StatusSource.API, this.store.get(id).source());
    assertFalse(this.service.scheduler().isQueued(id));
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));
  }

  @Test
  void labyModUserSeenBeforeUnlistedAnswerGetsHiddenProfile() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.onLabyModUser(id);
    this.service.applyUnlisted(id, System.currentTimeMillis());

    assertEquals(StatusKind.PROFILE_HIDDEN, this.service.kindOf(id));
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));
  }

  @Test
  void oldUnlistedAnswerStillForcesRecheck() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.applyUnlisted(id, System.currentTimeMillis() - 3L * StatusPolicy.MINUTE_MS);

    this.service.onLabyModUser(id);
    assertEquals(StatusKind.NO_LABYMOD, this.service.kindOf(id));
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));
    assertTrue(this.service.scheduler().isQueued(id));
  }

  @Test
  void laterMissingAccountAnswerClearsTheUnlistedMark() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    long now = System.currentTimeMillis();
    this.service.applyUnlisted(id, now);
    this.service.applyLookup(id, StatusKind.NO_LABYMOD, now);

    this.service.onLabyModUser(id);
    assertEquals(StatusKind.NO_LABYMOD, this.service.kindOf(id));
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));
  }

  @Test
  void dueSoonReturnsTabPlayersAboutToRefresh() {
    long now = System.currentTimeMillis();
    long refresh = StatusPolicy.refreshAfterMs(StatusKind.OFFLINE);
    UUID soon = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    UUID offTab = UUID.randomUUID();
    UUID friend = UUID.randomUUID();
    UUID presence = UUID.randomUUID();
    UUID self = UUID.randomUUID();
    for (UUID id : List.of(soon, recent, friend, presence, self)) {
      this.service.onTabAdd(id, null);
    }
    this.service.applySelf(self, StatusKind.ONLINE);
    this.store.put(soon, aged(StatusKind.OFFLINE, StatusSource.API, now, refresh - 30_000L));
    this.store.put(recent, aged(StatusKind.OFFLINE, StatusSource.API, now, refresh / 2L));
    this.store.put(offTab, aged(StatusKind.OFFLINE, StatusSource.API, now, refresh - 30_000L));
    this.store.put(friend, aged(StatusKind.OFFLINE, StatusSource.FRIEND, now, refresh - 30_000L));
    this.store.put(presence, aged(StatusKind.ONLINE, StatusSource.API, now,
        StatusPolicy.refreshAfterMs(StatusKind.ONLINE) - 30_000L));
    this.store.put(self, aged(StatusKind.OFFLINE, StatusSource.API, now, refresh - 30_000L));

    assertEquals(Set.of(soon, presence), Set.copyOf(this.service.dueSoon(now, 100)));
    assertEquals(List.of(), this.service.dueSoon(now, 0));

    this.service.refreshSuppression("blocked.example");
    assertEquals(List.of(), this.service.dueSoon(now, 100));
  }

  @Test
  void dueSoonStopsAtTheLimit() {
    long now = System.currentTimeMillis();
    long age = StatusPolicy.refreshAfterMs(StatusKind.INVISIBLE) - 30_000L;
    for (int i = 0; i < 3; i++) {
      UUID id = UUID.randomUUID();
      this.service.onTabAdd(id, null);
      this.store.put(id, aged(StatusKind.INVISIBLE, StatusSource.CACHE, now, age));
    }
    assertEquals(2, this.service.dueSoon(now, 2).size());
  }

  @Test
  void labyConnectLossKeepsFriendStatusUntilNormalRefresh() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);

    this.service.onLabyConnectLost();
    long now = System.currentTimeMillis();
    assertEquals(StatusSource.CACHE, this.store.get(id).source());
    assertEquals(StatusKind.BUSY, this.service.kindOf(id));
    assertFalse(this.service.needsLookup(id, now));
    assertTrue(this.service.needsLookup(id, now + StatusPolicy.refreshAfterMs(StatusKind.BUSY) + 1_000L));

    this.service.commit(id, StatusKind.AWAY, StatusSource.FRIEND);
    assertEquals(StatusSource.FRIEND, this.store.get(id).source());
    assertEquals(StatusKind.AWAY, this.service.kindOf(id));
  }

  @Test
  void lookupAfterLabyConnectLossIsStillApplied() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);
    this.service.onLabyConnectLost();
    this.service.applyLookup(id, StatusKind.OFFLINE, System.currentTimeMillis());

    assertEquals(StatusKind.OFFLINE, this.service.kindOf(id));
    assertEquals(StatusSource.API, this.store.get(id).source());
  }

  @Test
  void ownStatusFollowsWithoutLabyChatSource() {
    StatusService service = this.createService(this.store, false);
    UUID id = UUID.randomUUID();
    service.applySelf(id, StatusKind.BUSY);
    assertEquals(StatusKind.BUSY, service.kindOf(id));
    assertEquals(StatusSource.SELF, this.store.get(id).source());

    service.demoteLive();
    service.applySelf(id, StatusKind.ONLINE);
    assertEquals(StatusKind.ONLINE, service.kindOf(id));
    assertEquals(StatusSource.SELF, this.store.get(id).source());

    service.onTabAdd(id, null);
    assertFalse(service.needsLookup(id, System.currentTimeMillis()));
  }

  @Test
  void accountSwitchDemotesLiveEntriesOfThePreviousAccount() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID friend = UUID.randomUUID();
    assertFalse(this.service.applySelf(first, StatusKind.ONLINE));
    this.service.commit(friend, StatusKind.BUSY, StatusSource.FRIEND);

    assertFalse(this.service.applySelf(first, StatusKind.AWAY));
    assertEquals(StatusSource.FRIEND, this.store.get(friend).source());
    assertEquals(StatusKind.AWAY, this.service.kindOf(first));

    assertTrue(this.service.applySelf(second, StatusKind.ONLINE));
    assertEquals(StatusSource.CACHE, this.store.get(friend).source());
    assertEquals(StatusSource.CACHE, this.store.get(first).source());
    assertEquals(StatusSource.SELF, this.store.get(second).source());

    this.service.onTabAdd(friend, null);
    this.service.onTabAdd(first, null);
    long now = System.currentTimeMillis();
    assertTrue(this.service.needsLookup(friend, now));
    assertTrue(this.service.needsLookup(first, now));
  }

  @Test
  void priorityTable() {
    long now = System.currentTimeMillis();
    for (StatusSource live : new StatusSource[] {StatusSource.FRIEND, StatusSource.SELF}) {
      StatusEntry current = aged(StatusKind.BUSY, live, now, StatusPolicy.DAY_MS);
      assertTrue(StatusService.outranks(current, StatusSource.SYNC, now));
      assertTrue(StatusService.outranks(current, StatusSource.API, now));
    }
    for (StatusSource source : StatusSource.values()) {
      StatusEntry current = aged(StatusKind.BUSY, source, now, 0L);
      assertFalse(StatusService.outranks(current, StatusSource.FRIEND, now));
      assertFalse(StatusService.outranks(current, StatusSource.SELF, now));
    }

    StatusEntry freshSync = aged(StatusKind.AWAY, StatusSource.SYNC, now, StatusPolicy.SYNC_FRESH_MS - 1L);
    StatusEntry staleSync = aged(StatusKind.AWAY, StatusSource.SYNC, now, StatusPolicy.SYNC_FRESH_MS);
    assertTrue(StatusService.outranks(freshSync, StatusSource.API, now));
    assertFalse(StatusService.outranks(staleSync, StatusSource.API, now));
    assertFalse(StatusService.outranks(freshSync, StatusSource.SYNC, now));

    for (StatusSource stored : new StatusSource[] {StatusSource.API, StatusSource.CACHE}) {
      StatusEntry current = aged(StatusKind.ONLINE, stored, now, 0L);
      assertFalse(StatusService.outranks(current, StatusSource.API, now));
      assertFalse(StatusService.outranks(current, StatusSource.SYNC, now));
    }
  }

  @Test
  void staleSyncYieldsToLookup() {
    UUID kept = UUID.randomUUID();
    UUID replaced = UUID.randomUUID();
    long now = System.currentTimeMillis();
    this.store.put(kept, aged(StatusKind.BUSY, StatusSource.SYNC, now, StatusPolicy.SYNC_FRESH_MS - 1_000L));
    this.store.put(replaced, aged(StatusKind.BUSY, StatusSource.SYNC, now, StatusPolicy.SYNC_FRESH_MS));

    this.service.applyLookup(kept, StatusKind.PROFILE_HIDDEN, now);
    this.service.applyLookup(replaced, StatusKind.PROFILE_HIDDEN, now);
    assertEquals(StatusKind.BUSY, this.service.kindOf(kept));
    assertEquals(StatusSource.SYNC, this.store.get(kept).source());
    assertEquals(StatusKind.PROFILE_HIDDEN, this.service.kindOf(replaced));
    assertEquals(StatusSource.API, this.store.get(replaced).source());
  }

  @Test
  void outrankedLookupOnlyRecordsTheCheck() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.AWAY, StatusSource.SYNC);
    StatusEntry before = this.store.get(id);
    long checkedAt = before.updatedAt() + 1_000L;

    this.service.applyLookup(id, StatusKind.ONLINE, checkedAt);
    StatusEntry after = this.store.get(id);
    assertEquals(StatusKind.AWAY, after.kind());
    assertEquals(StatusSource.SYNC, after.source());
    assertEquals(before.updatedAt(), after.updatedAt());
    assertEquals(checkedAt, after.checkedAt());
  }

  @Test
  void offlineAnswerDoesNotReplaceARecentlySeenStatus() {
    long now = System.currentTimeMillis();
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.applyLookup(id, StatusKind.AWAY, now);

    this.service.applyLookup(id, StatusKind.INVISIBLE, now + 120_000L);
    assertEquals(StatusKind.AWAY, this.service.kindOf(id));
    assertEquals(now + 120_000L, this.store.get(id).checkedAt());
    assertEquals(now, this.store.get(id).updatedAt());

    this.service.applyLookup(id, StatusKind.ONLINE, now + 240_000L);
    assertEquals(StatusKind.ONLINE, this.service.kindOf(id));

    long late = now + 240_000L + StatusPolicy.PRESENCE_HOLD_MS;
    this.service.applyLookup(id, StatusKind.INVISIBLE, late - 1L);
    assertEquals(StatusKind.ONLINE, this.service.kindOf(id));
    this.service.applyLookup(id, StatusKind.INVISIBLE, late);
    assertEquals(StatusKind.INVISIBLE, this.service.kindOf(id));
  }

  @Test
  void hiddenProfileAnswerStillReplacesASeenStatus() {
    long now = System.currentTimeMillis();
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.applyLookup(id, StatusKind.ONLINE, now);
    this.service.applyLookup(id, StatusKind.PROFILE_HIDDEN, now + 120_000L);
    assertEquals(StatusKind.PROFILE_HIDDEN, this.service.kindOf(id));
  }

  @Test
  void playersKnownWithAStatusStayLabyModUsers() {
    long now = System.currentTimeMillis();
    UUID hidden = UUID.randomUUID();
    UUID busy = UUID.randomUUID();
    UUID none = UUID.randomUUID();
    UUID invisible = UUID.randomUUID();
    this.store.put(hidden, aged(StatusKind.PROFILE_HIDDEN, StatusSource.CACHE, now, 60_000L));
    this.store.put(busy, aged(StatusKind.BUSY, StatusSource.SYNC, now, 60_000L));
    this.store.put(none, aged(StatusKind.NO_LABYMOD, StatusSource.API, now, 60_000L));
    this.store.put(invisible, aged(StatusKind.INVISIBLE, StatusSource.API, now, 60_000L));

    assertTrue(this.service.isKnownLabyModUser(hidden));
    assertTrue(this.service.isKnownLabyModUser(busy));
    assertFalse(this.service.isKnownLabyModUser(none));
    assertFalse(this.service.isKnownLabyModUser(invisible));
    assertFalse(this.service.isKnownLabyModUser(UUID.randomUUID()));
  }

  @Test
  void forgottenSyncStatusFallsBackToLookups() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.commit(id, StatusKind.BUSY, StatusSource.SYNC);
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.forgetSynced();
    assertEquals(StatusSource.CACHE, this.store.get(id).source());
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.applyLookup(id, StatusKind.ONLINE, System.currentTimeMillis());
    assertEquals(StatusKind.ONLINE, this.service.kindOf(id));
  }

  @Test
  void demotedFriendFallsBackToLookups() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);
    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);
    assertFalse(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.demoteLive();
    assertEquals(StatusSource.CACHE, this.store.get(id).source());
    assertEquals(0L, this.store.get(id).checkedAt());
    assertTrue(this.service.needsLookup(id, System.currentTimeMillis()));

    this.service.applyLookup(id, StatusKind.OFFLINE, System.currentTimeMillis());
    assertEquals(StatusKind.OFFLINE, this.service.kindOf(id));
    assertEquals(StatusSource.API, this.store.get(id).source());
  }

  @Test
  void unchangedLiveStatusKeepsTheEntry() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);
    StatusEntry before = this.store.get(id);

    this.service.commit(id, StatusKind.BUSY, StatusSource.FRIEND);
    assertSame(before, this.store.get(id));
  }

  @Test
  void countsKindsInTab() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    this.service.onTabAdd(a, "a_player");
    this.service.onTabAdd(b, "b_player");
    this.service.commit(a, StatusKind.BUSY, StatusSource.SYNC);

    int[] counts = this.service.tabKindCounts();
    assertEquals(2, this.service.tabPlayerCount());
    assertEquals(1, counts[StatusKind.BUSY.ordinal()]);
    assertEquals(1, counts[StatusKind.UNKNOWN.ordinal()]);
  }

  @Test
  void remembersLastOnlineUntilCacheIsCleared() {
    UUID id = UUID.randomUUID();
    assertEquals(null, this.service.lastOnline(id));
    this.service.recordLastOnline(id, 1_234L);
    assertEquals(1_234L, this.service.lastOnline(id));
  }

  @Test
  void ignoresNonMojangUuids() {
    assertFalse(this.service.needsLookup(UUID.nameUUIDFromBytes("OfflinePlayer:Alex".getBytes()),
        System.currentTimeMillis()));
  }

  @Test
  void defaultColorsGroupHiddenWithOfflineAndUnknownWithMissing() {
    UUID hidden = UUID.randomUUID();
    UUID offline = UUID.randomUUID();
    this.service.commit(hidden, StatusKind.PROFILE_HIDDEN, StatusSource.API);
    this.service.commit(offline, StatusKind.OFFLINE, StatusSource.API);
    assertEquals(0xFFAAAAAA, this.service.shownArgb(hidden));
    assertEquals(0xFFAAAAAA, this.service.shownArgb(offline));
    assertEquals(0xFF555555, this.service.shownArgb(UUID.randomUUID()));
  }

  @Test
  void usesConfiguredColors() {
    UUID id = UUID.randomUUID();
    this.service.commit(id, StatusKind.ONLINE, StatusSource.SYNC);
    assertEquals(0xFF000000 | StatusKind.ONLINE.defaultRgb(), this.service.shownArgb(id));
  }

  @Test
  void disabledServerStopsLookups() {
    UUID id = UUID.randomUUID();
    this.service.onTabAdd(id, null);

    this.service.refreshSuppression("blocked.example");
    assertTrue(this.service.isSuppressed());
    assertFalse(this.service.lookupsEnabled());
    this.service.requestLookup(id);
    assertEquals(0, this.service.scheduler().size());

    this.service.refreshSuppression("other.example");
    assertFalse(this.service.isSuppressed());
    assertTrue(this.service.lookupsEnabled());
  }
}
