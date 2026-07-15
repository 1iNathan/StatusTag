package net.statustag.core.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.service.StatusDebugLog;
import net.statustag.core.service.StatusEntry;
import net.statustag.core.service.StatusFetchScheduler;
import net.statustag.core.service.StatusResolver;
import net.labymod.api.Laby;
import net.labymod.api.client.network.ClientPacketListener;
import net.labymod.api.client.network.NetworkPlayerInfo;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.network.playerinfo.PlayerInfoAddEvent;
import net.labymod.api.event.client.network.server.ServerJoinEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectConnectEvent;

public class TabListStatusListener {

  private static final long BULK_SCAN_COOLDOWN_MS = 30_000L;

  private final StatusResolver resolver;
  private final StatusFetchScheduler fetchScheduler;
  private final StatusDebugLog debugLog;
  private final Supplier<StatusTagConfiguration> configuration;
  private long lastBulkScanAt;

  public TabListStatusListener(
      StatusResolver resolver,
      StatusFetchScheduler fetchScheduler,
      StatusDebugLog debugLog,
      Supplier<StatusTagConfiguration> configuration
  ) {
    this.resolver = resolver;
    this.fetchScheduler = fetchScheduler;
    this.debugLog = debugLog;
    this.configuration = configuration;
  }

  @Subscribe
  public void onServerJoin(ServerJoinEvent event) {
    this.debugLog.listener("TabListStatusListener", "ServerJoin", StatusDebugLog.config());
    Laby.labyAPI().minecraft().executeNextTick(this::scanTabPlayers);
  }

  @Subscribe
  public void onLabyConnectConnect(LabyConnectConnectEvent event) {
    this.debugLog.listener("TabListStatusListener", "LabyConnectConnect", StatusDebugLog.config());
    this.fetchScheduler.onSessionAvailable();
    this.scanTabPlayers();
  }

  @Subscribe
  public void onPlayerInfoAdd(PlayerInfoAddEvent event) {
    this.resolver.markPlayerOnTab(event.playerInfo().profile().getUniqueId());

    if (!this.configuration.get().isApiEnabled()) {
      return;
    }

    UUID uniqueId = event.playerInfo().profile().getUniqueId();
    this.debugLog.listener("TabListStatusListener", "PlayerInfoAdd", StatusDebugLog.config("uuid", uniqueId));

    StatusEntry entry = this.resolver.peekEntry(uniqueId);
    if (entry == null) {
      this.resolver.scheduleFetchIfNeeded(uniqueId, true);
      return;
    }

    this.resolver.maybeScheduleReprobe(uniqueId, entry, true);
  }

  private void scanTabPlayers() {
    if (!this.configuration.get().isApiEnabled()) {
      return;
    }

    long now = System.currentTimeMillis();
    if (now - this.lastBulkScanAt < BULK_SCAN_COOLDOWN_MS) {
      return;
    }

    this.lastBulkScanAt = now;
    if (!Laby.isInitialized() || !Laby.labyAPI().minecraft().isIngame()) {
      return;
    }

    ClientPacketListener listener = Laby.labyAPI().minecraft().getClientPacketListener();
    if (listener == null) {
      return;
    }

    List<UUID> missing = new ArrayList<>();
    int playerCount = 0;
    for (NetworkPlayerInfo playerInfo : listener.getNetworkPlayerInfos()) {
      playerCount++;
      UUID uniqueId = playerInfo.profile().getUniqueId();

      StatusEntry entry = this.resolver.peekEntry(uniqueId);
      if (entry == null) {
        if (this.resolver.shouldFetchFromApi(uniqueId)) {
          missing.add(uniqueId);
        }
        continue;
      }

      if (this.resolver.shouldFetchFromApi(uniqueId)) {
        this.resolver.maybeScheduleReprobe(uniqueId, entry, true);
        missing.add(uniqueId);
      }
    }

    this.debugLog.tabScan(playerCount, missing.size(), now);
    this.fetchScheduler.enqueueAll(missing);
  }
}
