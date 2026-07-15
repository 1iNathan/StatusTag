package net.statustag.core.snapshot;

import net.statustag.api.snapshot.OnlineStatusSnapshot;
import net.statustag.core.StatusTagAddon;
import net.statustag.core.service.StatusKind;
import net.statustag.core.service.StatusResolver;
import net.statustag.core.util.StatusColors;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.laby3d.renderer.snapshot.AbstractLabySnapshot;
import net.labymod.api.laby3d.renderer.snapshot.Extras;

public class DefaultOnlineStatusSnapshot extends AbstractLabySnapshot implements OnlineStatusSnapshot {

  private final boolean enabled;
  private final boolean showOnNameTag;
  private final TextColor statusColor;
  private final int statusArgb;

  public DefaultOnlineStatusSnapshot(
      StatusTagAddon addon,
      StatusResolver resolver,
      java.util.UUID uniqueId,
      Extras extras
  ) {
    super(extras);
    this.enabled = addon.configuration().enabled().get();
    this.showOnNameTag = addon.configuration().nameTag().enabled().get();
    StatusKind kind = resolver.resolve(uniqueId);
    this.statusArgb = StatusColors.toArgb(kind, addon);
    this.statusColor = StatusColors.cachedTextColor(this.statusArgb);
  }

  @Override
  public boolean enabled() {
    return this.enabled && this.showOnNameTag;
  }

  @Override
  public TextColor statusColor() {
    return this.statusColor;
  }

  @Override
  public int statusArgb() {
    return this.statusArgb;
  }
}
