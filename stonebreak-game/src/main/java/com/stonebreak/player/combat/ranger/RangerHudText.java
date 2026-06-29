package com.stonebreak.player.combat.ranger;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.combat.QuarryController;

/**
 * Live one-line summaries of the Ranger's Quarry and ability state for HUD display
 * alongside the Quarry indicator. The static {@code ClassAbility} description (Character
 * Sheet) covers the full mechanics; this is the dynamic counterpart that tells the player
 * exactly what state their hunt is in right now.
 */
public final class RangerHudText {

    private RangerHudText() {}

    public static String quarryStatus(QuarryController quarry) {
        LivingEntity target = quarry.getQuarry();
        if (target == null) {
            return "Quarry: none";
        }
        String name = target.getType().getDisplayName();
        if (quarry.isMarkedPrey()) {
            return "Marked Prey: " + name;
        }
        return "Quarry: " + name + " (" + quarry.getStudyStacks() + "/3)";
    }

    public static String snareStatus(SnareAbility snare) {
        if (snare.hasActiveTrap() && !snare.isTrapArmed()) {
            return "Snare: trap arming...";
        }
        if (snare.isTrapArmed()) {
            return snare.isOnCooldown()
                ? String.format("Snare: trap armed (cd %.1fs)", snare.getCooldownRemaining())
                : "Snare: trap armed";
        }
        if (snare.isOnCooldown()) {
            return String.format("Snare: cooldown %.1fs", snare.getCooldownRemaining());
        }
        return "Snare: ready";
    }

    public static String cullingShotStatus(CullingShotAbility cullingShot) {
        if (cullingShot.isChanneling()) {
            return "Culling Shot: channeling...";
        }
        if (cullingShot.isDashing()) {
            return "Culling Shot: closing in!";
        }
        if (cullingShot.isOnCooldown()) {
            return String.format("Culling Shot: cooldown %.1fs", cullingShot.getCooldownRemaining());
        }
        return "Culling Shot: ready";
    }

    public static String revealLine(EntityType type) {
        return "Armor: " + type.getArmorType() + "  |  Resists: " + type.getResistances();
    }
}
