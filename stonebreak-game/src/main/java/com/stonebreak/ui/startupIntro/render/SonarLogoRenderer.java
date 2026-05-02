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
 * Loads {@code /ui/startupIntro/SonarArts_Retro.png} once and draws it centered
 * above the submarine, with a drop shadow. Reveal opacity (0..1) is supplied
 * each frame from {@code SonarSystem.largestRadius()}.
 */
public final class SonarLogoRenderer implements AutoCloseable {

    private static final String RESOURCE_PATH = "/ui/startupIntro/SonarArts_Retro.png";
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

    public void draw(IntroPainter p, int screenWidth, int screenHeight, float revealAmount) {
        if (revealAmount <= 0f) return;
        ensureLoaded();
        if (logo == null) return;

        float scale = IntroConfig.scale();
        float scaledW = logo.getWidth() * scale;
        float scaledH = logo.getHeight() * scale;

        float x = (screenWidth - scaledW) / 2f;
        float y = screenHeight * LOGO_Y_PERCENT;
        float shadowOffset = IntroConfig.s(4);

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
