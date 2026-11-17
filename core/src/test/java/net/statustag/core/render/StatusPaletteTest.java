package net.statustag.core.render;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class StatusPaletteTest {

  @Test
  void rebuildChangesTheRevision() {
    StatusPalette palette = new StatusPalette(kind -> null);
    int before = palette.revision();
    palette.rebuild();
    assertNotEquals(before, palette.revision());
  }
}
