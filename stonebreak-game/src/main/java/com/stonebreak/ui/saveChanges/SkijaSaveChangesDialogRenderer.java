package com.stonebreak.ui.saveChanges;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * Skija/MasonryUI-backed renderer for the "Save changes?" confirmation that
 * appears when exiting the inventory/character sheet with unspent RPG edits.
 * Mirrors {@code SkijaPauseMenuRenderer}: full-screen dim overlay, centered
 * stone panel, title, and a Yes/No button pair. Geometry lives here so the
 * dialog's hit-tests can never drift from what is drawn.
 */
public final class SkijaSaveChangesDialogRenderer {

    public static final float BASE_PANEL_W = 400f;
    public static final float BASE_PANEL_H = 170f;
    public static final float BASE_BTN_W   = 130f;
    public static final float BASE_BTN_H   = 42f;
    public static final float BASE_BTN_GAP = 24f;

    private static final float BASE_TITLE_SIZE = 30f;
    private static final float BASE_BTN_TEXT_SIZE = 20f;

    private static final int COLOR_OVERLAY = 0x90000000;
    private static final int COLOR_TEXT_SHADOW = MStyle.TEXT_SHADOW;

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontButton;
    private float lastFontScale = -1f;

    public SkijaSaveChangesDialogRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    // ─────────────────────────────────────────────── Geometry (single source)

    private record Geo(float panelX, float panelY, float panelW, float panelH,
                       float yesX, float noX, float btnY, float btnW, float btnH) {}

    private Geo geo(int windowWidth, int windowHeight, float scale) {
        float panelW = BASE_PANEL_W * scale;
        float panelH = BASE_PANEL_H * scale;
        float btnW   = BASE_BTN_W   * scale;
        float btnH   = BASE_BTN_H   * scale;
        float gap    = BASE_BTN_GAP * scale;
        float panelX = windowWidth  / 2f - panelW / 2f;
        float panelY = windowHeight / 2f - panelH / 2f;
        float startX = panelX + (panelW - (btnW * 2f + gap)) / 2f;
        float btnY   = panelY + panelH - btnH - 22f * scale;
        return new Geo(panelX, panelY, panelW, panelH,
                startX, startX + btnW + gap, btnY, btnW, btnH);
    }

    public boolean hitYes(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return hit(geo(windowWidth, windowHeight, com.stonebreak.config.Settings.getInstance().getUiScale()),
                mouseX, mouseY, true);
    }

    public boolean hitNo(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return hit(geo(windowWidth, windowHeight, com.stonebreak.config.Settings.getInstance().getUiScale()),
                mouseX, mouseY, false);
    }

    private static boolean hit(Geo g, float mx, float my, boolean yes) {
        float x = yes ? g.yesX() : g.noX();
        return mx >= x && mx <= x + g.btnW() && my >= g.btnY() && my <= g.btnY() + g.btnH();
    }

    // ─────────────────────────────────────────────── Render

    public void render(int windowWidth, int windowHeight, boolean yesHovered, boolean noHovered) {
        if (backend == null || !backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);
        Geo g = geo(windowWidth, windowHeight, scale);

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            MPainter.panel(canvas, g.panelX(), g.panelY(), g.panelW(), g.panelH());

            MPainter.drawCenteredStringWithShadow(canvas, "Save changes?",
                    windowWidth / 2f, g.panelY() + 56f * scale,
                    fontTitle, MStyle.TEXT_ACCENT, COLOR_TEXT_SHADOW);

            drawButton(canvas, "Yes", g.yesX(), g.btnY(), g.btnW(), g.btnH(), yesHovered);
            drawButton(canvas, "No",  g.noX(),  g.btnY(), g.btnW(), g.btnH(), noHovered);
        } finally {
            backend.endFrame();
        }
    }

    private void drawButton(Canvas canvas, String text, float x, float y,
                            float btnW, float btnH, boolean highlighted) {
        int fill = highlighted ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, x, y, btnW, btnH, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        int textColor = highlighted ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        float ty = y + btnH / 2f + 7f * com.stonebreak.config.Settings.getInstance().getUiScale();
        MPainter.drawCenteredStringWithShadow(canvas, text, x + btnW / 2f, ty,
                fontButton, textColor, COLOR_TEXT_SHADOW);
    }

    private void ensureFonts(float scale) {
        if (fontTitle != null && scale == lastFontScale) return;
        disposeFonts();
        lastFontScale = scale;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle  = new Font(tf, BASE_TITLE_SIZE * scale);
        fontButton = new Font(tf, BASE_BTN_TEXT_SIZE * scale);
    }

    private void disposeFonts() {
        if (fontTitle  != null) { fontTitle.close();  fontTitle  = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
    }

    public void dispose() {
        disposeFonts();
    }
}
