package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.SliderWidget.SliderSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.annotation.IntroducedIn;
import net.labymod.api.configuration.loader.annotation.ShowSettingInParent;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingRequires;
import net.statustag.core.StatusTagAddon;

@SettingRequires("enabled")
public class NameTagConfig extends Config {

  @ShowSettingInParent
  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.0")
  @SliderSetting(min = 0.5F, max = 2.0F, steps = 0.1F)
  private final ConfigProperty<Float> dotScale = new ConfigProperty<>(1.0F);

  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Float> dotScale() {
    return this.dotScale;
  }
}
