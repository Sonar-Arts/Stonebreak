package com.stonebreak.ui.mainMenu;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.util.Random;

/**
 * Procedural 64-bit pixel-art space scene drawn with Skija. The static layers
 * (gradient sky, nebula wisps, starfield, shaded planets) are baked once into a
 * low-resolution offscreen image and upscaled with nearest-neighbour sampling
 * for a chunky retro look; animated twinkling sparkles are drawn live on top.
 */
public final class SpaceBackgroundRenderer {

    private static final long LAYOUT_SEED = 0x5701B4EAL;
    private static final int LOW_RES_HEIGHT = 216;
    private static final int STAR_COUNT = 220;
    private static final int SPARKLE_COUNT = 14;

    private static final int SKY_TOP = 0xFF0B0B22;
    private static final int SKY_BOTTOM = 0xFF050510;

    private static final int[] STAR_PALETTE = {
        0xFFFFFFFF, 0xFFFFF6C8, 0xFFB9E6FF, 0xFF9FB8FF
    };
    private static final int[] SPARKLE_PALETTE = {
        0xFFFFFFFF, 0xFF8FE9FF, 0xFFFFB347, 0xFFFF8FD4
    };

    private Image baseImage;
    private int builtForWidth = -1;
    private int builtForHeight = -1;

    // Sparkle layout in normalised [0,1] screen coordinates, generated once.
    private float[] sparkleX;
    private float[] sparkleY;
    private float[] sparklePhase;
    private float[] sparkleSpeed;
    private int[] sparkleColor;

    /** Draws the full space scene scaled to the given screen size. */
    public void draw(Canvas canvas, int screenWidth, int screenHeight, float timeSeconds) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        ensureBaked(screenWidth, screenHeight);
        ensureSparkles();

        if (baseImage != null) {
            Rect src = Rect.makeWH(baseImage.getWidth(), baseImage.getHeight());
            Rect dst = Rect.makeWH(screenWidth, screenHeight);
            try (Paint paint = new Paint()) {
                canvas.drawImageRect(baseImage, src, dst, SamplingMode.DEFAULT, paint, true);
            }
        }

        drawSparkles(canvas, screenWidth, screenHeight, timeSeconds);
    }

    /** Forces the baked image to be regenerated on the next draw. */
    public void invalidate() {
        if (baseImage != null) {
            baseImage.close();
            baseImage = null;
        }
        builtForWidth = -1;
        builtForHeight = -1;
    }

    public void dispose() {
        invalidate();
    }

    private void ensureBaked(int screenWidth, int screenHeight) {
        if (baseImage != null && screenWidth == builtForWidth && screenHeight == builtForHeight) {
            return;
        }
        if (baseImage != null) {
            baseImage.close();
            baseImage = null;
        }
        builtForWidth = screenWidth;
        builtForHeight = screenHeight;

        int lowH = LOW_RES_HEIGHT;
        int lowW = Math.max(1, Math.round(lowH * (float) screenWidth / screenHeight));
        baseImage = bakeScene(lowW, lowH);
    }

    private Image bakeScene(int w, int h) {
        try (Surface surface = Surface.makeRasterN32Premul(w, h)) {
            Canvas canvas = surface.getCanvas();
            Random random = new Random(LAYOUT_SEED);
            drawSky(canvas, w, h);
            drawNebulae(canvas, w, h, random);
            drawStars(canvas, w, h, random);
            drawPlanets(canvas, w, h);
            return surface.makeImageSnapshot();
        }
    }

    private void drawSky(Canvas canvas, int w, int h) {
        try (Paint paint = new Paint().setAntiAlias(false)) {
            for (int y = 0; y < h; y++) {
                float t = (float) y / (h - 1);
                paint.setColor(lerpColor(SKY_TOP, SKY_BOTTOM, t));
                canvas.drawRect(Rect.makeXYWH(0, y, w, 1), paint);
            }
        }
    }

    private void drawNebulae(Canvas canvas, int w, int h, Random random) {
        int[] tints = {0x3A6A3AA0, 0x302A4AAA, 0x2A8A3AB0, 0x24305FC0};
        int clusters = 5;
        for (int c = 0; c < clusters; c++) {
            float cx = random.nextFloat() * w;
            float cy = random.nextFloat() * h;
            int tint = tints[c % tints.length];
            int blobs = 14 + random.nextInt(10);
            try (Paint paint = new Paint().setAntiAlias(false).setColor(tint)) {
                for (int b = 0; b < blobs; b++) {
                    float ox = (random.nextFloat() - 0.5f) * w * 0.32f;
                    float oy = (random.nextFloat() - 0.5f) * h * 0.30f;
                    float r = h * (0.04f + random.nextFloat() * 0.10f);
                    canvas.drawCircle(cx + ox, cy + oy, r, paint);
                }
            }
        }
    }

    private void drawStars(Canvas canvas, int w, int h, Random random) {
        try (Paint paint = new Paint().setAntiAlias(false)) {
            for (int i = 0; i < STAR_COUNT; i++) {
                float x = random.nextFloat() * w;
                float y = random.nextFloat() * h;
                int color = STAR_PALETTE[random.nextInt(STAR_PALETTE.length)];
                int size = random.nextFloat() < 0.18f ? 2 : 1;
                int alpha = 150 + random.nextInt(106);
                paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
                canvas.drawRect(Rect.makeXYWH(Math.round(x), Math.round(y), size, size), paint);
            }
        }
    }

    private void drawPlanets(Canvas canvas, int w, int h) {
        drawLavaPlanet(canvas, w * 0.17f, h * 0.46f, h * 0.11f);
        drawCrateredMoon(canvas, w * 0.36f, h * 0.16f, h * 0.075f);
        drawEarth(canvas, w * 0.74f, h * 0.24f, h * 0.085f);
        drawSmallMoon(canvas, w * 0.57f, h * 0.74f, h * 0.045f);
    }

    private void drawLavaPlanet(Canvas canvas, float cx, float cy, float r) {
        int save = canvas.save();
        clipToCircle(canvas, cx, cy, r);
        fillSquare(canvas, cx, cy, r, 0xFF7A1E10);
        try (Paint paint = new Paint().setAntiAlias(false)) {
            int[] streaks = {0xFFFF7A2A, 0xFFFFB14A, 0xFFE8531C};
            for (int i = 0; i < 5; i++) {
                paint.setColor(streaks[i % streaks.length]);
                float sy = cy - r * 0.6f + r * 0.3f * i;
                canvas.drawRect(Rect.makeXYWH(cx - r, sy, r * 2f, Math.max(1f, r * 0.07f)), paint);
            }
        }
        applySphereShading(canvas, cx, cy, r);
        canvas.restoreToCount(save);
    }

    private void drawCrateredMoon(Canvas canvas, float cx, float cy, float r) {
        int save = canvas.save();
        clipToCircle(canvas, cx, cy, r);
        fillSquare(canvas, cx, cy, r, 0xFFB9BCC4);
        try (Paint paint = new Paint().setAntiAlias(false).setColor(0xFF8B8E98)) {
            canvas.drawCircle(cx - r * 0.30f, cy + r * 0.10f, r * 0.22f, paint);
            canvas.drawCircle(cx + r * 0.28f, cy - r * 0.22f, r * 0.16f, paint);
            canvas.drawCircle(cx + r * 0.10f, cy + r * 0.40f, r * 0.12f, paint);
        }
        applySphereShading(canvas, cx, cy, r);
        canvas.restoreToCount(save);
    }

    private void drawEarth(Canvas canvas, float cx, float cy, float r) {
        int save = canvas.save();
        clipToCircle(canvas, cx, cy, r);
        fillSquare(canvas, cx, cy, r, 0xFF2A6FD0);
        try (Paint paint = new Paint().setAntiAlias(false).setColor(0xFF3FAE5A)) {
            canvas.drawCircle(cx - r * 0.30f, cy - r * 0.25f, r * 0.30f, paint);
            canvas.drawCircle(cx + r * 0.25f, cy + r * 0.10f, r * 0.26f, paint);
            canvas.drawCircle(cx - r * 0.05f, cy + r * 0.40f, r * 0.18f, paint);
        }
        applySphereShading(canvas, cx, cy, r);
        canvas.restoreToCount(save);
    }

    private void drawSmallMoon(Canvas canvas, float cx, float cy, float r) {
        int save = canvas.save();
        clipToCircle(canvas, cx, cy, r);
        fillSquare(canvas, cx, cy, r, 0xFF7C7F88);
        try (Paint paint = new Paint().setAntiAlias(false).setColor(0xFF61646D)) {
            canvas.drawCircle(cx + r * 0.20f, cy - r * 0.15f, r * 0.24f, paint);
        }
        applySphereShading(canvas, cx, cy, r);
        canvas.restoreToCount(save);
    }

    private void applySphereShading(Canvas canvas, float cx, float cy, float r) {
        try (Paint shadow = new Paint().setAntiAlias(false).setColor(0x88000814)) {
            canvas.drawCircle(cx + r * 0.45f, cy + r * 0.50f, r * 1.15f, shadow);
        }
        try (Paint light = new Paint().setAntiAlias(false).setColor(0x55FFFFFF)) {
            canvas.drawCircle(cx - r * 0.34f, cy - r * 0.38f, r * 0.55f, light);
        }
    }

    private void fillSquare(Canvas canvas, float cx, float cy, float r, int color) {
        try (Paint paint = new Paint().setAntiAlias(false).setColor(color)) {
            canvas.drawRect(Rect.makeXYWH(cx - r, cy - r, r * 2f, r * 2f), paint);
        }
    }

    private void clipToCircle(Canvas canvas, float cx, float cy, float r) {
        try (PathBuilder pb = new PathBuilder()) {
            int steps = 48;
            pb.moveTo(cx + r, cy);
            for (int i = 1; i <= steps; i++) {
                double a = 2 * Math.PI * i / steps;
                pb.lineTo((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            }
            pb.closePath();
            try (Path circle = pb.build()) {
                canvas.clipPath(circle, true);
            }
        }
    }

    private void ensureSparkles() {
        if (sparkleX != null) {
            return;
        }
        Random random = new Random(LAYOUT_SEED ^ 0x9E3779B9L);
        sparkleX = new float[SPARKLE_COUNT];
        sparkleY = new float[SPARKLE_COUNT];
        sparklePhase = new float[SPARKLE_COUNT];
        sparkleSpeed = new float[SPARKLE_COUNT];
        sparkleColor = new int[SPARKLE_COUNT];
        for (int i = 0; i < SPARKLE_COUNT; i++) {
            sparkleX[i] = random.nextFloat();
            sparkleY[i] = random.nextFloat();
            sparklePhase[i] = random.nextFloat() * (float) (2 * Math.PI);
            sparkleSpeed[i] = 1.5f + random.nextFloat() * 2.5f;
            sparkleColor[i] = SPARKLE_PALETTE[random.nextInt(SPARKLE_PALETTE.length)];
        }
    }

    private void drawSparkles(Canvas canvas, int screenWidth, int screenHeight, float time) {
        float pixel = Math.max(2f, screenHeight / (float) LOW_RES_HEIGHT);
        try (Paint paint = new Paint().setAntiAlias(false)) {
            for (int i = 0; i < SPARKLE_COUNT; i++) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(time * sparkleSpeed[i] + sparklePhase[i]);
                int alpha = 60 + Math.round(pulse * 195f);
                paint.setColor((sparkleColor[i] & 0x00FFFFFF) | (alpha << 24));

                float cx = snap(sparkleX[i] * screenWidth, pixel);
                float cy = snap(sparkleY[i] * screenHeight, pixel);
                float arm = pixel * (1.5f + pulse * 2.5f);
                float th = pixel;

                canvas.drawRect(Rect.makeXYWH(cx - arm, cy - th * 0.5f, arm * 2f, th), paint);
                canvas.drawRect(Rect.makeXYWH(cx - th * 0.5f, cy - arm, th, arm * 2f), paint);
                canvas.drawRect(Rect.makeXYWH(cx - th, cy - th, th * 2f, th * 2f), paint);
            }
        }
    }

    private static float snap(float value, float grid) {
        return Math.round(value / grid) * grid;
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF;
        int ar = (a >>> 16) & 0xFF;
        int ag = (a >>> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int br = (b >>> 16) & 0xFF;
        int bg = (b >>> 8) & 0xFF;
        int bb = b & 0xFF;
        int ra = Math.round(aa + (ba - aa) * t);
        int rr = Math.round(ar + (br - ar) * t);
        int rg = Math.round(ag + (bg - ag) * t);
        int rb = Math.round(ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
