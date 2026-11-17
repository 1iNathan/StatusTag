package net.statustag.core.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusPolicy;
import net.statustag.core.status.StatusService;
import net.statustag.core.status.StatusSettings;
import net.statustag.core.status.StatusStore;
import net.statustag.core.util.DebugLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusSyncTest {

  private record Sent(UUID[] receivers, JsonObject payload, long at) {
  }

  private static final class Transport implements SyncTransport {
    final UUID self = UUID.randomUUID();
    final List<JsonObject> broadcasts = new ArrayList<>();
    final List<Sent> sent = new ArrayList<>();
    final List<Long> times = new ArrayList<>();
    final List<Long> broadcastTimes = new ArrayList<>();
    final Set<UUID> absent = new HashSet<>();
    StatusKind status = StatusKind.ONLINE;
    boolean audience = true;
    long[] clock;

    @Override
    public @Nullable UUID selfId() {
      return this.self;
    }

    @Override
    public @Nullable StatusKind selfStatus() {
      return this.status;
    }

    @Override
    public boolean hasAudience() {
      return this.audience;
    }

    @Override
    public boolean inTabList(@NotNull UUID uniqueId) {
      return !this.absent.contains(uniqueId);
    }

    @Override
    public boolean broadcast(@NotNull String key, @NotNull JsonObject payload) {
      this.broadcasts.add(payload);
      this.times.add(this.clock[0]);
      this.broadcastTimes.add(this.clock[0]);
      return true;
    }

    @Override
    public boolean send(@NotNull String key, @NotNull UUID[] receivers, @NotNull JsonObject payload) {
      this.sent.add(new Sent(receivers, payload, this.clock[0]));
      this.times.add(this.clock[0]);
      return true;
    }
  }

  private static final class Settings implements StatusSettings {
    boolean sync = true;

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
      return this.sync;
    }
  }

  private static final long LEGACY_NEW_PEER_MS = 150_000L;
  private static final long RELEASED_FRESH_MS = 240_000L;

  @TempDir
  Path directory;

  private final long[] clock = {1_794_500_000_000L};
  private final Transport transport = new Transport();
  private final Settings settings = new Settings();
  private StatusService service;
  private StatusSync sync;

  @BeforeEach
  void setUp() {
    DebugLog debug = new DebugLog(this.directory.resolve("debug.log"), Runnable::run);
    this.service = new StatusService(
        this.settings,
        new StatusStore(this.directory.resolve("cache.json"), this.directory.resolve("legacy.json")),
        new StatusPalette(kind -> null),
        debug,
        (uniqueId, jwt, callback) -> {
        }
    );
    this.transport.clock = this.clock;
    this.sync = new StatusSync(this.settings, this.service, this.transport, debug, () -> this.clock[0]);
  }

  private void run(long millis) {
    for (long passed = 0; passed < millis; passed += 50L) {
      this.clock[0] += 50L;
      this.sync.tick();
    }
  }

  private void hear(UUID peer, String status, boolean replyRequested) {
    this.sync.handleIncoming(peer, StatusSync.BROADCAST_KEY, message(status, replyRequested));
  }

  private static JsonObject message(String status, boolean replyRequested) {
    return StatusSync.payload(StatusKind.valueOf(status), replyRequested);
  }

  @Test
  void announcesOnceTheStatusHasSettled() {
    this.run(StatusSync.STATUS_SETTLE_MS - 500L);
    assertTrue(this.transport.broadcasts.isEmpty());

    this.run(1_000L);
    assertEquals(1, this.transport.broadcasts.size());
    assertEquals("ONLINE", this.transport.broadcasts.get(0).get("status").getAsString());

    this.run(30_000L);
    assertEquals(1, this.transport.broadcasts.size());
  }

  @Test
  void cyclingThroughStatusesSendsOnlyTheLastOne() {
    this.run(5_000L);
    for (StatusKind kind : new StatusKind[]{StatusKind.AWAY, StatusKind.BUSY, StatusKind.OFFLINE, StatusKind.AWAY}) {
      this.transport.status = kind;
      this.run(600L);
    }
    this.run(10_000L);

    assertEquals(2, this.transport.broadcasts.size());
    assertEquals("AWAY", this.transport.broadcasts.get(1).get("status").getAsString());
  }

  @Test
  void resyncWaitsForTheTabListAndCarriesTheStatus() {
    this.sync.requestResync();
    this.run(StatusSync.RESYNC_DELAY_MS - 500L);
    assertTrue(this.transport.broadcasts.isEmpty());

    this.run(1_000L);
    assertEquals(1, this.transport.broadcasts.size());
    assertTrue(this.transport.broadcasts.get(0).get("r").getAsBoolean());

    this.run(20_000L);
    assertEquals(1, this.transport.broadcasts.size());
  }

  @Test
  void resyncWaitsForAStatusThatIsStillChanging() {
    this.sync.requestResync();
    this.run(StatusSync.RESYNC_DELAY_MS - 500L);
    this.transport.status = StatusKind.AWAY;
    this.run(600L);
    this.transport.status = StatusKind.BUSY;
    this.run(10_000L);

    assertEquals(1, this.transport.broadcasts.size());
    assertEquals("BUSY", this.transport.broadcasts.get(0).get("status").getAsString());
    assertTrue(this.transport.broadcasts.get(0).get("r").getAsBoolean());
  }

  @Test
  void repeatedResyncsAreSpacedOut() {
    this.sync.requestResync();
    this.run(5_000L);
    this.sync.requestResync();
    this.sync.requestResync();
    this.run(StatusSync.RESYNC_GAP_MS - 10_000L);
    assertEquals(1, this.transport.broadcasts.size());

    this.run(10_000L);
    assertEquals(2, this.transport.broadcasts.size());
  }

  @Test
  void answersToOurResyncAreNotEchoedBack() {
    this.sync.requestResync();
    this.run(4_000L);
    for (int i = 0; i < 4; i++) {
      this.hear(UUID.randomUUID(), "ONLINE", false);
      this.run(300L);
    }
    this.run(30_000L);

    assertEquals(4, this.sync.peerCount());
    assertTrue(this.transport.sent.isEmpty());
  }

  @Test
  void askingPeersShareOneReply() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    this.hear(a, "ONLINE", true);
    this.run(200L);
    this.hear(b, "AWAY", true);
    this.run(200L);
    this.hear(c, "BUSY", true);
    this.run(5_000L);

    assertEquals(1, this.transport.sent.size());
    assertEquals(3, this.transport.sent.get(0).receivers().length);
    assertEquals(StatusKind.BUSY, this.service.kindOf(c));
  }

  @Test
  void newPeerOutsideOurBroadcastGetsAReply() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", false);
    this.run(3_000L);

    assertEquals(1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{peer}, this.transport.sent.get(0).receivers());
  }

  @Test
  void knownPeersAreNotRepliedToAgain() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", false);
    this.run(3_000L);
    this.hear(peer, "AWAY", false);
    this.run(3_000L);

    assertEquals(1, this.transport.sent.size());
    assertEquals(StatusKind.AWAY, this.service.kindOf(peer));
  }

  @Test
  void heartbeatGoesOnlyToKnownPeers() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", false);
    this.run(StatusSync.HEARTBEAT_MS);

    assertEquals(1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{peer}, this.transport.sent.get(0).receivers());
    assertEquals(1, this.transport.broadcasts.size());
  }

  @Test
  void heavyTrafficNeverBreaksTheBudget() {
    List<Long> all = new ArrayList<>();
    this.sync.requestResync();
    for (int second = 0; second < 600; second++) {
      if (second % 7 == 0) {
        this.hear(UUID.randomUUID(), "ONLINE", true);
      }
      if (second % 11 == 0) {
        this.transport.status = this.transport.status == StatusKind.ONLINE ? StatusKind.AWAY : StatusKind.ONLINE;
      }
      if (second % 45 == 0) {
        this.sync.requestResync();
      }
      if (second % 40 == 0) {
        this.sync.onForeignBroadcast("spotify-track-sharing");
        all.add(this.clock[0]);
      }
      this.run(1_000L);
    }

    List<Long> own = this.transport.times;
    assertTrue(own.size() > 10);
    for (int i = 1; i < own.size(); i++) {
      assertTrue(own.get(i) - own.get(i - 1) >= StatusSync.SEND_GAP_MS);
    }
    for (long sent : own) {
      long ownBefore = own.stream().filter(at -> at <= sent && sent - at < StatusSync.SEND_WINDOW_MS).count();
      long foreignBefore = all.stream().filter(at -> at < sent && sent - at < StatusSync.SEND_WINDOW_MS).count();
      assertTrue(ownBefore + foreignBefore <= StatusSync.SEND_LIMIT, "window at " + sent);
    }
  }

  @Test
  void otherAddonsBroadcastsShareTheBudget() {
    this.run(5_000L);
    for (int i = 0; i < StatusSync.SEND_LIMIT - 1; i++) {
      this.sync.onForeignBroadcast("spotify-track-sharing");
    }
    this.sync.onForeignBroadcast(StatusSync.BROADCAST_KEY);
    this.transport.status = StatusKind.BUSY;
    this.run(30_000L);
    assertEquals(1, this.transport.broadcasts.size());

    this.run(StatusSync.SEND_WINDOW_MS);
    assertEquals(2, this.transport.broadcasts.size());
    assertEquals("BUSY", this.transport.broadcasts.get(1).get("status").getAsString());
  }

  @Test
  void rateLimitPausesAndBacksOff() {
    this.run(5_000L);
    this.sync.onRateLimited();
    this.transport.status = StatusKind.BUSY;
    this.run(StatusSync.BACKOFF_MS - 1_000L);
    assertEquals(1, this.transport.broadcasts.size());
    this.run(2_000L);
    assertEquals(2, this.transport.broadcasts.size());

    this.sync.onRateLimited();
    this.transport.status = StatusKind.AWAY;
    this.run(StatusSync.BACKOFF_MS * 2L - 2_000L);
    assertEquals(2, this.transport.broadcasts.size());
    this.run(3_000L);
    assertEquals(3, this.transport.broadcasts.size());
  }

  @Test
  void statusChangeReachesEveryoneEvenAfterAReply() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    this.hear(UUID.randomUUID(), "ONLINE", true);
    this.run(3_000L);
    this.transport.status = StatusKind.AWAY;
    this.run(10_000L);

    assertEquals(1, this.transport.sent.size());
    assertEquals(2, this.transport.broadcasts.size());
    assertEquals("AWAY", this.transport.broadcasts.get(1).get("status").getAsString());
  }

  @Test
  void statusChangeAnswersTheAskerWithOneBroadcast() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    long changedAt = this.clock[0];
    this.transport.status = StatusKind.AWAY;
    this.hear(UUID.randomUUID(), "ONLINE", true);
    this.run(10_000L);

    assertTrue(this.transport.sent.isEmpty());
    assertEquals(2, this.transport.broadcasts.size());
    assertEquals("AWAY", this.transport.broadcasts.get(1).get("status").getAsString());
    assertTrue(this.transport.broadcastTimes.get(1) - changedAt <= StatusSync.STATUS_SETTLE_MS + 500L);
  }

  @Test
  void repliesNeverCarryAStatusThatDidNotSettle() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", true);
    this.run(1_000L);
    this.transport.status = StatusKind.AWAY;
    this.run(1_000L);
    this.transport.status = StatusKind.ONLINE;
    this.run(30_000L);

    assertEquals(1, this.transport.broadcasts.size());
    assertEquals(1, this.transport.sent.size());
    assertEquals("ONLINE", this.transport.sent.get(0).payload().get("status").getAsString());
  }

  @Test
  void heartbeatsNeverCarryAStatusThatDidNotSettle() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", false);
    this.run(StatusSync.HEARTBEAT_MS - 1_500L);
    assertTrue(this.transport.sent.isEmpty());
    this.transport.status = StatusKind.BUSY;
    this.run(1_500L);
    this.transport.status = StatusKind.ONLINE;
    this.run(30_000L);

    assertEquals(1, this.transport.broadcasts.size());
    assertEquals(1, this.transport.sent.size());
    assertEquals("ONLINE", this.transport.sent.get(0).payload().get("status").getAsString());
  }

  @Test
  void nothingIsBroadcastWithoutPlayersAround() {
    this.transport.audience = false;
    this.sync.requestResync();
    this.run(20_000L);
    assertTrue(this.transport.broadcasts.isEmpty());

    this.transport.audience = true;
    this.sync.requestResync();
    this.run(StatusSync.RESYNC_DELAY_MS + 1_000L);
    assertEquals(1, this.transport.broadcasts.size());
    assertTrue(this.transport.broadcasts.get(0).get("r").getAsBoolean());
  }

  @Test
  void knownPeersKeepHearingUsUnderConstantAsks() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID quiet = UUID.randomUUID();
    this.hear(quiet, "ONLINE", false);
    long start = this.clock[0];

    for (int second = 0; second < 1_800; second++) {
      if (second % 5 == 0) {
        this.hear(UUID.randomUUID(), "AWAY", true);
      }
      if (second % 150 == 0) {
        this.hear(quiet, "ONLINE", false);
      }
      this.run(1_000L);
    }

    List<Long> reached = new ArrayList<>(this.transport.broadcastTimes);
    for (Sent sent : this.transport.sent) {
      if (List.of(sent.receivers()).contains(quiet)) {
        reached.add(sent.at());
      }
    }
    reached.add(this.clock[0]);
    reached.sort(Long::compare);

    long previous = start;
    for (long at : reached) {
      if (at >= start) {
        assertTrue(at - previous <= RELEASED_FRESH_MS, "gap " + (at - previous));
        previous = at;
      }
    }
  }

  @Test
  void periodicAnnounceReachesNewcomers() {
    this.run(5_000L);
    this.run(StatusSync.ANNOUNCE_MS);
    assertEquals(2, this.transport.broadcasts.size());
  }

  @Test
  void heldStatusChangeGoesOutWhenPlayersAppear() {
    this.run(5_000L);
    this.transport.audience = false;
    this.transport.status = StatusKind.BUSY;
    this.run(20_000L);
    assertEquals(1, this.transport.broadcasts.size());

    this.transport.audience = true;
    this.run(1_000L);
    assertEquals(2, this.transport.broadcasts.size());
    assertEquals("BUSY", this.transport.broadcasts.get(1).get("status").getAsString());
  }

  @Test
  void heartbeatsContinueWhileAloneWithAResyncPending() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", false);
    this.transport.audience = false;
    this.sync.requestResync();
    this.hear(peer, "ONLINE", false);
    this.run(StatusSync.HEARTBEAT_MS);

    assertEquals(1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{peer}, this.transport.sent.get(0).receivers());
  }

  @Test
  void rapidResyncRequestsDoNotPostponeIt() {
    for (int i = 0; i < 10; i++) {
      this.sync.requestResync();
      this.run(1_000L);
    }
    assertEquals(1, this.transport.broadcasts.size());
  }

  @Test
  void backoffDoublesUpToTheCapAndResetsAfterAQuietMinute() {
    this.run(5_000L);
    this.run(StatusSync.SEND_GAP_MS);
    long[] pauses = {
        StatusSync.BACKOFF_MS,
        StatusSync.BACKOFF_MS * 2L,
        StatusSync.BACKOFF_MS * 4L,
        StatusSync.MAX_BACKOFF_MS,
        StatusSync.MAX_BACKOFF_MS
    };
    for (long pause : pauses) {
      this.sync.onRateLimited();
      int before = this.transport.broadcasts.size();
      this.transport.status = this.transport.status == StatusKind.ONLINE ? StatusKind.AWAY : StatusKind.ONLINE;
      this.run(pause - 2_000L);
      assertEquals(before, this.transport.broadcasts.size());
      this.run(4_000L);
      assertEquals(before + 1, this.transport.broadcasts.size());
    }

    this.run(StatusSync.BACKOFF_MS + 5_000L);
    this.sync.onRateLimited();
    int before = this.transport.broadcasts.size();
    this.transport.status = this.transport.status == StatusKind.ONLINE ? StatusKind.AWAY : StatusKind.ONLINE;
    this.run(StatusSync.BACKOFF_MS + 2_000L);
    assertEquals(before + 1, this.transport.broadcasts.size());
  }

  @Test
  void samePeerAskingAgainWaitsForTheCooldown() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", true);
    this.run(3_000L);
    this.hear(peer, "ONLINE", true);
    this.run(StatusSync.REPLY_COOLDOWN_MS - 5_000L);
    assertEquals(1, this.transport.sent.size());

    this.run(5_000L);
    this.hear(peer, "ONLINE", true);
    this.run(3_000L);
    assertEquals(2, this.transport.sent.size());
  }

  @Test
  void expiredPeersAreDroppedAtTheNextHeartbeat() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID gone = UUID.randomUUID();
    this.hear(gone, "ONLINE", false);
    this.run(StatusSync.PEER_TTL_MS + StatusSync.HEARTBEAT_MS);

    assertEquals(0, this.sync.peerCount());
    int sentBefore = this.transport.sent.size();
    this.run(StatusSync.HEARTBEAT_MS * 2L);
    assertEquals(sentBefore, this.transport.sent.size());
  }

  @Test
  void intervalsFitWhatPeersExpect() {
    assertTrue(StatusSync.HEARTBEAT_MS < RELEASED_FRESH_MS);
    assertTrue(StatusPolicy.SYNC_FRESH_MS >= 2L * StatusSync.HEARTBEAT_MS + StatusSync.SEND_WINDOW_MS);
    assertTrue(StatusSync.HEARTBEAT_MS < StatusSync.PEER_TTL_MS);
    assertTrue(StatusSync.RESYNC_GAP_MS >= StatusSync.REPLY_COOLDOWN_MS + StatusSync.REPLY_BATCH_MS + 5_000L);
    assertTrue(StatusSync.SEND_LIMIT * StatusSync.SEND_GAP_MS <= StatusSync.SEND_WINDOW_MS);
    assertTrue(StatusSync.HEARTBEAT_MS + StatusSync.SEND_GAP_MS + 10_000L < LEGACY_NEW_PEER_MS);
    assertTrue(StatusSync.HEARTBEAT_MS + StatusSync.BACKOFF_MS < RELEASED_FRESH_MS);
    assertTrue(StatusSync.REJECT_WINDOW_MS < StatusSync.SEND_GAP_MS);
  }

  @Test
  void peersWhoLeftTheTabListGetNoHeartbeat() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID stays = UUID.randomUUID();
    UUID leaves = UUID.randomUUID();
    this.hear(stays, "ONLINE", false);
    this.hear(leaves, "ONLINE", false);
    assertEquals(2, this.sync.peerCount());

    this.transport.absent.add(leaves);
    assertEquals(1, this.sync.peerCount());
    this.run(StatusSync.HEARTBEAT_MS);

    assertEquals(1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{stays}, this.transport.sent.get(0).receivers());
    this.transport.absent.remove(leaves);
    assertEquals(1, this.sync.peerCount());
  }

  @Test
  void sendersOutsideTheTabListDoNotBecomePeers() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID stranger = UUID.randomUUID();
    this.transport.absent.add(stranger);
    for (int i = 0; i < 4; i++) {
      this.hear(stranger, "BUSY", false);
      this.run(StatusSync.HEARTBEAT_MS);
    }

    assertTrue(this.transport.sent.isEmpty());
    assertEquals(0, this.sync.peerCount());
    assertEquals(StatusKind.BUSY, this.service.kindOf(stranger));
  }

  @Test
  void askingSenderOutsideTheTabListGetsAReplyOnly() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID hidden = UUID.randomUUID();
    this.transport.absent.add(hidden);
    this.hear(hidden, "AWAY", true);
    this.run(3_000L);

    assertEquals(1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{hidden}, this.transport.sent.get(0).receivers());
    this.run(StatusSync.HEARTBEAT_MS * 2L);
    assertEquals(1, this.transport.sent.size());
    assertEquals(0, this.sync.peerCount());
  }

  @Test
  void askingSenderOutsideTheTabListKeepsTheReplyCooldown() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID hidden = UUID.randomUUID();
    this.transport.absent.add(hidden);
    for (int i = 0; i < 4; i++) {
      this.hear(hidden, "AWAY", true);
      this.run(6_000L);
    }

    assertEquals(1, this.transport.sent.size());
  }

  @Test
  void senderWhoseTabEntryArrivesLateIsGreetedOnce() {
    this.run(5_000L);
    this.run(StatusSync.ANSWER_WINDOW_MS);
    UUID late = UUID.randomUUID();
    this.transport.absent.add(late);
    this.hear(late, "AWAY", true);
    this.run(3_000L);
    this.transport.absent.remove(late);
    this.run(60_000L);
    this.hear(late, "AWAY", false);
    this.run(5_000L);

    assertEquals(1, this.transport.sent.size());
    assertEquals(1, this.sync.peerCount());
  }

  @Test
  void switchingSyncOffForgetsPeers() {
    this.sync.requestResync();
    this.run(4_000L);
    this.hear(UUID.randomUUID(), "ONLINE", false);
    assertEquals(1, this.sync.peerCount());

    this.settings.sync = false;
    this.run(1_000L);
    assertEquals(0, this.sync.peerCount());
  }

  @Test
  void rejectedStatusChangeIsSentAgainAfterThePause() {
    this.run(5_000L);
    this.run(StatusSync.SEND_GAP_MS);
    this.transport.status = StatusKind.BUSY;
    this.run(StatusSync.STATUS_SETTLE_MS + 1_000L);
    assertEquals(2, this.transport.broadcasts.size());

    this.sync.onRateLimited();
    this.run(StatusSync.BACKOFF_MS - 5_000L);
    assertEquals(2, this.transport.broadcasts.size());
    this.run(6_000L);
    assertEquals(3, this.transport.broadcasts.size());
    assertEquals("BUSY", this.transport.broadcasts.get(2).get("status").getAsString());
  }

  @Test
  void rejectedResyncAsksAgainAfterThePause() {
    this.sync.requestResync();
    this.run(StatusSync.RESYNC_DELAY_MS + 1_000L);
    assertEquals(1, this.transport.broadcasts.size());

    this.sync.onRateLimited();
    this.run(StatusSync.BACKOFF_MS + 1_000L);
    assertEquals(2, this.transport.broadcasts.size());
    assertTrue(this.transport.broadcasts.get(1).get("r").getAsBoolean());
  }

  @Test
  void rejectedHeartbeatIsSentAgainAfterThePause() {
    this.sync.requestResync();
    this.run(4_000L);
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", false);
    this.run(StatusSync.HEARTBEAT_MS);
    assertEquals(1, this.transport.sent.size());

    this.sync.onRateLimited();
    this.run(StatusSync.BACKOFF_MS + 1_000L);
    assertEquals(2, this.transport.sent.size());
    assertArrayEquals(new UUID[]{peer}, this.transport.sent.get(1).receivers());
  }

  @Test
  void rateLimitUnrelatedToOurSendsRepeatsNothing() {
    this.run(5_000L);
    this.run(StatusSync.REJECT_WINDOW_MS + 5_000L);
    this.sync.onRateLimited();
    this.run(StatusSync.BACKOFF_MS + 10_000L);

    assertEquals(1, this.transport.broadcasts.size());
    assertTrue(this.transport.sent.isEmpty());
  }

  @Test
  void ignoresSelfForeignKeysAndBadPayloads() {
    this.sync.handleIncoming(this.transport.self, StatusSync.BROADCAST_KEY, message("ONLINE", true));
    this.sync.handleIncoming(UUID.randomUUID(), "other:key", message("ONLINE", true));
    JsonObject invisible = new JsonObject();
    invisible.addProperty("status", "INVISIBLE");
    this.sync.handleIncoming(UUID.randomUUID(), StatusSync.BROADCAST_KEY, invisible);

    assertEquals(0, this.sync.peerCount());
    this.run(5_000L);
    assertTrue(this.transport.sent.isEmpty());
  }

  @Test
  void disabledSyncDoesNothing() {
    this.settings.sync = false;
    UUID peer = UUID.randomUUID();
    this.hear(peer, "ONLINE", true);
    this.sync.requestResync();
    this.run(10_000L);

    assertTrue(this.transport.broadcasts.isEmpty());
    assertTrue(this.transport.sent.isEmpty());
    assertFalse(this.service.kindOf(peer) == StatusKind.ONLINE);
  }
}
