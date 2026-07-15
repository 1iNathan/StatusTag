package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.annotation.ShowSettingInParent;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingRequires;

@SettingRequires("enabled")
public class NotificationsConfig extends Config {

  @ShowSettingInParent
  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> friendOnline = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> friendOffline = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> statusChange = new ConfigProperty<>(true);

  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Boolean> friendOnline() {
    return this.friendOnline;
  }

  public ConfigProperty<Boolean> friendOffline() {
    return this.friendOffline;
  }

  public ConfigProperty<Boolean> statusChange() {
    return this.statusChange;
  }
}
