package com.stonebreak.player.combat.stealth;

import com.stonebreak.player.PlayerConstants;

/**
 * Per-class tunables for the universal stealth system. The {@link StealthController} reads its
 * values exclusively through a resolved {@code StealthConfig}, so a class (e.g. the Rogue) can
 * override defaults without modifying the controller. Resolve via {@link #forClass(String)}.
 *
 * @param noiseRadiusStealth player noise radius (blocks) while fully stealthed
 * @param reentryDelay       seconds the re-entry cooldown lasts after stealth breaks
 * @param movementMult       movement-speed multiplier applied while stealthed
 * @param flatFootedCritBonus additional crit chance an attacker gains vs. a FLAT_FOOTED target
 * @param flatFootedDuration  seconds FLAT_FOOTED lasts when applied to an unaware target
 */
public record StealthConfig(
        float noiseRadiusStealth,
        float reentryDelay,
        float movementMult,
        float flatFootedCritBonus,
        float flatFootedDuration) {

    /** Default config used by every class that does not override stealth. */
    public static final StealthConfig BASE = new StealthConfig(
            PlayerConstants.NOISE_RADIUS_STEALTH,
            PlayerConstants.STEALTH_REENTRY_DELAY,
            PlayerConstants.STEALTH_MOVEMENT_MULT,
            PlayerConstants.FLAT_FOOTED_CRIT_BONUS,
            PlayerConstants.FLAT_FOOTED_DURATION);

    /**
     * Rogue overrides: quieter while stealthed, faster sneak, a shorter re-entry window, and a
     * guaranteed flat-footed crit ({@code flatFootedCritBonus = 1.0}). Keyed by the Rogue class id.
     */
    public static final StealthConfig ROGUE = new StealthConfig(
            1.0f,   // quieter than the 2-block base
            3.0f,   // re-enters stealth sooner than the 4s base
            0.75f,  // moves faster while stealthed than the 0.6 base
            1.0f,   // guaranteed crit on a flat-footed target
            PlayerConstants.FLAT_FOOTED_DURATION);

    private static final String ROGUE_CLASS_ID = "rogue";

    /** Resolves the stealth config for a selected class id ({@link #BASE} when unknown/null). */
    public static StealthConfig forClass(String selectedClassId) {
        if (ROGUE_CLASS_ID.equals(selectedClassId)) {
            return ROGUE;
        }
        return BASE;
    }
}
