package net.statustag.core.listener;

import java.util.function.Supplier;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.service.StatusResolver;
import net.labymod.api.Laby;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.lifecycle.GameTickEvent;
import net.labymod.api.user.GameUser;
import net.labymod.api.user.GameUserService;

public class GameUserSyncListener {

  private static final int SYNC_INTERVAL_TICKS = 40;

  private final StatusResolver resolver;
  private final Supplier<StatusTagConfiguration> configuration;
  private int ticksSinceSync;

  public GameUserSyncListener(
      StatusResolver resolver,
      Supplier<StatusTagConfiguration> configuration
  ) {
    this.resolver = resolver;
    this.configuration = configuration;
  }

  @Subscribe
  public void onGameTick(GameTickEvent event) {
    if (!this.configuration.get().isApiEnabled()) {
      return;
    }

    this.ticksSinceSync++;
    if (this.ticksSinceSync < SYNC_INTERVAL_TICKS) {
      return;
    }

    this.ticksSinceSync = 0;
    if (!Laby.isInitialized() || !Laby.labyAPI().minecraft().isIngame()) {
      return;
    }

    GameUserService gameUserService = Laby.labyAPI().gameUserService();
    for (GameUser gameUser : gameUserService.getGameUsers().values()) {
      if (gameUser == null) {
        continue;
      }

      if (gameUser.isUsingLabyMod()) {
        this.resolver.markLabyModUser(gameUser.getUniqueId());
      }

      if (this.resolver.peekKind(gameUser.getUniqueId()) == null) {
        this.resolver.scheduleFetchIfNeeded(gameUser.getUniqueId(), false);
      }
    }
  }
}
