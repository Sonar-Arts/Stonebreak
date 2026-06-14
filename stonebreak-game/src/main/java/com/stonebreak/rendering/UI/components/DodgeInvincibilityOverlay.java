package com.stonebreak.rendering.UI.components;

import com.stonebreak.player.Player;
import org.lwjgl.opengl.GL11;

/**
 * Renders a brief screen-edge vignette while the player is inside the dodge
 * invincibility window, giving on-screen feedback that the dodge's i-frames are
 * active. The tint is concentrated at the screen edges (transparent at the
 * centre) and fades in/out quickly. Mirrors the legacy-GL fullscreen approach of
 * {@link UnderwaterOverlayRenderer}.
 */
public class DodgeInvincibilityOverlay {

    private static final float OVERLAY_OPACITY = 0.45f; // peak alpha at the screen edge
    private static final float OVERLAY_RED   = 1.0f;
    private static final float OVERLAY_GREEN = 1.0f;
    private static final float OVERLAY_BLUE  = 0.8f;    // warm white flash

    /** Fraction of the smaller screen dimension the vignette band reaches inward. */
    private static final float EDGE_FRACTION = 0.18f;

    private float currentOpacity = 0.0f;
    private static final float FADE_SPEED = 10.0f; // snappy in/out for a 0.25s window

    /**
     * Updates the vignette opacity based on the player's dodge invincibility state.
     * @param player    the player to check (may be null)
     * @param deltaTime seconds since last frame
     */
    public void update(Player player, float deltaTime) {
        if (player == null) return;

        boolean active = player.getDodge().isInvincible();
        float targetOpacity = active ? OVERLAY_OPACITY : 0.0f;

        if (currentOpacity < targetOpacity) {
            currentOpacity = Math.min(targetOpacity, currentOpacity + FADE_SPEED * deltaTime);
        } else if (currentOpacity > targetOpacity) {
            currentOpacity = Math.max(targetOpacity, currentOpacity - FADE_SPEED * deltaTime);
        }
    }

    /**
     * Renders the edge vignette if currently visible. Call BEFORE UI rendering, like
     * the underwater overlay.
     */
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

        float edge = Math.min(windowWidth, windowHeight) * EDGE_FRACTION;
        float outerA = currentOpacity; // opaque tint at the screen border
        float innerA = 0.0f;           // transparent toward the centre

        // Draw four trapezoidal bands (top/bottom/left/right) each fading from the
        // edge (outerA) inward to transparent (innerA), forming a vignette ring.
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
        GL11.glColor4f(OVERLAY_RED, OVERLAY_GREEN, OVERLAY_BLUE, alpha);
        GL11.glVertex2f(x, y);
    }

    public float getCurrentOpacity() { return currentOpacity; }

    public boolean isVisible() { return currentOpacity > 0.0f; }
}
