package net.statustag.core.listener;

import java.util.UUID;
import net.labymod.api.client.chat.ChatMessage;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.event.ClickEvent;
import net.labymod.api.client.component.event.HoverEvent;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.chat.ChatReceiveEvent;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.config.ChatConfig;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ChatDotListener {

  private static final String DOT = "\u25CF ";
  private static final String NEW_LINE = "\n";
  private static final String PROFILE_URL = "https://laby.net/@";

  private final StatusTagConfiguration config;
  private final StatusService service;

  public ChatDotListener(@NotNull StatusTagConfiguration config, @NotNull StatusService service) {
    this.config = config;
    this.service = service;
  }

  @Subscribe
  public void onChatReceive(ChatReceiveEvent event) {
    ChatConfig chat = this.config.chat();
    if (!this.config.enabled().get() || !chat.enabled().get() || this.service.isSuppressed()) {
      return;
    }

    UUID sender = this.sender(event.chatMessage(), chat);
    if (sender == null) {
      return;
    }

    StatusKind kind = this.service.kindOf(sender);
    if (kind == StatusKind.UNKNOWN) {
      this.service.requestLookup(sender);
      return;
    }

    StatusPalette palette = this.service.palette();
    if (!palette.isShown(kind)) {
      return;
    }

    TextColor color = palette.textColor(kind);
    Component hover = Component.empty()
        .append(Component.translatable("statustag.status." + kind.key(), color))
        .append(Component.text(NEW_LINE))
        .append(Component.translatable("statustag.chat.openProfile", NamedTextColor.GRAY));
    Component dot = Component.text(DOT, color)
        .hoverEvent(HoverEvent.showText(hover))
        .clickEvent(ClickEvent.openUrl(PROFILE_URL + sender));
    event.setMessage(Component.empty().append(dot).append(event.message()));
  }

  private @Nullable UUID sender(@NotNull ChatMessage message, @NotNull ChatConfig chat) {
    UUID sender = message.getSenderUniqueId();
    if (sender != null || !chat.detectSender().get()) {
      return sender;
    }
    return this.service.findChatSender(message.getOriginalPlainText());
  }
}
