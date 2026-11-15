package net.statustag.core.listener;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.serializer.plain.PlainTextComponentSerializer;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendServerEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendStatusEvent;
import net.labymod.api.event.labymod.notification.PushNotificationEvent;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.labyconnect.protocol.model.friend.ServerInfo;
import net.labymod.api.notification.Notification;
import net.labymod.api.util.concurrent.task.Task;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.config.NotificationsConfig;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.CurrentServer;
import net.statustag.core.util.ServerFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FriendNotificationListener {

  private static final long NATIVE_GRACE_MS = 750L;
  private static final long NATIVE_MATCH_WINDOW_MS = 1_600L;
  private static final long PRUNE_AFTER_MS = 10_000L;
  private static final long JOIN_REPEAT_MS = 60_000L;

  private final StatusTagConfiguration config;
  private final StatusPalette palette;
  private final Map<String, Long> foreignNoticeAt = new ConcurrentHashMap<>();
  private final Map<UUID, Task> pending = new ConcurrentHashMap<>();
  private final Map<Notification, Long> ownNotifications = new ConcurrentHashMap<>();
  private final Map<UUID, Long> joinNoticeAt = new ConcurrentHashMap<>();

  public FriendNotificationListener(@NotNull StatusTagConfiguration config, @NotNull StatusPalette palette) {
    this.config = config;
    this.palette = palette;
  }

  @Subscribe
  public void onPushNotification(PushNotificationEvent event) {
    Notification notification = event.notification();
    if (this.ownNotifications.remove(notification) != null) {
      return;
    }

    String title = plainText(notification.title());
    if (title == null || title.isEmpty()) {
      return;
    }

    long now = System.currentTimeMillis();
    this.prune(now);
    this.foreignNoticeAt.put(title.toLowerCase(Locale.ROOT), now);
  }

  @Subscribe
  public void onFriendStatus(LabyConnectFriendStatusEvent event) {
    NotificationsConfig notifications = this.config.notifications();
    if (!this.config.enabled().get() || !notifications.enabled().get()) {
      return;
    }

    if (event.getStatus() == event.getPreviousStatus()) {
      return;
    }

    Friend friend = event.friend();
    if (friend == null || friend.getName() == null) {
      return;
    }

    if (notifications.pinnedOnly().get() && !friend.isPinned()) {
      return;
    }

    StatusKind kind = StatusKind.fromUserStatus(event.getStatus());
    if (kind == StatusKind.UNKNOWN) {
      return;
    }

    boolean cameOnline = !event.wasOnline() && event.isOnline();
    boolean wentOffline = event.wasOnline() && !event.isOnline();
    UUID uniqueId = friend.getUniqueId();

    if (!cameOnline && !wentOffline) {
      if (notifications.statusChange().get()) {
        this.cancelPending(uniqueId);
        this.push(friend, kind);
      }
      return;
    }

    if (cameOnline && !notifications.friendOnline().get() || wentOffline && !notifications.friendOffline().get()) {
      return;
    }

    this.cancelPending(uniqueId);
    String name = friend.getName();
    Task task = Task.builder(() -> {
      this.pending.remove(uniqueId);
      if (!this.announcedByOthers(name)) {
        this.push(friend, kind);
      }
    }).delay(NATIVE_GRACE_MS, TimeUnit.MILLISECONDS).build();

    this.pending.put(uniqueId, task);
    task.execute();
  }

  @Subscribe
  public void onFriendServer(LabyConnectFriendServerEvent event) {
    NotificationsConfig notifications = this.config.notifications();
    if (!this.config.enabled().get() || !notifications.enabled().get() || !notifications.friendJoinedServer().get()) {
      return;
    }

    Friend friend = event.friend();
    ServerInfo server = event.serverInfo();
    if (friend == null || friend.getName() == null || server == null || server.isLocalHost()) {
      return;
    }

    if (notifications.pinnedOnly().get() && !friend.isPinned()) {
      return;
    }

    String address = server.getAddress();
    if (!ServerFilter.sameServer(CurrentServer.host(), address)) {
      return;
    }

    ServerInfo previous = event.getPreviousServer();
    if (previous != null && ServerFilter.sameServer(previous.getAddress(), address)) {
      return;
    }

    UUID uniqueId = friend.getUniqueId();
    long now = System.currentTimeMillis();
    this.joinNoticeAt.values().removeIf(at -> now - at >= JOIN_REPEAT_MS);
    if (this.joinNoticeAt.putIfAbsent(uniqueId, now) != null) {
      return;
    }

    this.cancelPending(uniqueId);
    this.push(friend, Component.translatable("statustag.notify.joinedServer")
        .color(this.palette.textColor(StatusKind.ONLINE)));
  }

  private void cancelPending(@NotNull UUID uniqueId) {
    Task previous = this.pending.remove(uniqueId);
    if (previous != null) {
      previous.cancel();
    }
  }

  private boolean announcedByOthers(@NotNull String name) {
    Long at = this.foreignNoticeAt.get(name.toLowerCase(Locale.ROOT));
    return at != null && System.currentTimeMillis() - at <= NATIVE_MATCH_WINDOW_MS;
  }

  private void push(@NotNull Friend friend, @NotNull StatusKind kind) {
    this.push(friend, Component.translatable("statustag.notify." + kind.key()).color(this.palette.textColor(kind)));
  }

  private void push(@NotNull Friend friend, @NotNull Component text) {
    Notification notification = Notification.builder()
        .title(Component.text(friend.getName()))
        .text(text)
        .icon(Icon.head(friend.getUniqueId()))
        .type(Notification.Type.SOCIAL)
        .build();

    this.ownNotifications.put(notification, System.currentTimeMillis());
    Laby.labyAPI().notificationController().push(notification);

    if (friend.isPinned() && this.config.notifications().pinnedSound().get()) {
      Laby.labyAPI().minecraft().executeOnRenderThread(
          () -> Laby.labyAPI().minecraft().sounds().playChatFilterSound()
      );
    }
  }

  private void prune(long now) {
    long cutoff = now - PRUNE_AFTER_MS;
    this.foreignNoticeAt.values().removeIf(at -> at < cutoff);
    this.ownNotifications.values().removeIf(at -> at < cutoff);
  }

  private static @Nullable String plainText(@Nullable Component component) {
    if (component == null) {
      return null;
    }

    try {
      return PlainTextComponentSerializer.plainText().serialize(component);
    } catch (RuntimeException unsupported) {
      return null;
    }
  }
}
