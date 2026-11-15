package net.statustag.core.render;

import net.labymod.api.client.entity.player.badge.renderer.BadgeRenderer;
import net.labymod.api.client.gui.screen.ScreenContext;
import net.labymod.api.client.network.NetworkPlayerInfo;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.config.TabListConfig;
import net.statustag.core.status.StatusService;
import org.jetbrains.annotations.NotNull;

public final class StatusDotTabBadge extends BadgeRenderer {

  private static final float ROW_CENTER_Y = 4.0F;

  private final StatusTagConfiguration config;
  private final StatusService service;

  public StatusDotTabBadge(@NotNull StatusTagConfiguration config, @NotNull StatusService service) {
    this.config = config;
    this.service = service;
  }

  @Override
  protected boolean isVisible(NetworkPlayerInfo player) {
    return this.config.enabled().get() && this.config.tabList().enabled().get() && !this.service.isSuppressed();
  }

  @Override
  public void render(ScreenContext context, float x, float y, NetworkPlayerInfo player) {
    int argb = this.service.shownArgb(player.profile().getUniqueId());
    if (argb == 0) {
      return;
    }

    TabListConfig tabList = this.config.tabList();
    float centerX = x + tabList.badgeWidth().get() * 0.5F + tabList.dotOffset().get();
    context.canvas().submitCircle(centerX, y + ROW_CENTER_Y, tabList.dotSize().get(), argb);
  }

  @Override
  public int getSize(NetworkPlayerInfo player) {
    return this.isVisible(player) ? Math.round(this.config.tabList().badgeWidth().get()) : 0;
  }
}
