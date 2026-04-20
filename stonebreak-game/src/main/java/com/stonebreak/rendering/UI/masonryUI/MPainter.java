package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Stateless Skija drawing primitives shared by every MasonryUI widget.
 *
 * All of the Stonebreak Skija renderers had been re-implementing trapezoid-fan
 * bevels and measure-text-safe fallbacks inline; this class is the single
 * place that knows how to draw them. Widgets stay small and focused.
 */
public final class MPainter {

    private MPainter() {}

    // ─────────────────────────────────────────────── Rectangles

    public static void fillRect(Canvas canvas, float x, float y, float w, float h, int color) {
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    public static void fillRoundedRect(Canvas canvas, float x, float y, float w, float h, float r, int color) {
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), p);
        }
    }

    public static void strokeRect(Canvas canvas, float x, float y, float w, float h, int color, float width) {
        try (Paint p = new Paint().setColor(color).setMode(PaintMode.STROKE).setStrokeWidth(width)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    // ─────────────────────────────────────────────── Panel

    /**
     * Stone-look panel: drop shadow → rounded gray fill → procedural noise →
     * inner bevel (top/left highlight, bottom/right shadow) → 1.5px dark
     * border. Noise is deterministic so the same rect always gets the same
     * pattern.
     */
    public static void stoneSurface(Canvas canvas, float x, float y, float w, float h,
                                    float radius, int fill, int border, int highlight,
                                    int shadow, int dropShadow, int noiseDark, int noiseLight) {
        // Soft drop shadow: two offset rounded rects with decreasing alpha.
        if ((dropShadow & 0xFF000000) != 0) {
            int a = (dropShadow >>> 24) & 0xFF;
            int rgb = dropShadow & 0xFFFFFF;
            int a1 = (a * 7 / 10) & 0xFF;
            int a2 = (a * 4 / 10) & 0xFF;
            try (Paint p = new Paint().setColor((a1 << 24) | rgb).setAntiAlias(true)) {
                canvas.drawRRect(RRect.makeXYWH(x + 1f, y + 2f, w, h, radius), p);
            }
            try (Paint p = new Paint().setColor((a2 << 24) | rgb).setAntiAlias(true)) {
                canvas.drawRRect(RRect.makeXYWH(x + 2f, y + 4f, w, h, radius), p);
            }
        }
        try (Paint p = new Paint().setColor(fill).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, radius), p);
        }
        drawNoise(canvas, x, y, w, h, radius, noiseDark, noiseLight);
        drawInnerBevel(canvas, x, y, w, h, radius, highlight, shadow);
        try (Paint p = new Paint().setColor(border).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(1.5f)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, radius), p);
        }
    }

    public static void panel(Canvas canvas, float x, float y, float w, float h) {
        stoneSurface(canvas, x, y, w, h, MStyle.PANEL_RADIUS,
                MStyle.PANEL_FILL, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    /**
     * Inner bevel — thin highlight along top+left, thin shadow along
     * bottom+right. Clips to the rounded rect so corners follow the radius.
     */
    private static void drawInnerBevel(Canvas canvas, float x, float y, float w, float h,
                                       float radius, int highlight, int shadow) {
        int save = canvas.save();
        try {
            canvas.clipRRect(RRect.makeXYWH(x, y, w, h, radius), ClipMode.INTERSECT, true);
            if ((highlight & 0xFF000000) != 0) {
                try (Paint p = new Paint().setColor(highlight).setAntiAlias(false)) {
                    canvas.drawRect(Rect.makeXYWH(x, y, w, 1f), p);        // top
                    canvas.drawRect(Rect.makeXYWH(x, y, 1f, h), p);        // left
                }
            }
            if ((shadow & 0xFF000000) != 0) {
                try (Paint p = new Paint().setColor(shadow).setAntiAlias(false)) {
                    canvas.drawRect(Rect.makeXYWH(x, y + h - 1f, w, 1f), p);   // bottom
                    canvas.drawRect(Rect.makeXYWH(x + w - 1f, y, 1f, h), p);   // right
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Scatters tiny speckles across a clipped rounded rect. Uses a seeded
     * hash on a loop index to produce stable, non-grid-aligned noise.
     */
    private static void drawNoise(Canvas canvas, float x, float y, float w, float h,
                                  float radius, int darkColor, int lightColor) {
        int save = canvas.save();
        try {
            canvas.clipRRect(RRect.makeXYWH(x, y, w, h, radius), ClipMode.INTERSECT, true);
            int count = Math.max(40, (int) (w * h / 55f));
            try (Paint dark = new Paint().setColor(darkColor).setAntiAlias(false);
                 Paint light = new Paint().setColor(lightColor).setAntiAlias(false)) {
                for (int i = 0; i < count; i++) {
                    int h1 = hash(i * 0x27D4EB2D);
                    int h2 = hash((i + 1) * 0x85EBCA6B ^ 0x9E3779B9);
                    float px = x + (h1 & 0x7FFF) / 32767f * w;
                    float py = y + (h2 & 0x7FFF) / 32767f * h;
                    int size = 1 + ((h1 >>> 16) & 0x3); // 1–4 px
                    Paint p = ((h2 >>> 17) & 1) == 0 ? dark : light;
                    canvas.drawRect(Rect.makeXYWH(px, py, size, size), p);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    // ─────────────────────────────────────────────── Text

    /**
     * Skija's native measureTextWidth blows up on empty strings and lone
     * surrogates. Every widget text layout goes through this helper instead.
     */
    public static float measureWidth(Font font, String text) {
        if (font == null || text == null || text.isEmpty()) return 0f;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isSurrogate(text.charAt(i))) return 0f;
        }
        return font.measureTextWidth(text);
    }

    public static void drawString(Canvas canvas, String text, float x, float y, Font font, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, x, y, font, p);
        }
    }

    public static void drawCenteredString(Canvas canvas, String text, float cx, float y, Font font, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        float width = measureWidth(font, text);
        drawString(canvas, text, cx - width / 2f, y, font, color);
    }

    /**
     * Draws text with a soft two-layer drop shadow — a faint 2px-offset halo
     * beneath a crisp 1px shadow. Gives button/panel labels a subtle
     * depth without needing an actual blur filter.
     */
    public static void drawCenteredStringWithShadow(Canvas canvas, String text, float cx, float y,
                                                    Font font, int color, int shadow) {
        int softAlpha = ((shadow >>> 24) & 0xFF) * 2 / 5; // ~40% of base alpha
        int soft = (softAlpha << 24) | (shadow & 0xFFFFFF);
        drawCenteredString(canvas, text, cx + 2f, y + 2f, font, soft);
        drawCenteredString(canvas, text, cx + 1f, y + 1f, font, shadow);
        drawCenteredString(canvas, text, cx,       y,       font, color);
    }

    /**
     * Left-aligned variant of {@link #drawCenteredStringWithShadow}.
     */
    public static void drawStringWithShadow(Canvas canvas, String text, float x, float y,
                                            Font font, int color, int shadow) {
        int softAlpha = ((shadow >>> 24) & 0xFF) * 2 / 5;
        int soft = (softAlpha << 24) | (shadow & 0xFFFFFF);
        drawString(canvas, text, x + 2f, y + 2f, font, soft);
        drawString(canvas, text, x + 1f, y + 1f, font, shadow);
        drawString(canvas, text, x,       y,       font, color);
    }
}
