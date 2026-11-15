package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.annotation.IntroducedIn;
import net.labymod.api.configuration.loader.annotation.ShowSettingInParent;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingRequires;
import net.statustag.core.StatusTagAddon;

@SettingRequires("enabled")
public class ChatConfig extends Config {

  @ShowSettingInParent
  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.0")
  @SwitchSetting
  private final ConfigProperty<Boolean> detectSender = new ConfigProperty<>(true);

  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Boolean> detectSender() {
    return this.detectSender;
  }
}
