package net.statustag.core.tag;

import net.statustag.api.snapshot.OnlineStatusExtraKeys;
import net.statustag.api.snapshot.OnlineStatusSnapshot;
import net.statustag.core.StatusTagAddon;
import net.labymod.api.Laby;
import net.labymod.api.client.entity.player.tag.tags.AbstractNameTag;
import net.labymod.api.client.render.matrix.Stack;
import net.labymod.api.client.render.state.entity.EntitySnapshot;
import net.labymod.api.laby3d.render.queue.SubmissionCollector;
import net.labymod.api.util.color.format.ColorFormat;
import org.jetbrains.annotations.Nullable;

public class StatusDotNameTag extends AbstractNameTag {

  private static final float H_PADDING = 1.5F;

  private static final float PLATE_HEIGHT = 10.0F;
  private static final int CIRCLE_ROWS = 16;
  private static final int DISCRETE_ALPHA = 0x40;

  private static final int DEFAULT_BACKGROUND = ColorFormat.ARGB32.pack(0F, 0F, 0F, 0.25F);

  private final StatusTagAddon addon;

  private @Nullable OnlineStatusSnapshot status;

  public StatusDotNameTag(StatusTagAddon addon) {
    this.addon = addon;
  }

  @Override
  public void begin(EntitySnapshot snapshot) {
    super.begin(snapshot);

    OnlineStatusSnapshot captured = null;
    if (!snapshot.isInvisible() && snapshot.has(OnlineStatusExtraKeys.STATUS)) {
      OnlineStatusSnapshot candidate = snapshot.get(OnlineStatusExtraKeys.STATUS);
      if (candidate.enabled()) {
        captured = candidate;
      }
    }
    this.status = captured;
  }

  @Override
  public boolean isVisible() {
    return this.status != null;
  }

  private float diameter() {

    return PLATE_HEIGHT - 3.0F;
  }

  @Override
  public float getWidth() {
    return this.diameter() + 2.0F * H_PADDING;
  }

  @Override
  public float getHeight() {
    return PLATE_HEIGHT;
  }

  @Override
  public void render(Stack stack, SubmissionCollector collector, EntitySnapshot snapshot) {
    OnlineStatusSnapshot statusSnapshot = this.status;
    if (statusSnapshot == null) {
      return;
    }

    int light = snapshot.lightCoords();
    int background = Laby.labyAPI().minecraft().options()
        .getBackgroundColorWithOpacity(DEFAULT_BACKGROUND);

    int color = statusSnapshot.statusArgb();
    if (this.isDiscrete(snapshot)) {
      color = ColorFormat.ARGB32.withAlpha(color, DISCRETE_ALPHA);
    }

    float width = this.getWidth();
    float height = this.getHeight();
    float radius = this.diameter() / 2.0F;
    float centerX = width / 2.0F;
    float centerY = height / 2.0F;

    float rowHeight = height / CIRCLE_ROWS;
    for (int row = 0; row < CIRCLE_ROWS; row++) {
      float rowY = row * rowHeight;
      float rowMid = rowY + rowHeight / 2.0F - centerY;
      float halfSquared = radius * radius - rowMid * rowMid;

      if (halfSquared <= 0.0F) {

        collector.submitRectangle(stack, 0.0F, rowY, width, rowHeight, background, light);
        continue;
      }

      float halfWidth = (float) Math.sqrt(halfSquared);
      float left = centerX - halfWidth;
      float right = centerX + halfWidth;

      if (left > 0.0F) {
        collector.submitRectangle(stack, 0.0F, rowY, left, rowHeight, background, light);
      }
      collector.submitRectangle(stack, left, rowY, halfWidth * 2.0F, rowHeight, color, light);
      if (right < width) {
        collector.submitRectangle(stack, right, rowY, width - right, rowHeight, background, light);
      }
    }
  }
}
