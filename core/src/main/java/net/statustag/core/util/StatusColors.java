package net.statustag.core.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.statustag.core.StatusTagAddon;
import net.statustag.core.service.StatusKind;
import net.labymod.api.client.component.format.TextColor;

public final class StatusColors {

  private static final Map<Integer, TextColor> TEXT_COLORS = new ConcurrentHashMap<>();

  private StatusColors() {
  }


  public static int toArgb(StatusKind kind, StatusTagAddon addon) {
    StatusKind resolved = kind == null ? StatusKind.UNKNOWN : kind;
    if (addon == null) {
      return 0xFF000000 | resolved.defaultRgb();
    }

    return 0xFF000000 | addon.configuration().statusColors().colorFor(resolved);
  }

  public static TextColor toTextColor(StatusKind kind, StatusTagAddon addon) {
    return cachedTextColor(toArgb(kind, addon));
  }

  public static TextColor cachedTextColor(int argb) {
    return TEXT_COLORS.computeIfAbsent(argb & 0xFFFFFF, TextColor::color);
  }
}
