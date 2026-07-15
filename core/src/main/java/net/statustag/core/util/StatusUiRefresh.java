package net.statustag.core.util;

import net.labymod.api.Laby;
import net.labymod.api.event.client.gui.screen.playerlist.PlayerListUpdateEvent;

public final class StatusUiRefresh {

  private static final long MIN_INTERVAL_MS = 200L;
  private static volatile long lastFiredAt;

  private StatusUiRefresh() {
  }

  public static void refresh() {
    if (!Laby.isInitialized()) {
      return;
    }

    long now = System.currentTimeMillis();
    if (now - lastFiredAt < MIN_INTERVAL_MS) {
      return;
    }
    lastFiredAt = now;

    Laby.fireEventNextTick(new PlayerListUpdateEvent());
  }
}
