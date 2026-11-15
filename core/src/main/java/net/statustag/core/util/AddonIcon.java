package net.statustag.core.util;

import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public final class AddonIcon {

  private static volatile Icon icon;

  private AddonIcon() {
  }

  public static @NotNull Icon get() {
    Icon current = icon;
    if (current == null) {
      current = Icon.texture(ResourceLocation.create("statustag", "textures/icon.png"));
      icon = current;
    }
    return current;
  }
}
