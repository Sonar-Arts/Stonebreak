package com.stonebreak.ui.characterScreen.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.classes.ClassAbility;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.Rect;

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

  // ─── Content area layout ───────────────────────────────────────────────────
  private static final float CONTENT_X_PAD    = 193f;
  private static final float CONTENT_W        = 391f;
  private static final float ABILITY_ROW_H    = 64f;
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
                     float px, float py, float mx, float my) {
    drawSidebar(canvas, ui, stats, px, py, mx, my);
    drawContentArea(canvas, ui, stats, px, py, mx, my);
  }

  /** Handles a click; returns true if consumed. */
  public boolean handleClick(float mx, float my, CharacterStats stats, float px, float py) {
    return handleSidebarClick(mx, my, px, py)
        || handleAbilityClick(mx, my, stats);
  }

  /** Scrolls the sidebar (left area) or the ability list (right area). */
  public void handleScroll(float deltaY, float mx, float my, float px, float py) {
    float sideX = px + SIDEBAR_X_PAD;
    if (mx >= sideX && mx <= sideX + SIDEBAR_W) {
      float totalListH = ClassRegistry.ALL.size() * (CLASS_BTN_H + CLASS_BTN_GAP);
      float maxScroll = Math.max(0f, totalListH - SIDEBAR_CLIP_H);
      leftScrollOffset = Math.clamp(leftScrollOffset + deltaY * 20f, 0f, maxScroll);
    } else {
      Optional<PlayerClassDefinition> cls = classOpt();
      if (cls.isPresent()) {
        float totalH = cls.get().abilities().size() * ABILITY_ROW_H;
        float clipH = abilityClipH();
        float maxScroll = Math.max(0f, totalH - clipH);
        rightScrollOffset = Math.clamp(rightScrollOffset + deltaY * 20f, 0f, maxScroll);
      }
    }
  }

  // ─────────────────────────────────────────────── Sidebar

  private void drawSidebar(Canvas canvas, MasonryUI ui, CharacterStats stats,
                           float px, float py, float mx, float my) {
    float sideX = px + SIDEBAR_X_PAD;
    float sideY = py + 20f;

    MPainter.drawStringWithShadow(canvas, "Class Select",
        sideX + 4f, sideY + 14f,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    drawEngravedRule(canvas, sideX, sideY + 20f, SIDEBAR_W);

    float clipTop = sideY + LIST_CLIP_TOP_PAD;
    List<PlayerClassDefinition> classes = ClassRegistry.ALL;
    float totalListH = classes.size() * (CLASS_BTN_H + CLASS_BTN_GAP);

    canvas.save();
    canvas.clipRect(Rect.makeXYWH(sideX, clipTop, SIDEBAR_W, SIDEBAR_CLIP_H));

    for (int i = 0; i < classes.size(); i++) {
      PlayerClassDefinition cls = classes.get(i);
      float btnY = clipTop + i * (CLASS_BTN_H + CLASS_BTN_GAP) - leftScrollOffset;

      boolean selected = cls.id().equals(displayedClassId);
      // Hover detection in screen space (unaffected by canvas transforms)
      boolean hovered = mx >= sideX && mx <= sideX + CLASS_BTN_W
          && my >= btnY && my <= btnY + CLASS_BTN_H;

      int cpSpent = stats.getTotalCpSpentForClass(cls.id());
      boolean hasPoints = cpSpent > 0;

      int fill = selected ? 0xFF7A7A7A
          : hovered ? MStyle.BUTTON_FILL_HI
          : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, sideX + 1f, btnY, CLASS_BTN_W, CLASS_BTN_H,
          MStyle.BUTTON_RADIUS, fill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

      float textY = btnY + CLASS_BTN_H * 0.5f + MStyle.FONT_META * 0.38f;
      int textColor = selected ? MStyle.TEXT_ACCENT
          : hasPoints ? MStyle.TEXT_PRIMARY
          : MStyle.TEXT_SECONDARY;
      MPainter.drawStringWithShadow(canvas, cls.name(),
          sideX + 5f, textY,
          ui.fonts().get(MStyle.FONT_META), textColor, MStyle.TEXT_SHADOW);

      if (hasPoints) {
        String cpStr = cpSpent + " CP";
        MPainter.drawStringWithShadow(canvas, cpStr,
            sideX + CLASS_BTN_W - 34f, textY,
            ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
      }
    }

    canvas.restore();
    drawScrollbar(canvas, sideX + SIDEBAR_W - 6f, clipTop, 6f, SIDEBAR_CLIP_H,
        leftScrollOffset, totalListH);
  }

  // ─────────────────────────────────────────────── Content area

  private void drawContentArea(Canvas canvas, MasonryUI ui, CharacterStats stats,
                               float px, float py, float mx, float my) {
    float cx = px + CONTENT_X_PAD;
    float cy = py + 20f;

    Optional<PlayerClassDefinition> classOpt = classOpt();
    String className = classOpt.map(PlayerClassDefinition::name).orElse("Select a class");
    String classDesc = classOpt.map(PlayerClassDefinition::description)
        .orElse("Choose a class from the list on the left.");

    // Fixed header
    MPainter.drawStringWithShadow(canvas, className,
        cx + 4f, cy + 18f,
        ui.fonts().get(MStyle.FONT_ITEM), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    MPainter.drawStringWithShadow(canvas, classDesc,
        cx + 4f, cy + 36f,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    drawEngravedRule(canvas, cx, cy + 46f, CONTENT_W);

    // Dirt background
    float dirtY = cy + 52f;
    float dirtH = 400f;
    MPainter.stoneSurface(canvas, cx, dirtY, CONTENT_W, dirtH,
        MStyle.PANEL_RADIUS, DIRT_FILL, DIRT_BORDER,
        MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, 0,
        DIRT_NOISE_DARK, DIRT_NOISE_LIGHT);

    // CP counter (fixed inside dirt panel)
    MPainter.drawStringWithShadow(canvas, "CP Available: " + stats.getRemainingCp(),
        cx + 8f, dirtY + 16f,
        ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    drawEngravedRule(canvas, cx + 4f, dirtY + 22f, CONTENT_W - 8f);

    if (classOpt.isEmpty()) {
      return;
    }

    drawAbilityList(canvas, ui, stats, classOpt.get(), cx, dirtY, mx, my);
  }

  private void drawAbilityList(Canvas canvas, MasonryUI ui, CharacterStats stats,
                               PlayerClassDefinition cls, float cx, float dirtY,
                               float mx, float my) {
    List<ClassAbility> abilities = cls.abilities();
    float clipTop = dirtY + 28f;
    float clipH = abilityClipH();
    float totalH = abilities.size() * ABILITY_ROW_H;

    canvas.save();
    canvas.clipRect(Rect.makeXYWH(cx, clipTop, CONTENT_W - 8f, clipH));

    for (int i = 0; i < abilities.size(); i++) {
      ClassAbility ability = abilities.get(i);
      String key = cls.id() + ":" + i;
      int spent = stats.getSpentCp(key);
      boolean canSpend = stats.getRemainingCp() > 0;

      float rowY = clipTop + i * ABILITY_ROW_H - rightScrollOffset;

      // Ability name
      MPainter.drawStringWithShadow(canvas, ability.name(),
          cx + 8f, rowY + 16f,
          ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

      // Hint / description
      MPainter.drawStringWithShadow(canvas, ability.description(),
          cx + 8f, rowY + 32f,
          ui.fonts().get(MStyle.FONT_META), MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

      // Spend CP button — bounds stored in screen space for click detection
      float btnX = cx + CONTENT_W - SPEND_BTN_W - 8f;
      float btnY = rowY + (ABILITY_ROW_H - SPEND_BTN_H) / 2f;
      spendButtons[i].bounds(btnX, btnY, SPEND_BTN_W, SPEND_BTN_H);
      spendButtons[i].updateHover(mx, my);

      int btnFill = !canSpend ? MStyle.BUTTON_FILL_DIS
          : spendButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
          : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, btnX, btnY, SPEND_BTN_W, SPEND_BTN_H,
          MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

      String btnLabel = "Spend " + ability.cpCost() + " CP (" + spent + ")";
      int btnTextColor = canSpend ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
      float btnTextY = btnY + SPEND_BTN_H * 0.5f + MStyle.FONT_META * 0.38f;
      MPainter.drawCenteredStringWithShadow(canvas, btnLabel,
          btnX + SPEND_BTN_W / 2f, btnTextY,
          ui.fonts().get(MStyle.FONT_META), btnTextColor, MStyle.TEXT_SHADOW);

      // Row separator
      if (i < abilities.size() - 1) {
        drawEngravedRule(canvas, cx + 4f, rowY + ABILITY_ROW_H - 4f, CONTENT_W - 12f);
      }
    }

    canvas.restore();
    drawScrollbar(canvas, cx + CONTENT_W - 7f, clipTop, 6f, clipH,
        rightScrollOffset, totalH);
  }

  // ─────────────────────────────────────────────── Click handling

  private boolean handleSidebarClick(float mx, float my, float px, float py) {
    float sideX = px + SIDEBAR_X_PAD;
    float clipTop = py + 20f + LIST_CLIP_TOP_PAD;

    if (mx < sideX || mx > sideX + CLASS_BTN_W
        || my < clipTop || my > clipTop + SIDEBAR_CLIP_H) {
      return false;
    }

    float relY = my - clipTop + leftScrollOffset;
    int idx = (int) (relY / (CLASS_BTN_H + CLASS_BTN_GAP));

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

  private float abilityClipH() {
    return 365f;
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
