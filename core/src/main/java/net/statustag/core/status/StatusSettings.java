package net.statustag.core.status;

import org.jetbrains.annotations.Nullable;

public interface StatusSettings {

  boolean isActive();

  boolean isApiEnabled();

  boolean isLabyChatEnabled();

  boolean isSyncEnabled();

  default boolean isServerDisabled(@Nullable String host) {
    return false;
  }
}
