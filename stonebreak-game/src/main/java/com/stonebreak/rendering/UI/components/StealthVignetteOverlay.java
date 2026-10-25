package com.stonebreak.rendering.UI.components;

import com.stonebreak.player.Player;

/**
 * Darkens the screen borders while the player is fully stealthed (#321), so being hidden reads at
 * a glance rather than only through the "STEALTH" label. Nothing shows during the entry delay; the
 * border fades in once stealth takes hold and drops away quickly when it breaks or is toggled off.
 */
public class StealthVignetteOverlay {

    private static final int OVERLAY_RGB = 0x000000;
    private static final float OVERLAY_OPACITY = 0.85f;  // peak alpha at the screen edge: unmistakable, centre still clear
    private static final float EDGE_FRACTION = 0.3f;
    private static final float FADE_IN_SPEED = OVERLAY_OPACITY / 0.3f;   // ~0.3 s in
    private static final float FADE_OUT_SPEED = OVERLAY_OPACITY / 0.15f; // ~0.15 s out

    private final EdgeVignette vignette =
            new EdgeVignette(OVERLAY_RGB, OVERLAY_OPACITY, EDGE_FRACTION, FADE_IN_SPEED, FADE_OUT_SPEED);

    /**
     * @param player    the local player (may be null)
     * @param deltaTime seconds since last frame
     */
    public void update(Player player, float deltaTime) {
        vignette.update(player != null && player.getStealth().isStealthed(), deltaTime);
    }

    /** Renders the border if currently visible. Call BEFORE UI rendering. */
    public void render(int windowWidth, int windowHeight) {
        vignette.render(windowWidth, windowHeight);
    }

    public float getCurrentOpacity() { return vignette.getCurrentOpacity(); }
}
