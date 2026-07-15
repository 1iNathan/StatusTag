package net.statustag.core.listener;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.component.serializer.plain.PlainTextComponentSerializer;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.labymod.labyconnect.session.friend.LabyConnectFriendStatusEvent;
import net.labymod.api.event.labymod.notification.PushNotificationEvent;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.notification.Notification;
import net.labymod.api.util.concurrent.task.Task;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.service.StatusKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FriendStatusToastListener {

  private static final long NATIVE_GRACE_MS = 750L;

  private static final long NATIVE_MATCH_WINDOW_MS = 1_600L;
  private static final long PRUNE_AFTER_MS = 10_000L;

  private final Supplier<StatusTagConfiguration> configuration;
  private final Map<String, Long> foreignNoticeAt = new ConcurrentHashMap<>();
  private final Map<UUID, Task> pending = new ConcurrentHashMap<>();
  private final Map<Notification, Long> ownNotifications = new ConcurrentHashMap<>();

  public FriendStatusToastListener(Supplier<StatusTagConfiguration> configuration) {
    this.configuration = configuration;
  }

  @Subscribe
  public void onPushNotification(PushNotificationEvent event) {
    Notification notification = event.notification();
    if (this.ownNotifications.remove(notification) != null) {
      return;
    }

    String title = plainText(notification.title());
    if (title == null || title.isBlank()) {
      return;
    }

    this.pruneOldNotices();
    this.foreignNoticeAt.put(title.toLowerCase(Locale.ROOT), System.currentTimeMillis());
  }

  @Subscribe
  public void onFriendStatus(LabyConnectFriendStatusEvent event) {
    StatusTagConfiguration config = this.configuration.get();
    if (config == null || !config.enabled().get() || !config.notifications().enabled().get()) {
      return;
    }

    if (event.getStatus() == event.getPreviousStatus()) {
      return;
    }

    Friend friend = event.friend();
    if (friend == null || friend.getName() == null) {
      return;
    }

    StatusKind kind = StatusKind.fromUserStatus(event.getStatus());
    if (kind == StatusKind.UNKNOWN) {
      return;
    }

    boolean cameOnline = !event.wasOnline() && event.isOnline();
    boolean wentOffline = event.wasOnline() && !event.isOnline();
    boolean nativeCovered = cameOnline || wentOffline;

    if (!nativeCovered) {
      if (config.notifications().statusChange().get()) {
        this.cancelPending(friend.getUniqueId());
        this.push(friend, kind);
      }
      return;
    }

    if (cameOnline && !config.notifications().friendOnline().get()) {
      return;
    }
    if (wentOffline && !config.notifications().friendOffline().get()) {
      return;
    }

    UUID uniqueId = friend.getUniqueId();
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
    Notification notification = Notification.builder()
        .title(Component.text(friend.getName()))
        .text(Component.translatable(
                "statustag.notify." + kind.name().toLowerCase(Locale.ROOT))
            .color(color(kind)))
        .icon(Icon.head(friend.getUniqueId()))
        .type(Notification.Type.SOCIAL)

        .build();

    this.ownNotifications.put(notification, System.currentTimeMillis());
    Laby.labyAPI().notificationController().push(notification);
  }

  private static TextColor color(@NotNull StatusKind kind) {
    return kind.defaultColor();
  }

  private void pruneOldNotices() {
    long cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS;
    this.foreignNoticeAt.values().removeIf(at -> at < cutoff);
    this.ownNotifications.values().removeIf(at -> at < cutoff);
  }

  private static @Nullable String plainText(@Nullable Component component) {
    if (component == null) {
      return null;
    }

    try {
      return PlainTextComponentSerializer.plainText().serialize(component);
    } catch (Exception unsupported) {

      return null;
    }
  }
}
