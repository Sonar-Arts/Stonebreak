package com.stonebreak.rendering.UI.components;

import org.lwjgl.opengl.GL11;

/**
 * A screen-edge tint that fades toward a target opacity: four trapezoidal bands, opaque at the
 * border and transparent toward the centre, drawn with legacy-GL quads like
 * {@link UnderwaterOverlayRenderer}. The shared body of the full-screen state cues (the dodge
 * i-frame flash, the stealth border); each cue only decides when it is active.
 */
public final class EdgeVignette {

    private final float red;
    private final float green;
    private final float blue;
    private final float peakOpacity;
    /** Fraction of the smaller screen dimension the band reaches inward. */
    private final float edgeFraction;
    /** Opacity gained per second while active / lost per second while not. */
    private final float fadeInSpeed;
    private final float fadeOutSpeed;

    private float currentOpacity;

    /**
     * @param rgb          tint colour as {@code 0xRRGGBB}
     * @param peakOpacity  alpha at the very edge once fully faded in
     * @param edgeFraction how far inward the band reaches, as a fraction of the smaller screen side
     * @param fadeInSpeed  opacity per second while active
     * @param fadeOutSpeed opacity per second while inactive
     */
    public EdgeVignette(int rgb, float peakOpacity, float edgeFraction, float fadeInSpeed, float fadeOutSpeed) {
        this.red = ((rgb >> 16) & 0xFF) / 255f;
        this.green = ((rgb >> 8) & 0xFF) / 255f;
        this.blue = (rgb & 0xFF) / 255f;
        this.peakOpacity = peakOpacity;
        this.edgeFraction = edgeFraction;
        this.fadeInSpeed = fadeInSpeed;
        this.fadeOutSpeed = fadeOutSpeed;
    }

    /** Moves the opacity toward the peak while {@code active}, toward 0 otherwise. */
    public void update(boolean active, float deltaTime) {
        if (active) {
            currentOpacity = Math.min(peakOpacity, currentOpacity + fadeInSpeed * deltaTime);
        } else {
            currentOpacity = Math.max(0.0f, currentOpacity - fadeOutSpeed * deltaTime);
        }
    }

    /** Draws the vignette if visible. Call BEFORE UI rendering, like the underwater overlay. */
    public void render(int windowWidth, int windowHeight) {
        if (currentOpacity <= 0.0f) {
            return;
        }

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, windowWidth, windowHeight, 0, -1, 1);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        float edge = Math.min(windowWidth, windowHeight) * edgeFraction;
        float outerA = currentOpacity; // opaque tint at the screen border
        float innerA = 0.0f;           // transparent toward the centre

        // Four trapezoidal bands (top/bottom/left/right) each fading from the edge (outerA)
        // inward to transparent (innerA), forming a vignette ring.
        GL11.glBegin(GL11.GL_QUADS);

        // Top band
        edgeVertex(0, 0, outerA);
        edgeVertex(windowWidth, 0, outerA);
        edgeVertex(windowWidth - edge, edge, innerA);
        edgeVertex(edge, edge, innerA);

        // Bottom band
        edgeVertex(edge, windowHeight - edge, innerA);
        edgeVertex(windowWidth - edge, windowHeight - edge, innerA);
        edgeVertex(windowWidth, windowHeight, outerA);
        edgeVertex(0, windowHeight, outerA);

        // Left band
        edgeVertex(0, 0, outerA);
        edgeVertex(edge, edge, innerA);
        edgeVertex(edge, windowHeight - edge, innerA);
        edgeVertex(0, windowHeight, outerA);

        // Right band
        edgeVertex(windowWidth - edge, edge, innerA);
        edgeVertex(windowWidth, 0, outerA);
        edgeVertex(windowWidth, windowHeight, outerA);
        edgeVertex(windowWidth - edge, windowHeight - edge, innerA);

        GL11.glEnd();

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private void edgeVertex(float x, float y, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glVertex2f(x, y);
    }

    public float getCurrentOpacity() { return currentOpacity; }

    public boolean isVisible() { return currentOpacity > 0.0f; }
}
