package com.stonebreak.player.combat;

import com.stonebreak.audio.EntitySounds;
import com.stonebreak.mobs.entities.HitKind;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.ui.worldHud.DamageNumbers;
import org.joml.Vector3f;

/**
 * Client side of a hit the SERVER applied for the local player ({@code KillCreditS2C}, #371/#374).
 * The attacker's feedback and on-hit effects run from what actually landed, not from the
 * client's prediction: a hit the server refused (range, cooldown, the real mob's i-frames)
 * sends no result, so it shows no number, plays no sound and pays out nothing.
 *
 * <ul>
 *   <li>damage number (the applied amount, multipliers included) and hurt/death voice at the copy;</li>
 *   <li>melee: Rage, Quarry and T3 lifesteal via {@link Player#onMeleeHitLanded};</li>
 *   <li>arrows: the Ranger's Quarry/Study — arrows simulate on the server, so this result is
 *       the only place the shooter learns what they hit.</li>
 * </ul>
 */
public final class HitResultFeedback {

    private HitResultFeedback() {}

    /**
     * @param target the hit entity's copy on this client, or null when it is gone already
     * @param kind   null for an unknown wire value (feedback only)
     */
    public static void apply(Player player, LivingEntity target, LivingEntity.DamageSource source,
                             HitKind kind, float dealt, boolean killed) {
        if (target != null) {
            Vector3f p = target.getPosition();
            DamageNumbers.getInstance().spawn(p.x, p.y + target.getHeight() * 0.9f, p.z, dealt);
            // Data-driven voice: plays only when the entity's SBE declares the event.
            EntitySounds.playAt(target, killed ? EntitySounds.EVENT_DEATH : EntitySounds.EVENT_HURT);
        }
        if (player == null) {
            return;
        }
        if (kind == HitKind.MELEE) {
            player.onMeleeHitLanded(target, dealt);
        }
        if (source == LivingEntity.DamageSource.ARROW && target != null) {
            player.getRangerAbilities().onPlayerArrowHit(player, target);
        }
    }
}
