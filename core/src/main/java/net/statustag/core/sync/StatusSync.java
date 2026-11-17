package net.statustag.core.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  static final int SEND_LIMIT = 3;
  static final long SEND_WINDOW_MS = 60_000L;
  static final long BACKOFF_MS = 60_000L;
  static final long MAX_BACKOFF_MS = 300_000L;
  static final long SEND_GAP_MS = 5_000L;
  static final long REJECT_WINDOW_MS = 3_000L;
  static final long STATUS_SETTLE_MS = 2_000L;
  static final long RESYNC_DELAY_MS = 3_000L;
  static final long RESYNC_GAP_MS = 40_000L;
  static final long RESYNC_CARRIES_MS = 10_000L;
  static final long REPLY_BATCH_MS = 1_500L;
  static final long ANSWER_WINDOW_MS = 15_000L;
  static final long HEARTBEAT_MS = 120_000L;
  static final long ANNOUNCE_MS = 600_000L;
  static final long PEER_TTL_MS = 360_000L;
  static final long REPLY_COOLDOWN_MS = 30_000L;
  static final int MAX_PEERS = 2_048;
  static final int MAX_RECEIVERS = 256;

  private static final long NEVER = Long.MIN_VALUE / 2L;

  private enum Sent {
    TARGETED,
    BROADCAST,
    RESYNC
  }

  private record Peer(long heardAt, long repliedAt) {
  }

  private final StatusSettings settings;
  private final StatusService service;
  private final SyncTransport transport;
  private final DebugLog debug;
  private final LongSupplier clock;
  private final SendBudget budget = new SendBudget(SEND_LIMIT, SEND_WINDOW_MS, SEND_GAP_MS);
  private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
  private final Set<UUID> pendingReplies = ConcurrentHashMap.newKeySet();

  private volatile @Nullable StatusKind lastBroadcastKind;
  private volatile @Nullable StatusKind observedKind;
  private volatile long observedAt = NEVER;
  private volatile long lastBroadcastAt = NEVER;
  private volatile long lastCoverAt = NEVER;
  private volatile long lastResyncAt = NEVER;
  private volatile long resyncDueAt = NEVER;
  private volatile boolean resyncPending;
  private volatile long repliesSince = NEVER;
  private volatile long pausedUntil = NEVER;
  private volatile long backoffMs = BACKOFF_MS;
  private volatile long lastSendAt = NEVER;
  private volatile Sent lastSent = Sent.TARGETED;
  private int ticks;

  public StatusSync(
      @NotNull StatusSettings settings,
      @NotNull StatusService service,
      @NotNull SyncTransport transport,
      @NotNull DebugLog debug,
      @NotNull LongSupplier clock
  ) {
    this.settings = settings;
    this.service = service;
    this.transport = transport;
    this.debug = debug;
    this.clock = clock;
  }

  public int peerCount() {
    int count = 0;
    for (UUID peer : this.peers.keySet()) {
      if (this.transport.inTabList(peer)) {
        count++;
      }
    }
    return count;
  }

  public void tick() {
    if (!this.settings.isSyncEnabled()) {
      this.pendingReplies.clear();
      this.peers.clear();
      this.resyncPending = false;
      return;
    }

    if (++this.ticks < CHECK_INTERVAL_TICKS) {
      return;
    }
    this.ticks = 0;

    StatusKind kind = shareable(this.transport.selfStatus());
    if (kind == null) {
      return;
    }

    long now = this.clock.getAsLong();
    if (kind != this.observedKind) {
      this.observedKind = kind;
      this.observedAt = now;
    }

    if (now >= this.pausedUntil && this.budget.allows(now)) {
      this.sendNext(kind, now);
    }
  }

  public void onForeignBroadcast(@Nullable String key) {
    if (BROADCAST_KEY.equals(key)) {
      return;
    }

    this.budget.record(this.clock.getAsLong());
    if (this.debug.enabled()) {
      this.debug.log("sync counted foreign broadcast key=" + key);
    }
  }

  public synchronized void onRateLimited() {
    long now = this.clock.getAsLong();
    boolean repeated = now - this.pausedUntil < BACKOFF_MS;
    this.backoffMs = repeated ? Math.min(this.backoffMs * 2L, MAX_BACKOFF_MS) : BACKOFF_MS;
    this.pausedUntil = now + this.backoffMs;
    if (now - this.lastSendAt <= REJECT_WINDOW_MS) {
      this.lastSendAt = NEVER;
      switch (this.lastSent) {
        case RESYNC -> {
          this.resyncPending = true;
          this.resyncDueAt = now;
        }
        case BROADCAST -> this.lastBroadcastAt = NEVER;
        case TARGETED -> this.lastCoverAt = NEVER;
      }
    }
    if (this.debug.enabled()) {
      this.debug.log("sync rate limited by LabyConnect, pausing " + this.backoffMs / 1_000L + "s");
    }
  }

  private void sendNext(@NotNull StatusKind kind, long now) {
    if (kind != this.lastBroadcastKind && now - this.observedAt < STATUS_SETTLE_MS) {
      return;
    }

    if (this.transport.hasAudience() && this.broadcastIfDue(kind, now)) {
      return;
    }

    boolean heartbeatDue = now - this.lastCoverAt >= HEARTBEAT_MS;
    boolean repliesDue = !this.pendingReplies.isEmpty() && now - this.repliesSince >= REPLY_BATCH_MS;
    if (heartbeatDue || repliesDue) {
      this.deliver(kind, now, heartbeatDue);
    }
  }

  private boolean broadcastIfDue(@NotNull StatusKind kind, long now) {
    boolean pending = this.resyncPending;
    long due = this.resyncDueAt;
    if (pending && now >= due) {
      return this.broadcast(kind, now, true, due);
    }

    if (pending && due - now <= RESYNC_CARRIES_MS) {
      return false;
    }

    boolean changed = kind != this.lastBroadcastKind;
    boolean ready = changed
        ? now - this.observedAt >= STATUS_SETTLE_MS
        : now - this.lastBroadcastAt >= ANNOUNCE_MS;
    return ready && this.broadcast(kind, now, false, NEVER);
  }

  public synchronized void requestResync() {
    if (!this.settings.isSyncEnabled()) {
      return;
    }

    long now = this.clock.getAsLong();
    if (!this.resyncPending || this.resyncDueAt <= now) {
      this.resyncDueAt = Math.max(now + RESYNC_DELAY_MS, this.lastResyncAt + RESYNC_GAP_MS);
    }
    this.resyncPending = true;
    this.peers.clear();
    this.pendingReplies.clear();
  }

  public void announceNow() {
    this.ticks = CHECK_INTERVAL_TICKS - 1;
  }

  private boolean broadcast(@NotNull StatusKind kind, long now, boolean resync, long resyncDue) {
    if (!this.transport.broadcast(BROADCAST_KEY, payload(kind, resync))) {
      return false;
    }

    this.budget.record(now);
    this.lastSendAt = now;
    this.lastSent = resync ? Sent.RESYNC : Sent.BROADCAST;
    this.lastBroadcastKind = kind;
    this.lastBroadcastAt = now;
    this.lastCoverAt = now;
    this.pendingReplies.removeIf(this.transport::inTabList);
    if (resync) {
      this.lastResyncAt = now;
      synchronized (this) {
        if (this.resyncDueAt == resyncDue) {
          this.resyncPending = false;
        }
      }
    }
    if (this.debug.enabled()) {
      this.debug.log("sync announce " + kind + (resync ? " resync" : "") + " peers=" + this.peers.size());
    }
    return true;
  }

  private void deliver(@NotNull StatusKind kind, long now, boolean everyone) {
    Set<UUID> receivers = new LinkedHashSet<>();
    Iterator<UUID> asked = this.pendingReplies.iterator();
    while (asked.hasNext() && receivers.size() < MAX_RECEIVERS) {
      receivers.add(asked.next());
      asked.remove();
    }
    int replies = receivers.size();

    if (everyone) {
      this.lastCoverAt = now;
      Iterator<Map.Entry<UUID, Peer>> iterator = this.peers.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<UUID, Peer> entry = iterator.next();
        if (now - entry.getValue().heardAt() > PEER_TTL_MS || !this.transport.inTabList(entry.getKey())) {
          iterator.remove();
        } else if (receivers.size() < MAX_RECEIVERS) {
          receivers.add(entry.getKey());
        }
      }
    }

    if (!this.send(kind, new ArrayList<>(receivers), now)) {
      return;
    }

    for (UUID receiver : receivers) {
      this.peers.computeIfPresent(receiver, (key, peer) -> new Peer(peer.heardAt(), now));
    }
    if (this.debug.enabled()) {
      this.debug.log("sync " + (everyone ? "heartbeat " : "reply ") + kind + " to " + receivers.size()
          + " peer(s) replies=" + replies);
    }
  }

  private boolean send(@NotNull StatusKind kind, @NotNull List<UUID> receivers, long now) {
    if (receivers.isEmpty() || !this.transport.send(BROADCAST_KEY, receivers.toArray(new UUID[0]), payload(kind, false))) {
      return false;
    }

    this.budget.record(now);
    this.lastSendAt = now;
    this.lastSent = Sent.TARGETED;
    return true;
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
    long repliedAt = previous == null ? NEVER : previous.repliedAt();
    this.peers.put(sender, new Peer(now, repliedAt));

    boolean asked = bool(object.get("r"));
    boolean answeringUs = now - this.lastBroadcastAt < ANSWER_WINDOW_MS;
    boolean greeting = newPeer && !answeringUs && this.transport.inTabList(sender);
    if ((asked || greeting) && now - repliedAt >= REPLY_COOLDOWN_MS) {
      if (this.pendingReplies.isEmpty()) {
        this.repliesSince = now;
      }
      this.pendingReplies.add(sender);
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
