package com.stonebreak.ui.worldHud;

import com.stonebreak.player.Player;
import com.stonebreak.player.combat.stealth.StealthController;

/**
 * Screen-space HUD for the universal stealth system: a "STEALTH" indicator while entering (dim) or
 * active, and a depleting re-entry cooldown bar after stealth breaks, shown top-centre by the world HUD
 * document (not under the crosshair).
 */
public final class StealthHud {

    public static final int ACTIVE_COLOR    = 0xDFAEE3F0; // soft cyan-white
    public static final int ENTERING_COLOR  = 0x88AEE3F0; // dimmer while entering

    /**
     * What the stealth HUD shows (#301): {@code label} ("STEALTH" or empty) in {@code labelColor}
     * (dim while entering, full once stealthed), and while {@code cooling} the re-entry bar at
     * {@code fraction} (1 → 0).
     */
    public record State(String label, int labelColor, boolean cooling, float fraction) {
        public static final State NONE = new State("", 0, false, 0f);

        public boolean shown() {
            return !label.isEmpty() || cooling;
        }
    }

    private StealthHud() {}

    /** The local player's stealth HUD state, or {@link State#NONE}. */
    public static State state(Player player) {
        if (player == null) return State.NONE;
        StealthController stealth = player.getStealth();
        boolean active = stealth.isStealthed();
        boolean entering = stealth.isEntering();
        boolean cooling = stealth.isReentryCoolingDown();
        if (!active && !entering && !cooling) return State.NONE;
        String label = active || entering ? "STEALTH" : "";
        int color = active ? ACTIVE_COLOR : entering ? ENTERING_COLOR : 0;
        return new State(label, color, cooling, cooling ? stealth.getReentryRemainingFraction() : 0f);
    }
}
