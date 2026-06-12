package com.stonebreak.player.combat.arcanist;

import static com.stonebreak.player.PlayerConstants.ARCANIST_RESONANCE_MAX_STACKS;

import com.stonebreak.player.combat.magic.AbilitySpell;
import com.stonebreak.player.combat.magic.MagicSchool;
import com.stonebreak.player.combat.magic.SpellCast;

/**
 * Live one-line summaries of the Arcanist's Resonance and spell state for HUD display
 * alongside the Resonance indicator. The static {@code ClassAbility} description
 * (Character Sheet) covers the full mechanics; this is the dynamic counterpart.
 */
public final class ArcanistHudText {

    private ArcanistHudText() {}

    public static String resonanceStatus(ResonanceTracker resonance) {
        if (resonance.isOverloaded()) {
            return "Resonance: OVERLOADED";
        }
        return "Resonance: " + resonance.getResonanceStacks() + "/" + ARCANIST_RESONANCE_MAX_STACKS;
    }

    /**
     * Describes the active same-school discount/penalty, or null when the next cast of
     * any school is unmodified (no repeats yet, or Overloaded).
     */
    public static String sameSchoolStatus(ResonanceTracker resonance) {
        MagicSchool school = resonance.getLastSchoolCast();
        if (school == null || resonance.isOverloaded() || resonance.getSameSchoolCastCount() == 0) {
            return null;
        }
        SpellCast repeat = resonance.preview(school);
        int costOff = Math.round((1f - repeat.manaCostMult()) * 100f);
        int damageOff = Math.round((1f - repeat.damageMult()) * 100f);
        return school.getDisplayName() + " echo: -" + costOff + "% cost, -" + damageOff + "% damage";
    }

    public static String spellStatus(String name, AbilitySpell spell) {
        if (spell.isOnCooldown()) {
            return String.format("%s: cooldown %.1fs", name, spell.getCooldownRemaining());
        }
        return name + ": ready";
    }
}
