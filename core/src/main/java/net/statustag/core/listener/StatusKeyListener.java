package net.statustag.core.listener;

import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.input.KeyEvent;
import net.labymod.api.labyconnect.protocol.model.UserStatus;
import net.labymod.api.notification.Notification;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.OwnStatus;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.AddonIcon;
import org.jetbrains.annotations.NotNull;

public final class StatusKeyListener {

  private final StatusTagConfiguration config;
  private final StatusPalette palette;

  public StatusKeyListener(@NotNull StatusTagConfiguration config, @NotNull StatusPalette palette) {
    this.config = config;
    this.palette = palette;
  }

  @Subscribe
  public void onKey(KeyEvent event) {
    if (event.state() != KeyEvent.State.PRESS || !this.config.enabled().get()) {
      return;
    }

    Key key = this.config.cycleStatusKey().get();
    if (key == null || key == Key.NONE || event.key() != key) {
      return;
    }

    if (!Laby.labyAPI().minecraft().isMouseLocked()) {
      return;
    }

    UserStatus next = OwnStatus.next(OwnStatus.current());
    OwnStatus.set(next);

    StatusKind kind = StatusKind.fromUserStatus(next);
    Laby.labyAPI().notificationController().push(Notification.builder()
        .title(Component.text("StatusTag"))
        .text(Component.translatable("statustag.ownStatus.changed",
            Component.translatable("statustag.status." + kind.key(), this.palette.textColor(kind))))
        .icon(AddonIcon.get())
        .type(Notification.Type.SYSTEM)
        .build());
  }
}
