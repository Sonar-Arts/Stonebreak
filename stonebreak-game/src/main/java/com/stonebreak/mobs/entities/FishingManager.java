package com.stonebreak.mobs.entities;

import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Resolves a fishing catch. When the player reels in while a fish is biting,
 * this rolls the loot table and spawns the caught item as an {@link ItemDrop}
 * that arcs back toward the player.
 *
 * <p>Kept separate from {@link com.stonebreak.input.InputHandler} so the input
 * layer only decides <em>when</em> to reel in, while loot policy and drop
 * spawning live here.
 */
public final class FishingManager {

    /** Chance the catch is a fish ({@link ItemType#BASS}) rather than junk. */
    private static final float FISH_CATCH_CHANCE = 0.75f;

    /** Horizontal speed the caught drop is flung toward the player. */
    private static final float CATCH_PULL_SPEED = 6.0f;

    /** Upward component added to the catch arc. */
    private static final float CATCH_ARC_UP = 4.0f;

    /** Below this squared distance the bobber/player are treated as coincident. */
    private static final float COINCIDENT_EPSILON_SQ = 1.0e-6f;

    private final Random rng = new Random();

    /**
     * Attempts to resolve a catch for the given bobber. Spawns the caught item
     * drop only when a fish is currently biting; otherwise does nothing (a plain
     * reel-in with no catch).
     *
     * @return {@code true} if an item was caught and spawned.
     */
    public boolean tryCatch(Player player, FishingBobber bobber) {
        if (player == null || bobber == null || !bobber.isFishBiting()) {
            return false;
        }

        World world = Game.getWorld();
        EntityManager entityManager = Game.getEntityManager();
        if (world == null || entityManager == null) {
            return false;
        }

        ItemType caught = rollLoot();
        Vector3f bobberPos = new Vector3f(bobber.getPosition());
        Vector3f catchVelocity = computeCatchVelocity(bobberPos, player.getPosition());

        ItemDrop drop = ItemDrop.createDropWithVelocity(
                world, bobberPos, new ItemStack(caught, 1), catchVelocity);
        entityManager.addEntity(drop);
        return true;
    }

    private ItemType rollLoot() {
        return rng.nextFloat() < FISH_CATCH_CHANCE ? ItemType.BASS : ItemType.STICK;
    }

    /**
     * Velocity that pulls the drop from the bobber back toward the player with an
     * upward arc. Falls back to a straight-up toss when the two positions are
     * effectively coincident, avoiding a NaN from normalizing a zero vector.
     */
    private Vector3f computeCatchVelocity(Vector3f bobberPos, Vector3f playerPos) {
        Vector3f toPlayer = new Vector3f(playerPos).sub(bobberPos);
        if (toPlayer.lengthSquared() < COINCIDENT_EPSILON_SQ) {
            return new Vector3f(0, CATCH_ARC_UP, 0);
        }
        return toPlayer.normalize().mul(CATCH_PULL_SPEED).add(0, CATCH_ARC_UP, 0);
    }
}
