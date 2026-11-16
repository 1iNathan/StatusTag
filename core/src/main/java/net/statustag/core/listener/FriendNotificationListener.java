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
import net.statustag.core.status.NoticeGate;
import net.statustag.core.status.PresenceSettler;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.CurrentServer;
import net.statustag.core.util.DebugLog;
import net.statustag.core.util.ServerFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FriendNotificationListener {

  private static final long SETTLE_MS = 3_000L;
  private static final long SETTLE_SLACK_MS = 50L;
  private static final long OWN_FORGET_MS = 60_000L;

  private final StatusTagConfiguration config;
  private final StatusPalette palette;
  private final DebugLog debug;
  private final PresenceSettler<Friend> settler = new PresenceSettler<>(SETTLE_MS);
  private final NoticeGate gate = new NoticeGate();
  private final Map<Notification, Long> ownNotifications = new ConcurrentHashMap<>();

  public FriendNotificationListener(
      @NotNull StatusTagConfiguration config,
      @NotNull StatusPalette palette,
      @NotNull DebugLog debug
  ) {
    this.config = config;
    this.palette = palette;
    this.debug = debug;
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

    long now = now();
    this.ownNotifications.values().removeIf(at -> now - at >= OWN_FORGET_MS);
    this.gate.foreignNotice(title, now);
  }

  @Subscribe
  public void onFriendStatus(LabyConnectFriendStatusEvent event) {
    if (!this.notificationsOn()) {
      return;
    }

    if (event.getStatus() == event.getPreviousStatus()) {
      return;
    }

    Friend friend = event.friend();
    if (friend == null || friend.getName() == null) {
      return;
    }

    if (this.config.notifications().pinnedOnly().get() && !friend.isPinned()) {
      return;
    }

    this.settler.record(
        friend.getUniqueId(),
        friend,
        StatusKind.fromUserStatus(event.getPreviousStatus()),
        StatusKind.fromUserStatus(event.getStatus()),
        now()
    );

    Task.builder(this::flush)
        .delay(SETTLE_MS + SETTLE_SLACK_MS, TimeUnit.MILLISECONDS)
        .build()
        .execute();
  }

  @Subscribe
  public void onFriendServer(LabyConnectFriendServerEvent event) {
    NotificationsConfig notifications = this.config.notifications();
    if (!this.notificationsOn() || !notifications.friendJoinedServer().get()) {
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

    if (!this.gate.claimJoin(friend.getUniqueId(), now())) {
      return;
    }

    this.push(friend, Component.translatable("statustag.notify.joinedServer")
        .color(this.palette.textColor(StatusKind.ONLINE)));
  }

  private void flush() {
    for (PresenceSettler.Settled<Friend> settled : this.settler.due(now())) {
      String outcome;
      try {
        outcome = this.settle(settled);
      } catch (RuntimeException exception) {
        outcome = "failed " + exception;
      }

      if (this.debug.enabled()) {
        this.debug.log("notify " + settled.id() + " " + settled.before() + " -> " + settled.after()
            + " changes=" + settled.changes() + " " + outcome);
      }
    }
  }

  private @NotNull String settle(@NotNull PresenceSettler.Settled<Friend> settled) {
    if (!this.notificationsOn()) {
      return "disabled";
    }

    Friend friend = settled.subject();
    String name = friend.getName();
    if (name == null) {
      return "unnamed";
    }

    NotificationsConfig notifications = this.config.notifications();
    NoticeGate.Outcome outcome = this.gate.decide(settled, name, new NoticeGate.Toggles(
        notifications.statusChange().get(),
        notifications.friendOnline().get(),
        notifications.friendOffline().get()
    ));

    if (outcome == NoticeGate.Outcome.PUSH) {
      this.push(friend, settled.after());
    }
    return outcome.name().toLowerCase(Locale.ROOT);
  }

  private boolean notificationsOn() {
    return this.config.enabled().get() && this.config.notifications().enabled().get();
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

    this.ownNotifications.put(notification, now());
    Laby.labyAPI().notificationController().push(notification);

    if (friend.isPinned() && this.config.notifications().pinnedSound().get()) {
      Laby.labyAPI().minecraft().executeOnRenderThread(
          () -> Laby.labyAPI().minecraft().sounds().playChatFilterSound()
      );
    }
  }

  private static long now() {
    return System.nanoTime() / 1_000_000L;
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
