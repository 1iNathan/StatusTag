package net.statustag.core.mesh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.Supplier;
import net.labymod.api.Laby;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.protocol.model.User;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.service.StatusDebugLog;
import net.statustag.core.service.StatusKind;
import net.statustag.core.service.StatusResolver;
import net.statustag.core.service.StatusSource;
import net.statustag.core.util.StatusUiRefresh;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StatusSync {

  public static final String BROADCAST_KEY = "statustag:status";
  private static final int PROTOCOL_VERSION = 1;
  private static final long HEARTBEAT_MS = 90_000L;

  private static final long PEER_REPLY_WINDOW_MS = 150_000L;
  private static final long REPLY_STORM_GUARD_MS = 5_000L;
  private static final long RESYNC_COOLDOWN_MS = 10_000L;

  private final StatusResolver resolver;
  private final StatusDebugLog debugLog;
  private final Supplier<StatusTagConfiguration> configuration;
  private final java.util.Map<UUID, Long> lastReceivedFrom = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile long lastBroadcastAt;
  private volatile long lastReplyAt;
  private volatile long lastResyncAt;
  private volatile StatusKind lastBroadcastKind;

  public StatusSync(
      @NotNull StatusResolver resolver,
      @NotNull StatusDebugLog debugLog,
      @NotNull Supplier<StatusTagConfiguration> configuration
  ) {
    this.resolver = resolver;
    this.debugLog = debugLog;
    this.configuration = configuration;
  }

  public boolean isEnabled() {
    StatusTagConfiguration config = this.configuration.get();
    return config != null && config.enabled().get() && config.useMesh().get();
  }

  public void tick() {
    this.broadcast(false, false);
  }

  public void requestResync() {
    long now = System.currentTimeMillis();
    if (now - this.lastResyncAt < RESYNC_COOLDOWN_MS) {
      return;
    }
    this.lastResyncAt = now;

    this.lastReceivedFrom.clear();
    this.lastBroadcastKind = null;
    this.lastBroadcastAt = 0L;
    this.lastReplyAt = 0L;
    this.broadcast(true, true);
  }

  public void broadcastOwnStatus(boolean force) {
    this.broadcast(force, false);
  }

  private void broadcast(boolean force, boolean requestReplies) {
    if (!this.isEnabled() || !Laby.isInitialized()) {
      return;
    }

    LabyConnectSession session = this.session();
    if (session == null) {
      return;
    }

    User self = session.self();
    if (self == null) {
      return;
    }

    long now = System.currentTimeMillis();
    StatusKind kind = StatusKind.fromUserStatus(self.userStatus());

    boolean changed = kind != this.lastBroadcastKind;
    boolean heartbeatDue = now - this.lastBroadcastAt >= HEARTBEAT_MS;
    if (!force && !changed && !heartbeatDue) {
      return;
    }

    if (kind == StatusKind.UNKNOWN) {
      return;
    }

    this.lastBroadcastAt = now;
    this.lastBroadcastKind = kind;

    JsonObject payload = new JsonObject();
    payload.addProperty("v", PROTOCOL_VERSION);
    payload.addProperty("status", kind.name());
    if (requestReplies) {
      payload.addProperty("r", true);
    }

    try {
      session.sendBroadcastPayload(BROADCAST_KEY, payload);
      this.debugLog.meshOut("BROADCAST", kind, null, "v=" + PROTOCOL_VERSION);
    } catch (Exception exception) {

      this.debugLog.log("MESH_SEND_FAILED", StatusDebugLog.config(
          "error", String.valueOf(exception.getMessage())
      ));
    }
  }

  public void handleIncoming(
      @NotNull UUID sender,
      @NotNull String key,
      @Nullable JsonElement payload
  ) {
    if (!this.isEnabled() || !BROADCAST_KEY.equals(key)) {
      return;
    }

    if (this.isSelf(sender)) {
      return;
    }

    if (payload == null || !payload.isJsonObject()) {
      return;
    }

    JsonObject object = payload.getAsJsonObject();
    boolean replyRequested = object.has("r")
        && object.get("r").isJsonPrimitive()
        && object.get("r").getAsBoolean();
    StatusKind kind = parseStatus(object);
    long now = System.currentTimeMillis();
    if (kind != null && !isShareableKind(kind)) {
      kind = null;
    }
    if (kind == null) {
      this.debugLog.meshIn(sender, StatusKind.UNKNOWN, now, false, "BAD_PAYLOAD", null);
      return;
    }

    StatusKind previous = this.resolver.peekKind(sender);
    this.resolver.markLabyModUser(sender);
    this.resolver.commit(sender, kind, StatusSource.MESH, false, 0, now);
    this.debugLog.meshIn(sender, kind, now, true, "OK", null);

    if (previous != kind) {
      StatusUiRefresh.refresh();
    }

    Long previousReceive = this.lastReceivedFrom.put(sender, now);
    boolean newPeer = previousReceive == null || now - previousReceive > PEER_REPLY_WINDOW_MS;
    if ((replyRequested || newPeer) && now - this.lastReplyAt > REPLY_STORM_GUARD_MS) {
      this.lastReplyAt = now;
      this.broadcast(true, false);
    }
  }

  private boolean isSelf(@NotNull UUID uniqueId) {
    LabyConnectSession session = this.session();
    if (session == null) {
      return false;
    }
    User self = session.self();
    return self != null && uniqueId.equals(self.getUniqueId());
  }

  private @Nullable LabyConnectSession session() {
    if (!Laby.isInitialized()) {
      return null;
    }
    LabyConnectSession session = Laby.labyAPI().labyConnect().getSession();
    return session != null && session.isAuthenticated() ? session : null;
  }

  private static boolean isShareableKind(@NotNull StatusKind kind) {
    return kind == StatusKind.ONLINE
        || kind == StatusKind.AWAY
        || kind == StatusKind.BUSY
        || kind == StatusKind.OFFLINE;
  }

  private static @Nullable StatusKind parseStatus(@NotNull JsonObject object) {
    try {
      if (!object.has("status") || !object.get("status").isJsonPrimitive()) {
        return null;
      }
      return StatusKind.parse(object.get("status").getAsString());
    } catch (Exception malformed) {
      return null;
    }
  }
}
