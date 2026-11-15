package net.statustag.core.status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.labymod.api.Laby;
import net.labymod.api.client.network.ClientPacketListener;
import net.labymod.api.client.entity.player.Player;
import net.labymod.api.client.network.NetworkPlayerInfo;
import net.labymod.api.client.world.ClientWorld;
import net.labymod.api.event.client.gui.screen.playerlist.PlayerListUpdateEvent;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.protocol.model.User;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.mojang.GameProfile;
import net.labymod.api.user.GameUser;
import net.statustag.core.net.FetchHost;
import net.statustag.core.net.FetchScheduler;
import net.statustag.core.net.StatusLookup;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.util.Async;
import net.statustag.core.util.CurrentServer;
import net.statustag.core.util.DebugLog;
import net.statustag.core.util.LabyConnectAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusService implements FetchHost {

  private static final int SCAN_INTERVAL_TICKS = 20;
  private static final int GAME_USER_INTERVAL_TICKS = 100;
  private static final int TAB_REBUILD_INTERVAL_TICKS = 600;
  private static final long LIST_REFRESH_THROTTLE_MS = 250L;
  private static final long REQUEST_WINDOW_MS = 2L * StatusPolicy.MINUTE_MS;
  private static final int MAX_REQUESTED = 1_024;

  private final StatusSettings config;
  private final StatusStore store;
  private final StatusPalette palette;
  private final DebugLog debug;
  private final FetchScheduler scheduler;
  private final Set<UUID> tabPlayers = ConcurrentHashMap.newKeySet();
  private final Map<String, UUID> tabNames = new ConcurrentHashMap<>();
  private final Set<UUID> labyModUsers = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Long> requested = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastOnline = new ConcurrentHashMap<>();
  private final AtomicBoolean saving = new AtomicBoolean();

  private volatile @Nullable UUID selfId;
  private volatile boolean listRefreshRequested;
  private volatile boolean bulkSyncing;
  private volatile boolean suppressed;
  private long lastListRefreshAt;
  private int ticks;

  public StatusService(
      @NotNull StatusSettings config,
      @NotNull StatusStore store,
      @NotNull StatusPalette palette,
      @NotNull DebugLog debug,
      @NotNull StatusLookup lookup
  ) {
    this.config = config;
    this.store = store;
    this.palette = palette;
    this.debug = debug;
    this.scheduler = new FetchScheduler(this, lookup, debug, System::currentTimeMillis);
  }

  public @NotNull StatusKind kindOf(@NotNull UUID uniqueId) {
    StatusEntry entry = this.store.get(uniqueId);
    return entry == null ? StatusKind.UNKNOWN : entry.kind();
  }

  public int shownArgb(@NotNull UUID uniqueId) {
    return this.palette.shownArgb(this.kindOf(uniqueId));
  }

  public @NotNull StatusPalette palette() {
    return this.palette;
  }

  public @NotNull FetchScheduler scheduler() {
    return this.scheduler;
  }

  public void commit(@NotNull UUID uniqueId, @NotNull StatusKind kind, @NotNull StatusSource source) {
    this.commit(uniqueId, kind, source, System.currentTimeMillis());
  }

  private void commit(@NotNull UUID uniqueId, @NotNull StatusKind kind, @NotNull StatusSource source, long now) {
    StatusEntry[] previous = new StatusEntry[1];
    StatusEntry next = this.store.compute(uniqueId, (key, current) -> {
      previous[0] = current;
      if (current != null && outranks(current, source, now)) {
        return source == StatusSource.API ? current.withCheckedAt(now) : current;
      }
      if (current != null && source.isLive() && current.source() == source && current.kind() == kind) {
        return current;
      }

      long checkedAt = source == StatusSource.API ? now : current == null ? 0L : current.checkedAt();
      return new StatusEntry(kind, source, now, checkedAt);
    });

    StatusEntry before = previous[0];
    if (next != null && (before == null || before.kind() != next.kind())) {
      this.onKindChanged(uniqueId, before == null ? null : before.kind(), next.kind(), source);
    }
  }

  static boolean outranks(@NotNull StatusEntry current, @NotNull StatusSource incoming, long now) {
    if (incoming.isLive()) {
      return false;
    }
    if (current.source().isLive()) {
      return true;
    }
    return incoming == StatusSource.API
        && current.source() == StatusSource.SYNC
        && now - current.updatedAt() < StatusPolicy.SYNC_FRESH_MS;
  }

  private void onKindChanged(
      @NotNull UUID uniqueId,
      @Nullable StatusKind previous,
      @NotNull StatusKind kind,
      @NotNull StatusSource source
  ) {
    if (this.tabPlayers.contains(uniqueId)) {
      this.listRefreshRequested = true;
    }

    if (this.debug.enabled() && !this.bulkSyncing) {
      this.debug.log("status " + uniqueId + " " + previous + " -> " + kind + " via " + source);
    }
  }

  @Override
  public boolean lookupsEnabled() {
    return this.config.isApiEnabled() && !this.suppressed;
  }

  public boolean isSuppressed() {
    return this.suppressed;
  }

  public void refreshSuppression(@Nullable String host) {
    boolean disabled = this.config.isServerDisabled(host);
    if (disabled == this.suppressed) {
      return;
    }

    this.suppressed = disabled;
    if (disabled) {
      this.scheduler.clear();
    }
    this.listRefreshRequested = true;
    if (this.debug.enabled()) {
      this.debug.log("server " + host + (disabled ? " disabled" : " enabled"));
    }
  }

  @Override
  public @Nullable String jwt() {
    return LabyConnectAccess.jwt();
  }

  @Override
  public boolean needsLookup(@NotNull UUID uniqueId, long now) {
    return StatusPolicy.isLookupCandidate(uniqueId)
        && !uniqueId.equals(this.selfId)
        && this.isRelevant(uniqueId, now)
        && StatusPolicy.needsFetch(this.store.get(uniqueId), now);
  }

  private boolean isRelevant(@NotNull UUID uniqueId, long now) {
    if (this.tabPlayers.contains(uniqueId)) {
      return true;
    }
    Long requestedAt = this.requested.get(uniqueId);
    return requestedAt != null && now - requestedAt < REQUEST_WINDOW_MS;
  }

  @Override
  public boolean isOnTab(@NotNull UUID uniqueId) {
    return this.tabPlayers.contains(uniqueId);
  }

  @Override
  public boolean isKnownLabyModUser(@NotNull UUID uniqueId) {
    return this.labyModUsers.contains(uniqueId);
  }

  @Override
  public void applyLookup(@NotNull UUID uniqueId, @NotNull StatusKind kind, long now) {
    this.requested.remove(uniqueId);
    this.commit(uniqueId, kind, StatusSource.API, now);
  }

  @Override
  public void recordLastOnline(@NotNull UUID uniqueId, long lastOnline) {
    if (this.lastOnline.size() >= StatusStore.MAX_ENTRIES && !this.lastOnline.containsKey(uniqueId)) {
      this.lastOnline.clear();
    }
    this.lastOnline.put(uniqueId, lastOnline);
  }

  public @Nullable Long lastOnline(@NotNull UUID uniqueId) {
    return this.lastOnline.get(uniqueId);
  }

  public void requestLookup(@NotNull UUID uniqueId) {
    if (!this.lookupsEnabled() || !StatusPolicy.isLookupCandidate(uniqueId)) {
      return;
    }

    long now = System.currentTimeMillis();
    if (this.requested.size() >= MAX_REQUESTED) {
      this.requested.clear();
    }
    this.requested.put(uniqueId, now);
    if (this.needsLookup(uniqueId, now)) {
      this.scheduler.enqueue(uniqueId, true);
    }
  }

  public void tick() {
    if (!this.config.isActive()) {
      return;
    }

    long now = System.currentTimeMillis();
    int tick = ++this.ticks;
    if (tick % TAB_REBUILD_INTERVAL_TICKS == 0) {
      this.rebuildTabPlayers();
      this.requested.values().removeIf(at -> now - at >= REQUEST_WINDOW_MS);
    }
    if (tick % SCAN_INTERVAL_TICKS == 0) {
      this.refreshSuppression(CurrentServer.host());
    }
    if (tick % GAME_USER_INTERVAL_TICKS == 0 && !this.suppressed) {
      this.scanGameUsers();
    }
    if (tick % SCAN_INTERVAL_TICKS == 0) {
      this.syncSelf();
      this.scanTabPlayers(now);
    }

    this.scheduler.tick(now);

    if (this.listRefreshRequested && now - this.lastListRefreshAt >= LIST_REFRESH_THROTTLE_MS) {
      this.listRefreshRequested = false;
      this.lastListRefreshAt = now;
      Laby.fireEvent(new PlayerListUpdateEvent());
    }

    if (this.store.isSaveDue(now)) {
      this.saveAsync();
    }
  }

  private void scanTabPlayers(long now) {
    if (!this.lookupsEnabled() || this.tabPlayers.isEmpty()) {
      return;
    }

    this.enqueueNearby(now);
    for (UUID uniqueId : this.tabPlayers) {
      if (this.store.get(uniqueId) == null && this.needsLookup(uniqueId, now)) {
        this.scheduler.enqueue(uniqueId, false);
      }
    }
    for (UUID uniqueId : this.tabPlayers) {
      if (this.needsLookup(uniqueId, now)) {
        this.scheduler.enqueue(uniqueId, false);
      }
    }
  }

  private void enqueueNearby(long now) {
    ClientWorld world = Laby.labyAPI().minecraft().clientWorld();
    if (world == null) {
      return;
    }

    for (Player player : world.getPlayers()) {
      UUID uniqueId = player.getUniqueId();
      if (this.tabPlayers.contains(uniqueId) && this.needsLookup(uniqueId, now)) {
        this.scheduler.enqueue(uniqueId, true);
      }
    }
  }

  private void scanGameUsers() {
    if (!Laby.isInitialized() || this.tabPlayers.isEmpty()) {
      return;
    }

    for (GameUser gameUser : Laby.references().gameUserService().getGameUsers().values()) {
      if (gameUser != null && gameUser.isUsingLabyMod()) {
        this.onLabyModUser(gameUser.getUniqueId());
      }
    }
  }

  public void rebuildTabPlayers() {
    if (!Laby.isInitialized()) {
      return;
    }

    ClientPacketListener listener = Laby.labyAPI().minecraft().getClientPacketListener();
    if (listener == null) {
      this.tabPlayers.clear();
      return;
    }

    Collection<NetworkPlayerInfo> infos = listener.getNetworkPlayerInfos();
    Set<UUID> current = new HashSet<>(Math.max(16, infos.size() * 2));
    Map<String, UUID> names = new HashMap<>(Math.max(16, infos.size() * 2));
    for (NetworkPlayerInfo info : infos) {
      GameProfile profile = info.profile();
      current.add(profile.getUniqueId());
      String name = profile.getUsername();
      if (name != null) {
        names.put(name.toLowerCase(Locale.ROOT), profile.getUniqueId());
      }
    }
    this.tabPlayers.retainAll(current);
    this.tabPlayers.addAll(current);
    this.tabNames.keySet().retainAll(names.keySet());
    this.tabNames.putAll(names);
  }

  public void onTabAdd(@NotNull UUID uniqueId, @Nullable String name) {
    this.tabPlayers.add(uniqueId);
    if (name != null) {
      this.tabNames.put(name.toLowerCase(Locale.ROOT), uniqueId);
    }
  }

  public void onTabRemove(@NotNull UUID uniqueId, @Nullable String name) {
    this.tabPlayers.remove(uniqueId);
    if (name != null) {
      this.tabNames.remove(name.toLowerCase(Locale.ROOT), uniqueId);
    }
  }

  public @Nullable UUID findChatSender(@Nullable String plainText) {
    return ChatSenderDetector.detect(plainText, this.tabNames);
  }

  public @Nullable UUID findTabPlayer(@NotNull String name) {
    return this.tabNames.get(name.toLowerCase(Locale.ROOT));
  }

  public @NotNull List<String> completeTabName(@NotNull String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String name : this.tabNames.keySet()) {
      if (name.startsWith(lower)) {
        matches.add(name);
        if (matches.size() >= 50) {
          break;
        }
      }
    }
    Collections.sort(matches);
    return matches;
  }

  public @Nullable StatusEntry entry(@NotNull UUID uniqueId) {
    return this.store.get(uniqueId);
  }

  public int @NotNull [] tabKindCounts() {
    int[] counts = new int[StatusKind.count()];
    for (UUID uniqueId : this.tabPlayers) {
      counts[this.kindOf(uniqueId).ordinal()]++;
    }
    return counts;
  }

  public int tabPlayerCount() {
    return this.tabPlayers.size();
  }

  public void onServerJoin() {
    Laby.labyAPI().minecraft().executeNextTick(() -> {
      this.refreshSuppression(CurrentServer.host());
      this.rebuildTabPlayers();
      this.scanGameUsers();
    });
  }

  public void onServerDisconnect() {
    this.suppressed = false;
    this.tabPlayers.clear();
    this.tabNames.clear();
    this.labyModUsers.clear();
    this.requested.clear();
    this.scheduler.clear();
    this.saveAsync();
  }

  public void onLabyModUser(@NotNull UUID uniqueId) {
    if (!this.labyModUsers.add(uniqueId)) {
      return;
    }

    StatusEntry updated = this.store.compute(uniqueId, (key, entry) ->
        entry != null && entry.kind() == StatusKind.NO_LABYMOD && !entry.source().isLive()
            ? entry.withCheckedAt(0L)
            : entry);
    if (updated != null && updated.checkedAt() == 0L && this.tabPlayers.contains(uniqueId)) {
      this.requestLookup(uniqueId);
    }
  }

  public void onFriend(@Nullable Friend friend) {
    if (friend == null || !this.config.isLabyChatEnabled()) {
      return;
    }
    this.commit(friend.getUniqueId(), StatusKind.fromUserStatus(friend.userStatus()), StatusSource.FRIEND);
  }

  public void onFriendRemoved(@Nullable Friend friend) {
    if (friend != null) {
      this.store.compute(friend.getUniqueId(), (key, entry) ->
          entry != null && entry.source() == StatusSource.FRIEND ? entry.asCache() : entry);
    }
  }

  public void syncSelf() {
    User self = LabyConnectAccess.self();
    if (self == null) {
      return;
    }

    this.selfId = self.getUniqueId();
    if (this.config.isLabyChatEnabled()) {
      this.commit(self.getUniqueId(), StatusKind.fromUserStatus(self.userStatus()), StatusSource.SELF);
    }
  }

  public void syncSession() {
    LabyConnectSession session = LabyConnectAccess.session();
    if (session == null) {
      return;
    }

    this.syncSelf();
    if (!this.config.isLabyChatEnabled()) {
      return;
    }

    List<Friend> friends = session.getFriends();
    this.bulkSyncing = true;
    try {
      for (Friend friend : friends) {
        this.onFriend(friend);
      }
    } finally {
      this.bulkSyncing = false;
    }

    if (this.debug.enabled()) {
      this.debug.log("friends synced count=" + friends.size());
    }
  }

  public void demoteLive() {
    this.store.replaceAll((uniqueId, entry) -> entry.source().isLive() ? entry.asCache() : entry);
    this.listRefreshRequested = true;
  }

  public void clearCache() {
    this.store.clear();
    this.lastOnline.clear();
    this.requested.clear();
    this.scheduler.clear();
    this.syncSession();
    this.listRefreshRequested = true;
    this.saveAsync();
  }

  public void saveAsync() {
    if (this.saving.compareAndSet(false, true)) {
      Async.run(() -> {
        try {
          this.store.save(System.currentTimeMillis());
        } finally {
          this.saving.set(false);
        }
      });
    }
  }

  public void saveNow() {
    this.store.save(System.currentTimeMillis());
  }
}
