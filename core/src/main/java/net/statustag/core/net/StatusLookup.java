package net.statustag.core.net;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface StatusLookup {

  void lookup(@NotNull UUID uniqueId, @NotNull String jwt, @NotNull Consumer<LookupResult> callback);

  default void lookupBulk(
      @NotNull List<UUID> uniqueIds,
      @NotNull String jwt,
      @NotNull Consumer<BulkLookupResult> callback
  ) {
    callback.accept(BulkLookupResult.unsupported());
  }
}
