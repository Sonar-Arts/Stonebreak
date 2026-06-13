package com.openmason.main.systems.menus.textureCreator.panels.status;

/**
 * Per-frame canvas hover state for the status bar: which pixel the cursor is
 * over, if any. Updated by the canvas panel each frame; read by
 * {@link StatusBarPanel}.
 */
public final class CanvasHoverInfo {

    private boolean hovering;
    private int pixelX;
    private int pixelY;

    public void set(int pixelX, int pixelY) {
        this.hovering = true;
        this.pixelX = pixelX;
        this.pixelY = pixelY;
    }

    public void clear() {
        this.hovering = false;
    }

    public boolean isHovering() {
        return hovering;
    }

    public int getPixelX() {
        return pixelX;
    }

    public int getPixelY() {
        return pixelY;
    }
}
