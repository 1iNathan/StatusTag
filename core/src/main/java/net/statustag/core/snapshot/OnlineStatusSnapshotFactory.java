package net.statustag.core.snapshot;

import net.statustag.api.snapshot.OnlineStatusExtraKeys;
import net.statustag.api.snapshot.OnlineStatusSnapshot;
import net.statustag.core.StatusTagAddon;
import net.labymod.api.client.entity.player.Player;
import net.labymod.api.laby3d.renderer.snapshot.Extras;
import net.labymod.api.laby3d.renderer.snapshot.LabySnapshotFactory;
import net.labymod.api.service.annotation.AutoService;

@AutoService(LabySnapshotFactory.class)
public class OnlineStatusSnapshotFactory extends LabySnapshotFactory<Player, OnlineStatusSnapshot> {

  private final StatusTagAddon addon;

  public OnlineStatusSnapshotFactory(StatusTagAddon addon) {
    super(OnlineStatusExtraKeys.STATUS);
    this.addon = addon;
  }

  @Override
  protected OnlineStatusSnapshot create(Player player, Extras extras) {
    return new DefaultOnlineStatusSnapshot(
        this.addon,
        this.addon.statusResolver(),
        player.getUniqueId(),
        extras
    );
  }
}
