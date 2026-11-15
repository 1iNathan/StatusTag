package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.SliderWidget.SliderSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.annotation.ShowSettingInParent;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingRequires;

@SettingRequires("enabled")
public class TabListConfig extends Config {

  @ShowSettingInParent
  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SliderSetting(min = 2.0F, max = 5.0F, steps = 0.5F)
  private final ConfigProperty<Float> dotSize = new ConfigProperty<>(3.5F);

  @SliderSetting(min = 8.0F, max = 16.0F, steps = 1.0F)
  private final ConfigProperty<Float> badgeWidth = new ConfigProperty<>(8.0F);

  @SliderSetting(min = -4.0F, max = 4.0F, steps = 0.5F)
  private final ConfigProperty<Float> dotOffset = new ConfigProperty<>(0.0F);

  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Float> dotSize() {
    return this.dotSize;
  }

  public ConfigProperty<Float> badgeWidth() {
    return this.badgeWidth;
  }

  public ConfigProperty<Float> dotOffset() {
    return this.dotOffset;
  }
}
