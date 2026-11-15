package net.statustag.core.render;

import net.labymod.api.Laby;
import net.labymod.api.client.entity.player.tag.tags.AbstractNameTag;
import net.labymod.api.client.render.matrix.Stack;
import net.labymod.api.client.render.state.entity.EntitySnapshot;
import net.labymod.api.laby3d.render.queue.SubmissionCollector;
import net.labymod.api.util.color.format.ColorFormat;
import net.statustag.api.snapshot.OnlineStatusExtraKeys;
import net.statustag.core.config.NameTagConfig;
import org.jetbrains.annotations.NotNull;

public final class StatusDotNameTag extends AbstractNameTag {

  private static final float HEIGHT = 10.0F;
  private static final float DIAMETER = 7.0F;
  private static final float PADDING = 1.5F;
  private static final float WIDTH = DIAMETER + 2.0F * PADDING;
  private static final int ROWS = 16;
  private static final float ROW_HEIGHT = HEIGHT / ROWS;
  private static final int DISCRETE_ALPHA = 0x40;
  private static final int DEFAULT_BACKGROUND = ColorFormat.ARGB32.pack(0F, 0F, 0F, 0.25F);

  private static final float[] ROW_LEFT = new float[ROWS];
  private static final float[] ROW_SPAN = new float[ROWS];
  private static final int FIRST_ROW;
  private static final int LAST_ROW;

  static {
    float radius = DIAMETER * 0.5F;
    float centerX = WIDTH * 0.5F;
    float centerY = HEIGHT * 0.5F;
    int first = -1;
    int last = -1;
    for (int row = 0; row < ROWS; row++) {
      float offset = (row + 0.5F) * ROW_HEIGHT - centerY;
      float halfSquared = radius * radius - offset * offset;
      if (halfSquared <= 0.0F) {
        continue;
      }

      float half = (float) Math.sqrt(halfSquared);
      ROW_LEFT[row] = centerX - half;
      ROW_SPAN[row] = half * 2.0F;
      if (first < 0) {
        first = row;
      }
      last = row;
    }
    FIRST_ROW = first;
    LAST_ROW = last;
  }

  private final NameTagConfig config;
  private int argb;

  public StatusDotNameTag(@NotNull NameTagConfig config) {
    this.config = config;
  }

  @Override
  public float getScale() {
    Float scale = this.config.dotScale().get();
    return scale == null ? 1.0F : scale;
  }

  @Override
  public void begin(EntitySnapshot snapshot) {
    super.begin(snapshot);
    this.argb = !snapshot.isInvisible() && snapshot.has(OnlineStatusExtraKeys.STATUS)
        ? snapshot.get(OnlineStatusExtraKeys.STATUS).statusArgb()
        : 0;
  }

  @Override
  public boolean isVisible() {
    return this.argb != 0;
  }

  @Override
  public float getWidth() {
    return WIDTH;
  }

  @Override
  public float getHeight() {
    return HEIGHT;
  }

  @Override
  public void render(Stack stack, SubmissionCollector collector, EntitySnapshot snapshot) {
    int color = this.argb;
    if (color == 0) {
      return;
    }

    if (this.isDiscrete(snapshot)) {
      color = ColorFormat.ARGB32.withAlpha(color, DISCRETE_ALPHA);
    }

    int light = snapshot.lightCoords();
    int background = Laby.labyAPI().minecraft().options().getBackgroundColorWithOpacity(DEFAULT_BACKGROUND);

    if (FIRST_ROW > 0) {
      collector.submitRectangle(stack, 0.0F, 0.0F, WIDTH, FIRST_ROW * ROW_HEIGHT, background, light);
    }

    for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
      float top = row * ROW_HEIGHT;
      float left = ROW_LEFT[row];
      float right = left + ROW_SPAN[row];
      collector.submitRectangle(stack, 0.0F, top, left, ROW_HEIGHT, background, light);
      collector.submitRectangle(stack, left, top, ROW_SPAN[row], ROW_HEIGHT, color, light);
      collector.submitRectangle(stack, right, top, WIDTH - right, ROW_HEIGHT, background, light);
    }

    int tail = ROWS - 1 - LAST_ROW;
    if (tail > 0) {
      float top = (LAST_ROW + 1) * ROW_HEIGHT;
      collector.submitRectangle(stack, 0.0F, top, WIDTH, tail * ROW_HEIGHT, background, light);
    }
  }
}
