package net.statustag.core.service;

import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.labyconnect.protocol.model.UserStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum StatusKind {

  ONLINE(0x55FF55),
  AWAY(0xFFAA00),
  BUSY(0xFF5555),
  OFFLINE(0xAAAAAA),
  INVISIBLE(0xAAAAAA),
  PROFILE_HIDDEN(0x555555),
  NO_LABYMOD(0x555555),
  UNKNOWN(0xAAAAAA);

  private final int defaultRgb;

  StatusKind(int defaultRgb) {
    this.defaultRgb = defaultRgb;
  }

  public int defaultRgb() {
    return this.defaultRgb;
  }

  public TextColor defaultColor() {
    return TextColor.color(this.defaultRgb);
  }

  public static @NotNull StatusKind fromUserStatus(@Nullable UserStatus status) {
    if (status == null) {
      return UNKNOWN;
    }

    return switch (status) {
      case ONLINE -> ONLINE;
      case AWAY -> AWAY;
      case BUSY -> BUSY;
      case OFFLINE -> OFFLINE;
    };
  }

  public static @Nullable StatusKind parse(@Nullable String name) {
    if (name == null || name.isBlank()) {
      return null;
    }

    try {
      return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
