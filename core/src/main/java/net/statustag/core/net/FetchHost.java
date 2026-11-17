package net.statustag.core.net;

import java.util.List;
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

  default void applyUnlisted(@NotNull UUID uniqueId, long now) {
    this.applyLookup(uniqueId, StatusKind.NO_LABYMOD, now);
  }

  default @NotNull List<UUID> dueSoon(long now, int limit) {
    return List.of();
  }

  default void recordLastOnline(@NotNull UUID uniqueId, long lastOnline) {
  }
}
