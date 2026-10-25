package com.stonebreak.player.combat.stealth;

import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.StealthSkill;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.PlayerConstants;

/**
 * Per-class tunables for the universal stealth system. The {@link StealthController} reads its
 * values exclusively through a resolved {@code StealthConfig}, so a class (e.g. the Rogue) can
 * override defaults without modifying the controller. Resolve via {@link #forClass(CharacterStats)}.
 *
 * @param stealthLoudness    movement loudness while fully stealthed ({@link Loudness} scale)
 * @param reentryDelay       seconds the re-entry cooldown lasts after stealth breaks
 * @param movementMult       movement-speed multiplier applied while stealthed
 * @param flatFootedCritBonus additional crit chance an attacker gains vs. a FLAT_FOOTED target
 * @param flatFootedDuration  seconds FLAT_FOOTED lasts when applied to an unaware target
 * @param skillPerClassRank   Stealth skill points each rank in the class grants, on top of the
 *                            points any class can invest in the skill
 */
public record StealthConfig(
        float stealthLoudness,
        float reentryDelay,
        float movementMult,
        float flatFootedCritBonus,
        float flatFootedDuration,
        float skillPerClassRank) {

    /** Default config used by every class that does not override stealth. */
    public static final StealthConfig BASE = new StealthConfig(
            Loudness.STEALTH,
            PlayerConstants.STEALTH_REENTRY_DELAY,
            PlayerConstants.STEALTH_MOVEMENT_MULT,
            PlayerConstants.FLAT_FOOTED_CRIT_BONUS,
            PlayerConstants.FLAT_FOOTED_DURATION,
            0f);

    /**
     * Rogue overrides: quieter while stealthed, faster sneak, a shorter re-entry window, and a
     * guaranteed flat-footed crit ({@code flatFootedCritBonus = 1.0}). Keyed by the Rogue class id.
     */
    public static final StealthConfig ROGUE = new StealthConfig(
            0.2f,   // near-silent while stealthed (vs. the base 1)
            1.5f,   // re-enters stealth far sooner than the 4s base
            0.9f,   // near-normal movement speed while stealthed (vs. the 0.6 base)
            1.0f,   // guaranteed crit on a flat-footed target
            PlayerConstants.FLAT_FOOTED_DURATION,
            0.5f);  // a Stealth point for every two Rogue ranks

    private static final String ROGUE_CLASS_ID = "rogue";

    /** Resolves the stealth config for a character: {@link #ROGUE} once any rank is in Rogue. */
    public static StealthConfig forClass(CharacterStats stats) {
        if (stats != null && stats.hasClass(ROGUE_CLASS_ID)) {
            return ROGUE;
        }
        return BASE;
    }

    /**
     * The character's Stealth skill (#321): the points invested in it, which any class can do, plus
     * what their stealth class grants per rank — for now only the Rogue grants any.
     */
    public static float skillPoints(CharacterStats stats) {
        if (stats == null) {
            return 0f;
        }
        return stats.getSkillLevel(StealthSkill.SKILL_ID)
                + forClass(stats).skillPerClassRank() * stats.getClassRanks(ROGUE_CLASS_ID);
    }
}
