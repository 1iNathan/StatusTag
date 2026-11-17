package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.TextFieldWidget.TextFieldSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.annotation.IntroducedIn;
import net.labymod.api.configuration.loader.annotation.ShowSettingInParent;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingRequires;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.statustag.core.StatusTagAddon;

@SettingRequires("enabled")
public class NotificationsConfig extends Config {

  @ShowSettingInParent
  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(false);

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.0")
  @SwitchSetting
  private final ConfigProperty<Boolean> pinnedOnly = new ConfigProperty<>(true);

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.0")
  @SwitchSetting
  private final ConfigProperty<Boolean> pinnedSound = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> friendOnline = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> friendOffline = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> statusChange = new ConfigProperty<>(true);

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.0")
  @SwitchSetting
  private final ConfigProperty<Boolean> friendJoinedServer = new ConfigProperty<>(true);

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.2")
  @SettingSection("messages")
  @TextFieldSetting(maxLength = 120)
  private final ConfigProperty<String> onlineMessage = new ConfigProperty<>("");

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.2")
  @TextFieldSetting(maxLength = 120)
  private final ConfigProperty<String> offlineMessage = new ConfigProperty<>("");

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.2")
  @TextFieldSetting(maxLength = 120)
  private final ConfigProperty<String> statusMessage = new ConfigProperty<>("");

  @IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.2")
  @TextFieldSetting(maxLength = 120)
  private final ConfigProperty<String> joinedMessage = new ConfigProperty<>("");

  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Boolean> pinnedOnly() {
    return this.pinnedOnly;
  }

  public ConfigProperty<Boolean> pinnedSound() {
    return this.pinnedSound;
  }

  public ConfigProperty<Boolean> friendJoinedServer() {
    return this.friendJoinedServer;
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

  public ConfigProperty<String> onlineMessage() {
    return this.onlineMessage;
  }

  public ConfigProperty<String> offlineMessage() {
    return this.offlineMessage;
  }

  public ConfigProperty<String> statusMessage() {
    return this.statusMessage;
  }

  public ConfigProperty<String> joinedMessage() {
    return this.joinedMessage;
  }
}
