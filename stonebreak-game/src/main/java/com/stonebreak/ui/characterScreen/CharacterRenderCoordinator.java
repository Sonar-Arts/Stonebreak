package com.stonebreak.ui.characterScreen;

import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import java.util.List;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.Rect;
import org.joml.Vector2f;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Renders the Character Screen using Skija/MasonryUI (Phase A only — no GL item icons needed).
 *
 * Visual theme: "Stone Tablet" — the panel reads like an ancient carved stone record.
 * Engraved horizontal rules divide sections; ability score tiles are recessed stone
 * insets; a simple pawn silhouette occupies the left column.
 */
public class CharacterRenderCoordinator {

    // ─── Panel geometry ───────────────────────────────────────────────────────
    private static final int   PANEL_WIDTH  = 600;
    private static final int   PANEL_HEIGHT = 480;
    private static final int   TAB_HEIGHT   = 28;
    private static final int   TAB_WIDTH    = 100;
    private static final int   SLOT_SIZE    = 34;

    // Semi-transparent panel fill (75% opacity) — same as inventory
    private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;

    // Tab fills
    private static final int TAB_ACTIVE_FILL   = 0xFF7A7A7A; // lighter = active
    private static final int TAB_INACTIVE_FILL = MStyle.BUTTON_FILL;

    // Stat tile colors (recessed inset, same as MItemSlot)
    private static final int STAT_TILE_FILL   = 0xFF252525;
    private static final int STAT_TILE_BORDER = 0xFF151515;

    // Vitals colors
    private static final int COLOR_HEART     = 0xFFCC2222;
    private static final int COLOR_HEART_DIM = 0xFF551111;
    private static final int COLOR_MANA_DIM  = 0xFF334466;

    // Silhouette colors
    private static final int SILHOUETTE_FILL   = 0xFF4A4A4A;
    private static final int SILHOUETTE_STROKE = 0xFF2A2A2A;

    // ─── Ability score abbreviations ─────────────────────────────────────────
    private static final String[] ABILITY_ABBREV = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

    // ─── Fields ───────────────────────────────────────────────────────────────
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final CharacterStats stats;
    private final CharacterController controller;
    private final MasonryUI ui;

    // Tab buttons — bounds updated each frame so hit-tests stay accurate
    private final MButton tabInventory;
    private final MButton tabCharacter;

    // ─────────────────────────────────────────────────────────────────────────

    public CharacterRenderCoordinator(Renderer renderer, InputHandler inputHandler,
                                      CharacterStats stats, CharacterController controller) {
        this.renderer    = renderer;
        this.inputHandler = inputHandler;
        this.stats       = stats;
        this.controller  = controller;

        this.ui = new MasonryUI(renderer.getSkijaBackend());

        // Click callbacks are handled in handleMouseInput; buttons here are purely visual.
        this.tabInventory = new MButton("Inventory").fontSize(MStyle.FONT_META);
        this.tabCharacter = new MButton("Character").fontSize(MStyle.FONT_META);
    }

    // ─────────────────────────────────────────────── Public entry points

    public void render(int screenWidth, int screenHeight) {
        if (!ui.beginFrame(screenWidth, screenHeight, 1.0f)) return;

        float px = (screenWidth  - PANEL_WIDTH)  / 2f;
        float py = (screenHeight - PANEL_HEIGHT) / 2f;

        Vector2f mouse = inputHandler.getMousePosition();
        float mx = mouse.x;
        float my = mouse.y;

        Canvas canvas = ui.canvas();

        // ── Tab bar (drawn above panel) ──────────────────────────────────────
        updateTabBounds(px, py);
        tabInventory.updateHover(mx, my);
        tabCharacter.updateHover(mx, my);
        drawTabBar(canvas, px, py);

        // ── Main panel ───────────────────────────────────────────────────────
        MPainter.stoneSurface(canvas, px, py, PANEL_WIDTH, PANEL_HEIGHT, MStyle.PANEL_RADIUS,
                PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

        // ── Engraved rule below tab area ─────────────────────────────────────
        drawEngravedRule(canvas, px + 10, py + 12, PANEL_WIDTH - 20);

        // ── Column layout ────────────────────────────────────────────────────
        float leftColX = px + 16;
        float leftColW = PANEL_WIDTH * 0.38f;
        float rightColX = leftColX + leftColW + 12f;
        float rightColW = PANEL_WIDTH - leftColW - 44f;
        float contentY  = py + 20f;

        // ── Left column: silhouette + class + feats + currencies ─────────────
        drawCharacterSilhouette(canvas, leftColX, contentY, leftColW);
        drawClassAndFeats(canvas, leftColX, contentY + 162f, leftColW);
        drawCurrencies(canvas, leftColX, contentY + 300f, leftColW);

        // ── Right column: ability scores ─────────────────────────────────────
        drawAbilityScores(canvas, rightColX, contentY, rightColW, mx, my);

        // ── Right column: vitals ─────────────────────────────────────────────
        float barSectionY = contentY + 190f;
        drawHealthBar(canvas, rightColX, barSectionY, rightColW);
        drawManaBar(canvas,   rightColX, barSectionY + 32f, rightColW);

        // ── Right column: status effects ─────────────────────────────────────
        drawStatusEffects(canvas, rightColX, barSectionY + 74f, rightColW, mx, my);

        ui.renderOverlays();
        ui.endFrame();
    }

    public void handleMouseInput(int screenWidth, int screenHeight) {
        Vector2f mouse = inputHandler.getMousePosition();
        float mx = mouse.x;
        float my = mouse.y;

        if (!inputHandler.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) return;

        float px = (screenWidth  - PANEL_WIDTH)  / 2f;
        float py = (screenHeight - PANEL_HEIGHT) / 2f;
        updateTabBounds(px, py);

        // Inventory tab — close character screen, open inventory
        if (tabInventory.contains(mx, my)) {
            controller.setVisible(false);
            Game.getInstance().toggleInventoryScreen();
            inputHandler.consumeMouseButtonPress(GLFW_MOUSE_BUTTON_LEFT);
        }
        // Character tab click is a no-op (already on this screen)
    }

    // ─────────────────────────────────────────────── Tab bar

    private void updateTabBounds(float px, float py) {
        float tabY = py - TAB_HEIGHT; // flush against panel top
        tabInventory.bounds(px,                    tabY, TAB_WIDTH, TAB_HEIGHT);
        tabCharacter.bounds(px + TAB_WIDTH + 4f,   tabY, TAB_WIDTH, TAB_HEIGHT);
    }

    private void drawTabBar(Canvas canvas, float px, float py) {
        float tabY = py - TAB_HEIGHT;
        drawTab(canvas, px, tabY, TAB_WIDTH, TAB_HEIGHT,
                "Inventory", false, tabInventory.isHovered());
        drawTab(canvas, px + TAB_WIDTH + 4f, tabY, TAB_WIDTH, TAB_HEIGHT,
                "Character", true, tabCharacter.isHovered());
    }

    /**
     * Draws a single stone-surface tab. The active tab uses a lighter fill so it
     * reads as the currently selected page; inactive tabs recede with button color.
     */
    private void drawTab(Canvas canvas, float x, float y, float w, float h,
                         String label, boolean active, boolean hovered) {
        int fill = active ? TAB_ACTIVE_FILL
                : hovered ? MStyle.BUTTON_FILL_HI
                : TAB_INACTIVE_FILL;
        MPainter.stoneSurface(canvas, x, y, w, h, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        Font font  = ui.fonts().get(MStyle.FONT_META);
        int  color = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        // Baseline at roughly vertical center of tab
        float ty = y + h * 0.5f + MStyle.FONT_META * 0.38f;
        MPainter.drawCenteredStringWithShadow(canvas, label, x + w / 2f, ty, font, color, MStyle.TEXT_SHADOW);
    }

    // ─────────────────────────────────────────────── Decorative helpers

    /**
     * Two-line engraved rule: 1px dark line (shadow) then 1px light line (highlight)
     * two pixels below. Gives the "carved into stone" look.
     */
    private void drawEngravedRule(Canvas canvas, float x, float y, float w) {
        try (Paint dark  = new Paint().setColor(0x66000000);
             Paint light = new Paint().setColor(0x33FFFFFF)) {
            canvas.drawRect(Rect.makeXYWH(x, y,       w, 1f), dark);
            canvas.drawRect(Rect.makeXYWH(x, y + 2f, w, 1f), light);
        }
    }

    // ─────────────────────────────────────────────── Left column

    /**
     * Draws a simple humanoid pawn silhouette using Skija Paths.
     * Circle head + trapezoid body — intentionally low-detail as a placeholder
     * for a future 3D character model viewport.
     */
    private void drawCharacterSilhouette(Canvas canvas, float colX, float colY, float colW) {
        float cx   = colX + colW / 2f;
        float topY = colY + 16f;

        try (Paint fill   = new Paint().setColor(SILHOUETTE_FILL).setAntiAlias(true);
             Paint stroke = new Paint().setColor(SILHOUETTE_STROKE)
                     .setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {

            // Head: circle
            float headR  = 22f;
            float headCX = cx;
            float headCY = topY + headR;
            canvas.drawCircle(headCX, headCY, headR, fill);
            canvas.drawCircle(headCX, headCY, headR, stroke);

            // Body: symmetric trapezoid (wider at shoulders)
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

    /**
     * Draws the class label and feats list beneath the silhouette.
     */
    private void drawClassAndFeats(Canvas canvas, float colX, float colY, float colW) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);

        // Section divider
        drawEngravedRule(canvas, colX, colY, colW);
        float y = colY + 14f;

        // Class line
        String classLine = "Class: " + stats.getCharacterClass();
        MPainter.drawStringWithShadow(canvas, classLine, colX + 4f, y, metaFont,
                MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        y += 22f;

        // Feats divider
        drawEngravedRule(canvas, colX, y, colW);
        y += 12f;

        // "Feats:" label
        MPainter.drawStringWithShadow(canvas, "Feats:", colX + 4f, y, metaFont,
                MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        y += 18f;

        List<String> feats = stats.getFeats();
        if (feats.isEmpty()) {
            MPainter.drawStringWithShadow(canvas, "(None)", colX + 8f, y, metaFont,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
        } else {
            for (String feat : feats) {
                MPainter.drawStringWithShadow(canvas, feat, colX + 8f, y, metaFont,
                        MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
                y += 18f;
            }
        }
    }

    // ─────────────────────────────────────────────── Left column — currencies

    /**
     * Draws CP, SP, and FP point totals beneath the feats section.
     * All values are placeholder stubs until the level-up system is implemented.
     */
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

    /**
     * Draws a 3×2 grid of recessed stat tiles for the six ability scores.
     * Each tile shows the abbreviation, numeric value, and derived modifier.
     */
    private void drawAbilityScores(Canvas canvas, float colX, float colY,
                                   float colW, float mx, float my) {
        int[] scores = {
            stats.getStrength(),      stats.getDexterity(),    stats.getConstitution(),
            stats.getIntelligence(),  stats.getWisdom(),       stats.getCharisma()
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

            // Recessed tile (inverted bevel = "pressed in" look)
            MPainter.stoneSurface(canvas, tx, ty, tileW, tileH, 3f,
                    STAT_TILE_FILL, STAT_TILE_BORDER,
                    0x55000000,  // shadow on top+left (inset)
                    0x1AFFFFFF,  // highlight on bottom+right (inset)
                    0,
                    MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

            // Hover tint
            if (mx >= tx && mx <= tx + tileW && my >= ty && my <= ty + tileH) {
                MPainter.fillRoundedRect(canvas, tx + 1, ty + 1, tileW - 2, tileH - 2, 3f, 0x22FFFFFF);
            }

            float tileCX = tx + tileW / 2f;

            // Abbreviation at top
            MPainter.drawCenteredStringWithShadow(canvas, ABILITY_ABBREV[i],
                    tileCX, ty + 15f, abbrevFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

            // Score value — large, centered
            MPainter.drawCenteredStringWithShadow(canvas, String.valueOf(scores[i]),
                    tileCX, ty + tileH / 2f + 10f, valueFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

            // Modifier below value
            int mod = stats.getModifier(scores[i]);
            String modStr = (mod >= 0 ? "+" : "") + mod;
            MPainter.drawCenteredStringWithShadow(canvas, modStr,
                    tileCX, ty + tileH - 8f, modFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Right column — vitals

    /**
     * Draws a health bar using ten heart shapes. Partial hearts are shown at the
     * halfway point (score rounds up to nearest half-heart).
     */
    private void drawHealthBar(Canvas canvas, float x, float y, float w) {
        Font font   = ui.fonts().get(MStyle.FONT_META);
        float hp    = stats.getHealth();
        float maxHp = stats.getMaxHealth();

        MPainter.drawStringWithShadow(canvas, "HP", x, y, font, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

        float heartSize = 13f;
        float startX    = x + 28f;
        float heartTop  = y - heartSize;

        // How many hearts filled (0.0–10.0)
        float filled = maxHp > 0f ? (hp / maxHp) * 10f : 0f;

        for (int i = 0; i < 10; i++) {
            float hx    = startX + i * (heartSize + 2f);
            int   color = i < filled ? COLOR_HEART : COLOR_HEART_DIM;
            drawHeart(canvas, hx, heartTop, heartSize, color);
        }

        String hpText = (int) hp + "/" + (int) maxHp;
        MPainter.drawStringWithShadow(canvas, hpText,
                startX + 10 * (heartSize + 2f) + 6f, y, font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
    }

    /**
     * Draws a mana bar using ten diamond shapes. Currently all dims (mana = 0).
     */
    private void drawManaBar(Canvas canvas, float x, float y, float w) {
        Font font = ui.fonts().get(MStyle.FONT_META);

        MPainter.drawStringWithShadow(canvas, "MP", x, y, font, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);

        float dSize  = 11f;
        float startX = x + 28f;
        float dTop   = y - dSize;

        for (int i = 0; i < 10; i++) {
            float dx = startX + i * (dSize + 2f);
            drawDiamond(canvas, dx, dTop, dSize, COLOR_MANA_DIM);
        }

        MPainter.drawStringWithShadow(canvas, "0/0",
                startX + 10 * (dSize + 2f) + 6f, y, font, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
    }

    // ─────────────────────────────────────────────── Right column — status effects

    /**
     * Draws six empty status effect slots. Each slot shows a dim "?" until
     * the status effect system is implemented.
     */
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

            // Dim question mark inside each empty slot
            float qCX = sx + SLOT_SIZE / 2f;
            float qCY = slotY + SLOT_SIZE / 2f + MStyle.FONT_ITEM * 0.35f;
            MPainter.drawCenteredStringWithShadow(canvas, "?", qCX, qCY, qFont,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Icon primitives

    /**
     * Draws a filled heart using two overlapping circles (top lobes) and a
     * downward-pointing triangle (bottom tip).
     */
    private void drawHeart(Canvas canvas, float x, float y, float s, int color) {
        try (Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
            float r  = s * 0.27f;
            float lx = x + r;
            float rx = x + s - r;
            float ly = y + r;

            // Two top lobes
            canvas.drawCircle(lx, ly, r, fill);
            canvas.drawCircle(rx, ly, r, fill);

            // Bottom triangle
            try (PathBuilder triBuilder = new PathBuilder()) {
                triBuilder.moveTo(x,         ly);
                triBuilder.lineTo(x + s / 2f, y + s);
                triBuilder.lineTo(x + s,      ly);
                triBuilder.closePath();
                try (Path tri = triBuilder.build()) {
                    canvas.drawPath(tri, fill);
                }
            }
        }
    }

    /**
     * Draws a filled diamond (rotated square) centered in the bounding square.
     */
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
