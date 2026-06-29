package com.stonebreak.player.combat.arcanist;

import static com.stonebreak.player.PlayerConstants.ARCANIST_RESONANCE_MAX_STACKS;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_COST_REDUCTION_PER_CAST;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_DAMAGE_PENALTY_PER_CAST;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_MAX_COST_REDUCTION;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_MIN_DAMAGE_MULTIPLIER;

import com.stonebreak.player.combat.magic.MagicSchool;
import com.stonebreak.player.combat.magic.SpellCast;

/**
 * The Arcanist's Resonance passive. Casting spells of different schools builds Resonance
 * stacks (0-4); at 4 the Arcanist is Overloaded and the next cast executes its empowered
 * variant, consuming all stacks. Repeating the same school builds no Resonance — instead
 * each consecutive repeat is cheaper but weaker (capped/floored).
 *
 * <p>Two-phase API: {@link #preview} is pure and computes the modifiers a cast would get;
 * {@link #commitCast} mutates state and is only called once the cast actually succeeded
 * (mana paid, target valid) — so a blocked cast never advances Resonance.</p>
 *
 * <p>Like Rage and Quarry, Resonance is a combat-only resource: never persisted, reset on
 * world reload.</p>
 */
public class ResonanceTracker {

    private MagicSchool lastSchoolCast;
    private int resonanceStacks;
    private boolean isOverloaded;
    private int sameSchoolCastCount;

    /** Computes the modifiers a cast of the given school would receive. Pure. */
    public SpellCast preview(MagicSchool school) {
        if (isOverloaded) {
            // The Overloaded cast pays base cost at full damage — its payoff is the
            // empowered variant, not a discount.
            return new SpellCast(1f, 1f, true, resonanceStacks);
        }
        if (school == lastSchoolCast) {
            int n = sameSchoolCastCount + 1;
            float costMult = 1f - Math.min(ARCANIST_SAME_SCHOOL_MAX_COST_REDUCTION,
                ARCANIST_SAME_SCHOOL_COST_REDUCTION_PER_CAST * n);
            float damageMult = Math.max(ARCANIST_SAME_SCHOOL_MIN_DAMAGE_MULTIPLIER,
                1f - ARCANIST_SAME_SCHOOL_DAMAGE_PENALTY_PER_CAST * n);
            return new SpellCast(costMult, damageMult, false, resonanceStacks);
        }
        return new SpellCast(1f, 1f, false, resonanceStacks);
    }

    /** Records a successfully executed cast of the given school. */
    public void commitCast(MagicSchool school) {
        if (isOverloaded) {
            // The Overloaded cast just resolved — discharge everything.
            resonanceStacks = 0;
            isOverloaded = false;
            sameSchoolCastCount = 0;
        } else if (school == lastSchoolCast) {
            sameSchoolCastCount++;
        } else {
            sameSchoolCastCount = 0;
            if (resonanceStacks < ARCANIST_RESONANCE_MAX_STACKS) {
                resonanceStacks++;
            }
            if (resonanceStacks == ARCANIST_RESONANCE_MAX_STACKS) {
                isOverloaded = true;
            }
        }
        lastSchoolCast = school;
    }

    /** Clears all Resonance state (world reload). */
    public void reset() {
        lastSchoolCast = null;
        resonanceStacks = 0;
        isOverloaded = false;
        sameSchoolCastCount = 0;
    }

    public MagicSchool getLastSchoolCast() { return lastSchoolCast; }

    public int getResonanceStacks() { return resonanceStacks; }

    public boolean isOverloaded() { return isOverloaded; }

    public int getSameSchoolCastCount() { return sameSchoolCastCount; }
}
