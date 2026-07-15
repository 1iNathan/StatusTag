package net.statustag.core.config;

import net.labymod.api.client.gui.screen.widget.widgets.input.color.ColorPickerWidget.ColorPickerSetting;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.util.Color;
import net.statustag.core.service.StatusKind;
import org.jetbrains.annotations.NotNull;

public class StatusColorsConfig extends Config {

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> onlineColor = defaultColor(StatusKind.ONLINE);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> awayColor = defaultColor(StatusKind.AWAY);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> busyColor = defaultColor(StatusKind.BUSY);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> offlineColor = defaultColor(StatusKind.OFFLINE);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> invisibleColor = defaultColor(StatusKind.INVISIBLE);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> profileHiddenColor = defaultColor(StatusKind.PROFILE_HIDDEN);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> noLabyModColor = defaultColor(StatusKind.NO_LABYMOD);

  @ColorPickerSetting(chroma = true)
  private final ConfigProperty<Color> unknownColor = defaultColor(StatusKind.UNKNOWN);

  private static ConfigProperty<Color> defaultColor(StatusKind kind) {

    return new ConfigProperty<>(Color.of(kind.defaultRgb(), 255));
  }

  public int colorFor(@NotNull StatusKind kind) {
    ConfigProperty<Color> property = switch (kind) {
      case ONLINE -> this.onlineColor;
      case AWAY -> this.awayColor;
      case BUSY -> this.busyColor;
      case OFFLINE -> this.offlineColor;
      case INVISIBLE -> this.invisibleColor;
      case PROFILE_HIDDEN -> this.profileHiddenColor;
      case NO_LABYMOD -> this.noLabyModColor;
      case UNKNOWN -> this.unknownColor;
    };

    Color color = property.get();
    return color == null ? kind.defaultRgb() : color.get() & 0xFFFFFF;
  }
}
