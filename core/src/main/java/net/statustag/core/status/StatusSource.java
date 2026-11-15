package net.statustag.core.status;

import org.jetbrains.annotations.Nullable;

public enum StatusSource {

  SELF,
  FRIEND,
  SYNC,
  API,
  CACHE;

  public boolean isLive() {
    return this == SELF || this == FRIEND;
  }

  public static @Nullable StatusSource parse(@Nullable String name) {
    if (name == null) {
      return null;
    }

    for (StatusSource source : values()) {
      if (source.name().equalsIgnoreCase(name)) {
        return source;
      }
    }
    return "MESH".equalsIgnoreCase(name) ? SYNC : null;
  }
}
