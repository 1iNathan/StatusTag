package net.statustag.core.sync;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.protocol.model.User;
import net.statustag.core.status.StatusKind;
import net.statustag.core.util.LabyConnectAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LabyConnectTransport implements SyncTransport {

  @Override
  public @Nullable UUID selfId() {
    User self = LabyConnectAccess.self();
    return self == null ? null : self.getUniqueId();
  }

  @Override
  public @Nullable StatusKind selfStatus() {
    User self = LabyConnectAccess.self();
    return self == null ? null : StatusKind.fromUserStatus(self.userStatus());
  }

  @Override
  public boolean broadcast(@NotNull String key, @NotNull JsonObject payload) {
    LabyConnectSession session = LabyConnectAccess.session();
    if (session == null) {
      return false;
    }

    session.sendBroadcastPayload(key, payload);
    return true;
  }

  @Override
  public boolean send(@NotNull String key, @NotNull UUID[] receivers, @NotNull JsonObject payload) {
    LabyConnectSession session = LabyConnectAccess.session();
    if (session == null || receivers.length == 0) {
      return false;
    }

    session.sendCustomPayload(key, receivers, payload.toString().getBytes(StandardCharsets.UTF_8));
    return true;
  }
}
