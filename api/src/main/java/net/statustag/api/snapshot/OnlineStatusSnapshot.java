package net.statustag.api.snapshot;

import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.laby3d.renderer.snapshot.LabySnapshot;

public interface OnlineStatusSnapshot extends LabySnapshot {

  boolean enabled();


  TextColor statusColor();

  int statusArgb();
}
