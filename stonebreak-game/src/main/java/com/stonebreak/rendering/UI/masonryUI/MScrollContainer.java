package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.types.Rect;

/**
 * Viewport that clips its content to a rectangle and delegates scrolling to
 * {@link MScrollMath}. The caller supplies a content painter that is free to
 * draw using the current {@link #offset()} — the container does the clip,
 * scrollbar, and wheel routing.
 *
 * Using Skija's {@code Canvas.clipRect} here replaces the old NanoVG scissor
 * call; the backend already resets scissor state in SkiaContext's
 * restore-defaults path, so we don't need to manually unset it after.
 */
public final class MScrollContainer {

    private final MScrollMath math;
    private float x, y, width, height;
    private float scrollbarWidth = 12f;
    private float inset = 5f;

    public MScrollContainer(MScrollMath math) {
        this.math = math;
    }

    public MScrollContainer bounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public MScrollContainer scrollbarWidth(float w) { this.scrollbarWidth = w; return this; }
    public MScrollContainer inset(float v) { this.inset = v; return this; }

    public MScrollMath math() { return math; }
    public float offset() { return math.offset(); }

    public float x() { return x; }
    public float y() { return y; }
    public float width() { return width; }
    public float height() { return height; }
    public float bottom() { return y + height; }
    public float centerX() { return x + width / 2f; }

    public boolean contains(float mouseX, float mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * Route a wheel event; returns true if consumed.
     */
    public boolean handleWheel(float mouseX, float mouseY, float delta) {
        if (!contains(mouseX, mouseY)) return false;
        math.handleWheel(delta);
        return true;
    }

    /**
     * Draws the container background, clips to its viewport, invokes the
     * content painter, then draws the scrollbar outside the clip.
     */
    public void render(MasonryUI ui, Runnable contentPainter) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        // Subtle fill + crisp border outline visually bound the viewport.
        MPainter.fillRect(canvas, x, y, width, height, 0x1A000000);

        int save = canvas.save();
        canvas.clipRect(Rect.makeXYWH(x, y, width, height), ClipMode.INTERSECT, true);
        if (contentPainter != null) contentPainter.run();
        canvas.restoreToCount(save);

        MPainter.strokeRect(canvas, x, y, width, height, MStyle.PANEL_BORDER, 1.5f);

        if (math.isScrollNeeded()) drawScrollbar(canvas);
    }

    private void drawScrollbar(Canvas canvas) {
        float sbX = x + width - scrollbarWidth - inset;
        float sbY = y + inset;
        float sbH = height - inset * 2f;
        MPainter.fillRect(canvas, sbX, sbY, scrollbarWidth, sbH, MStyle.SCROLLBAR_TRACK);

        float thumbH = Math.max(20f, sbH * (height / math.contentHeight()));
        float thumbY = sbY + (math.maxOffset() > 0 ? (math.offset() / math.maxOffset()) : 0f)
                * (sbH - thumbH);
        MPainter.fillRect(canvas, sbX + 2f, thumbY, scrollbarWidth - 4f, thumbH, MStyle.SCROLLBAR_THUMB);
        MPainter.strokeRect(canvas, sbX + 2f, thumbY, scrollbarWidth - 4f, thumbH,
                MStyle.SCROLLBAR_THUMB_EDGE, 1f);
    }
}
