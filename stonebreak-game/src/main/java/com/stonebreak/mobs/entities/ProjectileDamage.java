package com.stonebreak.mobs.entities;

import com.stonebreak.core.Game;

/**
 * Damage application for projectile/ability entities, attribution-aware for the two-world
 * model. Projectiles now simulate on the AUTHORITATIVE server world, where the old
 * {@code le.damage(amount, source)} 2-arg path would silently credit the HOST's local
 * player for every hit (its default {@code creditLocalPlayer=true} resolves the Game
 * singleton). This helper:
 * <ul>
 *   <li>credits the local player directly only in local-fallback mode (projectile living
 *       in the rendered world — no session);</li>
 *   <li>on the server, routes the credit to the launching player via
 *       {@code KillCreditS2C} using the projectile's {@code ownerPlayerId} (set by
 *       {@code ServerEntityHandler.handleProjectileSpawn}).</li>
 * </ul>
 */
public final class ProjectileDamage {

    private ProjectileDamage() {}

    /** Deal {@code amount} from {@code projectile} to {@code target} with correct credit. */
    public static void deal(Entity projectile, LivingEntity target, float amount,
                            LivingEntity.DamageSource source) {
        boolean localFallback = Game.getWorld() == projectile.getWorld();
        float healthBefore = target.getHealth();
        target.damage(amount, source, null, localFallback);
        if (!localFallback && projectile.getOwnerPlayerId() >= 0) {
            float dealt = healthBefore - Math.max(0f, target.getHealth());
            if (dealt > 0f) {
                com.stonebreak.network.MultiplayerSession.sendKillCredit(
                    projectile.getOwnerPlayerId(), target, dealt, !target.isAlive());
            }
        }
    }
}
