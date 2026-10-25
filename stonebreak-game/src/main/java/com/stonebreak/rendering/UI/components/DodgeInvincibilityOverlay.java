package com.stonebreak.rendering.UI.components;

import com.stonebreak.player.Player;

/**
 * Renders a brief screen-edge vignette while the player is inside the dodge
 * invincibility window, giving on-screen feedback that the dodge's i-frames are
 * active. A warm-white {@link EdgeVignette} that fades in/out quickly.
 */
public class DodgeInvincibilityOverlay {

    private static final int OVERLAY_RGB = 0xFFFFCC;       // warm white flash
    private static final float OVERLAY_OPACITY = 0.45f;    // peak alpha at the screen edge
    private static final float EDGE_FRACTION = 0.18f;
    private static final float FADE_SPEED = 10.0f;         // snappy in/out for a 0.25s window

    private final EdgeVignette vignette =
            new EdgeVignette(OVERLAY_RGB, OVERLAY_OPACITY, EDGE_FRACTION, FADE_SPEED, FADE_SPEED);

    /**
     * Updates the vignette opacity based on the player's dodge invincibility state.
     * @param player    the player to check (may be null)
     * @param deltaTime seconds since last frame
     */
    public void update(Player player, float deltaTime) {
        if (player == null) return;
        vignette.update(player.getDodge().isInvincible(), deltaTime);
    }

    /** Renders the edge vignette if currently visible. Call BEFORE UI rendering. */
    public void render(int windowWidth, int windowHeight) {
        vignette.render(windowWidth, windowHeight);
    }

    public float getCurrentOpacity() { return vignette.getCurrentOpacity(); }

    public boolean isVisible() { return vignette.isVisible(); }
}
