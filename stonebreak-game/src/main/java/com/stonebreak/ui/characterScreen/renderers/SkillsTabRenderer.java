package com.stonebreak.ui.characterScreen.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.skills.SkillDefinition;
import com.stonebreak.rpg.skills.SkillRegistry;
import io.github.humbleui.skija.Canvas;

import java.util.List;

/**
 * Renders the Skills sub-tab within the character panel.
 *
 * <p>10 skill rows (no scrolling needed). Hovering a row shows a tooltip strip
 * pinned to the panel bottom. Each row has a [+1 SP] button.
 */
public class SkillsTabRenderer {

  // ─── Row layout ────────────────────────────────────────────────────────────
  private static final float ROW_START_OFFSET_Y = 48f; // offset from py
  private static final float ROW_H              = 24f;
  private static final float ROW_PADDING_X      = 14f;
  private static final float PANEL_INNER_W      = 580f;
  private static final float LEVEL_COL_X        = 300f; // offset from px
  private static final float BTN_X_OFFSET       = 490f; // offset from px
  private static final float BTN_W              = 86f;
  private static final float BTN_H              = 22f;

  // ─── Tooltip strip ─────────────────────────────────────────────────────────
  private static final float TOOLTIP_BOTTOM_PAD = 34f; // offset from py + PANEL_HEIGHT
  private static final int   TOOLTIP_BG         = 0xCC1A1A1A;
  private static final int   TOOLTIP_BORDER      = 0xFF141414;
  private static final float PANEL_HEIGHT        = 480f;

  // ─── State ─────────────────────────────────────────────────────────────────
  private String hoveredSkillId = null;

  /** One invest-SP button per skill. Bounds updated each frame. */
  private final MButton[] investButtons = new MButton[SkillRegistry.ALL.size()];

  public SkillsTabRenderer() {
    for (int i = 0; i < investButtons.length; i++) {
      investButtons[i] = new MButton("+1 SP").fontSize(MStyle.FONT_META);
    }
  }

  // ─────────────────────────────────────────────── Public entry points

  /** Renders the full Skills tab content. */
  public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                     float px, float py, float mx, float my, float scale) {
    drawHeader(canvas, ui, stats, px, py, scale);
    drawSkillRows(canvas, ui, stats, px, py, mx, my, scale);
    drawTooltip(canvas, ui, px, py, scale);
  }

  /** Handles a click; returns true if consumed. */
  public boolean handleClick(float mx, float my, CharacterStats stats) {
    if (stats.getRemainingSkillPoints() <= 0) {
      return false;
    }
    List<SkillDefinition> skills = SkillRegistry.ALL;
    for (int i = 0; i < skills.size() && i < investButtons.length; i++) {
      if (investButtons[i].contains(mx, my)) {
        stats.investSkillPoint(skills.get(i).id());
        return true;
      }
    }
    return false;
  }

  // ─────────────────────────────────────────────── Drawing

  private void drawHeader(Canvas canvas, MasonryUI ui, CharacterStats stats,
                          float px, float py, float scale) {
    float rowPadX = ROW_PADDING_X * scale;
    float innerW  = PANEL_INNER_W * scale;
    MPainter.drawStringWithShadow(canvas, "SP Available: " + stats.getRemainingSkillPoints(),
        px + rowPadX, py + 32f * scale,
        ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    drawEngravedRule(canvas, px + rowPadX, py + 38f * scale, innerW - rowPadX * 2f);
  }

  private void drawSkillRows(Canvas canvas, MasonryUI ui, CharacterStats stats,
                             float px, float py, float mx, float my, float scale) {
    List<SkillDefinition> skills = SkillRegistry.ALL;
    hoveredSkillId = null;
    float rowPadX  = ROW_PADDING_X * scale;
    float innerW   = PANEL_INNER_W * scale;
    float rowH     = ROW_H         * scale;
    float rowStart = ROW_START_OFFSET_Y * scale;
    float levelX   = LEVEL_COL_X   * scale;
    float btnXOff  = BTN_X_OFFSET  * scale;
    float btnW     = BTN_W         * scale;
    float btnH     = BTN_H         * scale;

    for (int i = 0; i < skills.size(); i++) {
      SkillDefinition skill = skills.get(i);
      float rowY = py + rowStart + i * rowH;
      float rowBottom = rowY + rowH;

      boolean hovered = mx >= px + rowPadX
          && mx <= px + rowPadX + innerW - rowPadX * 2f
          && my >= rowY && my < rowBottom;

      if (hovered) {
        hoveredSkillId = skill.id();
        MPainter.fillRoundedRect(canvas, px + rowPadX, rowY,
            innerW - rowPadX * 2f, rowH, 3f, 0x22FFFFFF);
      }

      float textY = rowY + rowH * 0.5f + MStyle.FONT_META * 0.38f * scale;
      MPainter.drawStringWithShadow(canvas, skill.name(),
          px + rowPadX, textY,
          ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

      int level = stats.getSkillLevel(skill.id());
      MPainter.drawStringWithShadow(canvas, "Lvl: " + level,
          px + levelX, textY,
          ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

      boolean canInvest = stats.getRemainingSkillPoints() > 0;
      investButtons[i].bounds(px + btnXOff, rowY + 1f, btnW, btnH);
      investButtons[i].updateHover(mx, my);

      int btnFill = !canInvest ? MStyle.BUTTON_FILL_DIS
          : investButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
          : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, px + btnXOff, rowY + 1f, btnW, btnH,
          MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

      int btnTextColor = canInvest ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
      float btnTextY = rowY + 1f + btnH * 0.5f + MStyle.FONT_META * 0.38f * scale;
      MPainter.drawCenteredStringWithShadow(canvas, "+1 SP",
          px + btnXOff + btnW / 2f, btnTextY,
          ui.fonts().getScaled(MStyle.FONT_META), btnTextColor, MStyle.TEXT_SHADOW);
    }
  }

  private void drawTooltip(Canvas canvas, MasonryUI ui, float px, float py, float scale) {
    if (hoveredSkillId == null) {
      return;
    }
    float rowPadX = ROW_PADDING_X * scale;
    float innerW  = PANEL_INNER_W * scale;
    float panelH  = PANEL_HEIGHT  * scale;
    float tipPad  = TOOLTIP_BOTTOM_PAD * scale;
    SkillRegistry.ALL.stream()
        .filter(s -> s.id().equals(hoveredSkillId))
        .findFirst()
        .ifPresent(skill -> {
          float tooltipY = py + panelH - tipPad;
          float tooltipW = innerW - rowPadX * 2f;
          MPainter.stoneSurface(canvas, px + rowPadX, tooltipY,
              tooltipW, 24f * scale, 3f, TOOLTIP_BG, TOOLTIP_BORDER,
              0x1AFFFFFF, 0x1A000000, 0, 0, 0);
          MPainter.drawCenteredStringWithShadow(canvas, skill.description(),
              px + rowPadX + tooltipW / 2f, tooltipY + 16f * scale,
              ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        });
  }

  // ─────────────────────────────────────────────── Helpers

  private void drawEngravedRule(Canvas canvas, float x, float y, float w) {
    MPainter.fillRect(canvas, x, y,       w, 1f, 0x66000000);
    MPainter.fillRect(canvas, x, y + 2f, w, 1f, 0x33FFFFFF);
  }
}
