package net.statustag.core.listener;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.TranslatableComponent;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.serializer.legacy.LegacyComponentSerializer;
import net.labymod.api.client.component.serializer.plain.PlainTextComponentSerializer;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendServerEvent;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendStatusEvent;
import net.labymod.api.event.labymod.notification.PushNotificationEvent;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.labyconnect.protocol.model.friend.ServerInfo;
import net.labymod.api.notification.Notification;
import net.labymod.api.util.I18n;
import net.labymod.api.util.concurrent.task.Task;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.config.NotificationsConfig;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.NoticeGate;
import net.statustag.core.status.PresenceSettler;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.CurrentServer;
import net.statustag.core.util.DebugLog;
import net.statustag.core.util.MessageTemplate;
import net.statustag.core.util.ServerFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FriendNotificationListener {

  private static final long SETTLE_MS = 3_500L;
  private static final long SETTLE_SLACK_MS = 50L;
  private static final long OWN_FORGET_MS = 60_000L;
  private static final long NATIVE_LATE_MS = 10_000L;
  private static final String NATIVE_ONLINE_KEY = "labymod.activity.labyconnect.notifications.online";
  private static final String NATIVE_OFFLINE_KEY = "labymod.activity.labyconnect.notifications.offline";

  private final StatusTagConfiguration config;
  private final StatusPalette palette;
  private final DebugLog debug;
  private final PresenceSettler<Friend> settler = new PresenceSettler<>(SETTLE_MS);
  private final NoticeGate gate = new NoticeGate();
  private final Map<Notification, Long> ownNotifications = Collections.synchronizedMap(new IdentityHashMap<>());
  private final Map<String, Presence> presenceNotices = new ConcurrentHashMap<>();

  private record Presence(@NotNull Notification notification, boolean online, long at) {
  }

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
    String key = notification.text() instanceof TranslatableComponent text ? text.getKey() : null;
    if (NATIVE_ONLINE_KEY.equals(key)) {
      this.gate.nativeNotice(title, true, now);
      this.dropLateDuplicate(title, true, now);
    } else if (NATIVE_OFFLINE_KEY.equals(key)) {
      this.gate.nativeNotice(title, false, now);
      this.dropLateDuplicate(title, false, now);
    } else {
      this.gate.foreignNotice(title, now);
    }
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

    String template = notifications.joinedMessage().get();
    if (MessageTemplate.isCustom(template)) {
      StatusKind kind = StatusKind.fromUserStatus(friend.userStatus());
      this.push(friend, this.custom(template, kind.isLabyPresence() ? kind : StatusKind.ONLINE, null));
    } else {
      this.push(friend, Component.translatable("statustag.notify.joinedServer")
          .color(this.palette.textColor(StatusKind.ONLINE)));
    }
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
            + " changes=" + settled.changes() + " " + outcome + this.nativeField(settled));
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
      PresenceSettler.Change change = settled.change();
      Notification shown = this.push(friend, this.message(change, settled.before(), settled.after()));
      if (change != PresenceSettler.Change.STATUS) {
        long now = now();
        this.presenceNotices.values().removeIf(presence -> now - presence.at() >= NATIVE_LATE_MS);
        this.presenceNotices.put(
            name.toLowerCase(Locale.ROOT),
            new Presence(shown, change == PresenceSettler.Change.CAME_ONLINE, now)
        );
      }
    }
    return outcome.name().toLowerCase(Locale.ROOT);
  }

  private @NotNull String nativeField(@NotNull PresenceSettler.Settled<Friend> settled) {
    PresenceSettler.Change change = settled.change();
    if (change != PresenceSettler.Change.CAME_ONLINE && change != PresenceSettler.Change.WENT_OFFLINE) {
      return "";
    }

    String name = settled.subject().getName();
    Long offset = name == null ? null : this.gate.nativeOffset(name, change, settled.startedAt());
    return offset == null ? " native=none" : " native=" + offset + "ms";
  }

  private boolean notificationsOn() {
    return this.config.enabled().get() && this.config.notifications().enabled().get();
  }

  private void dropLateDuplicate(@NotNull String title, boolean online, long now) {
    this.presenceNotices.values().removeIf(presence -> now - presence.at() >= NATIVE_LATE_MS);
    String key = title.toLowerCase(Locale.ROOT);
    Presence ours = this.presenceNotices.get(key);
    if (ours == null || ours.online() != online || !this.presenceNotices.remove(key, ours)) {
      return;
    }

    Laby.labyAPI().notificationController().pop(ours.notification());
    if (this.debug.enabled()) {
      this.debug.log("notify removed own duplicate of a late LabyMod notice online=" + online);
    }
  }

  private @NotNull Component message(
      @NotNull PresenceSettler.Change change,
      @NotNull StatusKind before,
      @NotNull StatusKind after
  ) {
    NotificationsConfig notifications = this.config.notifications();
    String template = switch (change) {
      case CAME_ONLINE -> notifications.onlineMessage().get();
      case WENT_OFFLINE -> notifications.offlineMessage().get();
      default -> notifications.statusMessage().get();
    };
    if (MessageTemplate.isCustom(template)) {
      return this.custom(template, after, before);
    }

    Component text = Component.translatable("statustag.notify." + after.key()).color(this.palette.textColor(after));
    if (change != PresenceSettler.Change.STATUS) {
      return text;
    }

    return Component.empty()
        .append(text)
        .append(Component.text(" "))
        .append(Component.translatable(
            "statustag.notify.was",
            NamedTextColor.DARK_GRAY,
            Component.translatable("statustag.status." + before.key())
        ));
  }

  private @NotNull Component custom(@NotNull String template, @NotNull StatusKind status, @Nullable StatusKind previous) {
    String filled = MessageTemplate.fill(
        template,
        I18n.translate("statustag.status." + status.key()),
        previous == null ? "" : I18n.translate("statustag.status." + previous.key())
    );
    return Component.empty()
        .color(this.palette.textColor(status))
        .append(LegacyComponentSerializer.legacyAmpersand().deserialize(filled));
  }

  private @NotNull Notification push(@NotNull Friend friend, @NotNull Component text) {
    Notification notification = Notification.builder()
        .title(Component.text(friend.getName()))
        .text(text)
        .icon(Icon.head(friend.getUniqueId()))
        .type(Notification.Type.SOCIAL)
        .build();

    long now = now();
    this.ownNotifications.values().removeIf(at -> now - at >= OWN_FORGET_MS);
    this.ownNotifications.put(notification, now);
    Laby.labyAPI().notificationController().push(notification);

    if (friend.isPinned()
        && this.config.notifications().pinnedSound().get()
        && notification.type().shouldPush()
        && Laby.labyAPI().config().notifications().enabled().get()) {
      Laby.labyAPI().minecraft().executeOnRenderThread(
          () -> Laby.labyAPI().minecraft().sounds().playChatFilterSound()
      );
    }
    return notification;
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
