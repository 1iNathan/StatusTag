package net.statustag.core;

import net.labymod.api.addon.AddonConfig;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget.ButtonSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.configuration.loader.annotation.ConfigName;
import net.labymod.api.configuration.loader.annotation.SpriteSlot;
import net.labymod.api.configuration.loader.annotation.SpriteTexture;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.Setting;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.labymod.api.util.MethodOrder;
import net.statustag.core.config.ChatConfig;
import net.statustag.core.config.NameTagConfig;
import net.statustag.core.config.NotificationsConfig;
import net.statustag.core.config.StatusColorsConfig;
import net.statustag.core.config.TabListConfig;
import org.jetbrains.annotations.NotNull;

@ConfigName("settings")
@SpriteTexture("settings")
public class StatusTagConfiguration extends AddonConfig {

  @SpriteSlot
  @SwitchSetting(hotkey = true)
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SpriteSlot(x = 1)
  private final TabListConfig tabList = new TabListConfig();

  @SpriteSlot(x = 2)
  private final NameTagConfig nameTag = new NameTagConfig();

  @SpriteSlot(x = 3)
  private final ChatConfig chat = new ChatConfig();

  @SpriteSlot(x = 4)
  private final NotificationsConfig notifications = new NotificationsConfig();

  @SettingSection("sources")
  @SpriteSlot(x = 5)
  @SwitchSetting
  private final ConfigProperty<Boolean> useLabyChat = new ConfigProperty<>(true);

  @SpriteSlot(x = 6)
  @SwitchSetting
  private final ConfigProperty<Boolean> useLabyNetApi = new ConfigProperty<>(true);

  @SpriteSlot(x = 7)
  @SwitchSetting
  private final ConfigProperty<Boolean> useMesh = new ConfigProperty<>(true);

  @SettingSection("advanced")
  @SpriteSlot(y = 1)
  private final StatusColorsConfig statusColors = new StatusColorsConfig();

  @SpriteSlot(x = 1, y = 1)
  @SwitchSetting
  private final ConfigProperty<Boolean> debugLogging = new ConfigProperty<>(false);

  @MethodOrder(after = "debugLogging")
  @SpriteSlot(x = 2, y = 1)
  @ButtonSetting
  public void clearCache(Setting setting) {
    StatusTagAddon addon = StatusTagAddon.instance();
    if (addon != null && addon.statusResolver() != null) {
      addon.statusResolver().clearAll();
      if (addon.statusSync() != null) {
        addon.statusSync().requestResync();
      }
    }
  }

  @Override
  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public @NotNull TabListConfig tabList() {
    return this.tabList;
  }

  public @NotNull NameTagConfig nameTag() {
    return this.nameTag;
  }

  public @NotNull ChatConfig chat() {
    return this.chat;
  }

  public @NotNull NotificationsConfig notifications() {
    return this.notifications;
  }

  public ConfigProperty<Boolean> useLabyChat() {
    return this.useLabyChat;
  }

  public ConfigProperty<Boolean> useLabyNetApi() {
    return this.useLabyNetApi;
  }

  public ConfigProperty<Boolean> useMesh() {
    return this.useMesh;
  }

  public @NotNull StatusColorsConfig statusColors() {
    return this.statusColors;
  }

  public ConfigProperty<Boolean> debugLogging() {
    return this.debugLogging;
  }

  public boolean isLabyChatEnabled() {
    return this.enabled.get() && this.useLabyChat.get();
  }

  public boolean isApiEnabled() {
    return this.enabled.get() && this.useLabyNetApi.get();
  }
}
