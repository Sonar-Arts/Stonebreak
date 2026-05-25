package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Horizontal vital bar: label on the left, colored progress fill, and a
 * "value/max" string right-aligned on top of the bar.
 *
 * Reused for HP, MP, and Stamina — callers set the fill color and data.
 */
public class MVitalBar extends MWidget {

  private static final int TRACK_COLOR = 0xFF1E1E1E;

  private String label = "";
  private float value = 0f;
  private float maxValue = 1f;
  private int fillColor = MStyle.SLIDER_FILL;

  public MVitalBar label(String l) { this.label = l; return this; }
  public MVitalBar value(float v) { this.value = v; return this; }
  public MVitalBar max(float m) { this.maxValue = m; return this; }
  public MVitalBar fillColor(int c) { this.fillColor = c; return this; }

  @Override
  public MVitalBar bounds(float x, float y, float w, float h) {
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
    float labelW = MPainter.measureWidth(font, label);
    float baseline = y + height * 0.5f + MStyle.FONT_META * 0.38f;

    MPainter.drawStringWithShadow(canvas, label, x, baseline, font,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    float barX = x + labelW + 6f;
    float barW = width - labelW - 6f;
    float fraction = maxValue > 0f ? Math.min(1f, Math.max(0f, value / maxValue)) : 0f;
    float barH = Math.max(4f, height * 0.45f);
    float barY = y + (height - barH) / 2f;
    float radius = barH / 2f;

    MPainter.fillRoundedRect(canvas, barX, barY, barW, barH, radius, TRACK_COLOR);
    if (fraction > 0f) {
      MPainter.fillRoundedRect(canvas, barX, barY, barW * fraction, barH, radius, fillColor);
    }

    String valText = (int) value + "/" + (int) maxValue;
    float valW = MPainter.measureWidth(font, valText);
    MPainter.drawStringWithShadow(canvas, valText, barX + barW - valW - 2f, baseline, font,
        MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
  }
}
