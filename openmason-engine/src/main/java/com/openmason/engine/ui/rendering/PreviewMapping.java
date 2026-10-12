package com.openmason.engine.ui.rendering;

/**
 * Pointer coordinates of a host (an ImGui image item) mapped into canvas coordinates of the
 * frame shown inside it. Input is converted here, separately from drawing: the image may be
 * shown at any zoom, while the frame is always painted in its own pixels.
 *
 * <p>{@code canvas = (pointer - imageOrigin) / scale}, with no Y inversion: the displayed image
 * is already top-down, whichever path produced it (raster upload, or an FBO with
 * {@link UiRenderTarget.Origin#TOP_LEFT}).
 *
 * @param imageX screen X of the image's top-left
 * @param imageY screen Y of the image's top-left
 * @param scale  displayed pixels per canvas pixel (editor zoom × display scale)
 */
public record PreviewMapping(float imageX, float imageY, float scale) {

    public PreviewMapping {
        if (!(scale > 0f) || !Float.isFinite(scale)) {
            throw new IllegalArgumentException("scale must be positive: " + scale);
        }
    }

    public float canvasX(float pointerX) {
        return (pointerX - imageX) / scale;
    }

    public float canvasY(float pointerY) {
        return (pointerY - imageY) / scale;
    }

    public float screenX(float canvasX) {
        return imageX + canvasX * scale;
    }

    public float screenY(float canvasY) {
        return imageY + canvasY * scale;
    }
}
