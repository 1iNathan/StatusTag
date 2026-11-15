package net.statustag.core.net;

import java.util.UUID;
import net.statustag.core.status.StatusKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface FetchHost {

  boolean lookupsEnabled();

  @Nullable String jwt();

  boolean needsLookup(@NotNull UUID uniqueId, long now);

  boolean isOnTab(@NotNull UUID uniqueId);

  boolean isKnownLabyModUser(@NotNull UUID uniqueId);

  void applyLookup(@NotNull UUID uniqueId, @NotNull StatusKind kind, long now);

  default void recordLastOnline(@NotNull UUID uniqueId, long lastOnline) {
  }
}
