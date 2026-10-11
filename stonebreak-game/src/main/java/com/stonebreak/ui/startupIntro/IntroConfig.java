package com.stonebreak.ui.startupIntro;

public final class IntroConfig {

    private IntroConfig() {}

    public static final float REFERENCE_WIDTH = 1920f;
    public static final float REFERENCE_HEIGHT = 1080f;

    private static float scale = 1f;

    public static void initializeScale(int screenWidth, int screenHeight) {
        float sx = screenWidth / REFERENCE_WIDTH;
        float sy = screenHeight / REFERENCE_HEIGHT;
        scale = Math.min(sx, sy);
    }

    public static float scale() { return scale; }

    public static float s(float value) { return value * scale; }

    public static int si(float value) { return Math.round(value * scale); }

    public static int siMin1(float value) { return Math.max(1, Math.round(value * scale)); }

    /** The logo image's pixel size ({@code /ui/shared/stonebreak/ui/images/sonar_arts_retro.png}). */
    public static final int LOGO_WIDTH = 1200;
    public static final int LOGO_HEIGHT = 324;
    private static final float LOGO_Y_PERCENT = 0.18f;

    /**
     * Where the logo goes on a {@code screenWidth × screenHeight} window at the intro's scale:
     * {@code {x, y, width, height, shadowOffset}} in device px (the intro document places its logo and
     * drop shadow from these numbers, #301).
     */
    public static float[] logoPlacement(int screenWidth, int screenHeight) {
        float scaledW = LOGO_WIDTH * scale;
        float scaledH = LOGO_HEIGHT * scale;
        return new float[]{(screenWidth - scaledW) / 2f, screenHeight * LOGO_Y_PERCENT, scaledW, scaledH, s(4)};
    }

    public static final float SUBMARINE_DURATION = 1.8f;
    public static final float HOLD_DURATION = 1.2f;
    public static final float FADE_OUT_DURATION = 1.0f;

    public static final float SONAR_SPAWN_INTERVAL = 0.6f;
    public static final float SONAR_BASE_SPEED_BASE = 200f;
    public static final float SONAR_SPEED_VARIANCE_BASE = 50f;
    public static final float SONAR_INITIAL_RADIUS_BASE = 30f;

    public static float sonarBaseSpeed() { return s(SONAR_BASE_SPEED_BASE); }
    public static float sonarSpeedVariance() { return s(SONAR_SPEED_VARIANCE_BASE); }
    public static float sonarInitialRadius() { return s(SONAR_INITIAL_RADIUS_BASE); }

    public static int rgba(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int rgb(int r, int g, int b) { return rgba(r, g, b, 255); }

    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    public static int multiplyAlpha(int argb, float alpha) {
        int srcA = (argb >>> 24) & 0xFF;
        int newA = Math.round(srcA * Math.max(0f, Math.min(1f, alpha)));
        return (argb & 0x00FFFFFF) | ((newA & 0xFF) << 24);
    }

    public static int lerpRgb(int aArgb, int bArgb, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aA = (aArgb >>> 24) & 0xFF;
        int aR = (aArgb >>> 16) & 0xFF;
        int aG = (aArgb >>> 8) & 0xFF;
        int aB = aArgb & 0xFF;
        int bA = (bArgb >>> 24) & 0xFF;
        int bR = (bArgb >>> 16) & 0xFF;
        int bG = (bArgb >>> 8) & 0xFF;
        int bB = bArgb & 0xFF;
        int rr = Math.round(aR + (bR - aR) * t);
        int rg = Math.round(aG + (bG - aG) * t);
        int rb = Math.round(aB + (bB - aB) * t);
        int ra = Math.round(aA + (bA - aA) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    public static final int OCEAN_DEEP = rgb(8, 24, 48);
    public static final int OCEAN_MID = rgb(16, 48, 80);
    public static final int OCEAN_SURFACE = rgb(32, 80, 120);

    public static final int SONAR_GREEN = rgb(34, 197, 94);

    public static final int SUBMARINE_COLOR = rgb(20, 30, 50);
    public static final int SUBMARINE_HIGHLIGHT = rgb(60, 80, 100);

    public static final int BUBBLE_COLOR = rgb(150, 200, 255);
}
