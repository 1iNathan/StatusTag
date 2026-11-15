package net.statustag.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
        return true;
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
    this.store = new StatusStore(this.directory.resolve("cache.json"), this.directory.resolve("legacy.json"));
    this.palette = new StatusPalette(kind -> null);
    this.service = new StatusService(
        config,
        this.store,
        this.palette,
        new DebugLog(this.directory.resolve("debug.log"), Runnable::run),
        (uniqueId, jwt, callback) -> {
        }
    );
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
    assertEquals(java.util.List.of("steve"), this.service.completeTabName("st"));

    this.service.onTabRemove(steve, "Steve");
    assertEquals(null, this.service.findChatSender("[VIP] Steve: hi"));
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
