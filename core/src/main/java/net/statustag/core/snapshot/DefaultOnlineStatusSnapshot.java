package net.statustag.core.snapshot;

import net.labymod.api.laby3d.renderer.snapshot.AbstractLabySnapshot;
import net.labymod.api.laby3d.renderer.snapshot.Extras;
import net.statustag.api.snapshot.OnlineStatusSnapshot;

public final class DefaultOnlineStatusSnapshot extends AbstractLabySnapshot implements OnlineStatusSnapshot {

  private final int statusArgb;

  public DefaultOnlineStatusSnapshot(Extras extras, int statusArgb) {
    super(extras);
    this.statusArgb = statusArgb;
  }

  @Override
  public int statusArgb() {
    return this.statusArgb;
  }
}
