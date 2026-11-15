package net.statustag.core.listener;

import net.labymod.api.event.Phase;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.lifecycle.ShutdownEvent;
import net.labymod.api.event.client.lifecycle.GameTickEvent;
import net.labymod.api.event.client.network.playerinfo.PlayerInfoAddEvent;
import net.labymod.api.event.client.network.playerinfo.PlayerInfoRemoveEvent;
import net.labymod.api.event.client.network.server.ServerDisconnectEvent;
import net.labymod.api.event.client.network.server.ServerJoinEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectBroadcastEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectConnectEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectDisconnectEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectUpdateSettingEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendAddEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendRemoveEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendStatusEvent;
import net.labymod.api.event.labymod.labyconnect.session.login.LabyConnectFriendAddBulkEvent;
import net.labymod.api.event.labymod.user.UserDiscoverEvent;
import net.labymod.api.event.labymod.user.UserFamiliarEvent;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.mojang.GameProfile;
import net.labymod.api.user.GameUser;
import net.statustag.core.status.StatusService;
import net.statustag.core.sync.StatusSync;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusListener {

  private final StatusService service;
  private final StatusSync sync;

  public StatusListener(@NotNull StatusService service, @NotNull StatusSync sync) {
    this.service = service;
    this.sync = sync;
  }

  @Subscribe
  public void onTick(GameTickEvent event) {
    if (event.phase() != Phase.PRE) {
      return;
    }

    this.service.tick();
    this.sync.tick();
  }

  @Subscribe
  public void onServerJoin(ServerJoinEvent event) {
    this.service.onServerJoin();
    this.sync.requestResync();
  }

  @Subscribe
  public void onServerDisconnect(ServerDisconnectEvent event) {
    this.service.onServerDisconnect();
  }

  @Subscribe
  public void onPlayerInfoAdd(PlayerInfoAddEvent event) {
    GameProfile profile = event.playerInfo().profile();
    this.service.onTabAdd(profile.getUniqueId(), profile.getUsername());
  }

  @Subscribe
  public void onPlayerInfoRemove(PlayerInfoRemoveEvent event) {
    GameProfile profile = event.playerInfo().profile();
    this.service.onTabRemove(profile.getUniqueId(), profile.getUsername());
  }

  @Subscribe
  public void onUserDiscover(UserDiscoverEvent event) {
    this.registerGameUser(event.gameUser());
  }

  @Subscribe
  public void onUserFamiliar(UserFamiliarEvent event) {
    this.registerGameUser(event.gameUser());
  }

  @Subscribe
  public void onLabyConnectConnect(LabyConnectConnectEvent event) {
    this.service.syncSession();
    this.sync.requestResync();
  }

  @Subscribe
  public void onLabyConnectDisconnect(LabyConnectDisconnectEvent event) {
    this.service.demoteLive();
  }

  @Subscribe
  public void onSettingsUpdate(LabyConnectUpdateSettingEvent event) {
    this.service.syncSelf();
    this.sync.announceNow();
  }

  @Subscribe
  public void onFriendAdd(LabyConnectFriendAddEvent event) {
    this.service.onFriend(event.friend());
  }

  @Subscribe
  public void onFriendAddBulk(LabyConnectFriendAddBulkEvent event) {
    for (Friend friend : event.getFriends()) {
      this.service.onFriend(friend);
    }
  }

  @Subscribe
  public void onFriendRemove(LabyConnectFriendRemoveEvent event) {
    this.service.onFriendRemoved(event.friend());
  }

  @Subscribe
  public void onFriendStatus(LabyConnectFriendStatusEvent event) {
    this.service.onFriend(event.friend());
  }

  @Subscribe
  public void onBroadcast(LabyConnectBroadcastEvent event) {
    if (event.action() == LabyConnectBroadcastEvent.Action.RECEIVE) {
      this.sync.handleIncoming(event.getSender(), event.getKey(), event.getPayload());
    }
  }

  @Subscribe
  public void onShutdown(ShutdownEvent event) {
    if (event.stage() != ShutdownEvent.Stage.PRE) {
      return;
    }
    this.service.saveNow();
  }

  private void registerGameUser(@Nullable GameUser gameUser) {
    if (gameUser != null && gameUser.isUsingLabyMod()) {
      this.service.onLabyModUser(gameUser.getUniqueId());
    }
  }
}
