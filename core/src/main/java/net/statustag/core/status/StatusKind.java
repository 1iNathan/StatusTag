package net.statustag.core.status;

import java.util.Locale;
import net.labymod.api.labyconnect.protocol.model.UserStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum StatusKind {

  ONLINE(0x55FF55),
  AWAY(0xFFAA00),
  BUSY(0xFF5555),
  OFFLINE(0xAAAAAA),
  INVISIBLE(0xAAAAAA),
  PROFILE_HIDDEN(0xAAAAAA),
  NO_LABYMOD(0x555555),
  UNKNOWN(0x555555);

  private static final StatusKind[] VALUES = values();

  private final int defaultRgb;
  private final String key;

  StatusKind(int defaultRgb) {
    this.defaultRgb = defaultRgb;
    this.key = this.name().toLowerCase(Locale.ROOT);
  }

  public int defaultRgb() {
    return this.defaultRgb;
  }

  public @NotNull String key() {
    return this.key;
  }

  public boolean isLabyPresence() {
    return this == ONLINE || this == AWAY || this == BUSY;
  }

  public static int count() {
    return VALUES.length;
  }

  public static @NotNull StatusKind byOrdinal(int ordinal) {
    return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNKNOWN;
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
    if (name == null) {
      return null;
    }

    for (StatusKind kind : VALUES) {
      if (kind.name().equalsIgnoreCase(name)) {
        return kind;
      }
    }
    return null;
  }
}
