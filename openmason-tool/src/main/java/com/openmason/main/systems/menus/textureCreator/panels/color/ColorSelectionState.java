package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.color.ColorUtils;

/**
 * Single source of truth for the texture editor's active color. Canonical
 * representation is HSV + alpha (so hue is preserved while dragging through
 * desaturated colors); packed-RGBA and component views are derived.
 *
 * Also tracks the previous/last-painted color pair driving the
 * "click previous swatch to swap" behavior.
 */
public final class ColorSelectionState {

    private float hue = 0f;          // 0-360
    private float saturation = 1f;   // 0-1
    private float value = 1f;        // 0-1
    private int alpha = 255;         // 0-255

    private int previousColor = 0xFF000000;
    private int lastPaintedColor = 0xFF000000;

    public ColorSelectionState() {
        setColor(0xFF000000); // opaque black
    }

    // ---- canonical accessors ----

    public float getHue() {
        return hue;
    }

    public float getSaturation() {
        return saturation;
    }

    public float getValue() {
        return value;
    }

    public int getAlpha() {
        return alpha;
    }

    /** Current color as packed RGBA int (PixelCanvas format, 0xAABBGGRR). */
    public int getCurrentColor() {
        return ColorUtils.hsvToPackedColor(hue, saturation, value, alpha);
    }

    // ---- mutators (each keeps the canonical HSV consistent) ----

    public void setColor(int packedRgba) {
        float[] hsva = ColorUtils.packedColorToHsv(packedRgba);
        this.hue = hsva[0];
        this.saturation = hsva[1];
        this.value = hsva[2];
        this.alpha = (int) hsva[3];
    }

    public void setHue(float hue) {
        this.hue = ColorUtils.clamp(hue, 0f, 360f);
    }

    public void setSaturationValue(float saturation, float value) {
        this.saturation = ColorUtils.clamp(saturation, 0f, 1f);
        this.value = ColorUtils.clamp(value, 0f, 1f);
    }

    public void setAlpha(int alpha) {
        this.alpha = ColorUtils.clamp(alpha, 0, 255);
    }

    public void setRgb(int r, int g, int b) {
        float[] hsv = ColorUtils.rgbToHsv(
                ColorUtils.clamp(r, 0, 255),
                ColorUtils.clamp(g, 0, 255),
                ColorUtils.clamp(b, 0, 255));
        this.hue = hsv[0];
        this.saturation = hsv[1];
        this.value = hsv[2];
    }

    /** Current color's RGBA components [r, g, b, a], each 0-255. */
    public int[] getRgba() {
        return PixelCanvas.unpackRGBA(getCurrentColor());
    }

    // ---- previous-color tracking ----

    public int getPreviousColor() {
        return previousColor;
    }

    /**
     * Record that the current color was painted on the canvas. The previous
     * swatch only advances when a different color is painted.
     */
    public void markPainted() {
        int current = getCurrentColor();
        if (current != lastPaintedColor) {
            previousColor = lastPaintedColor;
            lastPaintedColor = current;
        }
    }

    /** Swap the current and previous colors (previous-swatch click). */
    public void swapWithPrevious() {
        int current = getCurrentColor();
        int previous = previousColor;
        previousColor = current;
        setColor(previous);
    }
}
