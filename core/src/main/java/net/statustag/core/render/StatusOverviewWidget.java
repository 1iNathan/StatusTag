package net.statustag.core.render;

import net.labymod.api.client.component.Component;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidgetConfig;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine;
import net.labymod.api.configuration.loader.annotation.IntroducedIn;
import net.statustag.core.StatusTagAddon;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusService;
import net.statustag.core.util.AddonIcon;
import org.jetbrains.annotations.NotNull;

@IntroducedIn(namespace = StatusTagAddon.NAMESPACE, value = "1.1.0")
public final class StatusOverviewWidget extends TextHudWidget<TextHudWidgetConfig> {

  public static final String ID = "status_overview";
  private static final String DOT = "\u25CF ";
  private static final String GAP = "  ";
  private static final int UPDATE_TICKS = 20;
  private static final int[] SAMPLE = {12, 18, 2, 36};

  private final StatusService service;
  private TextLine line;
  private int ticks;
  private int lastOnline = -1;
  private int lastAway = -1;
  private int lastBusy = -1;
  private int lastOther = -1;
  private int lastRevision = -1;
  private boolean sample;

  public StatusOverviewWidget(@NotNull StatusService service) {
    super(ID);
    this.service = service;
    this.setIcon(AddonIcon.get());
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);
    this.line = this.createLine(Component.translatable("statustag.hudWidget." + ID + ".label"), Component.empty());
    this.lastOnline = -1;
    this.refresh(true);
  }

  @Override
  public boolean isVisibleInGame() {
    return !this.service.isSuppressed();
  }

  @Override
  public void onTick(boolean isEditorContext) {
    boolean leavingSample = this.sample && !isEditorContext;
    if (++this.ticks < UPDATE_TICKS && !leavingSample) {
      return;
    }
    this.ticks = 0;
    this.refresh(isEditorContext);
  }

  private void refresh(boolean editor) {
    if (this.line == null) {
      return;
    }

    int online;
    int away;
    int busy;
    int other;
    boolean sample = editor && this.service.tabPlayerCount() == 0;
    this.sample = sample;
    if (sample) {
      online = SAMPLE[0];
      away = SAMPLE[1];
      busy = SAMPLE[2];
      other = SAMPLE[3];
    } else {
      int[] counts = this.service.tabKindCounts();
      online = counts[StatusKind.ONLINE.ordinal()];
      away = counts[StatusKind.AWAY.ordinal()];
      busy = counts[StatusKind.BUSY.ordinal()];
      other = counts[StatusKind.OFFLINE.ordinal()]
          + counts[StatusKind.INVISIBLE.ordinal()]
          + counts[StatusKind.PROFILE_HIDDEN.ordinal()];
    }

    int revision = this.service.palette().revision();
    if (online == this.lastOnline && away == this.lastAway && busy == this.lastBusy && other == this.lastOther
        && revision == this.lastRevision) {
      return;
    }
    this.lastOnline = online;
    this.lastAway = away;
    this.lastBusy = busy;
    this.lastOther = other;
    this.lastRevision = revision;

    StatusPalette palette = this.service.palette();
    Component value = Component.empty()
        .append(Component.text(DOT, palette.textColor(StatusKind.ONLINE)))
        .append(Component.text(online + GAP))
        .append(Component.text(DOT, palette.textColor(StatusKind.AWAY)))
        .append(Component.text(away + GAP))
        .append(Component.text(DOT, palette.textColor(StatusKind.BUSY)))
        .append(Component.text(busy + GAP))
        .append(Component.text(DOT, palette.textColor(StatusKind.OFFLINE)))
        .append(Component.text(String.valueOf(other)));
    this.line.updateAndFlush(value);
  }
}
