package net.statustag.core.sync;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.statustag.core.status.StatusKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface SyncTransport {

  @Nullable UUID selfId();

  @Nullable StatusKind selfStatus();

  boolean hasAudience();

  boolean broadcast(@NotNull String key, @NotNull JsonObject payload);

  boolean send(@NotNull String key, @NotNull UUID[] receivers, @NotNull JsonObject payload);
}
