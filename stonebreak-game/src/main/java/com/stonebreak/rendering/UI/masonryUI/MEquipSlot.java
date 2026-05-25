package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Equipment slot widget — a stone-recessed slot with a slot-type label
 * (e.g. "HEAD", "CHEST") drawn centered below the slot rect.
 *
 * Bounds cover only the slot itself; the label renders below and is purely
 * decorative (not part of the hit area).
 */
public class MEquipSlot extends MWidget {

  private static final int FILL = 0xFF252525;
  private static final int BORDER = 0xFF151515;
  private static final int INSET_SHADOW = 0x55000000;
  private static final int INSET_LIGHT = 0x1AFFFFFF;
  private static final float RADIUS = 3f;
  private static final int HOVER_OVERLAY = 0x33FFFFFF;

  private String slotLabel = "";

  public MEquipSlot slotLabel(String l) { this.slotLabel = l; return this; }

  @Override
  public MEquipSlot bounds(float x, float y, float w, float h) {
    super.bounds(x, y, w, h);
    return this;
  }

  @Override
  public void render(MasonryUI ui) {
    Canvas canvas = ui.canvas();
    if (canvas == null) {
      return;
    }

    MPainter.stoneSurface(canvas, x, y, width, height, RADIUS,
        FILL, BORDER,
        INSET_SHADOW,
        INSET_LIGHT,
        0,
        MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

    if (hovered) {
      MPainter.fillRoundedRect(canvas, x + 1, y + 1, width - 2, height - 2, RADIUS, HOVER_OVERLAY);
    }

    if (!slotLabel.isEmpty()) {
      Font font = ui.fonts().get(MStyle.FONT_META);
      float labelY = y + height + MStyle.FONT_META * 0.9f + 2f;
      MPainter.drawCenteredStringWithShadow(canvas, slotLabel, x + width / 2f, labelY,
          font, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
    }
  }
}
