package net.statustag.core.util;

import net.labymod.api.Laby;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.TokenStorage;
import net.labymod.api.labyconnect.protocol.model.User;
import org.jetbrains.annotations.Nullable;

public final class LabyConnectAccess {

  private LabyConnectAccess() {
  }

  public static @Nullable LabyConnectSession session() {
    if (!Laby.isInitialized()) {
      return null;
    }

    LabyConnectSession session = Laby.labyAPI().labyConnect().getSession();
    return session != null && session.isAuthenticated() ? session : null;
  }

  public static @Nullable User self() {
    LabyConnectSession session = session();
    return session == null ? null : session.self();
  }

  public static @Nullable String jwt() {
    LabyConnectSession session = session();
    if (session == null) {
      return null;
    }

    User self = session.self();
    if (self == null) {
      return null;
    }

    TokenStorage.Token token = session.tokenStorage().getToken(TokenStorage.Purpose.JWT, self.getUniqueId());
    return token != null && token.isValid() ? token.getToken() : null;
  }
}
