package com.stonebreak.world.spawn;

import java.util.Random;

import org.joml.Vector3f;

import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Picks a deterministic-but-randomized safe surface spawn within a configurable
 * radius of world origin. Uses {@link World#getFinalTerrainHeightAt} so the
 * height is queried from the terrain noise functions without loading any
 * chunks — this avoids contention with the save IO executor and keeps the
 * loading screen responsive.
 *
 * "Safe" means terrain height is at or above sea level, so the player isn't
 * placed on / under water.
 */
public final class SpawnLocator {

    private static final int DEFAULT_RADIUS = 1000;
    private static final int MAX_ATTEMPTS = 256;
    private static final long SEED_MIX = 0x5BAAAD5L;
    private static final Vector3f FALLBACK = new Vector3f(0, 100, 0);

    private final World world;
    private final int radius;
    private final Random random;

    public SpawnLocator(World world) {
        this(world, DEFAULT_RADIUS);
    }

    public SpawnLocator(World world, int radius) {
        this.world = world;
        this.radius = radius;
        this.random = new Random(world.getSeed() ^ SEED_MIX);
    }

    public Vector3f findSafeSurfaceSpawn() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int x = random.nextInt(2 * radius + 1) - radius;
            int z = random.nextInt(2 * radius + 1) - radius;

            int height = world.getFinalTerrainHeightAt(x, z);
            if (height < WorldConfiguration.SEA_LEVEL) continue;

            int standY = height + 1;
            System.out.println("[SPAWN] Selected safe surface spawn (" + x + ", " + standY + ", " + z
                + "), terrain height " + height + ", attempt " + (attempt + 1));
            return new Vector3f(x, standY, z);
        }

        System.err.println("[SPAWN] No land surface found within " + MAX_ATTEMPTS
            + " attempts; falling back to " + FALLBACK);
        return new Vector3f(FALLBACK);
    }
}
