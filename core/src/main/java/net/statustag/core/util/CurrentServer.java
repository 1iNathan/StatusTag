package net.statustag.core.util;

import net.labymod.api.Laby;
import net.labymod.api.client.network.server.ServerAddress;
import net.labymod.api.client.network.server.ServerData;
import org.jetbrains.annotations.Nullable;

public final class CurrentServer {

  private CurrentServer() {
  }

  public static @Nullable String host() {
    if (!Laby.isInitialized()) {
      return null;
    }

    ServerData data = Laby.labyAPI().serverController().getCurrentServerData();
    if (data == null) {
      return null;
    }

    ServerAddress address = data.address();
    return address == null ? null : address.getHost();
  }
}
