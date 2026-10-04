package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

/**
 * Where the nearest player is, as far as a behaviour is concerned.
 *
 * <p>A seam rather than a direct {@code Game.getPlayer()} call, for two reasons: behaviours become
 * testable without booting the game, and the day mobs need to notice remote players as well as the
 * local one, only this changes.
 */
public interface PlayerLocator {

    /**
     * Writes the position of the player nearest {@code from} into {@code out}.
     *
     * @return {@code out} when a player was found, {@code null} when there is nobody to find
     */
    Vector3f nearestPlayer(Vector3f from, Vector3f out);

    /**
     * Whether the player nearest {@code from} is sprinting. Skittish mobs notice a running player
     * from further away than a walking one.
     */
    default boolean nearestPlayerSprinting(Vector3f from) {
        return false;
    }

    /**
     * Whether nothing solid stands between {@code eye} and the body of the player nearest
     * {@code from}. Open by default, so stubs and tests need not model terrain.
     */
    default boolean canSeeNearestPlayer(Vector3f from, Vector3f eye) {
        return true;
    }

    /**
     * Hurts the player nearest {@code from} and shoves them horizontally away from it. The seam a
     * hostile behaviour lands its blow through, so attack timing stays testable without a player.
     *
     * @param knockback horizontal push speed in blocks per second; 0 for none
     * @return whether a player was there to take the hit
     */
    default boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
        return false;
    }

    /**
     * The live local player.
     *
     * <p>Only the local player today, matching what the old AI saw. On a server world the
     * authoritative mobs still resolve through here, so extending this to the connected roster is
     * the single change needed to make mobs react to remote players.
     */
    PlayerLocator LOCAL = new PlayerLocator() {
        @Override
        public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
            Player player = Game.getPlayer();
            if (player == null || player.isDead()) {
                return null;
            }
            return out.set(player.getPosition());
        }

        @Override
        public boolean nearestPlayerSprinting(Vector3f from) {
            Player player = Game.getPlayer();
            return player != null && !player.isDead() && player.isSprinting();
        }

        @Override
        public boolean canSeeNearestPlayer(Vector3f from, Vector3f eye) {
            Player player = Game.getPlayer();
            if (player == null || player.isDead()) {
                return false;
            }
            Vector3f chest = new Vector3f(player.getPosition()).add(0, CHEST_HEIGHT, 0);
            Vector3f direction = chest.sub(eye);
            float distance = direction.length();
            if (distance < 1.0e-3f) {
                return true;
            }
            direction.div(distance);
            return player.getRaycastEngine().distanceToFirstSolid(eye, direction, distance) >= distance;
        }

        @Override
        public boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
            Player player = Game.getPlayer();
            if (player == null || player.isDead()) {
                return false;
            }
            player.damage(amount);
            Vector3f away = new Vector3f(player.getPosition()).sub(from);
            away.y = 0;
            if (knockback > 0f && away.lengthSquared() > 1.0e-4f) {
                away.normalize(knockback);
                Vector3f velocity = player.getVelocity();
                // A small hop so the shove carries instead of dying in ground friction.
                player.setVelocity(new Vector3f(velocity.x + away.x,
                        Math.max(velocity.y, KNOCKBACK_LIFT * knockback), velocity.z + away.z));
            }
            return true;
        }
    };

    /** Height above a player's feet that ranged attackers aim at and sight on. */
    float CHEST_HEIGHT = 1.1f;

    /** Upward speed of a knockback hop, as a fraction of its horizontal push. */
    float KNOCKBACK_LIFT = 0.4f;

    /** Nobody to react to; for tests and for worlds with no players. */
    PlayerLocator NONE = (from, out) -> null;
}
