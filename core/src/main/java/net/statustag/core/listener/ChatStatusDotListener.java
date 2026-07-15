package net.statustag.core.listener;

import java.util.UUID;
import net.labymod.api.client.component.Component;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.chat.ChatReceiveEvent;
import net.statustag.core.StatusTagAddon;
import net.statustag.core.service.StatusKind;
import net.statustag.core.util.StatusColors;

public class ChatStatusDotListener {

  private final StatusTagAddon addon;

  public ChatStatusDotListener(StatusTagAddon addon) {
    this.addon = addon;
  }

  @Subscribe
  public void onChatReceive(ChatReceiveEvent event) {
    if (!this.addon.configuration().enabled().get()
        || !this.addon.configuration().chat().enabled().get()) {
      return;
    }

    UUID sender = event.chatMessage().getSenderUniqueId();
    if (sender == null) {
      return;
    }

    StatusKind kind = this.addon.statusResolver().peekKind(sender);
    if (kind == null || kind == StatusKind.UNKNOWN) {
      return;
    }

    event.setMessage(Component.empty()
        .append(Component.text("● ", StatusColors.toTextColor(kind, this.addon)))
        .append(event.message()));
  }
}
