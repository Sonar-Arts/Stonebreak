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

    /** Extra horizontal grab room around the scrollbar so the thumb is easy to hit. */
    private static final float GRAB_SLOP = 4f;

    private MScrollMath math;
    private float x, y, width, height;
    private float scrollbarWidth = 12f;
    private float inset = 5f;

    private boolean draggingThumb;
    private float dragGrabDY; // distance from thumb top to the grab point

    public MScrollContainer(MScrollMath math) {
        this.math = math;
    }

    /** Rebinds the scroll math (e.g. when the active category changes). Ends any drag. */
    public MScrollContainer math(MScrollMath math) {
        if (this.math != math) {
            this.math = math;
            draggingThumb = false;
        }
        return this;
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
     * Route a mouse press; returns true if it landed on the scrollbar (thumb
     * starts dragging, a track click jumps there and keeps dragging).
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        if (!math.isScrollNeeded()) return false;
        float sbX = x + width - scrollbarWidth - inset;
        float sbY = y + inset;
        float sbH = height - inset * 2f;
        if (mouseX < sbX - GRAB_SLOP || mouseX > sbX + scrollbarWidth + inset + GRAB_SLOP
                || mouseY < sbY || mouseY > sbY + sbH) {
            return false;
        }
        float thumbH = thumbHeight(sbH);
        float thumbY = thumbTop(sbY, sbH, thumbH);
        draggingThumb = true;
        if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
            dragGrabDY = mouseY - thumbY;
        } else {
            // Track click: center the thumb on the cursor and drag from there.
            dragGrabDY = thumbH / 2f;
            applyThumbDrag(mouseY, sbY, sbH, thumbH);
        }
        return true;
    }

    /** Continues a thumb drag; no-op unless a press landed on the scrollbar. */
    public void handleMouseDrag(float mouseY) {
        if (!draggingThumb) return;
        float sbY = y + inset;
        float sbH = height - inset * 2f;
        applyThumbDrag(mouseY, sbY, sbH, thumbHeight(sbH));
    }

    public void handleMouseRelease() {
        draggingThumb = false;
    }

    public boolean isDraggingThumb() {
        return draggingThumb;
    }

    private void applyThumbDrag(float mouseY, float sbY, float sbH, float thumbH) {
        float travel = sbH - thumbH;
        if (travel <= 0f) return;
        float t = (mouseY - dragGrabDY - sbY) / travel;
        math.scrollTo(Math.max(0f, Math.min(1f, t)) * math.maxOffset());
    }

    private float thumbHeight(float sbH) {
        return Math.max(20f, sbH * (height / math.contentHeight()));
    }

    private float thumbTop(float sbY, float sbH, float thumbH) {
        return sbY + (math.maxOffset() > 0 ? (math.offset() / math.maxOffset()) : 0f)
                * (sbH - thumbH);
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

        float thumbH = thumbHeight(sbH);
        float thumbY = thumbTop(sbY, sbH, thumbH);
        MPainter.fillRect(canvas, sbX + 2f, thumbY, scrollbarWidth - 4f, thumbH, MStyle.SCROLLBAR_THUMB);
        MPainter.strokeRect(canvas, sbX + 2f, thumbY, scrollbarWidth - 4f, thumbH,
                MStyle.SCROLLBAR_THUMB_EDGE, 1f);
    }
}
