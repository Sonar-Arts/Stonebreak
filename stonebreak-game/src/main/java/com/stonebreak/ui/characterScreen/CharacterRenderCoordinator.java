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

  // Ability score +/- buttons (one pair per stat, index 0–5: STR DEX CON INT WIS CHA)
  private final MButton[] scorePlusButtons  = new MButton[6];
  private final MButton[] scoreMinusButtons = new MButton[6];

  // ─────────────────────────────────────────────────────────────────────────

  public CharacterRenderCoordinator(Renderer renderer, InputHandler inputHandler,
                                    CharacterStats stats, CharacterController controller) {
    this.renderer     = renderer;
    this.inputHandler = inputHandler;
    this.stats        = stats;
    this.controller   = controller;
    this.ui           = new MasonryUI(renderer.getSkijaBackend());
    for (int i = 0; i < 6; i++) {
      scorePlusButtons[i]  = new MButton("+").fontSize(MStyle.FONT_META);
      scoreMinusButtons[i] = new MButton("−").fontSize(MStyle.FONT_META);
    }
  }

  // ─────────────────────────────────────────────── Public entry points

  public void render(int screenWidth, int screenHeight) {
    if (!ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
      return;
    }

    float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
    float scaledPW = PANEL_WIDTH  * scale;
    float scaledPH = PANEL_HEIGHT * scale;
    float px = (screenWidth  - scaledPW) / 2f;
    float py = (screenHeight - scaledPH) / 2f;

    Vector2f mouse = inputHandler.getMousePosition();
    float mx = mouse.x;
    float my = mouse.y;

    Canvas canvas = ui.canvas();

    updateTabBounds(px, py, scale, scaledPW);
    updateTabHovers(mx, my);
    drawTabBar(canvas, px, py, scale, scaledPW);

    MPainter.stoneSurface(canvas, px, py, scaledPW, scaledPH, MStyle.PANEL_RADIUS,
        PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
        MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
        MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

    drawEngravedRule(canvas, px + 10 * scale, py + 12 * scale, scaledPW - 20 * scale);

    switch (controller.getActiveTab()) {
      case OVERVIEW -> drawOverviewContent(canvas, mx, my, px, py, scale, scaledPW);
      case CLASSES  -> classesRenderer.render(canvas, ui, stats, px, py, mx, my, scale);
      case SKILLS   -> skillsRenderer.render(canvas, ui, stats, px, py, mx, my, scale);
      case FEATS    -> featsRenderer.render(canvas, ui, stats, px, py, mx, my, scale);
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

    float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
    float scaledPW = PANEL_WIDTH  * scale;
    float scaledPH = PANEL_HEIGHT * scale;
    float px = (screenWidth  - scaledPW) / 2f;
    float py = (screenHeight - scaledPH) / 2f;
    updateTabBounds(px, py, scale, scaledPW);

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
        case OVERVIEW -> handleOverviewClick(mx, my, px, py, scale);
        case CLASSES  -> classesRenderer.handleClick(mx, my, stats, px, py, scale);
        case SKILLS   -> skillsRenderer.handleClick(mx, my, stats);
        case FEATS    -> featsRenderer.handleClick(mx, my, stats, px, py, scale);
      };
      if (consumed) {
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
      }
    }

    if (rightClick) {
      boolean consumed = switch (controller.getActiveTab()) {
        case FEATS -> featsRenderer.handleRightClick(mx, my, stats, px, py, scale);
        default    -> false;
      };
      if (consumed) {
        inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_RIGHT);
      }
    }
  }

  private boolean handleOverviewClick(float mx, float my, float px, float py, float scale) {
    for (int i = 0; i < 6; i++) {
      if (scorePlusButtons[i].contains(mx, my)) {
        stats.incrementAbilityScore(i);
        return true;
      }
      if (scoreMinusButtons[i].contains(mx, my)) {
        stats.decrementAbilityScore(i);
        return true;
      }
    }
    return false;
  }

  /** Forwards scroll wheel events to the active tab renderer. */
  public void handleScroll(float deltaY) {
    Vector2f mouse = inputHandler.getMousePosition();
    int screenW = Game.getWindowWidth();
    int screenH = Game.getWindowHeight();
    float scale  = com.stonebreak.config.Settings.getInstance().getUiScale();
    float px = (screenW - PANEL_WIDTH  * scale) / 2f;
    float py = (screenH - PANEL_HEIGHT * scale) / 2f;

    switch (controller.getActiveTab()) {
      case CLASSES -> classesRenderer.handleScroll(deltaY, mouse.x, mouse.y, px, py, scale);
      case FEATS   -> featsRenderer.handleScroll(deltaY, px, py, scale);
      default      -> { /* OVERVIEW and SKILLS do not scroll */ }
    }
  }

  // ─────────────────────────────────────────────── Tab bar

  private void updateTabBounds(float px, float py, float scale, float scaledPW) {
    float tabH = TAB_HEIGHT * scale;
    float tabW = TAB_WIDTH  * scale;
    float tabG = TAB_GAP    * scale;
    float tabStartOffset = (scaledPW - (5 * tabW + 4 * tabG)) / 2f;
    float tabY = py - tabH;
    float startX = px + tabStartOffset;
    float stride = tabW + tabG;
    tabInventory.bounds(startX,              tabY, tabW, tabH);
    tabCharacter.bounds(startX + stride,     tabY, tabW, tabH);
    tabClasses  .bounds(startX + stride * 2, tabY, tabW, tabH);
    tabSkills   .bounds(startX + stride * 3, tabY, tabW, tabH);
    tabFeats    .bounds(startX + stride * 4, tabY, tabW, tabH);
  }

  private void updateTabHovers(float mx, float my) {
    tabInventory.updateHover(mx, my);
    tabCharacter.updateHover(mx, my);
    tabClasses  .updateHover(mx, my);
    tabSkills   .updateHover(mx, my);
    tabFeats    .updateHover(mx, my);
  }

  private void drawTabBar(Canvas canvas, float px, float py, float scale, float scaledPW) {
    CharacterPanelTab active = controller.getActiveTab();
    float tabH = TAB_HEIGHT * scale;
    float tabW = TAB_WIDTH  * scale;
    float tabG = TAB_GAP    * scale;
    float tabStartOffset = (scaledPW - (5 * tabW + 4 * tabG)) / 2f;
    float tabY = py - tabH;
    float startX = px + tabStartOffset;
    float stride = tabW + tabG;
    drawTab(canvas, startX,              tabY, "Inventory",
        false, tabInventory.isHovered(), tabW, tabH);
    drawTab(canvas, startX + stride,     tabY, "Character",
        active == CharacterPanelTab.OVERVIEW, tabCharacter.isHovered(), tabW, tabH);
    drawTab(canvas, startX + stride * 2, tabY, "Classes",
        active == CharacterPanelTab.CLASSES, tabClasses.isHovered(), tabW, tabH);
    drawTab(canvas, startX + stride * 3, tabY, "Skills",
        active == CharacterPanelTab.SKILLS, tabSkills.isHovered(), tabW, tabH);
    drawTab(canvas, startX + stride * 4, tabY, "Feats",
        active == CharacterPanelTab.FEATS, tabFeats.isHovered(), tabW, tabH);
  }

  private void drawTab(Canvas canvas, float x, float y, String label,
                       boolean active, boolean hovered, float tabW, float tabH) {
    int fill = active ? TAB_ACTIVE_FILL
        : hovered ? MStyle.BUTTON_FILL_HI
        : MStyle.BUTTON_FILL;
    MPainter.stoneSurface(canvas, x, y, tabW, tabH, MStyle.BUTTON_RADIUS,
        fill, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    Font font = ui.fonts().get(MStyle.FONT_META);
    int color = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
    float ty = y + tabH * 0.5f + MStyle.FONT_META * 0.38f;
    MPainter.drawCenteredStringWithShadow(canvas, label, x + tabW / 2f, ty,
        font, color, MStyle.TEXT_SHADOW);
  }

  // ─────────────────────────────────────────────── Overview tab content

  private void drawOverviewContent(Canvas canvas, float mx, float my, float px, float py,
                                   float scale, float scaledPW) {
    float leftColX  = px + 16 * scale;
    float leftColW  = scaledPW * 0.38f;
    float rightColX = leftColX + leftColW + 12f * scale;
    float rightColW = scaledPW - leftColW - 44f * scale;
    float contentY  = py + 20f * scale;

    drawCharacterSilhouette(canvas, leftColX, contentY, leftColW, scale);
    drawClassAndFeats(canvas, leftColX, contentY + 162f * scale, leftColW, scale);
    drawCurrencies(canvas, leftColX, contentY + 300f * scale, leftColW, scale);

    drawAbilityScores(canvas, rightColX, contentY, rightColW, mx, my, scale);

    float barSectionY = contentY + 190f * scale;
    drawHealthBar(canvas, rightColX, barSectionY, rightColW, scale);
    drawManaBar(canvas, rightColX, barSectionY + 32f * scale, rightColW, scale);
    drawStatusEffects(canvas, rightColX, barSectionY + 74f * scale, rightColW, mx, my, scale);
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

  private void drawCharacterSilhouette(Canvas canvas, float colX, float colY, float colW, float scale) {
    float cx   = colX + colW / 2f;
    float topY = colY + 16f * scale;

    try (Paint fill   = new Paint().setColor(SILHOUETTE_FILL).setAntiAlias(true);
         Paint stroke = new Paint().setColor(SILHOUETTE_STROKE)
             .setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {

      float headR  = 22f * scale;
      float headCX = cx;
      float headCY = topY + headR;
      canvas.drawCircle(headCX, headCY, headR, fill);
      canvas.drawCircle(headCX, headCY, headR, stroke);

      float shoulderW = 52f * scale;
      float waistW    = 34f * scale;
      float bodyH     = 80f * scale;
      float bodyTopY  = headCY + headR + 4f * scale;
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

  private void drawClassAndFeats(Canvas canvas, float colX, float colY, float colW, float scale) {
    Font metaFont = ui.fonts().get(MStyle.FONT_META);

    drawEngravedRule(canvas, colX, colY, colW);
    float y = colY + 14f * scale;

    String classLine = "Class: " + stats.getCharacterClass();
    MPainter.drawStringWithShadow(canvas, classLine, colX + 4f, y, metaFont,
        MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    y += 22f * scale;

    drawEngravedRule(canvas, colX, y, colW);
    y += 12f * scale;

    MPainter.drawStringWithShadow(canvas, "Feats:", colX + 4f, y, metaFont,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    y += 18f * scale;

    List<String> feats = stats.getFeats();
    if (feats.isEmpty()) {
      MPainter.drawStringWithShadow(canvas, "(None)", colX + 8f, y, metaFont,
          MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
    } else {
      int maxVisible = 6;
      for (int i = 0; i < Math.min(feats.size(), maxVisible); i++) {
        MPainter.drawStringWithShadow(canvas, feats.get(i), colX + 8f, y, metaFont,
            MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        y += 18f * scale;
      }
      if (feats.size() > maxVisible) {
        MPainter.drawStringWithShadow(canvas, "+" + (feats.size() - maxVisible) + " more…",
            colX + 8f, y, metaFont, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
      }
    }
  }

  private void drawCurrencies(Canvas canvas, float colX, float colY, float colW, float scale) {
    Font font = ui.fonts().get(MStyle.FONT_META);
    drawEngravedRule(canvas, colX, colY, colW);
    float y = colY + 14f * scale;

    record Entry(String label, int value) {}
    Entry[] entries = {
        new Entry("CP", stats.getClassPoints()),
        new Entry("SP", stats.getSkillPoints()),
        new Entry("FP", stats.getFeatPoints()),
        new Entry("AP", stats.getRemainingAp()),
    };
    for (Entry e : entries) {
      MPainter.drawStringWithShadow(canvas, e.label() + ": " + e.value(),
          colX + 4f, y, font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
      y += 18f * scale;
    }
  }

  // ─────────────────────────────────────────────── Right column — ability scores

  private void drawAbilityScores(Canvas canvas, float colX, float colY,
                                 float colW, float mx, float my, float scale) {
    int[] scores = {
        stats.getStrength(),     stats.getDexterity(),    stats.getConstitution(),
        stats.getIntelligence(), stats.getWisdom(),       stats.getCharisma()
    };

    final float tileW   = 64f * scale;
    final float tileH   = 80f * scale;
    final float tileGap = 8f  * scale;
    final float btnW    = 16f * scale;
    final float btnH    = 14f * scale;

    Font abbrevFont = ui.fonts().get(MStyle.FONT_META);
    Font valueFont  = ui.fonts().get(MStyle.FONT_BUTTON);
    Font modFont    = ui.fonts().get(MStyle.FONT_META);
    Font btnFont    = ui.fonts().get(MStyle.FONT_META);

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
          tileCX, ty + 14f * scale, abbrevFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

      MPainter.drawCenteredStringWithShadow(canvas, String.valueOf(scores[i]),
          tileCX, ty + 50f * scale, valueFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

      float btnY = ty + 34f * scale;
      scoreMinusButtons[i].bounds(tx + 2f, btnY, btnW, btnH);
      scoreMinusButtons[i].updateHover(mx, my);
      boolean minusHovered = scoreMinusButtons[i].isHovered();
      boolean minusEnabled = scores[i] > 1;
      int minusFill = !minusEnabled ? MStyle.BUTTON_FILL_DIS
          : minusHovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, tx + 2f, btnY, btnW, btnH, 2f,
          minusFill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
      int minusTextColor = minusEnabled ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
      MPainter.drawCenteredStringWithShadow(canvas, "−",
          tx + 2f + btnW / 2f, btnY + btnH * 0.72f, btnFont, minusTextColor, MStyle.TEXT_SHADOW);

      // [+] button (right side)
      scorePlusButtons[i].bounds(tx + tileW - btnW - 2f, btnY, btnW, btnH);
      scorePlusButtons[i].updateHover(mx, my);
      boolean plusHovered = scorePlusButtons[i].isHovered();
      boolean plusEnabled = stats.getRemainingAp() > 0;
      int plusFill = !plusEnabled ? MStyle.BUTTON_FILL_DIS
          : plusHovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
      MPainter.stoneSurface(canvas, tx + tileW - btnW - 2f, btnY, btnW, btnH, 2f,
          plusFill, MStyle.BUTTON_BORDER,
          MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
          MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
      int plusTextColor = plusEnabled ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
      MPainter.drawCenteredStringWithShadow(canvas, "+",
          tx + tileW - btnW - 2f + btnW / 2f, btnY + btnH * 0.72f, btnFont, plusTextColor, MStyle.TEXT_SHADOW);

      int mod = stats.getModifier(scores[i]);
      String modStr = (mod >= 0 ? "+" : "") + mod;
      MPainter.drawCenteredStringWithShadow(canvas, modStr,
          tileCX, ty + tileH - 10f * scale, modFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    }
  }

  // ─────────────────────────────────────────────── Right column — vitals

  private void drawHealthBar(Canvas canvas, float x, float y, float w, float scale) {
    Font font   = ui.fonts().get(MStyle.FONT_META);
    float hp    = stats.getHealth();
    float maxHp = stats.getMaxHealth();

    MPainter.drawStringWithShadow(canvas, "HP", x, y, font,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    float heartSize = 13f * scale;
    float startX    = x + 28f * scale;
    float heartTop  = y - heartSize;
    float filled    = maxHp > 0f ? (hp / maxHp) * 10f : 0f;

    for (int i = 0; i < 10; i++) {
      float hx    = startX + i * (heartSize + 2f * scale);
      int   color = i < filled ? COLOR_HEART : COLOR_HEART_DIM;
      drawHeart(canvas, hx, heartTop, heartSize, color);
    }

    String hpText = (int) hp + "/" + (int) maxHp;
    MPainter.drawStringWithShadow(canvas, hpText,
        startX + 10 * (heartSize + 2f * scale) + 6f * scale, y, font,
        MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
  }

  private void drawManaBar(Canvas canvas, float x, float y, float w, float scale) {
    Font font = ui.fonts().get(MStyle.FONT_META);

    MPainter.drawStringWithShadow(canvas, "MP", x, y, font,
        MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);

    float dSize  = 11f * scale;
    float startX = x + 28f * scale;
    float dTop   = y - dSize;

    for (int i = 0; i < 10; i++) {
      float dx = startX + i * (dSize + 2f * scale);
      drawDiamond(canvas, dx, dTop, dSize, COLOR_MANA_DIM);
    }

    MPainter.drawStringWithShadow(canvas, "0/0",
        startX + 10 * (dSize + 2f * scale) + 6f * scale, y, font,
        MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
  }

  private void drawStatusEffects(Canvas canvas, float x, float y, float w,
                                 float mx, float my, float scale) {
    Font labelFont = ui.fonts().get(MStyle.FONT_META);
    Font qFont     = ui.fonts().get(MStyle.FONT_ITEM);

    MPainter.drawStringWithShadow(canvas, "Status Effects", x, y, labelFont,
        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    int scaledSlot = Math.round(SLOT_SIZE * scale);
    float slotY = y + 6f * scale;
    float gap   = 6f * scale;

    for (int i = 0; i < 6; i++) {
      float sx = x + i * (scaledSlot + gap);
      MItemSlot slot = new MItemSlot().bounds(sx, slotY, scaledSlot, scaledSlot);
      slot.updateHover(mx, my);
      slot.render(ui);

      float qCX = sx + scaledSlot / 2f;
      float qCY = slotY + scaledSlot / 2f + MStyle.FONT_ITEM * 0.35f;
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
