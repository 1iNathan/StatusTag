package net.statustag.core.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.StatusKind;
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

  private record Sent(UUID[] receivers, JsonObject payload) {
  }

  private static final class Transport implements SyncTransport {
    final UUID self = UUID.randomUUID();
    StatusKind status = StatusKind.ONLINE;
    final List<JsonObject> broadcasts = new ArrayList<>();
    final List<Sent> sent = new ArrayList<>();

    @Override
    public @Nullable UUID selfId() {
      return this.self;
    }

    @Override
    public @Nullable StatusKind selfStatus() {
      return this.status;
    }

    @Override
    public boolean broadcast(@NotNull String key, @NotNull JsonObject payload) {
      this.broadcasts.add(payload);
      return true;
    }

    @Override
    public boolean send(@NotNull String key, @NotNull UUID[] receivers, @NotNull JsonObject payload) {
      this.sent.add(new Sent(receivers, payload));
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
    this.sync = new StatusSync(this.settings, this.service, this.transport, debug, () -> this.clock[0], () -> 500L);
  }

  private void ticks(int count) {
    for (int i = 0; i < count; i++) {
      this.sync.tick();
    }
  }

  private static JsonObject message(String status, boolean replyRequested) {
    return StatusSync.payload(StatusKind.valueOf(status), replyRequested);
  }

  @Test
  void announcesOnFirstCheckAndOnChange() {
    this.ticks(StatusSync.CHECK_INTERVAL_TICKS);
    assertEquals(1, this.transport.broadcasts.size());
    assertEquals("ONLINE", this.transport.broadcasts.get(0).get("status").getAsString());

    this.ticks(StatusSync.CHECK_INTERVAL_TICKS);
    assertEquals(1, this.transport.broadcasts.size());

    this.transport.status = StatusKind.BUSY;
    this.ticks(StatusSync.CHECK_INTERVAL_TICKS);
    assertEquals(2, this.transport.broadcasts.size());
    assertEquals("BUSY", this.transport.broadcasts.get(1).get("status").getAsString());
  }

  @Test
  void resyncAsksForRepliesWithCooldown() {
    this.sync.requestResync();
    this.sync.requestResync();
    assertEquals(1, this.transport.broadcasts.size());
    assertTrue(this.transport.broadcasts.get(0).get("r").getAsBoolean());
  }

  @Test
  void repliesOnlyToTheAskingPeerAfterDelay() {
    UUID peer = UUID.randomUUID();
    this.sync.handleIncoming(peer, StatusSync.BROADCAST_KEY, message("AWAY", true));
    assertEquals(StatusKind.AWAY, this.service.kindOf(peer));

    this.sync.tick();
    assertTrue(this.transport.sent.isEmpty());

    this.clock[0] += 500L;
    this.sync.tick();
    assertEquals(1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{peer}, this.transport.sent.get(0).receivers());
    assertTrue(this.transport.broadcasts.isEmpty());
  }

  @Test
  void batchesRepliesToSeveralPeers() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    this.sync.handleIncoming(a, StatusSync.BROADCAST_KEY, message("ONLINE", false));
    this.sync.handleIncoming(b, StatusSync.BROADCAST_KEY, message("BUSY", false));
    this.clock[0] += 500L;
    this.sync.tick();

    assertEquals(1, this.transport.sent.size());
    assertEquals(2, this.transport.sent.get(0).receivers().length);
  }

  @Test
  void knownPeersAreNotRepliedToAgain() {
    UUID peer = UUID.randomUUID();
    this.sync.handleIncoming(peer, StatusSync.BROADCAST_KEY, message("ONLINE", false));
    this.clock[0] += 500L;
    this.sync.tick();
    this.sync.handleIncoming(peer, StatusSync.BROADCAST_KEY, message("AWAY", false));
    this.clock[0] += 500L;
    this.sync.tick();

    assertEquals(1, this.transport.sent.size());
    assertEquals(StatusKind.AWAY, this.service.kindOf(peer));
  }

  @Test
  void heartbeatGoesOnlyToKnownPeers() {
    UUID peer = UUID.randomUUID();
    this.ticks(StatusSync.CHECK_INTERVAL_TICKS);
    this.sync.handleIncoming(peer, StatusSync.BROADCAST_KEY, message("ONLINE", false));
    this.clock[0] += 500L;
    this.sync.tick();
    int before = this.transport.sent.size();

    this.clock[0] += StatusSync.HEARTBEAT_MS;
    this.ticks(StatusSync.CHECK_INTERVAL_TICKS);

    assertEquals(before + 1, this.transport.sent.size());
    assertArrayEquals(new UUID[]{peer}, this.transport.sent.get(before).receivers());
    assertEquals(1, this.transport.broadcasts.size());
  }

  @Test
  void ignoresSelfForeignKeysAndBadPayloads() {
    this.sync.handleIncoming(this.transport.self, StatusSync.BROADCAST_KEY, message("ONLINE", true));
    this.sync.handleIncoming(UUID.randomUUID(), "other:key", message("ONLINE", true));
    JsonObject invisible = new JsonObject();
    invisible.addProperty("status", "INVISIBLE");
    this.sync.handleIncoming(UUID.randomUUID(), StatusSync.BROADCAST_KEY, invisible);

    assertEquals(0, this.sync.peerCount());
    this.clock[0] += 1_000L;
    this.sync.tick();
    assertTrue(this.transport.sent.isEmpty());
  }

  @Test
  void disabledSyncDoesNothing() {
    this.settings.sync = false;
    UUID peer = UUID.randomUUID();
    this.sync.handleIncoming(peer, StatusSync.BROADCAST_KEY, message("ONLINE", true));
    this.sync.requestResync();
    this.ticks(StatusSync.CHECK_INTERVAL_TICKS);

    assertTrue(this.transport.broadcasts.isEmpty());
    assertTrue(this.transport.sent.isEmpty());
    assertFalse(this.service.kindOf(peer) == StatusKind.ONLINE);
  }
}
