package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.SamplingMode;
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

    /**
     * Anti-aliased rounded-rect outline, inset half a stroke so the line sits
     * fully inside the given bounds (the same convention as the stone-surface
     * border).
     */
    public static void strokeRoundedRect(Canvas canvas, float x, float y, float w, float h,
                                         float r, int color, float width) {
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(width)) {
            float half = width / 2f;
            canvas.drawRRect(RRect.makeXYWH(x + half, y + half, w - width, h - width, r), p);
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
     * Hard cap on noise speckles for a single surface. A large panel would
     * otherwise scatter many thousands of {@code drawRect} calls every frame
     * (count grows with area); the unbounded batch was a source of rendering
     * instability. The cap keeps every stone surface to a bounded, predictable
     * draw-call count regardless of size — that is the "guard".
     */
    private static final int NOISE_MAX = 1200;

    /**
     * Inner bevel — thin highlight along top+left, thin shadow along
     * bottom+right.
     *
     * <p>Drawn as two stroked open {@link Path}s that follow the corner radius
     * by construction (the corner arcs are tessellated into short line
     * segments). The previous implementation clipped four straight edge rects
     * to a rounded rect via {@code clipRRect}; relying on Skia's anti-aliased
     * round-rect clip made the bevel intermittently render with square corners
     * when GPU state churned between frames. Path strokes need no clip, so the
     * corners can no longer "flicker into triangles".
     */
    private static void drawInnerBevel(Canvas canvas, float x, float y, float w, float h,
                                       float radius, int highlight, int shadow) {
        if (w < 3f || h < 3f) return;

        // Trace the bevel one half-pixel inside the fill so the 1px stroke
        // sits flush against the inner edge.
        float ix = x + 0.5f, iy = y + 0.5f;
        float iw = w - 1f, ih = h - 1f;
        float ir = Math.max(0f, radius - 1f);

        if ((highlight & 0xFF000000) != 0) {
            try (PathBuilder pb = new PathBuilder()) {
                pb.moveTo(ix, iy + ih - ir);                  // up the left edge
                pb.lineTo(ix, iy + ir);
                arcSegments(pb, ix + ir, iy + ir, ir, 180f, 270f); // top-left corner
                pb.lineTo(ix + iw - ir, iy);                  // along the top edge
                strokePath(canvas, pb, highlight);
            }
        }
        if ((shadow & 0xFF000000) != 0) {
            try (PathBuilder pb = new PathBuilder()) {
                pb.moveTo(ix + ir, iy + ih);                  // along the bottom edge
                pb.lineTo(ix + iw - ir, iy + ih);
                arcSegments(pb, ix + iw - ir, iy + ih - ir, ir, 90f, 0f); // bottom-right corner
                pb.lineTo(ix + iw, iy + ir);                  // up the right edge
            strokePath(canvas, pb, shadow);
            }
        }
    }

    /**
     * Appends a quarter (or partial) corner arc to {@code pb} as short line
     * segments — Skija's {@code PathBuilder} only exposes {@code moveTo} /
     * {@code lineTo}, and a 1px stroke hides the faceting at UI corner radii.
     */
    private static void arcSegments(PathBuilder pb, float cx, float cy, float r,
                                    float startDeg, float endDeg) {
        final int steps = 6;
        for (int i = 1; i <= steps; i++) {
            double t = Math.toRadians(startDeg + (endDeg - startDeg) * i / steps);
            pb.lineTo((float) (cx + r * Math.cos(t)), (float) (cy + r * Math.sin(t)));
        }
    }

    /** Strokes the built path with a crisp 1px anti-aliased line. */
    private static void strokePath(Canvas canvas, PathBuilder pb, int color) {
        try (Path path = pb.build();
             Paint paint = new Paint().setColor(color).setAntiAlias(true)
                     .setMode(PaintMode.STROKE).setStrokeWidth(1f)) {
            canvas.drawPath(path, paint);
        }
    }

    /**
     * Scatters tiny speckles across a rounded rect. Uses a seeded hash on the
     * loop index to produce stable, non-grid-aligned noise.
     *
     * <p>Speckles that would spill past the rounded corners are rejected with
     * a containment test rather than masked with {@code clipRRect}, so the
     * surface never depends on Skia's AA round-rect clip. The speckle count is
     * also capped via {@link #NOISE_MAX}.
     */
    private static void drawNoise(Canvas canvas, float x, float y, float w, float h,
                                  float radius, int darkColor, int lightColor) {
        if ((darkColor & 0xFF000000) == 0 && (lightColor & 0xFF000000) == 0) return;

        int count = Math.min(NOISE_MAX, Math.max(40, (int) (w * h / 55f)));
        try (Paint dark = new Paint().setColor(darkColor).setAntiAlias(false);
             Paint light = new Paint().setColor(lightColor).setAntiAlias(false)) {
            for (int i = 0; i < count; i++) {
                int h1 = hash(i * 0x27D4EB2D);
                int h2 = hash((i + 1) * 0x85EBCA6B ^ 0x9E3779B9);
                float px = x + (h1 & 0x7FFF) / 32767f * w;
                float py = y + (h2 & 0x7FFF) / 32767f * h;
                int size = 1 + ((h1 >>> 16) & 0x3); // 1–4 px

                // Drop speckles straddling the rounded corners — keeps the
                // noise inside the surface without an AA clip.
                if (!insideRoundedRect(px, py, x, y, w, h, radius)
                        || !insideRoundedRect(px + size, py + size, x, y, w, h, radius)) {
                    continue;
                }

                Paint p = ((h2 >>> 17) & 1) == 0 ? dark : light;
                canvas.drawRect(Rect.makeXYWH(px, py, size, size), p);
            }
        }
    }

    /**
     * Standard rounded-rect containment test: a point is inside when it lies
     * within the rect and, in the corner quadrants, within {@code r} of the
     * corner centre.
     */
    private static boolean insideRoundedRect(float px, float py,
                                             float x, float y, float w, float h, float r) {
        if (px < x || px > x + w || py < y || py > y + h) return false;
        if (r <= 0f) return true;
        float cx = Math.max(x + r, Math.min(px, x + w - r));
        float cy = Math.max(y + r, Math.min(py, y + h - r));
        float dx = px - cx, dy = py - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    // ─────────────────────────────────────────────── Crafting arrow

    /**
     * Right-pointing arrow drawn as a vector glyph: a horizontal shaft plus a
     * filled triangular head whose single tip meets the right edge of the box.
     * The head is a real triangle (not a right-aligned diamond), so the arrow
     * always points toward the output slot.
     *
     * Used by inventory, workbench, and the recipe detail pane. Vertical
     * centring against the output slot is handled by
     * {@link #craftingArrowPlacement(float, float, float, float, float)}.
     */
    public static void craftingArrow(Canvas canvas, float x, float y, float w, float h, int color) {
        if (canvas == null || w <= 0f || h <= 0f) return;
        float centerY = y + h / 2f;
        float shaftLen = w * 0.55f;
        float shaftH   = Math.min(h, Math.max(2f, h * 0.28f));
        float headHalf = h * 0.42f;
        float faceX    = x + shaftLen; // flat face where the head meets the shaft

        try (Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawRect(Rect.makeXYWH(x, centerY - shaftH / 2f, shaftLen, shaftH), fill);
            try (PathBuilder pb = new PathBuilder()) {
                pb.moveTo(faceX, centerY - headHalf);
                pb.lineTo(x + w, centerY);
                pb.lineTo(faceX, centerY + headHalf);
                pb.closePath();
                try (Path path = pb.build()) {
                    canvas.drawPath(path, fill);
                }
            }
        }
    }

    /**
     * Auto-aligning placement for the crafting arrow: horizontally centred in
     * the gap between the input grid's right edge and the output slot, and
     * vertically centred on the output slot. This works for any grid size
     * (2×2 inventory, 3×3 workbench) and any uiScale because it derives every
     * dimension from the surrounding slots.
     *
     * @return the arrow's top-left corner as a two-element array {@code {x, y}}
     */
    public static float[] craftingArrowPlacement(float gridRightX, float outputX,
                                                 float outputY, float slotSize,
                                                 float arrowSize) {
        float x = gridRightX + (outputX - gridRightX - arrowSize) / 2f;
        float y = outputY + (slotSize - arrowSize) / 2f;
        return new float[]{x, y};
    }

    // ─────────────────────────────────────────────── Navigation arrow

    /**
     * Clean horizontal navigation arrow (shaft + chevron head) drawn as a
     * stroked vector path inside the box {@code (x, y, w, h)} — a real icon,
     * not a font glyph. The default UI font lacks the {@code ←}/{@code →}
     * code points, so labels that embedded them rendered as blank/tofu; nav
     * buttons draw this instead.
     *
     * <p>{@code pointLeft} flips the head to the left ({@code ←}) versus the
     * right ({@code →}). A 1px offset shadow pass matches the depth of the
     * shadowed text labels it sits beside.
     */
    public static void navArrow(Canvas canvas, float x, float y, float w, float h,
                                boolean pointLeft, int color, int shadow) {
        if (canvas == null || w <= 0f || h <= 0f) return;
        drawArrowGlyph(canvas, x + 1f, y + 1f, w, h, pointLeft, shadow);
        drawArrowGlyph(canvas, x, y, w, h, pointLeft, color);
    }

    private static void drawArrowGlyph(Canvas canvas, float x, float y, float w, float h,
                                       boolean pointLeft, int color) {
        if ((color & 0xFF000000) == 0) return;
        float cy = y + h / 2f;
        float padX = w * 0.18f;
        float tip  = pointLeft ? x + padX : x + w - padX;
        float tail = pointLeft ? x + w - padX : x + padX;
        float head = h * 0.30f;                       // barb reach from tip
        float headBackX = pointLeft ? tip + head : tip - head;
        float stroke = Math.max(2f, h * 0.13f);

        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(tail, cy);                       // shaft
            pb.lineTo(tip, cy);
            pb.moveTo(headBackX, cy - head);           // upper barb → tip → lower barb
            pb.lineTo(tip, cy);
            pb.lineTo(headBackX, cy + head);
            try (Path path = pb.build();
                 Paint paint = new Paint().setColor(color).setAntiAlias(true)
                         .setMode(PaintMode.STROKE).setStrokeWidth(stroke)
                         .setStrokeCap(PaintStrokeCap.ROUND)
                         .setStrokeJoin(PaintStrokeJoin.ROUND)) {
                canvas.drawPath(path, paint);
            }
        }
    }

    // ─────────────────────────────────────────────── Images

    /**
     * Draw a Skija {@link Image} into the rect {@code (x, y, w, h)}.
     * Uses nearest-neighbour sampling so pixel-art textures (the SBT/OMT
     * assets that feed MasonryUI) stay crisp under integer scaling.
     */
    public static void drawImage(Canvas canvas, Image image, float x, float y, float w, float h) {
        if (canvas == null || image == null || w <= 0f || h <= 0f) return;
        try (Paint paint = new Paint()) {
            Rect src = Rect.makeWH(image.getWidth(), image.getHeight());
            Rect dst = Rect.makeXYWH(x, y, w, h);
            canvas.drawImageRect(image, src, dst, SamplingMode.DEFAULT, paint, true);
        }
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
