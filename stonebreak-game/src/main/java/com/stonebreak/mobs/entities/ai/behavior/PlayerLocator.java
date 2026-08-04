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
    };

    /** Nobody to react to; for tests and for worlds with no players. */
    PlayerLocator NONE = (from, out) -> null;
}
