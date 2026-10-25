package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Where the nearest player is, as far as a behaviour is concerned.
 *
 * <p>A seam rather than a direct {@code Game.getPlayer()} call, for two reasons: behaviours become
 * testable without booting the game, and mob AI runs on the server tick thread, where the client's
 * player is both the wrong answer (it is only the host) and another thread's state. The
 * authoritative world is handed a locator over the connected roster by the integrated server
 * ({@code network.server.ServerPlayerLocator}); mobs reach it through {@link #ofWorld}.
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
     * How far the nearest player's movement can be heard, in blocks, for stealth awareness.
     *
     * <p>The default knows only what the server is told — whether the player sprints. Stealth
     * state, walking noise and dodge spikes live on the player's own client and are not
     * replicated, so a walking player is heard at zero range and found by sight alone.
     */
    default float nearestPlayerNoiseRadius(Vector3f from) {
        return nearestPlayerSprinting(from) ? PlayerConstants.NOISE_RADIUS_RUN : 0.0f;
    }

    /** Height above a player's feet that ranged attackers aim at and sight on. */
    float CHEST_HEIGHT = 1.1f;

    /** Nobody to react to; for tests and for worlds with no players. */
    PlayerLocator NONE = (from, out) -> null;

    /**
     * Whoever the entity's own world says is around, resolved on every call: the connected roster
     * on the authoritative server world, nobody anywhere else. Resolved lazily because a mob is
     * built before its world is wired to a server, and because the world is the one thing that
     * knows which players share it.
     */
    static PlayerLocator ofWorld(Entity entity) {
        return new PlayerLocator() {
            @Override
            public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
                return players().nearestPlayer(from, out);
            }

            @Override
            public boolean nearestPlayerSprinting(Vector3f from) {
                return players().nearestPlayerSprinting(from);
            }

            @Override
            public float nearestPlayerNoiseRadius(Vector3f from) {
                return players().nearestPlayerNoiseRadius(from);
            }

            private PlayerLocator players() {
                World world = entity.getWorld();
                PlayerLocator players = world != null ? world.getPlayerLocator() : null;
                return players != null ? players : NONE;
            }
        };
    }
}
