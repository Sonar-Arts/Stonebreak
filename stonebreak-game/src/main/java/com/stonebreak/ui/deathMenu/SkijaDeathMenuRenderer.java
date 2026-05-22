package com.stonebreak.ui.deathMenu;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * Skija/MasonryUI-backed renderer for the death menu. Mirrors the pattern used
 * by {@code SkijaPauseMenuRenderer} so all menus share one GL backend and the
 * same Minecraft-style stone surface aesthetic.
 *
 * Draws a dark red overlay, a "You Died!" title, and a single Respawn button.
 */
public final class SkijaDeathMenuRenderer {

    public static final float BUTTON_WIDTH  = 360f;
    public static final float BUTTON_HEIGHT = 50f;

    private static final float BASE_TITLE_SIZE       = 96f;
    private static final float BASE_BUTTON_TEXT_SIZE = 20f;

    private static final int COLOR_TEXT_PRIMARY   = MStyle.TEXT_PRIMARY;
    private static final int COLOR_TEXT_SHADOW    = MStyle.TEXT_SHADOW;
    private static final int COLOR_TEXT_HIGHLIGHT = MStyle.TEXT_ACCENT;
    private static final int COLOR_TITLE          = 0xFFFF3232; // bright red
    private static final int COLOR_TITLE_SHADOW   = 0xFF280000; // dark red
    private static final int COLOR_OVERLAY        = 0xB4500000; // ~180/255 alpha, dark red

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontButton;
    private float lastFontScale = -1f;

    public SkijaDeathMenuRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void render(int windowWidth, int windowHeight, boolean respawnHovered) {
        if (backend == null || !backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);

        float buttonWidth  = BUTTON_WIDTH  * scale;
        float buttonHeight = BUTTON_HEIGHT * scale;

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            float centerX = windowWidth  / 2f;
            float centerY = windowHeight / 2f;

            drawTitle(canvas, centerX, centerY - 100f * scale, "You Died!");

            float buttonX = centerX - buttonWidth / 2f;
            float buttonY = centerY + 20f * scale;
            drawButton(canvas, "Respawn", buttonX, buttonY, respawnHovered, buttonWidth, buttonHeight);
        } finally {
            backend.endFrame();
        }
    }

    private void ensureFonts(float scale) {
        if (fontTitle != null && scale == lastFontScale) return;
        disposeFonts();
        lastFontScale = scale;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle  = new Font(tf, BASE_TITLE_SIZE * scale);
        fontButton = new Font(tf, BASE_BUTTON_TEXT_SIZE * scale);
    }

    private void disposeFonts() {
        if (fontTitle  != null) { fontTitle.close();  fontTitle  = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
    }

    private void drawTitle(Canvas canvas, float cx, float cy, String title) {
        // Shadow
        MPainter.drawCenteredString(canvas, title, cx + 4f, cy + 4f, fontTitle, COLOR_TITLE_SHADOW);
        // Main text
        MPainter.drawCenteredString(canvas, title, cx, cy, fontTitle, COLOR_TITLE);
    }

    private void drawButton(Canvas canvas, String text, float x, float y, boolean highlighted,
                            float buttonWidth, float buttonHeight) {
        int fill = highlighted ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;

        MPainter.stoneSurface(canvas, x, y, buttonWidth, buttonHeight, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        int textColor = highlighted ? COLOR_TEXT_HIGHLIGHT : COLOR_TEXT_PRIMARY;
        float tx = x + buttonWidth / 2f;
        float ty = y + buttonHeight / 2f + 7f;
        MPainter.drawCenteredStringWithShadow(canvas, text, tx, ty, fontButton, textColor, COLOR_TEXT_SHADOW);
    }

    public void dispose() {
        disposeFonts();
    }
}
