package net.statustag.core.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusService;
import net.statustag.core.status.StatusSettings;
import net.statustag.core.status.StatusSource;
import net.statustag.core.util.DebugLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusSync {

  public static final String BROADCAST_KEY = "statustag:status";
  static final int PROTOCOL_VERSION = 1;
  static final int CHECK_INTERVAL_TICKS = 10;
  static final long HEARTBEAT_MS = 90_000L;
  static final long ANNOUNCE_MS = 300_000L;
  static final long RESYNC_COOLDOWN_MS = 10_000L;
  static final long PEER_TTL_MS = 360_000L;
  static final long REPLY_COOLDOWN_MS = 30_000L;
  static final long REPLY_DELAY_MIN_MS = 150L;
  static final long REPLY_DELAY_MAX_MS = 1_200L;
  static final int MAX_PEERS = 2_048;
  static final int MAX_RECEIVERS = 256;

  private record Peer(long heardAt, long repliedAt) {
  }

  private final StatusSettings settings;
  private final StatusService service;
  private final SyncTransport transport;
  private final DebugLog debug;
  private final LongSupplier clock;
  private final LongSupplier replyDelay;
  private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
  private final Map<UUID, Long> pendingReplies = new ConcurrentHashMap<>();

  private volatile @Nullable StatusKind lastSentKind;
  private volatile long lastAnnounceAt;
  private volatile long lastHeartbeatAt;
  private volatile long lastResyncAt = Long.MIN_VALUE / 2L;
  private int ticks;

  public StatusSync(
      @NotNull StatusSettings settings,
      @NotNull StatusService service,
      @NotNull SyncTransport transport,
      @NotNull DebugLog debug,
      @NotNull LongSupplier clock,
      @NotNull LongSupplier replyDelay
  ) {
    this.settings = settings;
    this.service = service;
    this.transport = transport;
    this.debug = debug;
    this.clock = clock;
    this.replyDelay = replyDelay;
  }

  public int peerCount() {
    return this.peers.size();
  }

  public void tick() {
    if (!this.settings.isSyncEnabled()) {
      this.pendingReplies.clear();
      return;
    }

    long now = this.clock.getAsLong();
    if (!this.pendingReplies.isEmpty()) {
      this.flushReplies(now);
    }

    if (++this.ticks < CHECK_INTERVAL_TICKS) {
      return;
    }
    this.ticks = 0;

    StatusKind kind = shareable(this.transport.selfStatus());
    if (kind == null) {
      return;
    }

    if (kind != this.lastSentKind || now - this.lastAnnounceAt >= ANNOUNCE_MS) {
      this.announce(kind, now, false);
    } else if (now - this.lastHeartbeatAt >= HEARTBEAT_MS) {
      this.heartbeat(kind, now);
    }
  }

  public void requestResync() {
    if (!this.settings.isSyncEnabled()) {
      return;
    }

    long now = this.clock.getAsLong();
    if (now - this.lastResyncAt < RESYNC_COOLDOWN_MS) {
      return;
    }

    StatusKind kind = shareable(this.transport.selfStatus());
    if (kind == null) {
      return;
    }

    this.lastResyncAt = now;
    this.peers.clear();
    this.pendingReplies.clear();
    this.announce(kind, now, true);
  }

  public void announceNow() {
    if (!this.settings.isSyncEnabled()) {
      return;
    }

    StatusKind kind = shareable(this.transport.selfStatus());
    if (kind != null) {
      this.announce(kind, this.clock.getAsLong(), false);
    }
  }

  private void announce(@NotNull StatusKind kind, long now, boolean requestReplies) {
    if (this.transport.broadcast(BROADCAST_KEY, payload(kind, requestReplies))) {
      this.lastSentKind = kind;
      this.lastAnnounceAt = now;
      this.lastHeartbeatAt = now;
      if (this.debug.enabled()) {
        this.debug.log("sync announce " + kind + (requestReplies ? " resync" : "") + " peers=" + this.peers.size());
      }
    }
  }

  private void heartbeat(@NotNull StatusKind kind, long now) {
    this.lastHeartbeatAt = now;
    List<UUID> receivers = new ArrayList<>(Math.min(this.peers.size(), MAX_RECEIVERS));
    Iterator<Map.Entry<UUID, Peer>> iterator = this.peers.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Peer> entry = iterator.next();
      if (now - entry.getValue().heardAt() > PEER_TTL_MS) {
        iterator.remove();
      } else if (receivers.size() < MAX_RECEIVERS) {
        receivers.add(entry.getKey());
      }
    }

    if (!receivers.isEmpty()) {
      this.transport.send(BROADCAST_KEY, receivers.toArray(new UUID[0]), payload(kind, false));
    }
  }

  private void flushReplies(long now) {
    List<UUID> due = null;
    Iterator<Map.Entry<UUID, Long>> iterator = this.pendingReplies.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Long> entry = iterator.next();
      if (entry.getValue() > now) {
        continue;
      }

      iterator.remove();
      if (due == null) {
        due = new ArrayList<>();
      }
      if (due.size() < MAX_RECEIVERS) {
        due.add(entry.getKey());
      }
    }

    if (due == null) {
      return;
    }

    StatusKind kind = shareable(this.transport.selfStatus());
    if (kind == null || !this.transport.send(BROADCAST_KEY, due.toArray(new UUID[0]), payload(kind, false))) {
      return;
    }

    for (UUID receiver : due) {
      this.peers.computeIfPresent(receiver, (key, peer) -> new Peer(peer.heardAt(), now));
    }
    if (this.debug.enabled()) {
      this.debug.log("sync reply " + kind + " to " + due.size() + " peer(s)");
    }
  }

  public void handleIncoming(@Nullable UUID sender, @Nullable String key, @Nullable JsonElement payload) {
    if (sender == null || !BROADCAST_KEY.equals(key) || !this.settings.isSyncEnabled()) {
      return;
    }

    if (!(payload instanceof JsonObject object) || sender.equals(this.transport.selfId())) {
      return;
    }

    StatusKind kind = shareable(StatusKind.parse(string(object.get("status"))));
    if (kind == null) {
      return;
    }

    this.service.onLabyModUser(sender);
    this.service.commit(sender, kind, StatusSource.SYNC);

    long now = this.clock.getAsLong();
    if (this.peers.size() >= MAX_PEERS && !this.peers.containsKey(sender)) {
      this.peers.clear();
    }

    Peer previous = this.peers.get(sender);
    boolean newPeer = previous == null || now - previous.heardAt() > PEER_TTL_MS;
    long repliedAt = previous == null ? Long.MIN_VALUE / 2L : previous.repliedAt();
    this.peers.put(sender, new Peer(now, repliedAt));

    if ((newPeer || bool(object.get("r"))) && now - repliedAt >= REPLY_COOLDOWN_MS) {
      this.pendingReplies.putIfAbsent(sender, now + this.replyDelay.getAsLong());
    }
  }

  static @NotNull JsonObject payload(@NotNull StatusKind kind, boolean requestReplies) {
    JsonObject payload = new JsonObject();
    payload.addProperty("v", PROTOCOL_VERSION);
    payload.addProperty("status", kind.name());
    if (requestReplies) {
      payload.addProperty("r", true);
    }
    return payload;
  }

  private static @Nullable StatusKind shareable(@Nullable StatusKind kind) {
    return kind != null && (kind.isLabyPresence() || kind == StatusKind.OFFLINE) ? kind : null;
  }

  private static @Nullable String string(@Nullable JsonElement element) {
    return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
  }

  private static boolean bool(@Nullable JsonElement element) {
    return element instanceof JsonPrimitive primitive && primitive.isBoolean() && primitive.getAsBoolean();
  }
}
