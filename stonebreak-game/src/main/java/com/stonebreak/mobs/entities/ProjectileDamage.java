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
        float dealt = healthBefore - Math.max(0f, target.getHealth());
        if (localFallback) {
            // No server to send a KillCreditS2C (the battle-test arena): the local player shot
            // it, so the arrow's Quarry/Study hook runs here, on the main thread that ticks
            // this world. The number and voice already came from the local damage above.
            com.stonebreak.player.Player player = Game.getPlayer();
            if (player != null && dealt > 0f && source == LivingEntity.DamageSource.ARROW) {
                player.getRangerAbilities().onPlayerArrowHit(player, target);
            }
        } else if (projectile.getOwnerPlayerId() >= 0) {
            if (dealt > 0f) {
                com.stonebreak.network.MultiplayerSession.sendKillCredit(
                    projectile.getOwnerPlayerId(), target, dealt, !target.isAlive(), source);
            }
        }
    }
}
