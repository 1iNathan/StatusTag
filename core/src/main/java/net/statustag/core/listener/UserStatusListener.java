package net.statustag.core.listener;

import java.util.function.Supplier;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.service.StatusDebugLog;
import net.statustag.core.service.StatusKind;
import net.statustag.core.service.StatusResolver;
import net.statustag.core.service.StatusSource;
import net.statustag.core.util.StatusUiRefresh;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.network.server.ServerDisconnectEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectConnectEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectUpdateSettingEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendAddEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendServerEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendStatusEvent;
import net.labymod.api.event.labymod.labyconnect.session.login.LabyConnectFriendAddBulkEvent;
import net.labymod.api.event.labymod.user.UserDiscoverEvent;
import net.labymod.api.event.labymod.user.UserFamiliarEvent;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.protocol.model.User;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.user.GameUser;

public class UserStatusListener {

  private final StatusResolver resolver;
  private final StatusDebugLog debugLog;
  private final Supplier<StatusTagConfiguration> configuration;

  public UserStatusListener(
      StatusResolver resolver,
      StatusDebugLog debugLog,
      Supplier<StatusTagConfiguration> configuration
  ) {
    this.resolver = resolver;
    this.debugLog = debugLog;
    this.configuration = configuration;
  }

  @Subscribe
  public void onUserDiscover(UserDiscoverEvent event) {
    GameUser gameUser = event.gameUser();
    this.debugLog.listener("UserStatusListener", "UserDiscover", StatusDebugLog.config(
        "uuid", gameUser == null ? "null" : gameUser.getUniqueId(),
        "labyMod", gameUser != null && gameUser.isUsingLabyMod()
    ));
    this.registerGameUser(gameUser, false);
  }

  @Subscribe
  public void onUserFamiliar(UserFamiliarEvent event) {
    GameUser gameUser = event.gameUser();
    this.debugLog.listener("UserStatusListener", "UserFamiliar", StatusDebugLog.config(
        "uuid", gameUser == null ? "null" : gameUser.getUniqueId(),
        "labyMod", gameUser != null && gameUser.isUsingLabyMod()
    ));
    this.registerGameUser(gameUser, true);
  }

  @Subscribe
  public void onLabyConnectConnect(LabyConnectConnectEvent event) {
    this.debugLog.listener("UserStatusListener", "LabyConnectConnect", StatusDebugLog.config());

    if (this.resolver.getFetchScheduler() != null) {
      this.resolver.getFetchScheduler().onSessionAvailable();
    }

    if (this.configuration.get().isLabyChatEnabled()) {
      this.resolver.syncFromSession();
    }
  }

  @Subscribe
  public void onFriendAdd(LabyConnectFriendAddEvent event) {
    this.debugLog.listener("UserStatusListener", "FriendAdd", StatusDebugLog.config(
        "uuid", event.friend() == null ? "null" : event.friend().getUniqueId()
    ));
    this.updateFriendStatus(event.friend());
  }

  @Subscribe
  public void onFriendAddBulk(LabyConnectFriendAddBulkEvent event) {
    this.debugLog.listener("UserStatusListener", "FriendAddBulk", StatusDebugLog.config(
        "count", event.getFriends().size()
    ));
    for (Friend friend : event.getFriends()) {
      this.updateFriendStatus(friend);
    }
  }

  @Subscribe
  public void onFriendStatusUpdate(LabyConnectFriendStatusEvent event) {
    Friend friend = event.friend();
    this.debugLog.listener("UserStatusListener", "FriendStatus", StatusDebugLog.config(
        "uuid", friend == null ? "null" : friend.getUniqueId(),
        "status", friend == null ? "null" : friend.userStatus()
    ));
    this.updateFriendStatus(friend);
    StatusUiRefresh.refresh();
  }

  @Subscribe
  public void onFriendServerUpdate(LabyConnectFriendServerEvent event) {
    Friend friend = event.friend();
    this.debugLog.listener("UserStatusListener", "FriendServer", StatusDebugLog.config(
        "uuid", friend == null ? "null" : friend.getUniqueId()
    ));
    this.updateFriendStatus(friend);
    StatusUiRefresh.refresh();
  }

  @Subscribe
  public void onSettingsUpdate(LabyConnectUpdateSettingEvent event) {
    StatusTagConfiguration config = this.configuration.get();
    LabyConnectSession session = event.labyConnect().getSession();
    if (session == null) {
      return;
    }

    User self = session.self();
    if (self == null) {
      return;
    }

    this.debugLog.listener("UserStatusListener", "SettingsUpdate", StatusDebugLog.config(
        "self", self.getUniqueId(),
        "status", self.userStatus()
    ));

    if (config.isLabyChatEnabled()) {
      StatusKind kind = StatusKind.fromUserStatus(self.userStatus());
      this.resolver.commit(self.getUniqueId(), kind, StatusSource.SELF, false, 0, 0L);
    }

    StatusUiRefresh.refresh();
  }

  @Subscribe
  public void onServerDisconnect(ServerDisconnectEvent event) {
    this.debugLog.listener("UserStatusListener", "ServerDisconnect", StatusDebugLog.config());
    this.resolver.clearSession();
  }

  private void registerGameUser(GameUser gameUser, boolean priority) {
    if (gameUser == null) {
      return;
    }

    if (gameUser.isUsingLabyMod()) {
      this.resolver.markLabyModUser(gameUser.getUniqueId());
    }

    if (this.configuration.get().isApiEnabled()) {
      this.resolver.scheduleFetchIfNeeded(gameUser.getUniqueId(), priority);
    }
  }

  private void updateFriendStatus(Friend friend) {
    if (friend == null || !this.configuration.get().isLabyChatEnabled()) {
      return;
    }

    this.resolver.commit(
        friend.getUniqueId(),
        StatusKind.fromUserStatus(friend.userStatus()),
        StatusSource.FRIEND,
        false,
        0,
        0L
    );
  }
}
