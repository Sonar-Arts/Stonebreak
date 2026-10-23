package com.stonebreak.mobs.entities;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Resolves a fishing catch. When the angler reels in while a fish is biting, this rolls the
 * loot table and spawns the caught item as an {@link ItemDrop} that arcs back toward the
 * angler.
 *
 * <p>Server-side (#341): the server owns the bobber, so it decides whether a fish is biting,
 * rolls the loot and spawns the drop in the bobber's own (server) world, from where it
 * replicates to every client and is picked up through the normal server path. The client
 * only sends the rod-use request.
 */
public final class FishingManager {

    /** Chance the catch is a fish ({@link ItemType#BASS}) rather than junk. */
    private static final float FISH_CATCH_CHANCE = 0.75f;

    /** Horizontal speed the caught drop is flung toward the angler. */
    private static final float CATCH_PULL_SPEED = 6.0f;

    /** Upward component added to the catch arc. */
    private static final float CATCH_ARC_UP = 4.0f;

    /** Below this squared distance the bobber/angler are treated as coincident. */
    private static final float COINCIDENT_EPSILON_SQ = 1.0e-6f;

    private final Random rng;

    public FishingManager() {
        this(new Random());
    }

    /** Seeded loot rolls (tests). */
    public FishingManager(Random rng) {
        this.rng = rng;
    }

    /**
     * Resolves the reel-in of {@code bobber} by an angler standing at {@code anglerPos}. Spawns
     * the caught item into {@code entityManager} (the bobber's world) only when a fish is
     * currently biting; otherwise does nothing (a plain reel-in with no catch).
     *
     * @return the spawned catch, or null when nothing was caught.
     */
    public ItemDrop tryCatch(FishingBobber bobber, Vector3f anglerPos, EntityManager entityManager) {
        if (bobber == null || anglerPos == null || entityManager == null || !bobber.isFishBiting()) {
            return null;
        }

        ItemType caught = rollLoot();
        Vector3f bobberPos = new Vector3f(bobber.getPosition());
        Vector3f catchVelocity = computeCatchVelocity(bobberPos, anglerPos);

        ItemDrop drop = ItemDrop.createDropWithVelocity(
                entityManager.getWorld(), bobberPos, new ItemStack(caught, 1), catchVelocity);
        entityManager.addEntity(drop);
        return drop;
    }

    private ItemType rollLoot() {
        return rng.nextFloat() < FISH_CATCH_CHANCE ? ItemType.BASS : ItemType.STICK;
    }

    /**
     * Velocity that pulls the drop from the bobber back toward the angler with an upward
     * arc. Falls back to a straight-up toss when the two positions are effectively
     * coincident, avoiding a NaN from normalizing a zero vector.
     */
    private Vector3f computeCatchVelocity(Vector3f bobberPos, Vector3f anglerPos) {
        Vector3f toAngler = new Vector3f(anglerPos).sub(bobberPos);
        if (toAngler.lengthSquared() < COINCIDENT_EPSILON_SQ) {
            return new Vector3f(0, CATCH_ARC_UP, 0);
        }
        return toAngler.normalize().mul(CATCH_PULL_SPEED).add(0, CATCH_ARC_UP, 0);
    }
}
