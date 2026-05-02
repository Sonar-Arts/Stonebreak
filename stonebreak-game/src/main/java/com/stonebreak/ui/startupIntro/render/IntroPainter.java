package com.stonebreak.ui.startupIntro.render;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.Rect;

/**
 * Faithful Skija port of TheMythalProphecy's MonoGame {@code PrimitiveRenderer}.
 * Every primitive uses the same scanline / line-segment construction the C#
 * version uses so the geometry matches pixel-for-pixel: filled circles and
 * ellipses are stacks of 1-row-tall rectangles, and circle outlines are 16+
 * segment polygons. ARGB ints are 0xAARRGGBB.
 */
public final class IntroPainter {

    private final Canvas canvas;

    public IntroPainter(Canvas canvas) {
        this.canvas = canvas;
    }

    public Canvas canvas() { return canvas; }

    /** Mirrors {@code PrimitiveRenderer.DrawRectangle(Rectangle, Color)}. */
    public void drawRectangleI(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        try (Paint p = new Paint().setColor(argb)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    /** Float-position rectangle. C# uses int rectangles, so positions are truncated. */
    public void drawRectangle(float x, float y, float w, float h, int argb) {
        drawRectangleI((int) x, (int) y, (int) w, (int) h, argb);
    }

    /**
     * Mirrors {@code PrimitiveRenderer.DrawFilledCircle}. Iterates rows of
     * height 1 from {@code -ceil(radius)} to {@code +ceil(radius)} so the
     * resulting silhouette has the same scanline stepping as MonoGame.
     */
    public void drawFilledCircle(float centerX, float centerY, float radius, int argb) {
        if (radius <= 0f) return;
        int iRadius = (int) Math.ceil(radius);
        float radiusSq = radius * radius;
        try (Paint p = new Paint().setColor(argb)) {
            for (int y = -iRadius; y <= iRadius; y++) {
                float halfWidth = (float) Math.sqrt(radiusSq - (float) y * y);
                if (halfWidth < 0.5f) continue;
                int left = (int) (centerX - halfWidth);
                int right = (int) (centerX + halfWidth);
                int width = right - left + 1;
                if (width <= 0) continue;
                canvas.drawRect(Rect.makeXYWH(left, (int) centerY + y, width, 1), p);
            }
        }
    }

    /**
     * Mirrors {@code PrimitiveRenderer.DrawFilledEllipse}. Uses
     * {@code max(8, height/2)} horizontal slices, each
     * {@code height/steps + 1} pixels tall, with widths derived from the
     * unit-circle equation.
     */
    public void drawFilledEllipse(float centerX, float centerY, float width, float height, int argb) {
        if (width <= 0f || height <= 0f) return;
        int steps = Math.max(8, (int) (height / 2f));
        float halfHeight = height / 2f;
        float halfWidth = width / 2f;
        int sliceH = (int) (height / steps) + 1;
        try (Paint p = new Paint().setColor(argb)) {
            for (int i = 0; i < steps; i++) {
                float y = -halfHeight + (height * i / steps);
                float t = y / halfHeight;
                float xWidth = halfWidth * (float) Math.sqrt(1f - t * t);
                int left = (int) (centerX - xWidth);
                int top = (int) (centerY + y);
                int w = (int) (xWidth * 2f);
                if (w <= 0) continue;
                canvas.drawRect(Rect.makeXYWH(left, top, w, sliceH), p);
            }
        }
    }

    /**
     * Mirrors {@code PrimitiveRenderer.DrawCircleOutline}. The C# version
     * stitches together {@code max(16, radius/4)} line segments around the
     * circle, so small rings look faintly polygonal — that retro stepping is
     * part of the look the user wants preserved.
     */
    public void drawCircleOutline(float centerX, float centerY, float radius, int argb, float thickness) {
        if (radius <= 0f) return;
        int segments = Math.max(16, (int) (radius / 4f));
        float twoPi = (float) (Math.PI * 2.0);
        try (Paint p = new Paint()
                .setColor(argb)
                .setMode(PaintMode.STROKE)
                .setStrokeWidth(Math.max(1f, thickness))) {
            for (int i = 0; i < segments; i++) {
                float a1 = twoPi * i / segments;
                float a2 = twoPi * (i + 1) / segments;
                float x1 = centerX + (float) Math.cos(a1) * radius;
                float y1 = centerY + (float) Math.sin(a1) * radius;
                float x2 = centerX + (float) Math.cos(a2) * radius;
                float y2 = centerY + (float) Math.sin(a2) * radius;
                canvas.drawLine(x1, y1, x2, y2, p);
            }
        }
    }
}
