package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.color.ColorPickerWidget.ColorPickerSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.ColorRowBreak;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.labymod.api.util.Color;
import net.statustag.core.status.StatusKind;
import org.jetbrains.annotations.NotNull;

public class StatusColorsConfig extends Config {

  @SettingSection("labyStatuses")
  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> onlineColor = defaultColor(StatusKind.ONLINE);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> awayColor = defaultColor(StatusKind.AWAY);

  @ColorRowBreak
  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> busyColor = defaultColor(StatusKind.BUSY);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> offlineColor = defaultColor(StatusKind.OFFLINE);

  @SettingSection("otherStatuses")
  @ColorRowBreak
  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> invisibleColor = defaultColor(StatusKind.INVISIBLE);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> profileHiddenColor = defaultColor(StatusKind.PROFILE_HIDDEN);

  @ColorRowBreak
  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> noLabyModColor = defaultColor(StatusKind.NO_LABYMOD);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> unknownColor = defaultColor(StatusKind.UNKNOWN);

  @Override
  public int getConfigVersion() {
    return ConfigMigration.CONFIG_VERSION;
  }

  private static ConfigProperty<Color> defaultColor(StatusKind kind) {
    return new ConfigProperty<>(Color.of(kind.defaultRgb(), 255));
  }

  public @NotNull ConfigProperty<Color> property(@NotNull StatusKind kind) {
    return switch (kind) {
      case ONLINE -> this.onlineColor;
      case AWAY -> this.awayColor;
      case BUSY -> this.busyColor;
      case OFFLINE -> this.offlineColor;
      case INVISIBLE -> this.invisibleColor;
      case PROFILE_HIDDEN -> this.profileHiddenColor;
      case NO_LABYMOD -> this.noLabyModColor;
      case UNKNOWN -> this.unknownColor;
    };
  }
}
