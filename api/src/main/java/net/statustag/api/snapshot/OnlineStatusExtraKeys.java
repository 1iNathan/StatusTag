package net.statustag.api.snapshot;

import net.labymod.api.laby3d.renderer.snapshot.ExtraKey;

public final class OnlineStatusExtraKeys {

  public static final ExtraKey<OnlineStatusSnapshot> STATUS = ExtraKey.of(
      "online_status",
      OnlineStatusSnapshot.class
  );

  private OnlineStatusExtraKeys() {
  }
}
