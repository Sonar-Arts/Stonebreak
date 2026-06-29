package com.stonebreak.player.combat.illusionist;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.combat.DoubtController;

import java.util.List;

/**
 * Live one-line summaries of the Illusionist's Doubt and ability state for HUD display alongside
 * the Doubt indicator. The static {@code ClassAbility} description (Character Sheet) covers the
 * full mechanics; this is the dynamic counterpart.
 */
public final class IllusionistHudText {

    private IllusionistHudText() {}

    /** Summarizes how many enemies currently carry Doubt and how many are Bewildered. */
    public static String doubtStatus(DoubtController doubt) {
        List<LivingEntity> doubted = doubt.getAllDoubted();
        if (doubted.isEmpty()) {
            return "Doubt: none";
        }
        int bewildered = 0;
        for (LivingEntity target : doubted) {
            if (doubt.isBewildered(target)) {
                bewildered++;
            }
        }
        if (bewildered > 0) {
            return "Doubt: " + doubted.size() + " marked (" + bewildered + " Bewildered)";
        }
        return "Doubt: " + doubted.size() + " marked";
    }

    public static String mirroredDeceitStatus(MirroredDeceitAbility ability) {
        if (ability.isActive()) {
            return "Mirrored Deceit: active";
        }
        if (ability.isOnCooldown()) {
            return String.format("Mirrored Deceit: cooldown %.1fs", ability.getCooldownRemaining());
        }
        return "Mirrored Deceit: ready";
    }

    public static String fractureStatus(FractureAbility ability) {
        if (ability.isOnCooldown()) {
            return String.format("Fracture: cooldown %.1fs", ability.getCooldownRemaining());
        }
        return "Fracture: ready";
    }
}
