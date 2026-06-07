package com.stonebreak.ui.characterScreen.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.classes.AbilityIconCache;
import com.stonebreak.rpg.classes.ClassAbility;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders the Classes sub-tab within the character panel.
 *
 * <p>Left sidebar: scrollable list of all 21 classes.
 * Right area: fixed header (class name + description) + dirt-style scrollable abilities list.
 */
public class ClassesTabRenderer {

  // ─── Sidebar layout ────────────────────────────────────────────────────────
  private static final float SIDEBAR_X_PAD   = 10f;
  private static final float SIDEBAR_W        = 175f;
  private static final float CLASS_BTN_W      = 163f;
  private static final float CLASS_BTN_H      = 22f;
  private static final float CLASS_BTN_GAP    = 2f;
  private static final float LIST_CLIP_TOP_PAD = 30f; // below "Class Select" label + rule
  private static final float SIDEBAR_CLIP_H   = 420f;
  private static final float CLASS_ICON_SIZE  = 16f;
  private static final float CLASS_ICON_GAP   = 4f;

  // ─── Content area layout ───────────────────────────────────────────────────
  private static final float CONTENT_X_PAD    = 193f;
  private static final float CONTENT_W        = 391f;
  private static final float ABILITY_ROW_H    = 64f;
  private static final float ABILITY_ICON_SIZE = 32f;
  private static final float ABILITY_ICON_GAP  = 10f;
  private static final float SPEND_BTN_W      = 88f;
  private static final float SPEND_BTN_H      = 22f;

  // ─── Dirt background (earthy, distinct from stone panel) ───────────────────
  private static final int DIRT_FILL        = 0xBF5C4033;
  private static final int DIRT_BORDER      = 0xFF2A1A0A;
  private static final int DIRT_NOISE_DARK  = 0x38000000;
  private static final int DIRT_NOISE_LIGHT = 0x1A7A5A2A;

  // ─── State ─────────────────────────────────────────────────────────────────
  private float leftScrollOffset  = 0f;
  private float rightScrollOffset = 0f;
  private String displayedClassId = null;

  /** Ability-list clip height computed during the last draw; reused for scroll-clamping. */
  private float lastAbilityClipH = 365f;

  /** One spend-CP button per ability slot (max 5). Bounds updated each frame. */
  private final MButton[] spendButtons = new MButton[5];

  public ClassesTabRenderer() {
    for (int i = 0; i < spendButtons.length; i++) {
      spendButtons[i] = new MButton("Spend CP").fontSize(MStyle.FONT_META);
    }
  }

  // ─────────────────────────────────────────────── Public entry points

  /** Renders the full Classes tab content. */
  public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                     float px, float py, float mx, float my, float scale) {
    drawSidebar(canvas, ui, stats, px, py, mx, my, scale);
    drawContentArea(canvas, ui, stats, px, py, mx, my, scale);
  }

  /** Handles a click; returns true if consumed. */
  public boolean handleClick(float mx, float my, CharacterStats stats, float px, float py, float scale) {
    return handleSidebarClick(mx, my, px, py, scale)
        || handleAbilityClick(mx, my, stats);
  }

  /** Scrolls the sidebar (left area) or the ability list (right area). */
  public void handleScroll(float deltaY, float mx, float my, float px, float py, float scale) {
    float sideX = px + SIDEBAR_X_PAD * scale;
    if (mx >= sideX && mx <= sideX + SIDEBAR_W * scale) {
      float totalListH = ClassRegistry.ALL.size() * ((CLASS_BTN_H + CLASS_BTN_GAP) * scale);
      float maxScroll = Math.max(0f, totalListH - SIDEBAR_CLIP_H * scale);
      leftScrollOffset = Math.clamp(leftScrollOffset + deltaY * 20f, 0f, maxScroll);
    } else {
      Optional<PlayerClassDefinition> cls = classOpt();
      if (cls.isPresent()) {
        float totalH = cls.get().abilities().size() * ABILITY_ROW_H * scale;
        float maxScroll = Math.max(0f, totalH - lastAbilityClipH);
        rightScrollOffset = Math.clamp(rightScrollOffset + deltaY * 20f, 0f, maxScroll);
      }
    }
  }

  // ─────────────────────────────────────────────── Sidebar

  private void drawSidebar(Canvas canvas, MasonryUI ui, CharacterStats stats,
                           float px, float py, float mx, float my, float scale) {
    float sideX = px + SIDEBAR_X_PAD * scale;
    float sideY = py + 20f * scale;
    float btnW  = CLASS_BTN_W * scale;
    float btnH  = CLASS_BTN_H * scale;
    float btnG  = CLASS_BTN_GAP * scale;
    float sideW = SIDEBAR_W * scale;
    float clipH = SIDEBAR_CLIP_H * scale;

    MPainter.drawStringWithShadow(canvas, "Class Select",
        sideX + 4f, sideY + 14f * scale,
        ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    drawEngravedRule(canvas, sideX, sideY + 20f * scale, sideW);

    float clipTop = sideY + LIST_CLIP_TOP_PAD * scale;
    List<PlayerClassDefinition> classes = ClassRegistry.ALL;
    float totalListH = classes.size() * (btnH + btnG);

    canvas.save();
    canvas.clipRect(Rect.makeXYWH(sideX, clipTop, sideW, clipH));

    for (int i = 0; i < classes.size(); i++) {
      PlayerClassDefinition cls = classes.get(i);
      float btnY = clipTop + i * (btnH + btnG) - leftScrollOffset;

      boolean selected = cls.id().equals(displayedClassId);
      boolean hovered = mx >= sideX && mx <= sideX + btnW
          && my >= btnY && my <= btnY + btnH;

      int cpSpent = stats.getTotalCpSpentForClass(cls.id());
      boolean hasPoints = cpSpent > 0;

      int fill = selected ? 0xFF7A7A7A
          : hovered ? MStyle.BUTTON_FILL_HI
          : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, sideX + 1f, btnY, btnW, btnH,
          MStyle.BUTTON_RADIUS, fill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

      float textY = btnY + btnH * 0.5f + MStyle.FONT_META * 0.38f * scale;
      int textColor = selected ? MStyle.TEXT_ACCENT
          : hasPoints ? MStyle.TEXT_PRIMARY
          : MStyle.TEXT_SECONDARY;

      float nameX = sideX + 5f;
      Image classIcon = cls.iconPath() != null ? AbilityIconCache.get(cls.iconPath()) : null;
      if (classIcon != null) {
        float iconSize = CLASS_ICON_SIZE * scale;
        float iconY = btnY + (btnH - iconSize) / 2f;
        MPainter.drawImage(canvas, classIcon, nameX, iconY, iconSize, iconSize);
        nameX += iconSize + CLASS_ICON_GAP * scale;
      }

      MPainter.drawStringWithShadow(canvas, cls.name(),
          nameX, textY,
          ui.fonts().getScaled(MStyle.FONT_META), textColor, MStyle.TEXT_SHADOW);

      if (hasPoints) {
        String cpStr = cpSpent + " CP";
        MPainter.drawStringWithShadow(canvas, cpStr,
            sideX + btnW - 34f * scale, textY,
            ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
      }
    }

    canvas.restore();
    drawScrollbar(canvas, sideX + sideW - 6f, clipTop, 6f, clipH,
        leftScrollOffset, totalListH);
  }

  // ─────────────────────────────────────────────── Content area

  private void drawContentArea(Canvas canvas, MasonryUI ui, CharacterStats stats,
                               float px, float py, float mx, float my, float scale) {
    float cx = px + CONTENT_X_PAD * scale;
    float cy = py + 20f * scale;
    float cw = CONTENT_W * scale;

    Optional<PlayerClassDefinition> classOpt = classOpt();
    String className = classOpt.map(PlayerClassDefinition::name).orElse("Select a class");
    String classDesc = classOpt.map(PlayerClassDefinition::description)
        .orElse("Choose a class from the list on the left.");

    MPainter.drawStringWithShadow(canvas, className,
        cx + 4f, cy + 18f * scale,
        ui.fonts().getScaled(MStyle.FONT_ITEM), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

    Font metaFont = ui.fonts().getScaled(MStyle.FONT_META);
    List<String> descLines = wrapText(metaFont, classDesc, cw - 8f);
    float descLineH = 16f * scale;
    float descY = cy + 36f * scale;
    for (int i = 0; i < descLines.size(); i++) {
      MPainter.drawStringWithShadow(canvas, descLines.get(i),
          cx + 4f, descY + i * descLineH,
          metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    }

    float lastDescBaseline = descY + Math.max(0, descLines.size() - 1) * descLineH;
    float ruleY = lastDescBaseline + 10f * scale;
    drawEngravedRule(canvas, cx, ruleY, cw);

    float dirtY = ruleY + 6f * scale;
    float dirtBottom = cy + 452f * scale;
    float dirtH = Math.max(150f * scale, dirtBottom - dirtY);
    MPainter.stoneSurface(canvas, cx, dirtY, cw, dirtH,
        MStyle.PANEL_RADIUS, DIRT_FILL, DIRT_BORDER,
        MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, 0,
        DIRT_NOISE_DARK, DIRT_NOISE_LIGHT);

    MPainter.drawStringWithShadow(canvas, "CP Available: " + stats.getRemainingCp(),
        cx + 8f, dirtY + 16f * scale,
        ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    drawEngravedRule(canvas, cx + 4f, dirtY + 22f * scale, cw - 8f);

    if (classOpt.isEmpty()) {
      return;
    }

    drawAbilityList(canvas, ui, stats, classOpt.get(), cx, dirtY, dirtH, mx, my, scale, cw);
  }

  /** Greedily wraps {@code text} to fit within {@code maxWidth} pixels, honoring "\n" breaks. */
  private List<String> wrapText(Font font, String text, float maxWidth) {
    List<String> lines = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return lines;
    }
    for (String paragraph : text.split("\n")) {
      StringBuilder current = new StringBuilder();
      for (String word : paragraph.split(" ")) {
        String candidate = current.isEmpty() ? word : current + " " + word;
        if (current.length() > 0 && MPainter.measureWidth(font, candidate) > maxWidth) {
          lines.add(current.toString());
          current = new StringBuilder(word);
        } else {
          current = new StringBuilder(candidate);
        }
      }
      lines.add(current.toString());
    }
    return lines;
  }

  private void drawAbilityList(Canvas canvas, MasonryUI ui, CharacterStats stats,
                               PlayerClassDefinition cls, float cx, float dirtY, float dirtH,
                               float mx, float my, float scale, float cw) {
    List<ClassAbility> abilities = cls.abilities();
    float rowH  = ABILITY_ROW_H  * scale;
    float btnW  = SPEND_BTN_W    * scale;
    float btnH  = SPEND_BTN_H    * scale;
    float clipTop = dirtY + 28f * scale;
    float clipH = Math.max(0f, dirtH - 35f * scale);
    lastAbilityClipH = clipH;
    float totalH = abilities.size() * rowH;

    canvas.save();
    canvas.clipRect(Rect.makeXYWH(cx, clipTop, cw - 8f, clipH));

    for (int i = 0; i < abilities.size(); i++) {
      ClassAbility ability = abilities.get(i);
      String key = cls.id() + ":" + i;
      int spent = stats.getSpentCp(key);
      boolean canSpend = stats.getRemainingCp() > 0;

      float rowY = clipTop + i * rowH - rightScrollOffset;

      float textX = cx + 8f;
      Image icon = AbilityIconCache.get(ability.iconPath());
      if (icon != null) {
        float iconSize = ABILITY_ICON_SIZE * scale;
        float iconY = rowY + (rowH - iconSize) / 2f;
        MPainter.drawImage(canvas, icon, textX, iconY, iconSize, iconSize);
        textX += iconSize + ABILITY_ICON_GAP * scale;
      }

      MPainter.drawStringWithShadow(canvas, ability.name(),
          textX, rowY + 16f * scale,
          ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

      MPainter.drawStringWithShadow(canvas, ability.description(),
          textX, rowY + 32f * scale,
          ui.fonts().getScaled(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

      float btnX = cx + cw - btnW - 8f;
      float btnY = rowY + (rowH - btnH) / 2f;
      spendButtons[i].bounds(btnX, btnY, btnW, btnH);
      spendButtons[i].updateHover(mx, my);

      int btnFill = !canSpend ? MStyle.BUTTON_FILL_DIS
          : spendButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
          : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, btnX, btnY, btnW, btnH,
          MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

      String btnLabel = "Spend " + ability.cpCost() + " CP (" + spent + ")";
      int btnTextColor = canSpend ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
      float btnTextY = btnY + btnH * 0.5f + MStyle.FONT_META * 0.38f * scale;
      MPainter.drawCenteredStringWithShadow(canvas, btnLabel,
          btnX + btnW / 2f, btnTextY,
          ui.fonts().getScaled(MStyle.FONT_META), btnTextColor, MStyle.TEXT_SHADOW);

      if (i < abilities.size() - 1) {
        drawEngravedRule(canvas, cx + 4f, rowY + rowH - 4f * scale, cw - 12f);
      }
    }

    canvas.restore();
    drawScrollbar(canvas, cx + cw - 7f, clipTop, 6f, clipH,
        rightScrollOffset, totalH);
  }

  // ─────────────────────────────────────────────── Click handling

  private boolean handleSidebarClick(float mx, float my, float px, float py, float scale) {
    float sideX = px + SIDEBAR_X_PAD * scale;
    float clipTop = py + (20f + LIST_CLIP_TOP_PAD) * scale;
    float btnW = CLASS_BTN_W * scale;
    float btnH = CLASS_BTN_H * scale;
    float btnG = CLASS_BTN_GAP * scale;

    if (mx < sideX || mx > sideX + btnW
        || my < clipTop || my > clipTop + SIDEBAR_CLIP_H * scale) {
      return false;
    }

    float relY = my - clipTop + leftScrollOffset;
    int idx = (int) (relY / (btnH + btnG));

    if (idx >= 0 && idx < ClassRegistry.ALL.size()) {
      displayedClassId = ClassRegistry.ALL.get(idx).id();
      rightScrollOffset = 0f;
      return true;
    }
    return false;
  }

  private boolean handleAbilityClick(float mx, float my, CharacterStats stats) {
    if (displayedClassId == null || stats.getRemainingCp() <= 0) {
      return false;
    }
    for (int i = 0; i < spendButtons.length; i++) {
      if (spendButtons[i].contains(mx, my)) {
        stats.spendCpOnAbility(displayedClassId + ":" + i);
        return true;
      }
    }
    return false;
  }

  // ─────────────────────────────────────────────── Helpers

  private Optional<PlayerClassDefinition> classOpt() {
    return displayedClassId != null
        ? ClassRegistry.findById(displayedClassId)
        : Optional.empty();
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
