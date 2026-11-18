package com.stonebreak.world.spawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Picks a deterministic-but-randomized safe surface spawn within a configurable radius of world
 * origin.
 *
 * <p>Candidates stand on the generator's pre-carve rim ({@code terrain().getFinalTerrainHeightAt},
 * native, no loaded cubes needed); once the column's cubes are resident they are checked against
 * the real blocks, starting from the column's {@link World#topY} (first free y above the topmost
 * non-air block).
 *
 * <p>"Safe" means the column is dry — its water level (sea, river, or lake) does not stand above its
 * ground, so the player isn't placed on / under water. Checking against a global sea level alone
 * would place spawn underwater in a submerged inland column, or reject a perfectly dry column that
 * merely sits at the old global threshold with no water anywhere nearby.
 */
public final class SpawnLocator {

    /** {@link #resolveStandingY} found nothing to stand on (any y is a valid answer otherwise). */
    public static final long NO_STAND = Long.MIN_VALUE;

    private static final int DEFAULT_RADIUS = 1000;
    private static final int MAX_ATTEMPTS = 256;
    private static final long SEED_MIX = 0x5BAAAD5L;
    private static final Vector3dc FALLBACK = new Vector3d(0, WorldConfiguration.SEA_LEVEL + 36, 0);
    /**
     * How far below its starting point {@link #resolveStandingY} looks. A surface spawn sits on the
     * column's top; anything deeper than this is a pit or a cave, never a surface.
     */
    private static final int MAX_SCAN_DEPTH = 384;

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

    /**
     * A dry candidate column, standing on its surface (the first free y above the topmost
     * sky-opaque block). Its real blocks are checked by {@link #acceptIfSafeSurface} once loaded.
     */
    public Vector3d findSafeSurfaceSpawn() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long x = random.nextInt(2 * radius + 1) - radius;
            long z = random.nextInt(2 * radius + 1) - radius;

            long standY = rimStandY(world, x, z);
            if (!WorldConfiguration.inBounds(standY) || standY <= WorldConfiguration.MIN_Y) continue;
            long ground = standY - 1;
            if (world.getGeneratedWaterLevelAt(x, z) > ground) continue;

            System.out.println("[SPAWN] Selected safe surface spawn (" + x + ", " + standY + ", " + z
                + "), ground " + ground + ", attempt " + (attempt + 1));
            return new Vector3d(x, standY, z);
        }

        System.err.println("[SPAWN] No land surface found within " + MAX_ATTEMPTS
            + " attempts; falling back to " + FALLBACK);
        return new Vector3d(FALLBACK);
    }

    /**
     * Resolves the actual standing Y for the column at {@code (x,z)} once its cubes are resident,
     * scanning down from {@code startY} for the topmost standable position — a solid ground block
     * (not AIR/WATER) with AIR above. This reads the real (carved) blocks, unlike the generator's
     * pre-carve column height, which reports rim height for a column cut open by a ravine or
     * sinkhole. The scan stays inside the world ({@link WorldConfiguration#MIN_Y}) and at most
     * {@link #MAX_SCAN_DEPTH} below {@code startY}. Returns {@link #NO_STAND} when nothing is
     * standable.
     */
    public static long resolveStandingY(World world, long x, long z, long startY) {
        long top = Math.min(startY, WorldConfiguration.MAX_Y - 2L);
        long floor = Math.max(WorldConfiguration.MIN_Y + 1L, startY - MAX_SCAN_DEPTH);
        for (long y = top; y >= floor; y--) {
            if (isStandableColumn(world.getBlockAt(x, y - 1, z),
                                  world.getBlockAt(x, y, z),
                                  world.getBlockAt(x, y + 1, z))) {
                return y;
            }
        }
        return NO_STAND;
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
     * Validates a candidate surface spawn against the real, carved blocks of its column once its
     * cubes are resident. {@code candidate.y} is expected to be the candidate's stand height (the
     * generator's surface, as produced by {@link #findSafeSurfaceSpawn()}). Returns the snapped,
     * standable spawn (on the real top solid block) if the column is a usable surface, or
     * {@code null} if it should be rejected — either because it was carved into a pit well below
     * the rim, or because the standing block sits below its column's water level (flooded). This
     * is the reject-half of the issue #250 fix: a player should spawn on real land, not in a
     * ravine or sinkhole pit.
     */
    public static Vector3d acceptIfSafeSurface(World world, Vector3dc candidate) {
        long x = (long) Math.floor(candidate.x());
        long z = (long) Math.floor(candidate.z());
        long expectedStand = (long) Math.floor(candidate.y());
        // Start at the column's TOP (first free cell above the topmost non-air block): no full-height scan.
        long standY = resolveStandingY(world, x, z, world.topY(x, z));
        if (standY == NO_STAND) {
            // Open air down to the floor / no standable column — nothing to stand on.
            return null;
        }
        if (expectedStand - standY > PIT_DEPTH_TOLERANCE) {
            // Carved far below the rim: a ravine/sinkhole pit. Reject and re-pick elsewhere.
            return null;
        }
        if (world.getGeneratedWaterLevelAt(x, z) > standY - 1) {
            // The standing block is under water — a flooded/lakebed spawn. Reject. Per-column
            // water (sea, river, lake) rather than global sea level, as findSafeSurfaceSpawn does.
            return null;
        }
        return new Vector3d(x + 0.5, standY, z + 0.5);
    }

    /**
     * Finds the column nearest to {@code (x,z)} — by Euclidean distance, the column itself
     * first — that {@link #acceptIfSafeSurface} okays, and returns its snapped spawn. This is
     * how a user-chosen spawn that lands in a ravine, sinkhole or cave mouth is redirected onto
     * the surrounding surface instead of the pit floor. Only resident cubes are consulted
     * (unloaded cells read as AIR and are rejected), so {@code radius} should stay inside the
     * pre-generated spawn area. Returns {@code null} if nothing within {@code radius} qualifies.
     */
    public static Vector3d nearestSafeSurface(World world, long x, long z, int radius) {
        for (int[] offset : offsetsByDistance(radius)) {
            long cx = x + offset[0];
            long cz = z + offset[1];
            Vector3d accepted = acceptIfSafeSurface(world, new Vector3d(cx, rimStandY(world, cx, cz), cz));
            if (accepted != null) {
                return accepted;
            }
        }
        return null;
    }

    /**
     * The stand height on the generator's pre-carve rim of column {@code (x,z)} — what the pit
     * check compares the real surface against. Beyond the generator's int range (far past where
     * its float noise is valid anyway) the world's surface tracker answers.
     */
    private static long rimStandY(World world, long x, long z) {
        if (x != (int) x || z != (int) z) {
            return world.surfaceY(x, z);
        }
        return world.terrain().getFinalTerrainHeightAt((int) x, (int) z) + 1L;
    }

    /** Every {@code (dx,dz)} within {@code radius}, nearest first (ties in scan order). */
    private static List<int[]> offsetsByDistance(int radius) {
        List<int[]> offsets = new ArrayList<>();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return offsets;
    }
}
