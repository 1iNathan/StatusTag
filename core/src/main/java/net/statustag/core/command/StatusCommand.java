package net.statustag.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.labymod.api.client.chat.command.Command;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.event.ClickEvent;
import net.labymod.api.client.component.event.HoverEvent;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.protocol.model.UserStatus;
import net.labymod.api.labyconnect.protocol.model.friend.Friend;
import net.labymod.api.labyconnect.protocol.model.friend.ServerInfo;
import net.statustag.core.status.OwnStatus;
import net.statustag.core.status.StatusEntry;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusPolicy;
import net.statustag.core.status.StatusService;
import net.statustag.core.sync.StatusSync;
import net.statustag.core.util.LabyConnectAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusCommand extends Command {

  private static final String DOT = "\u25CF";
  private static final String STAR = "\u2605 ";
  private static final String PROFILE_URL = "https://laby.net/@";
  private static final String FRIENDS = "friends";
  private static final int FRIEND_LIMIT = 15;
  private static final String[] WORDS = {FRIENDS, "online", "away", "busy", "offline"};
  private static final Comparator<Friend> FRIEND_ORDER = (first, second) -> {
    if (first.isPinned() != second.isPinned()) {
      return first.isPinned() ? -1 : 1;
    }

    int byStatus = Integer.compare(kindOf(first).ordinal(), kindOf(second).ordinal());
    return byStatus != 0 ? byStatus : String.CASE_INSENSITIVE_ORDER.compare(nameOf(first), nameOf(second));
  };

  private final StatusService service;
  private final StatusSync sync;

  public StatusCommand(@NotNull StatusService service, @NotNull StatusSync sync) {
    super("statustag", "stag");
    this.service = service;
    this.sync = sync;
  }

  @Override
  public boolean execute(String prefix, String[] arguments) {
    if (arguments.length == 0) {
      this.showSummary();
      return true;
    }

    if (FRIENDS.equalsIgnoreCase(arguments[0])) {
      this.showFriends();
      return true;
    }

    UserStatus status = OwnStatus.parse(arguments[0]);
    if (status != null) {
      this.setOwnStatus(status);
    } else {
      this.showPlayer(arguments[0]);
    }
    return true;
  }

  @Override
  public List<String> complete(String[] arguments) {
    if (arguments.length != 1) {
      return Collections.emptyList();
    }

    String lower = arguments[0].toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String word : WORDS) {
      if (word.startsWith(lower)) {
        matches.add(word);
      }
    }
    matches.addAll(this.service.completeTabName(arguments[0]));
    return matches;
  }

  private void setOwnStatus(@NotNull UserStatus status) {
    OwnStatus.set(status);
    StatusKind kind = StatusKind.fromUserStatus(status);
    this.displayMessage(Component.translatable("statustag.ownStatus.changed", NamedTextColor.GRAY, this.label(kind)));
  }

  private void showSummary() {
    int[] counts = this.service.tabKindCounts();
    int total = this.service.tabPlayerCount();
    int known = total - counts[StatusKind.UNKNOWN.ordinal()];

    this.displayMessage(Component.translatable(
        "statustag.command.summary",
        NamedTextColor.GRAY,
        number(total),
        number(known),
        number(this.sync.peerCount()),
        number(this.service.scheduler().size())
    ));

    if (total == 0) {
      return;
    }

    Component breakdown = Component.empty();
    for (int ordinal = 0; ordinal < counts.length; ordinal++) {
      if (counts[ordinal] == 0) {
        continue;
      }

      StatusKind kind = StatusKind.byOrdinal(ordinal);
      breakdown = breakdown.append(this.dot(kind))
          .append(Component.text(" "))
          .append(this.label(kind))
          .append(Component.text(" " + counts[ordinal] + "   ", NamedTextColor.GRAY));
    }
    this.displayMessage(breakdown);
  }

  private void showPlayer(@NotNull String name) {
    UUID uniqueId = this.service.findTabPlayer(name);
    if (uniqueId == null) {
      this.displayMessage(Component.translatable("statustag.command.notFound", NamedTextColor.RED, Component.text(name)));
      return;
    }

    StatusEntry entry = this.service.entry(uniqueId);
    StatusKind kind = entry == null ? StatusKind.UNKNOWN : entry.kind();
    String sourceKey = entry == null ? "none" : entry.source().name().toLowerCase(Locale.ROOT);

    long now = System.currentTimeMillis();
    Component details = Component.translatable("statustag.command.source." + sourceKey, NamedTextColor.GRAY);
    if (entry != null && entry.updatedAt() > 0L) {
      details = Component.empty()
          .append(details)
          .append(Component.text(", ", NamedTextColor.GRAY))
          .append(age(now - entry.updatedAt()));
    }

    Long lastOnline = this.service.lastOnline(uniqueId);
    if (lastOnline == null) {
      lastOnline = friendLastOnline(uniqueId);
    }
    if (lastOnline != null && (kind == StatusKind.OFFLINE || kind == StatusKind.INVISIBLE)) {
      details = Component.empty()
          .append(details)
          .append(Component.text(", ", NamedTextColor.GRAY))
          .append(Component.translatable("statustag.command.lastOnline", NamedTextColor.GRAY, age(now - lastOnline)));
    }

    this.displayMessage(Component.empty()
        .append(this.dot(kind))
        .append(Component.text(" " + name + " ", NamedTextColor.WHITE))
        .append(this.label(kind))
        .append(Component.text(" (", NamedTextColor.GRAY))
        .append(details)
        .append(Component.text(")", NamedTextColor.GRAY)));
  }

  private void showFriends() {
    LabyConnectSession session = LabyConnectAccess.session();
    if (session == null) {
      this.displayMessage(Component.translatable("statustag.command.friends.notConnected", NamedTextColor.RED));
      return;
    }

    List<Friend> friends = new ArrayList<>(session.getOnlineFriends());
    if (friends.isEmpty()) {
      this.displayMessage(Component.translatable("statustag.command.friends.none", NamedTextColor.GRAY));
      return;
    }

    friends.sort(FRIEND_ORDER);
    this.displayMessage(Component.translatable(
        "statustag.command.friends.header",
        NamedTextColor.GRAY,
        number(friends.size())
    ));

    int shown = Math.min(friends.size(), FRIEND_LIMIT);
    for (int index = 0; index < shown; index++) {
      this.displayMessage(this.friendLine(friends.get(index)));
    }

    if (friends.size() > shown) {
      this.displayMessage(Component.translatable(
          "statustag.command.friends.more",
          NamedTextColor.GRAY,
          number(friends.size() - shown)
      ));
    }
  }

  private @NotNull Component friendLine(@NotNull Friend friend) {
    StatusKind kind = kindOf(friend);
    Component name = Component.text(" " + nameOf(friend) + " ", NamedTextColor.WHITE)
        .hoverEvent(HoverEvent.showText(Component.translatable("statustag.chat.openProfile", NamedTextColor.GRAY)))
        .clickEvent(ClickEvent.openUrl(PROFILE_URL + friend.getUniqueId()));

    Component line = Component.empty();
    if (friend.isPinned()) {
      line = line.append(Component.text(STAR, NamedTextColor.GOLD));
    }
    line = line.append(this.dot(kind)).append(name).append(this.label(kind));

    String server = serverOf(friend);
    if (server != null) {
      line = line.append(Component.text(" - " + server, NamedTextColor.DARK_GRAY));
    }
    return line;
  }

  private static @NotNull StatusKind kindOf(@NotNull Friend friend) {
    return StatusKind.fromUserStatus(friend.userStatus());
  }

  private static @NotNull String nameOf(@NotNull Friend friend) {
    String name = friend.getName();
    return name == null ? String.valueOf(friend.getUniqueId()) : name;
  }

  private static @Nullable String serverOf(@NotNull Friend friend) {
    ServerInfo server = friend.getServer();
    if (server == null || server.isLocalHost()) {
      return null;
    }

    String info = server.getDisplayInfo();
    return info == null || info.isEmpty() ? null : info;
  }

  private static @Nullable Long friendLastOnline(@NotNull UUID uniqueId) {
    LabyConnectSession session = LabyConnectAccess.session();
    Friend friend = session == null ? null : session.getFriend(uniqueId);
    if (friend == null || friend.getLastOnline() <= 0L) {
      return null;
    }
    return friend.getLastOnline();
  }

  private @NotNull Component dot(@NotNull StatusKind kind) {
    return Component.text(DOT, this.service.palette().textColor(kind));
  }

  private @NotNull Component label(@NotNull StatusKind kind) {
    return Component.translatable("statustag.status." + kind.key(), this.service.palette().textColor(kind));
  }

  private static @NotNull Component number(long value) {
    return Component.text(String.valueOf(value), NamedTextColor.WHITE);
  }

  static @NotNull Component age(long elapsedMs) {
    long elapsed = Math.max(0L, elapsedMs);
    if (elapsed < StatusPolicy.MINUTE_MS) {
      return Component.translatable("statustag.command.age.now", NamedTextColor.GRAY);
    }
    if (elapsed < StatusPolicy.HOUR_MS) {
      return Component.translatable("statustag.command.age.minutes", NamedTextColor.GRAY,
          Component.text(String.valueOf(elapsed / StatusPolicy.MINUTE_MS)));
    }
    if (elapsed < StatusPolicy.DAY_MS) {
      return Component.translatable("statustag.command.age.hours", NamedTextColor.GRAY,
          Component.text(String.valueOf(elapsed / StatusPolicy.HOUR_MS)));
    }
    return Component.translatable("statustag.command.age.days", NamedTextColor.GRAY,
        Component.text(String.valueOf(elapsed / StatusPolicy.DAY_MS)));
  }
}
