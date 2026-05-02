package com.stonebreak.ui.characterScreen;

import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.ui.characterScreen.renderers.ClassesTabRenderer;
import com.stonebreak.ui.characterScreen.renderers.FeatsTabRenderer;
import com.stonebreak.ui.characterScreen.renderers.SkillsTabRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.Rect;
import org.joml.Vector2f;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * Renders the Character Screen using Skija/MasonryUI (Phase A only — no GL item icons needed).
 *
 * <p>Visual theme: "Stone Tablet" — the panel reads like an ancient carved stone record.
 * Hosts a 5-tab bar (Inventory, Character, Classes, Skills, Feats) and delegates
 * tab-specific content to dedicated sub-renderers.
 */
public class CharacterRenderCoordinator {

  // ─── Panel geometry ────────────────────────────────────────────────────────
  private static final int   PANEL_WIDTH  = 600;
  private static final int   PANEL_HEIGHT = 480;
  private static final float TAB_HEIGHT   = 28f;
  private static final float TAB_WIDTH    = 84f;   // 5 tabs fit within 600px panel
  private static final float TAB_GAP      = 4f;
  /** Left offset so the 5-tab group is centered over the 600px panel. */
  private static final float TAB_START_OFFSET = (PANEL_WIDTH - (5 * TAB_WIDTH + 4 * TAB_GAP)) / 2f;
  private static final int   SLOT_SIZE    = 34;

  // ─── Colors ────────────────────────────────────────────────────────────────
  private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;
  private static final int TAB_ACTIVE_FILL  = 0xFF7A7A7A;
  private static final int STAT_TILE_FILL   = 0xFF252525;
  private static final int STAT_TILE_BORDER = 0xFF151515;
  private static final int COLOR_HEART      = 0xFFCC2222;
  private static final int COLOR_HEART_DIM  = 0xFF551111;
  private static final int COLOR_MANA_DIM   = 0xFF334466;
  private static final int SILHOUETTE_FILL  = 0xFF4A4A4A;
  private static final int SILHOUETTE_STROKE= 0xFF2A2A2A;

  private static final String[] ABILITY_ABBREV = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

  // ─── Fields ────────────────────────────────────────────────────────────────
  private final Renderer renderer;
  private final InputHandler inputHandler;
  private final CharacterStats stats;
  private final CharacterController controller;
  private final MasonryUI ui;

  // Tab buttons — bounds updated each frame for hit-testing
  private final MButton tabInventory = new MButton("Inventory").fontSize(MStyle.FONT_META);
  private final MButton tabCharacter = new MButton("Character").fontSize(MStyle.FONT_META);
  private final MButton tabClasses   = new MButton("Classes").fontSize(MStyle.FONT_META);
  private final MButton tabSkills    = new MButton("Skills").fontSize(MStyle.FONT_META);
  private final MButton tabFeats     = new MButton("Feats").fontSize(MStyle.FONT_META);

  // Sub-renderers for the three new tabs
  private final ClassesTabRenderer classesRenderer = new ClassesTabRenderer();
  private final SkillsTabRenderer  skillsRenderer  = new SkillsTabRenderer();
  private final FeatsTabRenderer   featsRenderer   = new FeatsTabRenderer();

  // ─────────────────────────────────────────────────────────────────────────

  public CharacterRenderCoordinator(Renderer renderer, InputHandler inputHandler,
                                    CharacterStats stats, CharacterController controller) {
    this.renderer     = renderer;
    this.inputHandler = inputHandler;
    this.stats        = stats;
    this.controller   = controller;
    this.ui           = new MasonryUI(renderer.getSkijaBackend());
  }

  // ─────────────────────────────────────────────── Public entry points

  public void render(int screenWidth, int screenHeight) {
    if (!ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
      return;
    }

    float px = (screenWidth  - PANEL_WIDTH)  / 2f;
    float py = (screenHeight - PANEL_HEIGHT) / 2f;

    Vector2f mouse = inputHandler.getMousePosition();
    float mx = mouse.x;
    float my = mouse.y;

    Canvas canvas = ui.canvas();

    // Tab bar (above panel)
    updateTabBounds(px, py);
    updateTabHovers(mx, my);
    drawTabBar(canvas, px, py);

    // Main stone panel
    MPainter.stoneSurface(canvas, px, py, PANEL_WIDTH, PANEL_HEIGHT, MStyle.PANEL_RADIUS,
        PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
        MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
        MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

    drawEngravedRule(canvas, px + 10, py + 12, PANEL_WIDTH - 20);

    // Delegate content to the active tab
    switch (controller.getActiveTab()) {
      case OVERVIEW -> drawOverviewContent(canvas, mx, my, px, py);
      case CLASSES  -> classesRenderer.render(canvas, ui, stats, px, py, mx, my);
      case SKILLS   -> skillsRenderer.render(canvas, ui, stats, px, py, mx, my);
      case FEATS    -> featsRenderer.render(canvas, ui, stats, px, py, mx, my);
    }

    ui.renderOverlays();
    ui.endFrame();
  }

  public void handleMouseInput(int screenWidth, int screenHeight) {
    Vector2f mouse = inputHandler.getMousePosition();
    float mx = mouse.x;
    float my = mouse.y;

    boolean leftClick  = inputHandler.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT);
    boolean rightClick = inputHandler.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT);

    if (!leftClick && !rightClick) {
      return;
    }

    float px = (screenWidth  - PANEL_WIDTH)  / 2f;
    float py = (screenHeight - PANEL_HEIGHT) / 2f;
    updateTabBounds(px, py);

    if (leftClick) {
      // Inventory tab — close character screen, open inventory
      if (tabInventory.contains(mx, my)) {
        controller.setVisible(false);
        Game.getInstance().toggleInventoryScreen();
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
        return;
      }
      if (tabCharacter.contains(mx, my)) {
        controller.setActiveTab(CharacterPanelTab.OVERVIEW);
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
        return;
      }
      if (tabClasses.contains(mx, my)) {
        controller.setActiveTab(CharacterPanelTab.CLASSES);
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
        return;
      }
      if (tabSkills.contains(mx, my)) {
        controller.setActiveTab(CharacterPanelTab.SKILLS);
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
        return;
      }
      if (tabFeats.contains(mx, my)) {
        controller.setActiveTab(CharacterPanelTab.FEATS);
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
        return;
      }

      boolean consumed = switch (controller.getActiveTab()) {
        case CLASSES -> classesRenderer.handleClick(mx, my, stats, px, py);
        case SKILLS  -> skillsRenderer.handleClick(mx, my, stats);
        case FEATS   -> featsRenderer.handleClick(mx, my, stats, px, py);
        default      -> false;
      };
      if (consumed) {
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
      }
    }

    if (rightClick) {
      boolean consumed = switch (controller.getActiveTab()) {
        case FEATS -> featsRenderer.handleRightClick(mx, my, stats, px, py);
        default    -> false;
      };
      if (consumed) {
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_RIGHT);
      }
    }
  }

  /** Forwards scroll wheel events to the active tab renderer. */
  public void handleScroll(float deltaY) {
    Vector2f mouse = inputHandler.getMousePosition();
    int screenW = Game.getWindowWidth();
    int screenH = Game.getWindowHeight();
    float px = (screenW - PANEL_WIDTH) / 2f;
    float py = (screenH - PANEL_HEIGHT) / 2f;

    switch (controller.getActiveTab()) {
      case CLASSES -> classesRenderer.handleScroll(deltaY, mouse.x, mouse.y, px, py);
      case FEATS   -> featsRenderer.handleScroll(deltaY, px, py);
      default      -> { /* OVERVIEW and SKILLS do not scroll */ }
    }
  }

  // ─────────────────────────────────────────────── Tab bar

  private void updateTabBounds(float px, float py) {
    float tabY = py - TAB_HEIGHT;
    float startX = px + TAB_START_OFFSET;
    float stride = TAB_WIDTH + TAB_GAP;
    tabInventory.bounds(startX,              tabY, TAB_WIDTH, TAB_HEIGHT);
    tabCharacter.bounds(startX + stride,     tabY, TAB_WIDTH, TAB_HEIGHT);
    tabClasses  .bounds(startX + stride * 2, tabY, TAB_WIDTH, TAB_HEIGHT);
    tabSkills   .bounds(startX + stride * 3, tabY, TAB_WIDTH, TAB_HEIGHT);
    tabFeats    .bounds(startX + stride * 4, tabY, TAB_WIDTH, TAB_HEIGHT);
  }

  private void updateTabHovers(float mx, float my) {
    tabInventory.updateHover(mx, my);
    tabCharacter.updateHover(mx, my);
    tabClasses  .updateHover(mx, my);
    tabSkills   .updateHover(mx, my);
    tabFeats    .updateHover(mx, my);
  }

  private void drawTabBar(Canvas canvas, float px, float py) {
    CharacterPanelTab active = controller.getActiveTab();
    float tabY = py - TAB_HEIGHT;
    float startX = px + TAB_START_OFFSET;
    float stride = TAB_WIDTH + TAB_GAP;
    // Inventory tab is never "active" on the character screen — it always navigates away
    drawTab(canvas, startX,              tabY, "Inventory",
        false, tabInventory.isHovered());
    drawTab(canvas, startX + stride,     tabY, "Character",
        active == CharacterPanelTab.OVERVIEW, tabCharacter.isHovered());
    drawTab(canvas, startX + stride * 2, tabY, "Classes",
        active == CharacterPanelTab.CLASSES, tabClasses.isHovered());
    drawTab(canvas, startX + stride * 3, tabY, "Skills",
        active == CharacterPanelTab.SKILLS, tabSkills.isHovered());
    drawTab(canvas, startX + stride * 4, tabY, "Feats",
        active == CharacterPanelTab.FEATS, tabFeats.isHovered());
  }

  private void drawTab(Canvas canvas, float x, float y, String label,
                       boolean active, boolean hovered) {
    int fill = active ? TAB_ACTIVE_FILL
        : hovered ? MStyle.BUTTON_FILL_HI
        : MStyle.BUTTON_FILL;
    MPainter.stoneSurface(canvas, x, y, TAB_WIDTH, TAB_HEIGHT, MStyle.BUTTON_RADIUS,
        fill, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    Font font = ui.fonts().get(MStyle.FONT_META);
    int color = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
    float ty = y + TAB_HEIGHT * 0.5f + MStyle.FONT_META * 0.38f;
    MPainter.drawCenteredStringWithShadow(canvas, label, x + TAB_WIDTH / 2f, ty,
        font, color, MStyle.TEXT_SHADOW);
  }

  // ─────────────────────────────────────────────── Overview tab content

  private void drawOverviewContent(Canvas canvas, float mx, float my, float px, float py) {
    float leftColX = px + 16;
    float leftColW = PANEL_WIDTH * 0.38f;
    float rightColX = leftColX + leftColW + 12f;
    float rightColW = PANEL_WIDTH - leftColW - 44f;
    float contentY  = py + 20f;

    drawCharacterSilhouette(canvas, leftColX, contentY, leftColW);
    drawClassAndFeats(canvas, leftColX, contentY + 162f, leftColW);
    drawCurrencies(canvas, leftColX, contentY + 300f, leftColW);

    drawAbilityScores(canvas, rightColX, contentY, rightColW, mx, my);

    float barSectionY = contentY + 190f;
    drawHealthBar(canvas, rightColX, barSectionY, rightColW);
    drawManaBar(canvas, rightColX, barSectionY + 32f, rightColW);
    drawStatusEffects(canvas, rightColX, barSectionY + 74f, rightColW, mx, my);
  }

  // ─────────────────────────────────────────────── Decorative helpers

  private void drawEngravedRule(Canvas canvas, float x, float y, float w) {
    try (Paint dark  = new Paint().setColor(0x66000000);
         Paint light = new Paint().setColor(0x33FFFFFF)) {
      canvas.drawRect(Rect.makeXYWH(x, y,       w, 1f), dark);
      canvas.drawRect(Rect.makeXYWH(x, y + 2f, w, 1f), light);
    }
  }

  // ─────────────────────────────────────────────── Left column

  private void drawCharacterSilhouette(Canvas canvas, float colX, float colY, float colW) {
    float cx   = colX + colW / 2f;
    float topY = colY + 16f;

    try (Paint fill   = new Paint().setColor(SILHOUETTE_FILL).setAntiAlias(true);
         Paint stroke = new Paint().setColor(SILHOUETTE_STROKE)
             .setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {

      float headR  = 22f;
      float headCX = cx;
      float headCY = topY + headR;
      canvas.drawCircle(headCX, headCY, headR, fill);
      canvas.drawCircle(headCX, headCY, headR, stroke);

      float shoulderW = 52f;
      float waistW    = 34f;
      float bodyH     = 80f;
      float bodyTopY  = headCY + headR + 4f;
      float bodyBotY  = bodyTopY + bodyH;

      try (PathBuilder bodyBuilder = new PathBuilder()) {
        bodyBuilder.moveTo(cx - shoulderW / 2f, bodyTopY);
        bodyBuilder.lineTo(cx + shoulderW / 2f, bodyTopY);
        bodyBuilder.lineTo(cx + waistW    / 2f, bodyBotY);
        bodyBuilder.lineTo(cx - waistW    / 2f, bodyBotY);
        bodyBuilder.closePath();
        try (Path body = bodyBuilder.build()) {
          canvas.drawPath(body, fill);
          canvas.drawPath(body, stroke);
        }
      }
    }
  }

  private void drawClassAndFeats(Canvas canvas, float colX, float colY, float colW) {
    Font metaFont = ui.fonts().get(MStyle.FONT_META);

    drawEngravedRule(canvas, colX, colY, colW);
    float y = colY + 14f;

    String classLine = "Class: " + stats.getCharacterClass();
    MPainter.drawStringWithShadow(canvas, classLine, colX + 4f, y, metaFont,
        MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    y += 22f;

    drawEngravedRule(canvas, colX, y, colW);
    y += 12f;

    MPainter.drawStringWithShadow(canvas, "Feats:", colX + 4f, y, metaFont,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    y += 18f;

    List<String> feats = stats.getFeats();
    if (feats.isEmpty()) {
      MPainter.drawStringWithShadow(canvas, "(None)", colX + 8f, y, metaFont,
          MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
    } else {
      int maxVisible = 6; // cap so feats don't overflow into currencies section
      for (int i = 0; i < Math.min(feats.size(), maxVisible); i++) {
        MPainter.drawStringWithShadow(canvas, feats.get(i), colX + 8f, y, metaFont,
            MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        y += 18f;
      }
      if (feats.size() > maxVisible) {
        MPainter.drawStringWithShadow(canvas, "+" + (feats.size() - maxVisible) + " more…",
            colX + 8f, y, metaFont, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
      }
    }
  }

  private void drawCurrencies(Canvas canvas, float colX, float colY, float colW) {
    Font font = ui.fonts().get(MStyle.FONT_META);
    drawEngravedRule(canvas, colX, colY, colW);
    float y = colY + 14f;

    record Entry(String label, int value) {}
    Entry[] entries = {
        new Entry("CP", stats.getClassPoints()),
        new Entry("SP", stats.getSkillPoints()),
        new Entry("FP", stats.getFeatPoints()),
    };
    for (Entry e : entries) {
      MPainter.drawStringWithShadow(canvas, e.label() + ": " + e.value(),
          colX + 4f, y, font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
      y += 18f;
    }
  }

  // ─────────────────────────────────────────────── Right column — ability scores

  private void drawAbilityScores(Canvas canvas, float colX, float colY,
                                 float colW, float mx, float my) {
    int[] scores = {
        stats.getStrength(),     stats.getDexterity(),    stats.getConstitution(),
        stats.getIntelligence(), stats.getWisdom(),       stats.getCharisma()
    };

    final float tileW   = 64f;
    final float tileH   = 80f;
    final float tileGap = 8f;

    Font abbrevFont = ui.fonts().get(MStyle.FONT_META);
    Font valueFont  = ui.fonts().get(MStyle.FONT_BUTTON);
    Font modFont    = ui.fonts().get(MStyle.FONT_META);

    for (int i = 0; i < 6; i++) {
      int col = i % 3;
      int row = i / 3;

      float tx = colX + col * (tileW + tileGap);
      float ty = colY + row * (tileH + tileGap);

      MPainter.stoneSurface(canvas, tx, ty, tileW, tileH, 3f,
          STAT_TILE_FILL, STAT_TILE_BORDER,
          0x55000000, 0x1AFFFFFF, 0,
          MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

      if (mx >= tx && mx <= tx + tileW && my >= ty && my <= ty + tileH) {
        MPainter.fillRoundedRect(canvas, tx + 1, ty + 1, tileW - 2, tileH - 2, 3f, 0x22FFFFFF);
      }

      float tileCX = tx + tileW / 2f;

      MPainter.drawCenteredStringWithShadow(canvas, ABILITY_ABBREV[i],
          tileCX, ty + 15f, abbrevFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
      MPainter.drawCenteredStringWithShadow(canvas, String.valueOf(scores[i]),
          tileCX, ty + tileH / 2f + 10f, valueFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

      int mod = stats.getModifier(scores[i]);
      String modStr = (mod >= 0 ? "+" : "") + mod;
      MPainter.drawCenteredStringWithShadow(canvas, modStr,
          tileCX, ty + tileH - 8f, modFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    }
  }

  // ─────────────────────────────────────────────── Right column — vitals

  private void drawHealthBar(Canvas canvas, float x, float y, float w) {
    Font font   = ui.fonts().get(MStyle.FONT_META);
    float hp    = stats.getHealth();
    float maxHp = stats.getMaxHealth();

    MPainter.drawStringWithShadow(canvas, "HP", x, y, font,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    float heartSize = 13f;
    float startX    = x + 28f;
    float heartTop  = y - heartSize;
    float filled    = maxHp > 0f ? (hp / maxHp) * 10f : 0f;

    for (int i = 0; i < 10; i++) {
      float hx    = startX + i * (heartSize + 2f);
      int   color = i < filled ? COLOR_HEART : COLOR_HEART_DIM;
      drawHeart(canvas, hx, heartTop, heartSize, color);
    }

    String hpText = (int) hp + "/" + (int) maxHp;
    MPainter.drawStringWithShadow(canvas, hpText,
        startX + 10 * (heartSize + 2f) + 6f, y, font,
        MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
  }

  private void drawManaBar(Canvas canvas, float x, float y, float w) {
    Font font = ui.fonts().get(MStyle.FONT_META);

    MPainter.drawStringWithShadow(canvas, "MP", x, y, font,
        MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);

    float dSize  = 11f;
    float startX = x + 28f;
    float dTop   = y - dSize;

    for (int i = 0; i < 10; i++) {
      float dx = startX + i * (dSize + 2f);
      drawDiamond(canvas, dx, dTop, dSize, COLOR_MANA_DIM);
    }

    MPainter.drawStringWithShadow(canvas, "0/0",
        startX + 10 * (dSize + 2f) + 6f, y, font,
        MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
  }

  // ─────────────────────────────────────────────── Right column — status effects

  private void drawStatusEffects(Canvas canvas, float x, float y, float w,
                                 float mx, float my) {
    Font labelFont = ui.fonts().get(MStyle.FONT_META);
    Font qFont     = ui.fonts().get(MStyle.FONT_ITEM);

    MPainter.drawStringWithShadow(canvas, "Status Effects", x, y, labelFont,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    float slotY = y + 6f;
    float gap   = 6f;

    for (int i = 0; i < 6; i++) {
      float sx = x + i * (SLOT_SIZE + gap);
      MItemSlot slot = new MItemSlot().bounds(sx, slotY, SLOT_SIZE, SLOT_SIZE);
      slot.updateHover(mx, my);
      slot.render(ui);

      float qCX = sx + SLOT_SIZE / 2f;
      float qCY = slotY + SLOT_SIZE / 2f + MStyle.FONT_ITEM * 0.35f;
      MPainter.drawCenteredStringWithShadow(canvas, "?", qCX, qCY, qFont,
          MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
    }
  }

  // ─────────────────────────────────────────────── Icon primitives

  private void drawHeart(Canvas canvas, float x, float y, float s, int color) {
    try (Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
      float r  = s * 0.27f;
      float lx = x + r;
      float rx = x + s - r;
      float ly = y + r;

      canvas.drawCircle(lx, ly, r, fill);
      canvas.drawCircle(rx, ly, r, fill);

      try (PathBuilder triBuilder = new PathBuilder()) {
        triBuilder.moveTo(x,          ly);
        triBuilder.lineTo(x + s / 2f, y + s);
        triBuilder.lineTo(x + s,      ly);
        triBuilder.closePath();
        try (Path tri = triBuilder.build()) {
          canvas.drawPath(tri, fill);
        }
      }
    }
  }

  private void drawDiamond(Canvas canvas, float x, float y, float s, int color) {
    float cx = x + s / 2f;
    float cy = y + s / 2f;
    try (PathBuilder builder = new PathBuilder();
         Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
      builder.moveTo(cx,     y);
      builder.lineTo(x + s, cy);
      builder.lineTo(cx,     y + s);
      builder.lineTo(x,      cy);
      builder.closePath();
      try (Path path = builder.build()) {
        canvas.drawPath(path, fill);
      }
    }
  }
}
