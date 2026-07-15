package net.statustag.core.listener;

import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.lifecycle.GameTickEvent;
import net.labymod.api.event.client.network.server.ServerJoinEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectBroadcastEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectConnectEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectUpdateSettingEvent;
import net.statustag.core.mesh.StatusSync;

public class StatusSyncListener {

  private static final int TICK_INTERVAL = 10;

  private final StatusSync mesh;
  private int ticks;

  public StatusSyncListener(StatusSync mesh) {
    this.mesh = mesh;
  }

  @Subscribe
  public void onBroadcast(LabyConnectBroadcastEvent event) {
    if (event.action() != LabyConnectBroadcastEvent.Action.RECEIVE) {
      return;
    }

    this.mesh.handleIncoming(event.getSender(), event.getKey(), event.getPayload());
  }

  @Subscribe
  public void onLabyConnect(LabyConnectConnectEvent event) {
    this.mesh.requestResync();
  }

  @Subscribe
  public void onServerJoin(ServerJoinEvent event) {
    this.mesh.requestResync();
  }

  @Subscribe
  public void onSettingUpdate(LabyConnectUpdateSettingEvent event) {
    this.mesh.broadcastOwnStatus(true);
  }

  @Subscribe
  public void onGameTick(GameTickEvent event) {
    if (++this.ticks < TICK_INTERVAL) {
      return;
    }
    this.ticks = 0;
    this.mesh.tick();
  }
}
