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
    /** Vertical distance between consecutive button tops (base, pre-UI-scale). */
    public static final float BUTTON_SPACING = 70f;

    private static final float BASE_BUTTON_WIDTH  = BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = BUTTON_HEIGHT;
    private static final float BASE_PANEL_WIDTH   = 520f;
    private static final float BASE_PANEL_HEIGHT  = 560f;

    private static final float BASE_TITLE_SIZE       = 42f;
    private static final float BASE_BUTTON_TEXT_SIZE = 20f;

    private static final int COLOR_TEXT_PRIMARY   = MStyle.TEXT_PRIMARY;
    private static final int COLOR_TEXT_SHADOW    = MStyle.TEXT_SHADOW;
    private static final int COLOR_TEXT_HIGHLIGHT = MStyle.TEXT_ACCENT;
    private static final int COLOR_OVERLAY        = 0x78000000; // ~120/255

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontButton;
    private float lastFontScale = -1f;

    public SkijaPauseMenuRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    /**
     * Base (pre-UI-scale) vertical offset of a button's top from screen center, for a
     * column of {@code count} buttons centered on the screen. Single source of the layout —
     * {@code PauseMenu}'s hit-tests use the same formula, so they can never drift apart.
     */
    public static float buttonOffset(int slot, int count) {
        return (slot - (count - 1) / 2f) * BUTTON_SPACING;
    }

    public void render(int windowWidth, int windowHeight,
                       boolean statisticsHovered, boolean glossaryHovered, boolean settingsHovered,
                       boolean resyncVisible, boolean resyncHovered, boolean quitHovered) {
        if (backend == null || !backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);

        float buttonWidth  = BASE_BUTTON_WIDTH  * scale;
        float buttonHeight = BASE_BUTTON_HEIGHT * scale;
        float panelWidth   = BASE_PANEL_WIDTH   * scale;
        float panelHeight  = BASE_PANEL_HEIGHT  * scale;

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            float centerX = windowWidth  / 2f;
            float centerY = windowHeight / 2f;
            float panelX = centerX - panelWidth  / 2f;
            float panelY = centerY - panelHeight / 2f;

            MPainter.panel(canvas, panelX, panelY, panelWidth, panelHeight);

            drawTitle(canvas, centerX, panelY + 70f * scale, "GAME PAUSED");

            float buttonX = centerX - buttonWidth / 2f;
            int count = resyncVisible ? 6 : 5;
            int slot = 0;
            drawButton(canvas, "Resume Game", buttonX, centerY + buttonOffset(slot++, count) * scale, false,             buttonWidth, buttonHeight);
            drawButton(canvas, "Statistics",  buttonX, centerY + buttonOffset(slot++, count) * scale, statisticsHovered, buttonWidth, buttonHeight);
            drawButton(canvas, "Glossary",    buttonX, centerY + buttonOffset(slot++, count) * scale, glossaryHovered,   buttonWidth, buttonHeight);
            drawButton(canvas, "Settings",    buttonX, centerY + buttonOffset(slot++, count) * scale, settingsHovered,   buttonWidth, buttonHeight);
            if (resyncVisible) {
                drawButton(canvas, "Resync World", buttonX, centerY + buttonOffset(slot++, count) * scale, resyncHovered, buttonWidth, buttonHeight);
            }
            drawButton(canvas, "Quit to Main Menu", buttonX, centerY + buttonOffset(slot, count) * scale, quitHovered, buttonWidth, buttonHeight);
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

    private void drawButton(Canvas canvas, String text, float x, float y, boolean highlighted,
                            float buttonWidth, float buttonHeight) {
        int fill = highlighted ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;

        MPainter.stoneSurface(canvas, x, y, buttonWidth, buttonHeight, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        int textColor = highlighted ? COLOR_TEXT_HIGHLIGHT : COLOR_TEXT_PRIMARY;
        float tx = x + buttonWidth / 2f;
        float ty = y + buttonHeight / 2f + 7f * com.stonebreak.config.Settings.getInstance().getUiScale();
        MPainter.drawCenteredStringWithShadow(canvas, text, tx, ty, fontButton, textColor, COLOR_TEXT_SHADOW);
    }

    public void dispose() {
        disposeFonts();
    }
}
