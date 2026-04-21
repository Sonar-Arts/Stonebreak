package com.stonebreak.ui.characterScreen.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.Rect;

import java.util.List;

/**
 * Renders the Feats sub-tab within the character panel.
 *
 * <p>Filter bar at top (level + general toggle). Left: scrollable feat list sorted alphabetically.
 * Right: detail panel for the selected feat with Acquire button.
 */
public class FeatsTabRenderer {

  // ─── Filter bar ────────────────────────────────────────────────────────────
  private static final float FILTER_BAR_H      = 32f;
  private static final float FILTER_BAR_OFFSET_Y = 18f; // from py
  private static final int   FILTER_BAR_BG     = 0xBF3C3C3C;
  private static final float FILTER_PAD_X      = 10f;

  // ─── Feat list ─────────────────────────────────────────────────────────────
  private static final float LIST_X_PAD        = 10f;
  private static final float LIST_W            = 370f;
  private static final float LIST_TOP_OFFSET_Y = 58f; // from py
  private static final float LIST_H            = 414f;
  private static final float ROW_H             = 24f;
  private static final int   GENERAL_TAG_COLOR = 0xFFAACC66;

  // ─── Detail panel ──────────────────────────────────────────────────────────
  private static final float DETAIL_X_PAD      = 396f; // from px
  private static final float DETAIL_W          = 194f;
  private static final float ACQUIRE_BTN_W     = 140f;
  private static final float ACQUIRE_BTN_H     = 26f;
  private static final float ACQUIRE_BTN_Y_PAD = 430f; // from py

  // ─── State ─────────────────────────────────────────────────────────────────
  private int     filterLevel   = 0;     // 0 = All, 1-10 = specific level
  private boolean filterGeneral = false;
  private String  selectedFeatId = null;
  private float   scrollOffset   = 0f;

  // Filter buttons
  private final MButton levelFilterBtn   = new MButton("Level: All").fontSize(MStyle.FONT_META);
  private final MButton generalFilterBtn = new MButton("General").fontSize(MStyle.FONT_META);
  // Acquire button (shared, updated each frame for the selected feat)
  private final MButton acquireBtn       = new MButton("").fontSize(MStyle.FONT_META);

  // ─────────────────────────────────────────────── Public entry points

  /** Renders the full Feats tab content. */
  public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                     float px, float py, float mx, float my) {
    drawFilterBar(canvas, ui, px, py, mx, my);
    List<FeatDefinition> filtered = FeatRegistry.filter(filterLevel, filterGeneral);
    drawFeatList(canvas, ui, stats, filtered, px, py, mx, my);
    drawDetailPanel(canvas, ui, stats, px, py, mx, my);
  }

  /** Handles a click; returns true if consumed. */
  public boolean handleClick(float mx, float my, CharacterStats stats, float px, float py) {
    if (levelFilterBtn.contains(mx, my)) {
      filterLevel = (filterLevel + 1) % 11;
      levelFilterBtn.setText(filterLevel == 0 ? "Level: All" : "Level: " + filterLevel);
      selectedFeatId = null;
      scrollOffset = 0f;
      return true;
    }
    if (generalFilterBtn.contains(mx, my)) {
      filterGeneral = !filterGeneral;
      selectedFeatId = null;
      scrollOffset = 0f;
      return true;
    }
    if (acquireBtn.contains(mx, my) && selectedFeatId != null) {
      stats.acquireFeat(selectedFeatId);
      return true;
    }
    // Check feat list row clicks
    List<FeatDefinition> filtered = FeatRegistry.filter(filterLevel, filterGeneral);
    return handleListClick(mx, my, filtered, px, py);
  }

  /** Handles a right-click; returns true if consumed. */
  public boolean handleRightClick(float mx, float my, CharacterStats stats, float px, float py) {
    if (levelFilterBtn.contains(mx, my)) {
      filterLevel = (filterLevel - 1 + 11) % 11;
      levelFilterBtn.setText(filterLevel == 0 ? "Level: All" : "Level: " + filterLevel);
      selectedFeatId = null;
      scrollOffset = 0f;
      return true;
    }
    return false;
  }

  /** Scrolls the feat list. */
  public void handleScroll(float deltaY, float px, float py) {
    List<FeatDefinition> filtered = FeatRegistry.filter(filterLevel, filterGeneral);
    float totalH = filtered.size() * ROW_H;
    float maxScroll = Math.max(0f, totalH - LIST_H);
    scrollOffset = Math.clamp(scrollOffset + deltaY * 20f, 0f, maxScroll);
  }

  // ─────────────────────────────────────────────── Filter bar

  private void drawFilterBar(Canvas canvas, MasonryUI ui, float px, float py,
                             float mx, float my) {
    float barX = px + FILTER_PAD_X;
    float barY = py + FILTER_BAR_OFFSET_Y;
    float barW = 580f;

    MPainter.stoneSurface(canvas, barX, barY, barW, FILTER_BAR_H,
        MStyle.PANEL_RADIUS, FILTER_BAR_BG, MStyle.PANEL_BORDER,
        MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, 0,
        MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

    float btnY = barY + (FILTER_BAR_H - 22f) / 2f;

    // Level filter button
    levelFilterBtn.bounds(barX + 6f, btnY, 100f, 22f);
    levelFilterBtn.updateHover(mx, my);
    int lvlFill = levelFilterBtn.isHovered() ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
    MPainter.stoneSurface(canvas, barX + 6f, btnY, 100f, 22f,
        MStyle.BUTTON_RADIUS, lvlFill, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    String lvlLabel = filterLevel == 0 ? "Level: All" : "Level: " + filterLevel;
    MPainter.drawCenteredStringWithShadow(canvas, lvlLabel,
        barX + 6f + 50f, btnY + 22f * 0.5f + MStyle.FONT_META * 0.38f,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

    // General filter toggle
    generalFilterBtn.bounds(barX + 114f, btnY, 84f, 22f);
    generalFilterBtn.updateHover(mx, my);
    int genFill = filterGeneral ? MStyle.BUTTON_FILL_HI
        : generalFilterBtn.isHovered() ? MStyle.BUTTON_FILL_HI
        : MStyle.BUTTON_FILL;
    MPainter.stoneSurface(canvas, barX + 114f, btnY, 84f, 22f,
        MStyle.BUTTON_RADIUS, genFill, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    int genTextColor = filterGeneral ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
    MPainter.drawCenteredStringWithShadow(canvas, "General",
        barX + 114f + 42f, btnY + 22f * 0.5f + MStyle.FONT_META * 0.38f,
        ui.fonts().get(MStyle.FONT_META), genTextColor, MStyle.TEXT_SHADOW);

    // Ability Score pill (disabled/not implemented)
    MPainter.stoneSurface(canvas, barX + 206f, btnY, 148f, 22f,
        MStyle.BUTTON_RADIUS, MStyle.BUTTON_FILL_DIS, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    MPainter.drawCenteredStringWithShadow(canvas, "Ability Score: N/A",
        barX + 206f + 74f, btnY + 22f * 0.5f + MStyle.FONT_META * 0.38f,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
  }

  // ─────────────────────────────────────────────── Feat list

  private void drawFeatList(Canvas canvas, MasonryUI ui, CharacterStats stats,
                            List<FeatDefinition> feats, float px, float py,
                            float mx, float my) {
    float listX = px + LIST_X_PAD;
    float listY = py + LIST_TOP_OFFSET_Y;
    float totalH = feats.size() * ROW_H;

    canvas.save();
    canvas.clipRect(Rect.makeXYWH(listX, listY, LIST_W, LIST_H));

    for (int i = 0; i < feats.size(); i++) {
      FeatDefinition feat = feats.get(i);
      float rowY = listY + i * ROW_H - scrollOffset;
      boolean selected = feat.id().equals(selectedFeatId);
      boolean hovered = mx >= listX && mx <= listX + LIST_W - 8f
          && my >= rowY && my < rowY + ROW_H;

      if (selected) {
        MPainter.fillRoundedRect(canvas, listX, rowY, LIST_W - 8f, ROW_H, 2f, 0x33FFCC55);
      } else if (hovered) {
        MPainter.fillRoundedRect(canvas, listX, rowY, LIST_W - 8f, ROW_H, 2f, 0x22FFFFFF);
      }

      float textY = rowY + ROW_H * 0.5f + MStyle.FONT_META * 0.38f;
      int nameColor = selected ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
      MPainter.drawStringWithShadow(canvas, feat.name(),
          listX + 4f, textY,
          ui.fonts().get(MStyle.FONT_META), nameColor, MStyle.TEXT_SHADOW);

      String levelStr = "(Lvl " + feat.level() + ")";
      MPainter.drawStringWithShadow(canvas, levelStr,
          listX + 224f, textY,
          ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

      if (feat.isGeneral()) {
        MPainter.drawStringWithShadow(canvas, "(General)",
            listX + 282f, textY,
            ui.fonts().get(MStyle.FONT_META), GENERAL_TAG_COLOR, MStyle.TEXT_SHADOW);
      }
    }

    canvas.restore();

    // Vertical divider between list and detail panel
    drawEngravedRule(canvas, px + LIST_X_PAD + LIST_W + 4f, py + LIST_TOP_OFFSET_Y,
        1f, LIST_H);

    drawScrollbar(canvas, listX + LIST_W - 6f, listY, 6f, LIST_H,
        scrollOffset, totalH);
  }

  // ─────────────────────────────────────────────── Detail panel

  private void drawDetailPanel(Canvas canvas, MasonryUI ui, CharacterStats stats,
                               float px, float py, float mx, float my) {
    float detailX = px + DETAIL_X_PAD;

    if (selectedFeatId == null) {
      float centerY = py + LIST_TOP_OFFSET_Y + LIST_H / 2f;
      MPainter.drawCenteredStringWithShadow(canvas, "Select a feat",
          detailX + DETAIL_W / 2f, centerY,
          ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
      return;
    }

    FeatRegistry.ALL.stream()
        .filter(f -> f.id().equals(selectedFeatId))
        .findFirst()
        .ifPresent(feat -> drawFeatDetails(canvas, ui, stats, feat, detailX, py, mx, my));
  }

  private void drawFeatDetails(Canvas canvas, MasonryUI ui, CharacterStats stats,
                               FeatDefinition feat, float detailX, float py,
                               float mx, float my) {
    float y = py + LIST_TOP_OFFSET_Y + 12f;

    // Feat name
    MPainter.drawStringWithShadow(canvas, feat.name(),
        detailX + 4f, y,
        ui.fonts().get(MStyle.FONT_ITEM), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    y += 24f;

    // Type
    String typeStr = "Type: " + (feat.isGeneral() ? "General Feat" : "Feat");
    MPainter.drawStringWithShadow(canvas, typeStr,
        detailX + 4f, y,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    y += 18f;

    // Level
    MPainter.drawStringWithShadow(canvas, "Required Level: " + feat.level(),
        detailX + 4f, y,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    y += 18f;

    drawEngravedRule(canvas, detailX + 4f, y, DETAIL_W - 8f);
    y += 12f;

    // Description
    MPainter.drawStringWithShadow(canvas, feat.description(),
        detailX + 4f, y,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

    // Acquire button (pinned to bottom)
    boolean acquired = stats.hasFeat(feat.id());
    boolean canAfford = stats.getRemainingFeatPoints() > 0;
    float btnX = detailX + (DETAIL_W - ACQUIRE_BTN_W) / 2f;
    float btnY = py + ACQUIRE_BTN_Y_PAD;

    acquireBtn.bounds(btnX, btnY, ACQUIRE_BTN_W, ACQUIRE_BTN_H);
    acquireBtn.updateHover(mx, my);

    int btnFill;
    int btnTextColor;
    String btnLabel;

    if (acquired) {
      btnFill = MStyle.BUTTON_FILL_DIS;
      btnTextColor = MStyle.TEXT_DISABLED;
      btnLabel = "Acquired";
    } else if (!canAfford) {
      btnFill = MStyle.BUTTON_FILL_DIS;
      btnTextColor = MStyle.TEXT_ERROR;
      btnLabel = "Acquire? (1 FP)";
    } else {
      btnFill = acquireBtn.isHovered() ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
      btnTextColor = MStyle.TEXT_PRIMARY;
      btnLabel = "Acquire? (1 FP)";
    }

    MPainter.stoneSurface(canvas, btnX, btnY, ACQUIRE_BTN_W, ACQUIRE_BTN_H,
        MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    float btnTextY = btnY + ACQUIRE_BTN_H * 0.5f + MStyle.FONT_META * 0.38f;
    MPainter.drawCenteredStringWithShadow(canvas, btnLabel,
        btnX + ACQUIRE_BTN_W / 2f, btnTextY,
        ui.fonts().get(MStyle.FONT_META), btnTextColor, MStyle.TEXT_SHADOW);
  }

  // ─────────────────────────────────────────────── Click handling

  private boolean handleListClick(float mx, float my, List<FeatDefinition> feats,
                                  float px, float py) {
    float listX = px + LIST_X_PAD;
    float listY = py + LIST_TOP_OFFSET_Y;

    if (mx < listX || mx > listX + LIST_W - 8f
        || my < listY || my > listY + LIST_H) {
      return false;
    }

    float relY = my - listY + scrollOffset;
    int idx = (int) (relY / ROW_H);
    if (idx >= 0 && idx < feats.size()) {
      selectedFeatId = feats.get(idx).id();
      return true;
    }
    return false;
  }

  // ─────────────────────────────────────────────── Helpers

  /** Draws a 1px wide vertical rule using the engraved (dark + light) style. */
  private void drawEngravedRule(Canvas canvas, float x, float y, float w, float h) {
    // Vertical: dark on left, light on right, 1px each
    MPainter.fillRect(canvas, x,       y, 1f, h, 0x55000000);
    MPainter.fillRect(canvas, x + 2f, y, 1f, h, 0x22FFFFFF);
  }

  private void drawEngravedRule(Canvas canvas, float x, float y, float w) {
    MPainter.fillRect(canvas, x, y,       w, 1f, 0x66000000);
    MPainter.fillRect(canvas, x, y + 2f, w, 1f, 0x33FFFFFF);
  }

  private void drawScrollbar(Canvas canvas, float x, float y, float w, float viewH,
                             float offset, float contentH) {
    if (contentH <= viewH) {
      return;
    }
    MPainter.fillRoundedRect(canvas, x, y, w, viewH, 3f, MStyle.SCROLLBAR_TRACK);
    float thumbH = Math.max(20f, viewH * viewH / contentH);
    float thumbY = y + (offset / contentH) * viewH;
    MPainter.fillRoundedRect(canvas, x, thumbY, w, thumbH, 3f, MStyle.SCROLLBAR_THUMB);
  }
}
