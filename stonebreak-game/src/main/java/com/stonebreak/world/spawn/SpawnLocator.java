package com.stonebreak.world.spawn;

import java.util.Random;

import org.joml.Vector3f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Picks a deterministic-but-randomized safe surface spawn within a configurable
 * radius of world origin. Uses {@code World.terrain().getFinalTerrainHeightAt} so the
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

            int height = world.terrain().getFinalTerrainHeightAt(x, z);
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

    /**
     * Resolves the actual standing Y for the column at {@code (x,z)} once its chunk is
     * resident, scanning down from {@code startY} for the topmost standable position — a solid
     * ground block (not AIR/WATER) with AIR above. This reads the real (carved) blocks, unlike
     * {@code getFinalTerrainHeightAt}, which samples pre-carve noise and so reports rim height
     * for a column cut open by a ravine or sinkhole. The predicate mirrors
     * {@code EntitySpawner.isStandableColumn}. Returns {@code -1} if no standable position is
     * found down to the world floor.
     */
    public static int resolveStandingY(World world, int x, int z, int startY) {
        for (int y = startY; y >= 1; y--) {
            if (isStandableColumn(world.getBlockAt(x, y - 1, z),
                                  world.getBlockAt(x, y, z),
                                  world.getBlockAt(x, y + 1, z))) {
                return y;
            }
        }
        return -1;
    }

    private static boolean isStandableColumn(BlockType ground, BlockType head, BlockType above) {
        if (ground == null || ground == BlockType.AIR || ground == BlockType.WATER) return false;
        return (head == null || head == BlockType.AIR) && (above == null || above == BlockType.AIR);
    }

    /**
     * A candidate column is rejected as a spawn if its real (carved) surface sits this many
     * blocks below the pre-carve rim — i.e. it has been cut out by a ravine or sinkhole. Ravine
     * depth is 25-60 and sinkholes reach ~90, while an un-carved column's surface matches the
     * pre-carve height within a block or two, so this threshold cleanly separates the two.
     */
    private static final int PIT_DEPTH_TOLERANCE = 4;

    /**
     * Validates a candidate surface spawn against the real, carved blocks of its column once
     * that chunk is resident. {@code candidate.y} is expected to be {@code noiseHeight + 1} (the
     * pre-carve rim), as produced by {@link #findSafeSurfaceSpawn()}. Returns the snapped,
     * standable spawn (on the real top solid block) if the column is a usable surface, or
     * {@code null} if it should be rejected — either because it was carved into a pit well below
     * the rim, or because the standing block sits below sea level (the column is flooded). This
     * is the reject-half of the issue #250 fix: a player should spawn on real land, not in a
     * ravine or sinkhole pit.
     */
    public static Vector3f acceptIfSafeSurface(World world, Vector3f candidate) {
        int x = (int) Math.floor(candidate.x);
        int z = (int) Math.floor(candidate.z);
        int noiseHeight = (int) Math.floor(candidate.y) - 1;
        int standY = resolveStandingY(world, x, z, WorldConfiguration.WORLD_HEIGHT - 1);
        if (standY < 1) {
            // Open air down to the floor / no standable column — nothing to stand on.
            return null;
        }
        if (noiseHeight - standY > PIT_DEPTH_TOLERANCE) {
            // Carved far below the rim: a ravine/sinkhole pit. Reject and re-pick elsewhere.
            return null;
        }
        if (standY - 1 < WorldConfiguration.SEA_LEVEL) {
            // The standing block is under water — a flooded/lakebed spawn. Reject.
            return null;
        }
        return new Vector3f(x + 0.5f, standY, z + 0.5f);
    }
}
