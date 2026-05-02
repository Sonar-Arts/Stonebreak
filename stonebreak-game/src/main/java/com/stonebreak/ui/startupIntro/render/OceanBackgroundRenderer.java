package com.stonebreak.ui.startupIntro.render;

import com.stonebreak.ui.startupIntro.IntroConfig;

/**
 * Renders the deep-ocean gradient + caustic light shafts. Port of
 * BackgroundRenderer.DrawOceanBackground / DrawCaustics. Sky / sun / cloud
 * paths are intentionally dropped because the intro never leaves the ocean
 * phase.
 */
public final class OceanBackgroundRenderer {

    private final int screenWidth;
    private final int screenHeight;

    public OceanBackgroundRenderer(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public void draw(IntroPainter p, float totalElapsed) {
        int bands = 60;
        int bandHeight = screenHeight / bands + 1;

        for (int i = 0; i < bands; i++) {
            float t = (float) i / bands;
            int color;
            if (t < 0.4f) {
                color = IntroConfig.lerpRgb(IntroConfig.OCEAN_DEEP, IntroConfig.OCEAN_MID, t / 0.4f);
            } else {
                color = IntroConfig.lerpRgb(IntroConfig.OCEAN_MID, IntroConfig.OCEAN_SURFACE, (t - 0.4f) / 0.6f);
            }
            float wave = (float) Math.sin(totalElapsed * 0.5f + i * 0.1f) * IntroConfig.s(3);
            int y = (int) (i * bandHeight + wave);
            p.drawRectangle(0, y, screenWidth, bandHeight + 2, color);
        }

        drawCaustics(p, totalElapsed);
    }

    private void drawCaustics(IntroPainter p, float totalElapsed) {
        for (int i = 0; i < 5; i++) {
            float x = (screenWidth * 0.1f) + i * (screenWidth * 0.2f);
            float wave = (float) Math.sin(totalElapsed * 0.3f + i * 1.5f) * IntroConfig.s(30);
            x += wave;

            float opacity = 0.03f + (float) Math.sin(totalElapsed * 0.5f + i) * 0.02f;
            int color = IntroConfig.multiplyAlpha(0xFFFFFFFF, Math.max(0f, opacity));

            int step = Math.max(1, IntroConfig.si(4));
            for (int j = 0; j < screenHeight; j += step) {
                float rayWidth = IntroConfig.s(20) + (float) Math.sin(j * 0.01f + totalElapsed) * IntroConfig.s(10);
                p.drawRectangle(x - rayWidth / 2f, j, rayWidth, step, color);
            }
        }
    }
}
