package net.statustag.core.status;

import java.util.Locale;
import net.labymod.api.Laby;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.labyconnect.configuration.LabyConnectConfigAccessor;
import net.labymod.api.labyconnect.protocol.model.UserStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class OwnStatus {

  private OwnStatus() {
  }

  public static @Nullable UserStatus parse(@Nullable String name) {
    if (name == null) {
      return null;
    }

    return switch (name.toLowerCase(Locale.ROOT)) {
      case "online" -> UserStatus.ONLINE;
      case "away", "afk" -> UserStatus.AWAY;
      case "busy", "dnd" -> UserStatus.BUSY;
      case "offline", "invisible" -> UserStatus.OFFLINE;
      default -> null;
    };
  }

  public static @NotNull UserStatus next(@Nullable UserStatus current) {
    if (current == null) {
      return UserStatus.ONLINE;
    }

    return switch (current) {
      case ONLINE -> UserStatus.AWAY;
      case AWAY -> UserStatus.BUSY;
      case BUSY, OFFLINE -> UserStatus.ONLINE;
    };
  }

  public static @Nullable UserStatus current() {
    ConfigProperty<UserStatus> property = property();
    return property == null ? null : property.get();
  }

  public static void set(@NotNull UserStatus status) {
    ConfigProperty<UserStatus> property = property();
    if (property != null) {
      property.set(status);
    }
  }

  private static @Nullable ConfigProperty<UserStatus> property() {
    if (!Laby.isInitialized()) {
      return null;
    }

    LabyConnectConfigAccessor config = Laby.labyAPI().labyConnect().configProvider().get();
    return config == null ? null : config.onlineStatus();
  }
}
