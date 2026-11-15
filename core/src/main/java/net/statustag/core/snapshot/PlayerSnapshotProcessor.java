package net.statustag.core.snapshot;

import net.labymod.api.client.entity.Entity;
import net.labymod.api.client.entity.player.Player;
import net.labymod.api.client.render.state.entity.EntitySnapshotProcessor;
import net.labymod.api.client.render.state.entity.EntitySnapshotRegistry;
import net.labymod.api.laby3d.renderer.snapshot.ExtrasWriter;
import net.labymod.api.service.annotation.AutoService;
import net.statustag.api.snapshot.OnlineStatusExtraKeys;
import net.statustag.core.StatusTagAddon;

@AutoService(EntitySnapshotProcessor.class)
public class PlayerSnapshotProcessor extends EntitySnapshotProcessor<Player> {

  public PlayerSnapshotProcessor(EntitySnapshotRegistry registry) {
    super(registry);
  }

  @Override
  public boolean supports(Entity entity) {
    return entity instanceof Player;
  }

  @Override
  public void process(Player player, float partialTicks, ExtrasWriter entityWriter) {
    StatusTagAddon addon = StatusTagAddon.instance();
    if (addon != null && addon.isNameTagActive()) {
      this.registry().captureSnapshot(entityWriter, OnlineStatusExtraKeys.STATUS, player);
    }
  }
}
