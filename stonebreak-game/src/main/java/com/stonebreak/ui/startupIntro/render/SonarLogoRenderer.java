package com.stonebreak.ui.startupIntro.render;

import com.stonebreak.ui.startupIntro.IntroConfig;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads {@code /ui/shared/stonebreak/ui/images/sonar_arts_retro.png} once and draws it centered
 * above the submarine, with a drop shadow. Reveal opacity (0..1) is supplied
 * each frame from {@code SonarSystem.largestRadius()}.
 */
public final class SonarLogoRenderer implements AutoCloseable {

    /** The shared UI image the intro document shows too (#301). */
    private static final String RESOURCE_PATH = "/ui/shared/stonebreak/ui/images/sonar_arts_retro.png";
    private static final float LOGO_Y_PERCENT = 0.18f;
    private static final int SHADOW_ARGB = 0x96000000;

    private Image logo;
    private boolean loadAttempted;

    private void ensureLoaded() {
        if (loadAttempted) return;
        loadAttempted = true;
        try (InputStream in = SonarLogoRenderer.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                System.err.println("[SonarIntro] Missing logo resource: " + RESOURCE_PATH);
                return;
            }
            byte[] bytes = in.readAllBytes();
            logo = Image.makeFromEncoded(bytes);
        } catch (IOException e) {
            System.err.println("[SonarIntro] Failed to load logo: " + e.getMessage());
        }
    }

    /** The logo image's pixel size ({@code sonar_arts_retro.png}). */
    public static final int LOGO_WIDTH = 1200;
    public static final int LOGO_HEIGHT = 324;

    /**
     * Where the logo goes on a {@code screenWidth × screenHeight} window at the intro's scale:
     * {@code {x, y, width, height, shadowOffset}} in device px, float maths exactly as drawn (#301: the
     * intro document places its logo from these numbers, so nearest sampling picks the same texels).
     */
    public static float[] placement(int screenWidth, int screenHeight) {
        float scale = IntroConfig.scale();
        float scaledW = LOGO_WIDTH * scale;
        float scaledH = LOGO_HEIGHT * scale;
        return new float[]{(screenWidth - scaledW) / 2f, screenHeight * LOGO_Y_PERCENT, scaledW, scaledH,
            IntroConfig.s(4)};
    }

    public void draw(IntroPainter p, int screenWidth, int screenHeight, float revealAmount) {
        if (revealAmount <= 0f) return;
        ensureLoaded();
        if (logo == null) return;

        float[] at = placement(screenWidth, screenHeight);
        float scaledW = at[2];
        float scaledH = at[3];
        float x = at[0];
        float y = at[1];
        float shadowOffset = at[4];

        Canvas canvas = p.canvas();
        Rect shadowDst = Rect.makeXYWH(x + shadowOffset, y + shadowOffset, scaledW, scaledH);
        Rect logoDst = Rect.makeXYWH(x, y, scaledW, scaledH);

        Rect src = Rect.makeXYWH(0, 0, logo.getWidth(), logo.getHeight());

        try (Paint shadowPaint = new Paint();
             ColorFilter tint = ColorFilter.makeBlend(SHADOW_ARGB, BlendMode.SRC_IN)) {
            shadowPaint.setColorFilter(tint);
            shadowPaint.setAlphaf(revealAmount);
            canvas.drawImageRect(logo, src, shadowDst, SamplingMode.DEFAULT, shadowPaint, true);
        }

        try (Paint logoPaint = new Paint()) {
            logoPaint.setAlphaf(revealAmount);
            canvas.drawImageRect(logo, src, logoDst, SamplingMode.DEFAULT, logoPaint, true);
        }
    }

    @Override
    public void close() {
        if (logo != null) { logo.close(); logo = null; }
    }
}
