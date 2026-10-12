package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;

/**
 * Full-screen world effects drawn over the world and under the UI: the underwater tint, the dodge
 * invincibility flash and screen droplets.
 */
public class OverlayRenderer {

    private final UnderwaterOverlayRenderer underwaterOverlayRenderer;
    private final DodgeInvincibilityOverlay dodgeInvincibilityOverlay;
    private final ScreenDropletOverlay screenDropletOverlay;

    public OverlayRenderer() {
        this.underwaterOverlayRenderer = new UnderwaterOverlayRenderer();
        this.dodgeInvincibilityOverlay = new DodgeInvincibilityOverlay();
        this.screenDropletOverlay = new ScreenDropletOverlay();
    }

    /**
     * Renders the underwater overlay effect if the player is underwater.
     * This is a public method that should be called BEFORE UI rendering, not after.
     * It updates and renders the underwater tint effect behind all UI elements.
     */
    public void renderUnderwaterOverlay(Game game, int windowWidth, int windowHeight) {
        Player player = game.getPlayer();
        if (player != null) {
            underwaterOverlayRenderer.update(player, game.getDeltaTime());
            underwaterOverlayRenderer.render(windowWidth, windowHeight);
            dodgeInvincibilityOverlay.update(player, game.getDeltaTime());
            dodgeInvincibilityOverlay.render(windowWidth, windowHeight);
            screenDropletOverlay.update(player, game.getDeltaTime());
            screenDropletOverlay.render(windowWidth, windowHeight);
        }
    }

    /**
     * Get the underwater overlay renderer for access to underwater effects.
     */
    public UnderwaterOverlayRenderer getUnderwaterOverlayRenderer() {
        return underwaterOverlayRenderer;
    }

    /**
     * Cleanup method to release any resources.
     */
    public void cleanup() {
        // Currently no resources to cleanup, but method exists for consistency
    }
}
