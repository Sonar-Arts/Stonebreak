package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Vector symbol library for MasonryUI. Each constant draws itself into an
 * arbitrary box — a real icon, never a font glyph, for the same reason
 * {@link MPainter#navArrow} exists: the default UI font lacks most symbol
 * code points, so glyph-based icons render as blank/tofu.
 *
 * <p>Every symbol is pure geometry (stroked polylines, filled polygons,
 * circles), scales with the box, and is fully deterministic — the raster
 * tests rely on that. Following the codebase constraint that Skija's
 * {@code PathBuilder} only exposes {@code moveTo}/{@code lineTo}, arcs are
 * tessellated into short line segments and hidden under stroke width.
 *
 * <p>Usage: {@code MSymbol.GEAR.draw(canvas, x, y, w, h, color)} or
 * {@code drawWithShadow(...)} for the 1px depth pass that matches shadowed
 * text labels.
 */
public enum MSymbol {
    CHEVRON_UP, CHEVRON_DOWN, CHEVRON_LEFT, CHEVRON_RIGHT,
    PLUS, MINUS, CROSS, CHECK,
    GEAR, MAGNIFIER, WARNING, INFO,
    STAR, HEART, LOCK, PLAY, PAUSE;

    /**
     * Draws this symbol centered in the box {@code (x, y, w, h)}. Ignores
     * degenerate boxes and fully transparent colors so callers can pass
     * style constants unconditionally.
     */
    public void draw(Canvas canvas, float x, float y, float w, float h, int color) {
        if (canvas == null || w <= 0f || h <= 0f || (color & 0xFF000000) == 0) return;
        float s = Math.min(w, h);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float stroke = Math.max(1.5f, s * 0.11f);

        switch (this) {
            case CHEVRON_UP -> strokePolyline(canvas, color, stroke,
                    cx - 0.28f * s, cy + 0.14f * s, cx, cy - 0.14f * s, cx + 0.28f * s, cy + 0.14f * s);
            case CHEVRON_DOWN -> strokePolyline(canvas, color, stroke,
                    cx - 0.28f * s, cy - 0.14f * s, cx, cy + 0.14f * s, cx + 0.28f * s, cy - 0.14f * s);
            case CHEVRON_LEFT -> strokePolyline(canvas, color, stroke,
                    cx + 0.14f * s, cy - 0.28f * s, cx - 0.14f * s, cy, cx + 0.14f * s, cy + 0.28f * s);
            case CHEVRON_RIGHT -> strokePolyline(canvas, color, stroke,
                    cx - 0.14f * s, cy - 0.28f * s, cx + 0.14f * s, cy, cx - 0.14f * s, cy + 0.28f * s);
            case PLUS -> {
                strokePolyline(canvas, color, stroke, cx - 0.3f * s, cy, cx + 0.3f * s, cy);
                strokePolyline(canvas, color, stroke, cx, cy - 0.3f * s, cx, cy + 0.3f * s);
            }
            case MINUS -> strokePolyline(canvas, color, stroke,
                    cx - 0.3f * s, cy, cx + 0.3f * s, cy);
            case CROSS -> {
                strokePolyline(canvas, color, stroke,
                        cx - 0.25f * s, cy - 0.25f * s, cx + 0.25f * s, cy + 0.25f * s);
                strokePolyline(canvas, color, stroke,
                        cx + 0.25f * s, cy - 0.25f * s, cx - 0.25f * s, cy + 0.25f * s);
            }
            case CHECK -> strokePolyline(canvas, color, stroke,
                    cx - 0.28f * s, cy + 0.02f * s, cx - 0.08f * s, cy + 0.22f * s, cx + 0.3f * s, cy - 0.2f * s);
            case GEAR -> drawGear(canvas, cx, cy, s, color);
            case MAGNIFIER -> drawMagnifier(canvas, cx, cy, s, color);
            case WARNING -> drawWarning(canvas, cx, cy, s, color);
            case INFO -> drawInfo(canvas, cx, cy, s, color);
            case STAR -> drawStar(canvas, cx, cy, s, color);
            case HEART -> drawHeart(canvas, cx, cy, s, color);
            case LOCK -> drawLock(canvas, cx, cy, s, color);
            case PLAY -> fillPolygon(canvas, color,
                    cx - 0.22f * s, cy - 0.3f * s, cx - 0.22f * s, cy + 0.3f * s, cx + 0.34f * s, cy);
            case PAUSE -> {
                fillRect(canvas, color, cx - 0.24f * s, cy - 0.3f * s, 0.17f * s, 0.6f * s);
                fillRect(canvas, color, cx + 0.07f * s, cy - 0.3f * s, 0.17f * s, 0.6f * s);
            }
        }
    }

    /**
     * Draws the symbol over a 1px-offset shadow pass — same depth treatment
     * as {@link MPainter#drawStringWithShadow}, so icons sit visually level
     * with the shadowed labels beside them.
     */
    public void drawWithShadow(Canvas canvas, float x, float y, float w, float h,
                               int color, int shadow) {
        draw(canvas, x + 1f, y + 1f, w, h, shadow);
        draw(canvas, x, y, w, h, color);
    }

    // ─────────────────────────────────────────────── Composite symbols

    private static void drawGear(Canvas canvas, float cx, float cy, float s, int color) {
        // Thick-stroked ring for the wheel, radial stubs for the teeth. The
        // hub shows through the ring's hole, so no even-odd fill is needed.
        strokeCircle(canvas, cx, cy, 0.26f * s, s * 0.13f, color);
        float inner = 0.3f * s;
        float outer = 0.44f * s;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45.0 + 22.5);
            float dx = (float) Math.cos(a);
            float dy = (float) Math.sin(a);
            strokePolyline(canvas, color, s * 0.11f,
                    cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer);
        }
    }

    private static void drawMagnifier(Canvas canvas, float cx, float cy, float s, int color) {
        strokeCircle(canvas, cx - 0.09f * s, cy - 0.09f * s, 0.22f * s, s * 0.09f, color);
        strokePolyline(canvas, color, s * 0.12f,
                cx + 0.09f * s, cy + 0.09f * s, cx + 0.32f * s, cy + 0.32f * s);
    }

    private static void drawWarning(Canvas canvas, float cx, float cy, float s, int color) {
        strokePolyline(canvas, color, s * 0.09f,
                cx, cy - 0.34f * s,
                cx - 0.36f * s, cy + 0.26f * s,
                cx + 0.36f * s, cy + 0.26f * s,
                cx, cy - 0.34f * s);
        strokePolyline(canvas, color, s * 0.09f, cx, cy - 0.14f * s, cx, cy + 0.06f * s);
        fillCircle(canvas, cx, cy + 0.17f * s, s * 0.05f, color);
    }

    private static void drawInfo(Canvas canvas, float cx, float cy, float s, int color) {
        strokeCircle(canvas, cx, cy, 0.32f * s, s * 0.08f, color);
        strokePolyline(canvas, color, s * 0.09f, cx, cy - 0.02f * s, cx, cy + 0.18f * s);
        fillCircle(canvas, cx, cy - 0.15f * s, s * 0.055f, color);
    }

    private static void drawStar(Canvas canvas, float cx, float cy, float s, int color) {
        // Classic 10-vertex star polygon (alternating outer/inner radii) —
        // non-self-intersecting, so the default winding fill is safe.
        float outer = 0.42f * s;
        float inner = 0.18f * s;
        float[] pts = new float[20];
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90.0 + i * 36.0);
            float r = (i & 1) == 0 ? outer : inner;
            pts[i * 2] = cx + (float) (r * Math.cos(a));
            pts[i * 2 + 1] = cy + (float) (r * Math.sin(a));
        }
        fillPolygon(canvas, color, pts);
    }

    private static void drawHeart(Canvas canvas, float cx, float cy, float s, int color) {
        // Two round lobes joined by a triangle down to the point — a curve-free
        // approximation that reads as a heart at UI icon sizes.
        fillCircle(canvas, cx - 0.15f * s, cy - 0.11f * s, 0.17f * s, color);
        fillCircle(canvas, cx + 0.15f * s, cy - 0.11f * s, 0.17f * s, color);
        fillPolygon(canvas, color,
                cx - 0.3f * s, cy - 0.05f * s,
                cx + 0.3f * s, cy - 0.05f * s,
                cx, cy + 0.34f * s);
    }

    private static void drawLock(Canvas canvas, float cx, float cy, float s, int color) {
        // Shackle: upper semicircle tessellated into segments (y-down canvas:
        // 180°→360° sweeps through the top).
        float r = 0.15f * s;
        float top = cy - 0.05f * s;
        final int steps = 10;
        float[] pts = new float[(steps + 1) * 2];
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(180.0 + 180.0 * i / steps);
            pts[i * 2] = cx + (float) (r * Math.cos(a));
            pts[i * 2 + 1] = top + (float) (r * Math.sin(a));
        }
        strokePolyline(canvas, color, s * 0.08f, pts);
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(cx - 0.22f * s, top, 0.44f * s, 0.38f * s, 0.05f * s), p);
        }
    }

    // ─────────────────────────────────────────────── Geometry primitives

    /** Strokes a round-capped polyline through {@code (x0,y0), (x1,y1), ...}. */
    private static void strokePolyline(Canvas canvas, int color, float strokeWidth, float... pts) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(pts[0], pts[1]);
            for (int i = 2; i < pts.length; i += 2) {
                pb.lineTo(pts[i], pts[i + 1]);
            }
            try (Path path = pb.build();
                 Paint paint = new Paint().setColor(color).setAntiAlias(true)
                         .setMode(PaintMode.STROKE).setStrokeWidth(strokeWidth)
                         .setStrokeCap(PaintStrokeCap.ROUND)
                         .setStrokeJoin(PaintStrokeJoin.ROUND)) {
                canvas.drawPath(path, paint);
            }
        }
    }

    /** Fills the polygon through {@code (x0,y0), (x1,y1), ...} (implicitly closed). */
    private static void fillPolygon(Canvas canvas, int color, float... pts) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(pts[0], pts[1]);
            for (int i = 2; i < pts.length; i += 2) {
                pb.lineTo(pts[i], pts[i + 1]);
            }
            pb.lineTo(pts[0], pts[1]);
            try (Path path = pb.build();
                 Paint paint = new Paint().setColor(color).setAntiAlias(true)) {
                canvas.drawPath(path, paint);
            }
        }
    }

    private static void strokeCircle(Canvas canvas, float cx, float cy, float r,
                                     float strokeWidth, int color) {
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(strokeWidth)) {
            canvas.drawCircle(cx, cy, r, p);
        }
    }

    private static void fillCircle(Canvas canvas, float cx, float cy, float r, int color) {
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawCircle(cx, cy, r, p);
        }
    }

    private static void fillRect(Canvas canvas, int color, float x, float y, float w, float h) {
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }
}
