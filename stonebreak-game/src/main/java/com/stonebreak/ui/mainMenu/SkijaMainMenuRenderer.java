package com.stonebreak.ui.mainMenu;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.MainMenu;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
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

    private static final float BASE_BUTTON_WIDTH = 400f;
    private static final float BASE_BUTTON_HEIGHT = 40f;
    private static final float BASE_BUTTON_SPACING = 50f;
    private static final float BASE_SPLASH_SIZE = 18f;
    private static final float BASE_BUTTON_TEXT_SIZE = 20f;

    // Logo intrinsic viewBox is 1641 x 419 (aspect ~3.917).
    private static final float BASE_LOGO_HEIGHT = 140f;
    private static final float LOGO_ASPECT = 1641f / 419f;

    private static final int COLOR_TEXT_PRIMARY   = 0xFFFFFFF0;
    private static final int COLOR_TEXT_SHADOW    = 0xFF1A1A1A;
    private static final int COLOR_TEXT_HIGHLIGHT = 0xFFFFCC55;
    private static final int COLOR_OVERLAY        = 0x3C000000; // ~60/255


    private final SkijaUIBackend backend;

    private Font fontSplash;
    private Font fontButton;
    private float lastFontScale = -1f;

    private Shader dirtShader;

    public SkijaMainMenuRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void render(MainMenu menu, int windowWidth, int windowHeight) {
        if (!backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);
        ensureDirtShader();

        float buttonWidth = BASE_BUTTON_WIDTH * scale;
        float buttonHeight = BASE_BUTTON_HEIGHT * scale;
        float buttonSpacing = BASE_BUTTON_SPACING * scale;

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            drawBackground(canvas, windowWidth, windowHeight);

            float centerX = windowWidth / 2f;
            float centerY = windowHeight / 2f;

            float logoHeight = BASE_LOGO_HEIGHT * scale;
            float logoWidth = logoHeight * LOGO_ASPECT;
            float logoX = centerX - logoWidth / 2f;
            float logoY = centerY - 120f * scale - logoHeight / 2f;
            drawLogo(canvas, logoX, logoY, logoWidth, logoHeight);

            if (menu != null) {
                String splash = menu.getCurrentSplashText();
                if (splash != null && !splash.isEmpty()) {
                    float splashCx = centerX + logoWidth / 2f - 10f * scale;
                    float splashCy = logoY + logoHeight * 0.95f;
                    drawSplashText(canvas, splashCx, splashCy, splash);
                }
            }

            int selected = menu != null ? menu.getSelectedButton() : -1;
            drawButton(canvas, "Singleplayer", centerX - buttonWidth / 2f,
                    centerY - 20f * scale, selected == 0, buttonWidth, buttonHeight);
            drawButton(canvas, "Multiplayer", centerX - buttonWidth / 2f,
                    centerY - 20f * scale + buttonSpacing, selected == 1, buttonWidth, buttonHeight);
            drawButton(canvas, "Settings", centerX - buttonWidth / 2f,
                    centerY - 20f * scale + buttonSpacing * 2f, selected == 2, buttonWidth, buttonHeight);
            drawButton(canvas, "Quit Game", centerX - buttonWidth / 2f,
                    centerY - 20f * scale + buttonSpacing * 3f, selected == 3, buttonWidth, buttonHeight);
        } finally {
            backend.endFrame();
        }
    }

    private void ensureFonts(float scale) {
        if (fontSplash != null && scale == lastFontScale) return;
        disposeFonts();
        lastFontScale = scale;
        Typeface tf = backend.getMinecraftTypeface();
        fontSplash = new Font(tf, BASE_SPLASH_SIZE * scale);
        fontButton = new Font(tf, BASE_BUTTON_TEXT_SIZE * scale);
    }

    private void disposeFonts() {
        if (fontSplash != null) { fontSplash.close(); fontSplash = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
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

    private void drawLogo(Canvas canvas, float x, float y, float w, float h) {
        Image logo = backend.getStonebreakLogo();
        if (logo == null) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        // Soft drop shadow grounds the logo against the dirt background.
        try (ImageFilter shadow = ImageFilter.makeDropShadow(
                0f, 4f * scale, 6f * scale, 6f * scale, 0xC0000000, null);
             Paint paint = new Paint().setImageFilter(shadow)) {
            canvas.drawImageRect(logo, Rect.makeXYWH(x, y, w, h), paint);
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


    private void drawCentered(Canvas canvas, String text, float cx, float y, Font font, int color) {
        if (text == null || text.isEmpty()) return;
        float width = font.measureTextWidth(text);
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, cx - width / 2f, y, font, p);
        }
    }

    public void dispose() {
        if (dirtShader != null) { dirtShader.close(); dirtShader = null; }
        disposeFonts();
    }
}
