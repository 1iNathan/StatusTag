package net.statustag.core.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.internal.StatusTagInternals;
import net.labymod.api.Laby;
import net.labymod.api.client.network.ClientPacketListener;
import net.labymod.api.client.network.NetworkPlayerInfo;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.protocol.model.User;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StatusResolver {

  private static final long UNKNOWN_RESOLVE_RETRY_MS = 5_000L;
  private static final long TAB_CACHE_TTL_MS = 2_000L;
  private static final long RESOLVE_CACHE_TTL_MS = 250L;

  private record CachedKind(StatusKind kind, long expiresAt) {}

  private final PersistentStatusStore store;
  private final StatusDebugLog debugLog;
  private final Map<UUID, StatusEntry> memory = new ConcurrentHashMap<>();
  private final Set<UUID> labyModUsers = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Long> tabReprobeScheduledAt = new ConcurrentHashMap<>();
  private final Map<UUID, Long> unknownResolveAttemptAt = new ConcurrentHashMap<>();
  private final Map<UUID, CachedKind> resolveCache = new ConcurrentHashMap<>();
  private final Set<UUID> tabPlayers = ConcurrentHashMap.newKeySet();
  private final Object tabRefreshLock = new Object();
  private volatile long tabPlayersRefreshedAt;

  private @Nullable StatusFetchScheduler fetchScheduler;
  private Supplier<StatusTagConfiguration> configuration = () -> null;
  private Supplier<StatusTagInternals> internals = () -> null;

  public StatusResolver(
      @NotNull PersistentStatusStore store,
      @NotNull StatusDebugLog debugLog
  ) {
    this.store = store;
    this.debugLog = debugLog;
  }

  public void configure(
      @NotNull Supplier<StatusTagConfiguration> configuration,
      @NotNull Supplier<StatusTagInternals> internals
  ) {
    this.configuration = configuration;
    this.internals = internals;
  }

  public void setFetchScheduler(@NotNull StatusFetchScheduler fetchScheduler) {
    this.fetchScheduler = fetchScheduler;
  }

  public @Nullable StatusFetchScheduler getFetchScheduler() {
    return this.fetchScheduler;
  }

  public @NotNull PersistentStatusStore store() {
    return this.store;
  }

  public void markLabyModUser(@NotNull UUID uniqueId) {
    this.labyModUsers.add(uniqueId);
    StatusEntry entry = this.peekEntry(uniqueId);
    if (entry != null && entry.kind() == StatusKind.NO_LABYMOD) {

      if (this.fetchScheduler != null) {
        this.fetchScheduler.invalidate(uniqueId);
      }
      this.markForReprobe(uniqueId, "LABYMOD_DETECTED");
    }
  }

  public boolean isLabyModUser(@NotNull UUID uniqueId) {
    return this.labyModUsers.contains(uniqueId);
  }

  public @Nullable StatusEntry peekEntry(@NotNull UUID uniqueId) {
    StatusEntry entry = this.peekEntryShared(uniqueId);
    return entry == null ? null : entry.copy();
  }

  private @Nullable StatusEntry peekEntryShared(@NotNull UUID uniqueId) {
    StatusEntry memoryEntry = this.memory.get(uniqueId);
    if (memoryEntry != null) {
      return memoryEntry;
    }

    StatusEntry diskEntry = this.store.get(uniqueId);
    if (diskEntry != null) {

      StatusEntry winner = this.memory.putIfAbsent(uniqueId, diskEntry);
      return winner != null ? winner : diskEntry;
    }

    return null;
  }

  public @Nullable StatusKind peekKind(@NotNull UUID uniqueId) {
    StatusEntry entry = this.peekEntry(uniqueId);
    return entry == null ? null : entry.kind();
  }

  public @NotNull StatusKind resolve(@NotNull UUID uniqueId) {
    long now = System.currentTimeMillis();
    CachedKind cached = this.resolveCache.get(uniqueId);
    if (cached != null && now < cached.expiresAt()) {
      return cached.kind();
    }

    StatusKind kind = this.resolveUncached(uniqueId, now);
    this.resolveCache.put(uniqueId, new CachedKind(kind, now + RESOLVE_CACHE_TTL_MS));
    return kind;
  }

  private @NotNull StatusKind resolveUncached(@NotNull UUID uniqueId, long now) {
    if (this.isLabyChatEnabled()) {
      LabyConnectSession session = this.session();
      if (session != null) {
        User self = session.self();
        if (self != null && self.getUniqueId().equals(uniqueId)) {
          return this.commitLive(uniqueId, StatusKind.fromUserStatus(self.userStatus()),
              StatusSource.SELF, "SELF");
        }

        Friend friend = session.getFriend(uniqueId);
        if (friend != null) {
          return this.commitLive(uniqueId, StatusKind.fromUserStatus(friend.userStatus()),
              StatusSource.FRIEND, "FRIEND");
        }
      }
    }

    StatusEntry entry = this.peekEntryShared(uniqueId);
    if (entry != null && entry.kind() != StatusKind.UNKNOWN) {
      return entry.kind();
    }

    Long lastAttempt = this.unknownResolveAttemptAt.get(uniqueId);
    if (lastAttempt == null || now - lastAttempt >= UNKNOWN_RESOLVE_RETRY_MS) {
      this.unknownResolveAttemptAt.put(uniqueId, now);
      this.scheduleFetchIfNeeded(uniqueId, false);
      this.debugLog.resolve(uniqueId, "UNKNOWN", StatusKind.UNKNOWN,
          entry == null ? null : entry.kind());
    }

    if (this.isPlayerOnTab(uniqueId)) {
      return StatusKind.ONLINE;
    }

    return StatusKind.UNKNOWN;
  }

  private @NotNull StatusKind commitLive(
      @NotNull UUID uniqueId,
      @NotNull StatusKind kind,
      @NotNull StatusSource source,
      @NotNull String path
  ) {
    StatusEntry existing = this.peekEntryShared(uniqueId);
    if (existing == null || existing.kind() != kind || existing.source() != source) {
      StatusKind previous = existing == null ? null : existing.kind();
      this.commit(uniqueId, kind, source, false, 0, 0L);
      this.debugLog.resolve(uniqueId, path, kind, previous);
    }
    return kind;
  }

  public synchronized void commit(
      @NotNull UUID uniqueId,
      @NotNull StatusKind kind,
      @NotNull StatusSource source,
      boolean skipApi,
      int httpCode,
      long meshTimestamp
  ) {
    long now = System.currentTimeMillis();
    StatusEntry existing = this.peekEntry(uniqueId);

    if (existing != null && source == StatusSource.API) {
      if (existing.isAuthoritative() && now - existing.lastUpdatedAt() < 60_000L) {
        this.debugLog.commitRejected(uniqueId, source, "AUTHORITATIVE_FRESH", StatusDebugLog.config(
            "existingSource", existing.source().name(),
            "existingKind", existing.kind().name(),
            "ageMs", now - existing.lastUpdatedAt()
        ));
        return;
      }
    }

    StatusKind previousKind = existing == null ? null : existing.kind();
    long lastCheckedAt = source == StatusSource.API
        ? now
        : existing == null ? 0L : existing.lastCheckedAt();

    StatusEntry entry = new StatusEntry(
        uniqueId,
        kind,
        source,
        lastCheckedAt,
        now,
        skipApi,
        httpCode,
        meshTimestamp > 0L ? meshTimestamp : existing == null ? 0L : existing.meshTimestamp()
    );

    this.memory.put(uniqueId, entry);
    this.resolveCache.remove(uniqueId);
    this.store.put(entry);
    this.debugLog.persist(uniqueId, kind, source, previousKind, httpCode);
  }

  public boolean shouldFetchFromApi(@NotNull UUID uniqueId) {
    if (this.isFriendOrSelf(uniqueId)) {
      return false;
    }

    if (!this.isLabyModUser(uniqueId) && !this.isPlayerOnTab(uniqueId)) {
      return false;
    }

    StatusTagInternals runtime = this.internals.get();
    if (runtime == null) {
      return false;
    }

    return StatusReprobePolicy.shouldFetchFromApi(
        this.peekEntry(uniqueId),
        runtime
    );
  }

  public void maybeScheduleReprobe(@NotNull UUID uniqueId, @Nullable StatusEntry entry, boolean onTabPresence) {
    if (entry == null) {
      entry = this.peekEntry(uniqueId);
    }

    if (entry == null || !StatusReprobePolicy.isReprobeCandidate(entry.kind())) {
      return;
    }

    StatusTagInternals runtime = this.internals.get();
    if (runtime == null) {
      return;
    }

    if (onTabPresence) {
      long cooldownMs = StatusReprobePolicy.tabPresenceReprobeMs(runtime);
      if (cooldownMs <= 0L) {
        return;
      }

      long now = System.currentTimeMillis();
      Long lastScheduled = this.tabReprobeScheduledAt.get(uniqueId);
      if (lastScheduled != null && now - lastScheduled < cooldownMs) {
        return;
      }

      this.tabReprobeScheduledAt.put(uniqueId, now);
      this.debugLog.skip(uniqueId, "TAB_PRESENCE_REPROBE");
      this.markForReprobe(uniqueId, "TAB_PRESENCE");
      return;
    }

    this.scheduleFetchIfNeeded(uniqueId, false);
  }

  public void markForReprobe(@NotNull UUID uniqueId, @NotNull String reason) {
    StatusEntry entry = this.peekEntry(uniqueId);
    if (entry == null) {
      this.scheduleFetchIfNeeded(uniqueId, true);
      this.debugLog.skip(uniqueId, "REPROBE_" + reason);
      return;
    }

    if (entry.isAuthoritative()) {
      return;
    }

    StatusEntry reprobeEntry = new StatusEntry(
        uniqueId,
        entry.kind(),
        entry.source(),
        0L,
        entry.lastUpdatedAt(),
        false,
        entry.httpCode(),
        entry.meshTimestamp()
    );
    this.memory.put(uniqueId, reprobeEntry);
    this.store.put(reprobeEntry);
    this.debugLog.skip(uniqueId, "REPROBE_" + reason);
    this.scheduleFetchIfNeeded(uniqueId, true);
  }

  public void scheduleFetchIfNeeded(@NotNull UUID uniqueId, boolean priority) {
    if (this.fetchScheduler == null || !this.isApiEnabled()) {
      this.debugLog.fetchDenied(uniqueId, this.fetchScheduler == null ? "NO_SCHEDULER" : "API_DISABLED");
      return;
    }

    String denyReason = this.fetchDenyReason(uniqueId);
    if (denyReason != null) {
      this.debugLog.fetchDenied(uniqueId, denyReason);
      return;
    }

    this.fetchScheduler.enqueue(uniqueId, priority);
  }

  private @Nullable String fetchDenyReason(@NotNull UUID uniqueId) {
    if (this.isFriendOrSelf(uniqueId)) {
      return "FRIEND_OR_SELF";
    }

    if (!this.isLabyModUser(uniqueId) && !this.isPlayerOnTab(uniqueId)) {
      return "NOT_LABYMOD_AND_NOT_ON_TAB";
    }

    StatusTagInternals runtime = this.internals.get();
    if (runtime == null) {
      return "NO_INTERNALS";
    }

    if (!StatusReprobePolicy.shouldFetchFromApi(this.peekEntry(uniqueId), runtime)) {
      return "REPROBE_POLICY_COOLDOWN";
    }

    return null;
  }

  public void invalidateApiEntry(@NotNull UUID uniqueId) {
    this.memory.remove(uniqueId);
    this.store.remove(uniqueId);
    this.tabReprobeScheduledAt.remove(uniqueId);
    this.unknownResolveAttemptAt.remove(uniqueId);
    this.resolveCache.remove(uniqueId);
    if (this.fetchScheduler != null) {
      this.fetchScheduler.invalidate(uniqueId);
    }
  }

  public void syncFromSession() {
    if (!this.isLabyChatEnabled()) {
      return;
    }

    LabyConnectSession session = this.session();
    if (session == null) {
      return;
    }

    User self = session.self();
    if (self != null) {
      this.commit(
          self.getUniqueId(),
          StatusKind.fromUserStatus(self.userStatus()),
          StatusSource.SELF,
          false,
          0,
          0L
      );
    }

    for (Friend friend : session.getFriends()) {
      this.commit(
          friend.getUniqueId(),
          StatusKind.fromUserStatus(friend.userStatus()),
          StatusSource.FRIEND,
          false,
          0,
          0L
      );
    }
  }

  public void clearSession() {
    this.debugLog.sessionEnd("SERVER_DISCONNECT");
    this.labyModUsers.clear();
    this.tabReprobeScheduledAt.clear();
    this.unknownResolveAttemptAt.clear();
    this.resolveCache.clear();
    this.tabPlayers.clear();
    this.tabPlayersRefreshedAt = 0L;
    if (this.fetchScheduler != null) {
      this.fetchScheduler.clearSessionState();
    }
  }

  public void clearAll() {
    this.memory.clear();
    this.labyModUsers.clear();
    this.tabReprobeScheduledAt.clear();
    this.unknownResolveAttemptAt.clear();
    this.resolveCache.clear();
    this.tabPlayers.clear();
    this.tabPlayersRefreshedAt = 0L;
    this.store.clear();
    if (this.fetchScheduler != null) {
      this.fetchScheduler.clearSessionState();
    }
  }

  public boolean isPlayerOnTab(@NotNull UUID uniqueId) {
    this.refreshTabPlayersIfStale();
    return this.tabPlayers.contains(uniqueId);
  }

  private void refreshTabPlayersIfStale() {
    long now = System.currentTimeMillis();
    if (now - this.tabPlayersRefreshedAt < TAB_CACHE_TTL_MS) {
      return;
    }

    synchronized (this.tabRefreshLock) {
      if (now - this.tabPlayersRefreshedAt < TAB_CACHE_TTL_MS) {
        return;
      }
      this.tabPlayersRefreshedAt = now;

      java.util.Set<UUID> current = new java.util.HashSet<>();
      if (Laby.isInitialized() && Laby.labyAPI().minecraft().isIngame()) {
        ClientPacketListener listener = Laby.labyAPI().minecraft().getClientPacketListener();
        if (listener != null) {
          for (NetworkPlayerInfo playerInfo : listener.getNetworkPlayerInfos()) {
            current.add(playerInfo.profile().getUniqueId());
          }
        }
      }

      this.tabPlayers.retainAll(current);
      this.tabPlayers.addAll(current);
    }
  }

  public void markPlayerOnTab(@NotNull UUID uniqueId) {
    this.tabPlayers.add(uniqueId);
  }

  public void saveStoreIfNeeded() {
    this.store.saveIfNeeded();
  }

  private boolean isFriendOrSelf(@NotNull UUID uniqueId) {
    LabyConnectSession session = this.session();
    if (session == null) {
      return false;
    }

    if (session.getFriend(uniqueId) != null) {
      return true;
    }

    User self = session.self();
    return self != null && self.getUniqueId().equals(uniqueId);
  }

  private boolean isLabyChatEnabled() {
    StatusTagConfiguration configuration = this.configuration.get();
    return configuration != null && configuration.isLabyChatEnabled();
  }

  private boolean isApiEnabled() {
    StatusTagConfiguration configuration = this.configuration.get();
    if (configuration != null && configuration.isApiEnabled()) {
      return true;
    }

    StatusTagInternals runtime = this.internals.get();
    return runtime != null && runtime.fetchOnlineStatusFromApi();
  }

  private @Nullable LabyConnectSession session() {
    if (!Laby.isInitialized()) {
      return null;
    }

    LabyConnectSession session = Laby.labyAPI().labyConnect().getSession();
    if (session == null || !session.isAuthenticated()) {
      return null;
    }

    return session;
  }
}
