package com.stonebreak.ui;

import com.stonebreak.core.Game;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.player.Player;

/**
 * What the death menu's Respawn does, in one place: the legacy click handler
 * ({@code UiMouseRouter}) and the UI host's {@code stonebreak:screen.death.respawn} action (#299)
 * both call it, so the migrated death screen runs exactly the rule the old one did.
 */
public final class DeathMenuActions {

    private DeathMenuActions() {
    }

    /** Client-local respawn (no packet), hide the menu, recapture the cursor. */
    public static void respawn(Game game) {
        Player player = Game.getPlayer();
        if (player != null) {
            player.respawn();
        }
        DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null) {
            deathMenu.setVisible(false);
        }
        // Recapture the mouse now that the death menu is hidden.
        MouseCaptureManager capture = game.getMouseCaptureManager();
        if (capture != null) {
            capture.updateCaptureState();
        }
    }
}
