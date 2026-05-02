package com.stonebreak.ui.pauseMenu;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * Skija/MasonryUI-backed renderer for the in-game pause menu. Mirrors the
 * pattern used by {@code SkijaMainMenuRenderer} so all menus share one GL
 * backend and the same Minecraft-style stone surface aesthetic.
 */
public final class SkijaPauseMenuRenderer {

    public static final float BUTTON_WIDTH  = 360f;
    public static final float BUTTON_HEIGHT = 50f;
    public static final float PANEL_WIDTH   = 520f;
    public static final float PANEL_HEIGHT  = 450f;

    private static final float TITLE_SIZE       = 42f;
    private static final float BUTTON_TEXT_SIZE = 20f;

    private static final int COLOR_TEXT_PRIMARY   = MStyle.TEXT_PRIMARY;
    private static final int COLOR_TEXT_SHADOW    = MStyle.TEXT_SHADOW;
    private static final int COLOR_TEXT_HIGHLIGHT = MStyle.TEXT_ACCENT;
    private static final int COLOR_OVERLAY        = 0x78000000; // ~120/255

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontButton;

    public SkijaPauseMenuRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void render(int windowWidth, int windowHeight,
                       boolean settingsHovered, boolean quitHovered) {
        if (backend == null || !backend.isAvailable()) return;
        ensureFonts();

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            float centerX = windowWidth  / 2f;
            float centerY = windowHeight / 2f;
            float panelX = centerX - PANEL_WIDTH  / 2f;
            float panelY = centerY - PANEL_HEIGHT / 2f;

            MPainter.panel(canvas, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);

            drawTitle(canvas, centerX, panelY + 70f, "GAME PAUSED");

            float buttonX = centerX - BUTTON_WIDTH / 2f;
            drawButton(canvas, "Resume Game",       buttonX, centerY - 60f, false);
            drawButton(canvas, "Settings",          buttonX, centerY + 10f, settingsHovered);
            drawButton(canvas, "Quit to Main Menu", buttonX, centerY + 80f, quitHovered);
        } finally {
            backend.endFrame();
        }
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle  = new Font(tf, TITLE_SIZE);
        fontButton = new Font(tf, BUTTON_TEXT_SIZE);
    }

    private void drawTitle(Canvas canvas, float cx, float cy, String title) {
        for (int i = 4; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = 0xFFFFDC64;
                case 1 -> color = 0xFFDCB450;
                default -> {
                    int v = Math.max(30, 100 - i * 20);
                    color = (0xC8 << 24) | (v << 16) | (v << 8) | v;
                }
            }
            float offset = i * 2.0f;
            MPainter.drawCenteredString(canvas, title, cx + offset, cy + offset, fontTitle, color);
        }
    }

    private void drawButton(Canvas canvas, String text, float x, float y, boolean highlighted) {
        int fill = highlighted ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;

        MPainter.stoneSurface(canvas, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        int textColor = highlighted ? COLOR_TEXT_HIGHLIGHT : COLOR_TEXT_PRIMARY;
        float tx = x + BUTTON_WIDTH / 2f;
        float ty = y + BUTTON_HEIGHT / 2f + 7f;
        MPainter.drawCenteredStringWithShadow(canvas, text, tx, ty, fontButton, textColor, COLOR_TEXT_SHADOW);
    }

    public void dispose() {
        if (fontTitle  != null) { fontTitle.close();  fontTitle  = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
    }
}
