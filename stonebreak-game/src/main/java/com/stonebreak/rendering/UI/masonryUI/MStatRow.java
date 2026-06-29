package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Single stat row: label on the left, right-aligned value string, and an
 * optional 2px colored fill bar along the bottom edge.
 *
 * Use {@link #bar(int, float)} for ability scores that show a fill indicator.
 * Leave bar unset (default barColor=0) for placeholder rows whose value is "—".
 */
public class MStatRow extends MWidget {

  private String label = "";
  private String value = "—"; // em-dash placeholder
  private int barColor = 0;
  private float fraction = 0f;

  public MStatRow label(String l) { this.label = l; return this; }
  public MStatRow value(String v) { this.value = v; return this; }

  /** Draws a thin colored bar at the bottom of the row. fraction is clamped [0,1]. */
  public MStatRow bar(int color, float frac) {
    this.barColor = color;
    this.fraction = Math.min(1f, Math.max(0f, frac));
    return this;
  }

  @Override
  public MStatRow bounds(float x, float y, float w, float h) {
    super.bounds(x, y, w, h);
    return this;
  }

  @Override
  public void render(MasonryUI ui) {
    Canvas canvas = ui.canvas();
    if (canvas == null) {
      return;
    }

    Font font = ui.fonts().get(MStyle.FONT_META);
    float baseline = y + height * 0.5f + MStyle.FONT_META * 0.38f;

    MPainter.drawStringWithShadow(canvas, label, x, baseline, font,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    float valW = MPainter.measureWidth(font, value);
    MPainter.drawStringWithShadow(canvas, value, x + width - valW, baseline, font,
        MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

    if (barColor != 0 && fraction > 0f) {
      float barY = y + height - 2f;
      MPainter.fillRoundedRect(canvas, x, barY, width * fraction, 2f, 1f, barColor);
    }
  }
}
