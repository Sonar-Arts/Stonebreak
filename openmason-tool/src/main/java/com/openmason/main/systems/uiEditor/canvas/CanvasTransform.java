package com.openmason.main.systems.uiEditor.canvas;

/**
 * The designer's view transform: frame pixels (the device pixels the document is laid out and
 * painted in) to screen pixels. Editor zoom only scales the picture; it never changes the
 * authored UI scale, so a 100% zoom shows exactly the pixels the game would draw.
 *
 * <p>{@code screen = origin + pan + frame * zoom}, where {@code origin} is the canvas panel's
 * top-left on screen.
 */
public final class CanvasTransform {

    public static final float MIN_ZOOM = 0.05f;
    public static final float MAX_ZOOM = 16f;
    /** Zoom stops for the toolbar menu and Ctrl+/-. */
    public static final float[] STOPS = {0.1f, 0.125f, 0.25f, 0.333f, 0.5f, 0.667f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f,
        8f, 12f, 16f};

    private float zoom = 1f;
    private float panX;
    private float panY;
    private float originX;
    private float originY;

    public float zoom() {
        return zoom;
    }

    public float panX() {
        return panX;
    }

    public float panY() {
        return panY;
    }

    /** Where the canvas panel starts on screen this frame. */
    public void setOrigin(float x, float y) {
        originX = x;
        originY = y;
    }

    public float toScreenX(float frameX) {
        return originX + panX + frameX * zoom;
    }

    public float toScreenY(float frameY) {
        return originY + panY + frameY * zoom;
    }

    public float toFrameX(float screenX) {
        return (screenX - originX - panX) / zoom;
    }

    public float toFrameY(float screenY) {
        return (screenY - originY - panY) / zoom;
    }

    public void panBy(float dx, float dy) {
        panX += dx;
        panY += dy;
    }

    public void set(float zoom, float panX, float panY) {
        this.zoom = clamp(zoom);
        this.panX = panX;
        this.panY = panY;
    }

    /** Zooms to {@code newZoom} keeping the frame point under screen {@code (sx, sy)} fixed. */
    public void zoomAt(float newZoom, float sx, float sy) {
        float fx = toFrameX(sx);
        float fy = toFrameY(sy);
        zoom = clamp(newZoom);
        panX = sx - originX - fx * zoom;
        panY = sy - originY - fy * zoom;
    }

    /** The next stop above ({@code dir > 0}) or below the current zoom. */
    public float step(int dir) {
        if (dir > 0) {
            for (float s : STOPS) {
                if (s > zoom + 1e-4f) {
                    return s;
                }
            }
            return MAX_ZOOM;
        }
        for (int i = STOPS.length - 1; i >= 0; i--) {
            if (STOPS[i] < zoom - 1e-4f) {
                return STOPS[i];
            }
        }
        return MIN_ZOOM;
    }

    /**
     * Fits a {@code frameW x frameH} region whose top-left is {@code (fx, fy)} into a
     * {@code viewW x viewH} panel with {@code margin} screen pixels around it, never above
     * {@code maxZoom}.
     */
    public void fit(float fx, float fy, float frameW, float frameH, float viewW, float viewH, float margin, float maxZoom) {
        if (frameW <= 0 || frameH <= 0 || viewW <= 2 * margin || viewH <= 2 * margin) {
            return;
        }
        zoom = clamp(Math.min(maxZoom, Math.min((viewW - 2 * margin) / frameW, (viewH - 2 * margin) / frameH)));
        panX = (viewW - frameW * zoom) / 2f - fx * zoom;
        panY = (viewH - frameH * zoom) / 2f - fy * zoom;
    }

    private static float clamp(float z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }
}
