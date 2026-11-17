package net.statustag.core.util;

import net.labymod.api.user.GameUser;
import net.labymod.api.user.GameUserProfile;
import net.labymod.api.user.WhitelistState;
import org.jetbrains.annotations.Nullable;

public final class LabyModUsers {

  private LabyModUsers() {
  }

  public static boolean hasAccount(@Nullable GameUser user) {
    if (user == null) {
      return false;
    }

    if (user.isUsingLabyMod()) {
      return true;
    }

    GameUserProfile profile = user.profile();
    WhitelistState state = profile == null ? null : profile.whitelistState();
    return state != null && state.isWhitelisted();
  }
}
