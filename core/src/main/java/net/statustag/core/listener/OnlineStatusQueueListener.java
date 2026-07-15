package net.statustag.core.listener;

import net.statustag.core.service.StatusFetchScheduler;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.lifecycle.GameTickEvent;

public class OnlineStatusQueueListener {

  private final StatusFetchScheduler fetchScheduler;

  public OnlineStatusQueueListener(StatusFetchScheduler fetchScheduler) {
    this.fetchScheduler = fetchScheduler;
  }

  @Subscribe
  public void onGameTick(GameTickEvent event) {
    this.fetchScheduler.tick();
  }
}
