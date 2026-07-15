package net.statustag.core.badge;

import net.statustag.core.StatusTagAddon;
import net.statustag.core.config.TabListConfig;
import net.statustag.core.service.StatusKind;
import net.statustag.core.util.StatusColors;
import net.labymod.api.client.entity.player.badge.renderer.BadgeRenderer;
import net.labymod.api.client.gui.screen.ScreenContext;
import net.labymod.api.client.network.NetworkPlayerInfo;

public class StatusDotTabBadge extends BadgeRenderer {

  private static final float ROW_CENTER_Y = 4.0F;

  private final StatusTagAddon addon;

  public StatusDotTabBadge(StatusTagAddon addon) {
    this.addon = addon;
  }

  @Override
  protected boolean isVisible(NetworkPlayerInfo player) {
    return this.addon.configuration().enabled().get()
        && this.addon.configuration().tabList().enabled().get();
  }

  @Override
  public void render(ScreenContext context, float x, float y, NetworkPlayerInfo player) {
    StatusKind kind = this.addon.statusResolver().resolve(player.profile().getUniqueId());
    TabListConfig config = this.addon.configuration().tabList();

    float badgeWidth = config.badgeWidth().get();
    float dotRadius = config.dotSize().get();
    float centerX = x + badgeWidth / 2.0F + config.dotOffset().get();

    context.canvas().submitCircle(
        centerX,
        y + ROW_CENTER_Y,
        dotRadius,
        StatusColors.toArgb(kind, this.addon)
    );
  }

  @Override
  public int getSize(NetworkPlayerInfo player) {
    if (!this.isVisible(player)) {
      return 0;
    }

    return Math.round(this.addon.configuration().tabList().badgeWidth().get());
  }
}
