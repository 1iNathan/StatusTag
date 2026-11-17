package net.statustag.core.render;

import java.util.function.Function;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.util.Color;
import net.statustag.core.status.StatusKind;
import org.jetbrains.annotations.NotNull;

public final class StatusPalette {

  private static final int OPAQUE = 0xFF000000;

  private final Function<StatusKind, Color> colors;
  private final int[] argb = new int[StatusKind.count()];
  private final TextColor[] textColors = new TextColor[StatusKind.count()];
  private final Color[] chroma = new Color[StatusKind.count()];
  private final boolean[] hidden = new boolean[StatusKind.count()];
  private int revision;

  public StatusPalette(@NotNull Function<StatusKind, Color> colors) {
    this.colors = colors;
    this.rebuild();
  }

  public void rebuild() {
    for (int ordinal = 0; ordinal < StatusKind.count(); ordinal++) {
      StatusKind kind = StatusKind.byOrdinal(ordinal);
      Color color = this.colors.apply(kind);
      int rgb = color == null ? kind.defaultRgb() : color.get() & 0xFFFFFF;
      this.argb[ordinal] = OPAQUE | rgb;
      this.textColors[ordinal] = null;
      this.chroma[ordinal] = color != null && color.isChroma() ? color : null;
    }
    this.revision++;
  }

  public int revision() {
    return this.revision;
  }

  public void setShowPlayersWithoutLabyMod(boolean show) {
    this.hidden[StatusKind.NO_LABYMOD.ordinal()] = !show;
    this.hidden[StatusKind.UNKNOWN.ordinal()] = !show;
  }

  public boolean isShown(@NotNull StatusKind kind) {
    return !this.hidden[kind.ordinal()];
  }

  public int argb(@NotNull StatusKind kind) {
    int ordinal = kind.ordinal();
    Color color = this.chroma[ordinal];
    return color == null ? this.argb[ordinal] : OPAQUE | (color.get() & 0xFFFFFF);
  }

  public int shownArgb(@NotNull StatusKind kind) {
    return this.hidden[kind.ordinal()] ? 0 : this.argb(kind);
  }

  public @NotNull TextColor textColor(@NotNull StatusKind kind) {
    int ordinal = kind.ordinal();
    Color color = this.chroma[ordinal];
    if (color != null) {
      return TextColor.color(color.get() & 0xFFFFFF);
    }

    TextColor cached = this.textColors[ordinal];
    if (cached == null) {
      cached = TextColor.color(this.argb[ordinal] & 0xFFFFFF);
      this.textColors[ordinal] = cached;
    }
    return cached;
  }
}
