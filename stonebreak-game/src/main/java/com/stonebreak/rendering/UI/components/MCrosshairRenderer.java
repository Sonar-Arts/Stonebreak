package com.stonebreak.rendering.UI.components;

import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Skija/MasonryUI-based crosshair renderer.
 *
 * Replaces the NanoVG {@code CrosshairRenderer} using the same frame-bracketing
 * pattern as {@code MHotbarRenderer}. Supports six crosshair styles and
 * per-colour/outline configuration.
 */
public class MCrosshairRenderer {

    // ── Crosshair styles ─────────────────────────────────────────────────
    public enum CrosshairStyle {
        SIMPLE_CROSS,    // Traditional + shape
        DOT,            // Single center dot
        CIRCLE,         // Hollow circle
        SQUARE,         // Hollow square
        T_SHAPE,        // T-shaped crosshair
        PLUS_DOT        // + with center dot
    }

    // ── Configuration ────────────────────────────────────────────────────
    private CrosshairStyle style       = CrosshairStyle.SIMPLE_CROSS;
    private float          size        = 16.0f;
    private float          thickness   = 2.0f;
    private float          gap         = 4.0f;
    private float          opacity     = 1.0f;
    private float[]        color       = {1.0f, 1.0f, 1.0f};  // RGB white
    private boolean        outline     = true;
    private float          outlineThickness = 1.0f;
    private float[]        outlineColor = {0.0f, 0.0f, 0.0f}; // RGB black

    private final MasonryUI ui;

    // ── Colors (ARGB for Skija Paint.setColor) ───────────────────────────
    /** 0xFFFFFFFF — white, full alpha. */
    private static final int DEFAULT_FILL    = 0xFFFFFFFF;
    /** 0xFF000000 — black outline. */
    private static final int DEFAULT_OUTLINE = 0xFF000000;

    public MCrosshairRenderer(SkijaUIBackend skijaBackend) {
        this.ui = new MasonryUI(skijaBackend);
    }

    // ─────────────────────────────────────────────── Public API

    /**
     * Renders the crosshair at the center of the screen.
     */
    public void renderCrosshair(int windowWidth, int windowHeight) {
        if (!ui.isAvailable()) return;

        float cx = windowWidth / 2f;
        float cy = windowHeight / 2f;

        if (ui.beginFrame(windowWidth, windowHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            int fill = rgbaToArgb(color[0], color[1], color[2], opacity);
            int edge = rgbaToArgb(outlineColor[0], outlineColor[1], outlineColor[2], opacity);

            switch (style) {
                case SIMPLE_CROSS -> drawSimpleCross(canvas, cx, cy, fill, edge);
                case DOT          -> drawDot(canvas, cx, cy, fill, edge);
                case CIRCLE       -> drawCircle(canvas, cx, cy, fill, edge);
                case SQUARE       -> drawSquare(canvas, cx, cy, fill, edge);
                case T_SHAPE      -> drawTShape(canvas, cx, cy, fill, edge);
                case PLUS_DOT     -> drawPlusDot(canvas, cx, cy, fill, edge);
            }
            ui.endFrame();
        }
    }

    // ─────────────────────────────────────────────── Style draw methods

    private void drawSimpleCross(Canvas c, float cx, float cy, int fill, int edge) {
        float half    = size / 2f;
        float halfGap = gap / 2f;
        float arm     = half - halfGap;

        // Arms: left, right, top, bottom
        float[][] rects = {
                {cx - half,     cy - thickness / 2f, arm, thickness},
                {cx + halfGap,  cy - thickness / 2f, arm, thickness},
                {cx - thickness / 2f, cy - half, thickness, arm},
                {cx - thickness / 2f, cy + halfGap, thickness, arm}
        };
        drawRects(c, rects, fill, edge);
    }

    private void drawDot(Canvas c, float cx, float cy, int fill, int edge) {
        float r = size / 4f;
        if (outline) drawOutlinedCircle(c, cx, cy, r, fill, edge, 0f);
        else {
            try (Paint p = paint(fill)) {
                c.drawCircle(cx, cy, r, p);
            }
        }
    }

    private void drawCircle(Canvas c, float cx, float cy, int fill, int edge) {
        float r = size / 2f;
        if (outline) drawOutlinedCircle(c, cx, cy, r, edge, fill, thickness);
        else {
            try (Paint p = paint(edge).setMode(PaintMode.STROKE).setStrokeWidth(thickness)) {
                c.drawCircle(cx, cy, r, p);
            }
        }
    }

    private void drawSquare(Canvas c, float cx, float cy, int fill, int edge) {
        float half = size / 2f;
        float[][] rects = {{cx - half, cy - half, size, size}};
        if (outline) {
            drawOutlinedRects(c, rects, fill, edge, thickness);
        } else {
            drawRectsStroked(c, rects, edge, thickness);
        }
    }

    private void drawTShape(Canvas c, float cx, float cy, int fill, int edge) {
        float half    = size / 2f;
        float halfGap = gap / 2f;

        float[][] rects = {
                // horizontal top bar
                {cx - half, cy - halfGap - thickness, size, thickness},
                // vertical stem (below gap)
                {cx - thickness / 2f, cy + halfGap, thickness, half - halfGap}
        };
        drawRects(c, rects, fill, edge);
    }

    private void drawPlusDot(Canvas c, float cx, float cy, int fill, int edge) {
        drawSimpleCross(c, cx, cy, fill, edge);
        drawDot(c, cx, cy, fill, edge);
    }

    // ─────────────────────────────────────────────── Helpers

    /** Draw filled rects, then stroke the union outline (approximated by stroking each rect). */
    private void drawRects(Canvas c, float[][] rects, int fill, int edge) {
        try (Paint fillP = paint(fill)) {
            for (float[] r : rects) {
                c.drawRect(Rect.makeXYWH(r[0], r[1], r[2], r[3]), fillP);
            }
        }
        if (outline) {
            try (Paint strokeP = paint(edge).setMode(PaintMode.STROKE).setStrokeWidth(outlineThickness)) {
                for (float[] r : rects) {
                    c.drawRect(Rect.makeXYWH(r[0], r[1], r[2], r[3]), strokeP);
                }
            }
        }
    }

    private void drawRectsStroked(Canvas c, float[][] rects, int color, float width) {
        try (Paint p = paint(color).setMode(PaintMode.STROKE).setStrokeWidth(width)) {
            for (float[] r : rects) {
                c.drawRect(Rect.makeXYWH(r[0], r[1], r[2], r[3]), p);
            }
        }
    }

    private void drawOutlinedRects(Canvas c, float[][] rects, int fill, int edge, float strokeW) {
        try (Paint fillP = paint(edge).setMode(PaintMode.STROKE).setStrokeWidth(strokeW + outlineThickness * 2)) {
            for (float[] r : rects) {
                c.drawRect(Rect.makeXYWH(r[0], r[1], r[2], r[3]), fillP);
            }
        }
        try (Paint strokeP = paint(fill).setMode(PaintMode.STROKE).setStrokeWidth(strokeW)) {
            for (float[] r : rects) {
                c.drawRect(Rect.makeXYWH(r[0], r[1], r[2], r[3]), strokeP);
            }
        }
    }

    /** Draw a circle with a filled outline ring around it. */
    private void drawOutlinedCircle(Canvas c, float cx, float cy, float r,
                                    int outerColor, int innerColor, float innerStrokeW) {
        // Outer ring = thicker stroke
        try (Paint p = paint(outerColor).setMode(PaintMode.STROKE).setStrokeWidth(innerStrokeW + outlineThickness * 2)) {
            c.drawCircle(cx, cy, r, p);
        }
        // Inner circle/stroke = original color
        if (innerStrokeW > 0) {
            try (Paint p = paint(innerColor).setMode(PaintMode.STROKE).setStrokeWidth(innerStrokeW)) {
                c.drawCircle(cx, cy, r, p);
            }
        } else {
            try (Paint p = paint(innerColor)) {
                c.drawCircle(cx, cy, r, p);
            }
        }
    }

    private static Paint paint(int color) {
        return new Paint().setColor(color).setAntiAlias(true);
    }

    /** Convert RGBA floats (0-1) to ARGB int for Skija Paint.setColor. */
    private static int rgbaToArgb(float r, float g, float b, float a) {
        int ir = Math.round(r * 255) & 0xFF;
        int ig = Math.round(g * 255) & 0xFF;
        int ib = Math.round(b * 255) & 0xFF;
        int ia = Math.round(a * 255) & 0xFF;
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }

    // ─────────────────────────────────────────────── Configuration setters

    public void setStyle(CrosshairStyle style)       { this.style = style; }
    public void setSize(float size)                  { this.size = Math.max(4f, size); }
    public void setThickness(float thickness)        { this.thickness = Math.max(1f, thickness); }
    public void setGap(float gap)                    { this.gap = Math.max(0f, gap); }
    public void setOpacity(float opacity)            { this.opacity = Math.max(0f, Math.min(1f, opacity)); }
    public void setColor(float r, float g, float b) {
        this.color[0] = Math.max(0f, Math.min(1f, r));
        this.color[1] = Math.max(0f, Math.min(1f, g));
        this.color[2] = Math.max(0f, Math.min(1f, b));
    }
    public void setOutline(boolean outline)          { this.outline = outline; }
    public void setOutlineThickness(float thickness) { this.outlineThickness = Math.max(0.5f, thickness); }
    public void setOutlineColor(float r, float g, float b) {
        this.outlineColor[0] = Math.max(0f, Math.min(1f, r));
        this.outlineColor[1] = Math.max(0f, Math.min(1f, g));
        this.outlineColor[2] = Math.max(0f, Math.min(1f, b));
    }

    // ─────────────────────────────────────────────── Getters

    public CrosshairStyle getStyle()           { return style; }
    public float getSize()                     { return size; }
    public float getThickness()                { return thickness; }
    public float getGap()                      { return gap; }
    public float getOpacity()                  { return opacity; }
    public float[] getColor()                  { return color.clone(); }
    public boolean hasOutline()                { return outline; }
    public float getOutlineThickness()         { return outlineThickness; }
    public float[] getOutlineColor()           { return outlineColor.clone(); }
}
