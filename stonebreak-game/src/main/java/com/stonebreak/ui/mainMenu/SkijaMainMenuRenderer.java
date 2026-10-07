package com.stonebreak.ui.mainMenu;

import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.stonebreak.ui.MainMenu;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
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


    private final SkijaUIBackend backend;
    private final MainMenuBackdrop backdrop = new MainMenuBackdrop();
    private java.util.function.DoubleSupplier timeSource = SkijaMainMenuRenderer::spaceTime;
    private java.util.function.BiConsumer<String, float[]> layoutSink;

    private Font fontSplash;
    private Font fontButton;
    private float lastFontScale = -1f;

    public SkijaMainMenuRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    /**
     * Screen-space rectangle the logo occupies. Shared by the renderer and the
     * controller's click hit-test so both agree on the title bounds.
     */
    public static Rect computeLogoRect(int windowWidth, int windowHeight, float scale) {
        float centerX = windowWidth / 2f;
        float centerY = windowHeight / 2f;
        float logoHeight = BASE_LOGO_HEIGHT * scale;
        float logoWidth = logoHeight * LOGO_ASPECT;
        float logoX = centerX - logoWidth / 2f;
        float logoY = centerY - 120f * scale - logoHeight / 2f;
        return Rect.makeXYWH(logoX, logoY, logoWidth, logoHeight);
    }

    public void render(MainMenu menu, int windowWidth, int windowHeight) {
        if (!backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);

        float buttonWidth = BASE_BUTTON_WIDTH * scale;
        float buttonHeight = BASE_BUTTON_HEIGHT * scale;
        float buttonSpacing = BASE_BUTTON_SPACING * scale;

        MainMenuStage stage = menu != null ? menu.getStage() : null;

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            int frameSave = canvas.save();
            if (stage != null) {
                canvas.translate(stage.getScreenShakeX(), stage.getScreenShakeY());
            }

            backdrop.paint(canvas, windowWidth, windowHeight, stage, scale, (float) timeSource.getAsDouble());

            float centerX = windowWidth / 2f;
            float centerY = windowHeight / 2f;

            Rect logoRect = computeLogoRect(windowWidth, windowHeight, scale);
            report("logo", logoRect.getLeft(), logoRect.getTop(), logoRect.getWidth(), logoRect.getHeight());
            drawLogo(canvas, logoRect, stage);

            if (menu != null) {
                String splash = menu.getCurrentSplashText();
                if (splash != null && !splash.isEmpty()) {
                    float[] anchor = splashAnchor(windowWidth, windowHeight, scale);
                    drawSplashText(canvas, anchor[0], anchor[1], splash);
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

            canvas.restoreToCount(frameSave);
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

    static double spaceTime() {
        Game game = Game.getInstance();
        return game != null ? game.getTotalTimeElapsed() : 0f;
    }

    /** Where the space scene's clock comes from (the game's elapsed time; fixtures pin it). */
    public void setTimeSource(java.util.function.DoubleSupplier source) {
        this.timeSource = source == null ? SkijaMainMenuRenderer::spaceTime : source;
    }

    /** Receives {@code logo} and the button rects ({@code singleplayer} ... {@code quit}): the gate's oracle (#299). */
    public void setLayoutSink(java.util.function.BiConsumer<String, float[]> sink) {
        this.layoutSink = sink;
    }

    private void report(String part, float x, float y, float w, float h) {
        if (layoutSink != null) {
            layoutSink.accept(part, new float[]{x, y, w, h});
        }
    }

    private void drawLogo(Canvas canvas, Rect rect, MainMenuStage stage) {
        Image logo = backend.getStonebreakLogo();
        if (logo == null) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();

        float offsetX = stage != null ? stage.getTitleOffsetX() : 0f;
        float offsetY = stage != null ? stage.getTitleOffsetY() : 0f;
        float titleScale = stage != null ? stage.getTitleScale() : 1f;
        float rotation = stage != null ? stage.getTitleRotationDeg() : 0f;
        float cx = rect.getLeft() + rect.getWidth() / 2f;
        float cy = rect.getTop() + rect.getHeight() / 2f;

        int save = canvas.save();
        canvas.translate(cx + offsetX, cy + offsetY);
        canvas.rotate(rotation);
        canvas.scale(titleScale, titleScale);
        canvas.translate(-cx, -cy);
        // Soft drop shadow grounds the logo against the background.
        try (ImageFilter shadow = ImageFilter.makeDropShadow(
                0f, 4f * scale, 6f * scale, 6f * scale, 0xC0000000, null);
             Paint paint = new Paint().setImageFilter(shadow)) {
            canvas.drawImageRect(logo, rect, paint);
        }
        canvas.restoreToCount(save);
    }

    private void drawSplashText(Canvas canvas, float cx, float cy, String splash) {
        float scale = splashPulse(LegacyUiClock.millis());

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

    /** Where the splash line pivots: 10 px in from the logo's right edge, 95 % of the way down it. */
    public static float[] splashAnchor(int windowWidth, int windowHeight, float scale) {
        Rect logoRect = computeLogoRect(windowWidth, windowHeight, scale);
        return new float[]{logoRect.getRight() - 10f * scale, logoRect.getTop() + logoRect.getHeight() * 0.95f};
    }

    /** The splash line's beat: a ±5 % scale pulse every half second of {@code millis}. */
    public static float splashPulse(long millis) {
        float t = (millis % 500L) / 500.0f;
        return 1.0f + (float) (Math.sin(t * Math.PI * 2.0) * 0.05);
    }

    private void drawButton(Canvas canvas, String text, float x, float y, boolean highlighted,
                            float buttonWidth, float buttonHeight) {
        report(text.toLowerCase(java.util.Locale.ROOT).replace(" game", "").replace(" ", ""), x, y,
                buttonWidth, buttonHeight);
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
        backdrop.close();
        disposeFonts();
    }
}
