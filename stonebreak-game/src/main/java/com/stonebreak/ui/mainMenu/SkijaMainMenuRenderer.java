package com.stonebreak.ui.mainMenu;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.MainMenu;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * Skija-backed renderer for the main menu. Mirrors the former NanoVG render so
 * Stonebreak's intro screen and the Skija-native world-select screen share the
 * same GL backend. Owning a single backend for every menu is what makes the
 * dirt-texture background recoverable when the player pops between screens —
 * with two backends we were corrupting GL state on every transition.
 */
public final class SkijaMainMenuRenderer {

    private static final float BUTTON_WIDTH = 400f;
    private static final float BUTTON_HEIGHT = 40f;
    private static final float BUTTON_SPACING = 50f;
    private static final float TITLE_SIZE = 56f;
    private static final float SPLASH_SIZE = 18f;
    private static final float BUTTON_TEXT_SIZE = 20f;

    private static final int COLOR_TEXT_PRIMARY   = 0xFFFFFFF0;
    private static final int COLOR_TEXT_SHADOW    = 0xFF1A1A1A;
    private static final int COLOR_TEXT_HIGHLIGHT = 0xFFFFCC55;
    private static final int COLOR_OVERLAY        = 0x3C000000; // ~60/255


    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontSplash;
    private Font fontButton;

    private Shader dirtShader;

    public SkijaMainMenuRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void render(MainMenu menu, int windowWidth, int windowHeight) {
        if (!backend.isAvailable()) return;
        ensureFonts();
        ensureDirtShader();

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            drawBackground(canvas, windowWidth, windowHeight);

            float centerX = windowWidth / 2f;
            float centerY = windowHeight / 2f;

            String titleText = "STONEBREAK";
            float titleY = centerY - 120f;
            drawTitle(canvas, centerX, titleY, titleText);

            if (menu != null) {
                String splash = menu.getCurrentSplashText();
                if (splash != null && !splash.isEmpty()) {
                    // Anchor splash to the title's bottom-right corner so it
                    // hangs off the last letter like the classic MC splash.
                    float titleWidth = fontTitle.measureTextWidth(titleText);
                    float splashCx = centerX + titleWidth / 2f - 10f;
                    float splashCy = titleY + TITLE_SIZE * 0.45f;
                    drawSplashText(canvas, splashCx, splashCy, splash);
                }
            }

            int selected = menu != null ? menu.getSelectedButton() : -1;
            drawButton(canvas, "Singleplayer", centerX - BUTTON_WIDTH / 2f,
                    centerY - 20f, selected == 0);
            drawButton(canvas, "Settings", centerX - BUTTON_WIDTH / 2f,
                    centerY - 20f + BUTTON_SPACING, selected == 1);
            drawButton(canvas, "Quit Game", centerX - BUTTON_WIDTH / 2f,
                    centerY - 20f + BUTTON_SPACING * 2f, selected == 2);
        } finally {
            backend.endFrame();
        }
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle  = new Font(tf, TITLE_SIZE);
        fontSplash = new Font(tf, SPLASH_SIZE);
        fontButton = new Font(tf, BUTTON_TEXT_SIZE);
    }

    private void ensureDirtShader() {
        if (dirtShader != null) return;
        Image dirt = backend.getDirtTexture();
        if (dirt == null) return;
        dirtShader = dirt.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    private void drawBackground(Canvas canvas, int w, int h) {
        try (Paint p = new Paint().setColor(0xFF2C2C2C)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        if (dirtShader != null) {
            try (Paint p = new Paint().setShader(dirtShader)) {
                canvas.save();
                canvas.scale(4f, 4f);
                canvas.drawRect(Rect.makeXYWH(0, 0, w / 4f, h / 4f), p);
                canvas.restore();
            }
        }
        try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
    }

    private void drawTitle(Canvas canvas, float cx, float cy, String title) {
        for (int i = 6; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = COLOR_TEXT_PRIMARY;
                case 1 -> color = 0xFFC8C8BE;
                default -> {
                    int v = Math.max(20, 80 - i * 15);
                    color = (0xDC << 24) | (v << 16) | (v << 8) | v;
                }
            }
            float offset = i * 2.5f;
            drawCentered(canvas, title, cx + offset, cy + offset, fontTitle, color);
        }
    }

    private void drawSplashText(Canvas canvas, float cx, float cy, String splash) {
        long now = System.currentTimeMillis();
        float t = (now % 500L) / 500.0f;
        float scale = 1.0f + (float) (Math.sin(t * Math.PI * 2.0) * 0.05);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(-15f);
        canvas.scale(scale, scale);

        for (int i = 3; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = 0xFFFFFF55;
                case 1 -> color = 0xFFDCDC46;
                default -> {
                    int v = Math.max(20, 60 - i * 20);
                    color = (0xB4 << 24) | (v << 16) | (v << 8) | v;
                }
            }
            float offset = i * 1.5f;
            drawCentered(canvas, splash, offset, offset, fontSplash, color);
        }
        canvas.restore();
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


    private void drawCentered(Canvas canvas, String text, float cx, float y, Font font, int color) {
        if (text == null || text.isEmpty()) return;
        float width = font.measureTextWidth(text);
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, cx - width / 2f, y, font, p);
        }
    }

    public void dispose() {
        if (dirtShader != null) { dirtShader.close(); dirtShader = null; }
        if (fontTitle  != null) { fontTitle.close();  fontTitle  = null; }
        if (fontSplash != null) { fontSplash.close(); fontSplash = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
    }
}
