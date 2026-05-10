package com.stonebreak.world.generation.trees;

import java.util.Random;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Regular (oak-style) tree shape generator.
 *
 * Selects one of four silhouettes deterministically per world position:
 * <ul>
 *   <li>{@link Variant#ROUND}        — classic 5-log trunk with a tight ball-shaped canopy</li>
 *   <li>{@link Variant#TALL}         — taller 6-7 log trunk with a smaller crown perched on top</li>
 *   <li>{@link Variant#COMPACT}      — short 3-4 log trunk with a dense, low canopy</li>
 *   <li>{@link Variant#BIG_BRANCHED} — tall, wide oak with arching reach-2 branches and a
 *                                      radius-3 canopy lobe</li>
 * </ul>
 *
 * Every variant places a few short cardinal "inner branches" inside the canopy near the top
 * of the trunk, so the trunk doesn't read as a bare pole inside an otherwise dense leaf ball.
 *
 * Lopsided lean: a small (-1,0,1) horizontal canopy offset is applied to TALL for organic
 * variation. BIG_BRANCHED stays centred so its symmetric branches read clearly.
 *
 * Stays within {@link #LEAF_RADIUS} so chunk-scheduling guarantees remain valid.
 */
public final class RegularTree {

    public static final int LEAF_RADIUS = 3;
    public static final int MAX_HEIGHT = 13;
    private static final long SEED_TAG = 0xA5A5A5A5C3C3C3C3L;

    private enum Variant { ROUND, TALL, COMPACT, BIG_BRANCHED }

    private RegularTree() {}

    public static void place(World world, int worldX, int worldY, int worldZ) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);
        Random rng = TreeRandom.forPosition(worldX, worldY, worldZ, SEED_TAG);

        Variant variant = Variant.values()[rng.nextInt(Variant.values().length)];
        Profile profile = profileFor(variant, rng);

        if (worldY + profile.canopyTopOffset() >= WorldConfiguration.WORLD_HEIGHT) return;

        placeTrunk(placer, worldX, worldY, worldZ, profile.trunkHeight);

        // Lean offset: small horizontal shift of canopy centre, sampled once per tree.
        int leanX = profile.canLean ? rng.nextInt(3) - 1 : 0;
        int leanZ = profile.canLean ? rng.nextInt(3) - 1 : 0;
        int canopyCx = worldX + leanX;
        int canopyCz = worldZ + leanZ;

        placeCanopy(placer, rng, variant, profile, worldX, worldY, worldZ, canopyCx, canopyCz);

        // Inner branches go AFTER the canopy so a few logs stay visible inside the leaves
        // instead of being silently overwritten by leaf placement.
        placeInnerBranches(placer, rng, variant, worldX, worldY, worldZ, profile.trunkHeight);

        placer.complete();
    }

    private static Profile profileFor(Variant variant, Random rng) {
        return switch (variant) {
            // trunkHeight, canopyBottomOffset, canopyTopOffset, canLean
            case ROUND        -> new Profile(5, 3, 6, false);
            case TALL         -> new Profile(6 + rng.nextInt(2), 4, 9, true);
            case COMPACT      -> new Profile(3 + rng.nextInt(2), 2, 5, false);
            case BIG_BRANCHED -> new Profile(6 + rng.nextInt(2), 3, 8, false);
        };
    }

    private static void placeTrunk(TreeBlockPlacer placer, int wx, int wy, int wz, int trunkHeight) {
        for (int dy = 0; dy < trunkHeight; dy++) {
            placer.placeBlock(wx, wy + dy, wz, BlockType.WOOD);
        }
    }

    // --------------------------------------------------------------------------------------
    // Inner branches — short cardinal branches near the top of the trunk, embedded in canopy
    // --------------------------------------------------------------------------------------

    private static void placeInnerBranches(TreeBlockPlacer placer, Random rng, Variant variant,
                                           int wx, int wy, int wz, int trunkHeight) {
        switch (variant) {
            case ROUND, COMPACT -> placeShortInnerBranches(placer, rng, wx, wy, wz, trunkHeight, 2);
            case TALL           -> placeShortInnerBranches(placer, rng, wx, wy, wz, trunkHeight, 3);
            case BIG_BRANCHED   -> placeBigBranches(placer, wx, wy, wz, trunkHeight);
        }
    }

    /** Pick {@code count} (≤4) random cardinal directions and place a single log one block out. */
    private static void placeShortInnerBranches(TreeBlockPlacer placer, Random rng,
                                                int wx, int wy, int wz,
                                                int trunkHeight, int count) {
        if (trunkHeight < 3) return;
        int branchY = wy + trunkHeight - 2;

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        boolean[] used = new boolean[4];
        int placed = 0;
        while (placed < Math.min(count, 4)) {
            int idx = rng.nextInt(4);
            if (used[idx]) continue;
            used[idx] = true;
            placer.placeBlock(wx + dx[idx], branchY, wz + dz[idx], BlockType.WOOD);
            placed++;
        }
    }

    /** Four-direction arching branches that reach 2 blocks out and rise one block. */
    private static void placeBigBranches(TreeBlockPlacer placer, int wx, int wy, int wz,
                                         int trunkHeight) {
        if (trunkHeight < 4) return;
        int branchY = wy + trunkHeight - 3;
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        for (int d = 0; d < 4; d++) {
            // First step: 1 block out, level with branchY.
            placer.placeBlock(wx + dx[d], branchY, wz + dz[d], BlockType.WOOD);
            // Second step: 2 blocks out, raised one block — gives the arch a visible curve.
            placer.placeBlock(wx + 2 * dx[d], branchY + 1, wz + 2 * dz[d], BlockType.WOOD);
        }
    }

    // --------------------------------------------------------------------------------------
    // Canopy
    // --------------------------------------------------------------------------------------

    private static void placeCanopy(TreeBlockPlacer placer, Random rng, Variant variant,
                                    Profile profile, int trunkX, int trunkBaseY, int trunkZ,
                                    int canopyCx, int canopyCz) {
        for (int dy = profile.canopyBottomOffset; dy <= profile.canopyTopOffset; dy++) {
            int worldY = trunkBaseY + dy;
            if (worldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            float t = (profile.canopyTopOffset == profile.canopyBottomOffset) ? 0.5f
                    : (float) (dy - profile.canopyBottomOffset)
                      / (profile.canopyTopOffset - profile.canopyBottomOffset);
            int radius = canopyRadiusFor(variant, t);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int absDx = Math.abs(dx), absDz = Math.abs(dz);

                    // Round off the corners of the bounding square for a softer silhouette.
                    if (absDx == radius && absDz == radius) continue;
                    // For radius 3, also clip the next-to-corner cells so the lobe reads round.
                    if (radius >= 3 && absDx + absDz >= radius + 2) continue;

                    int worldX = canopyCx + dx;
                    int worldZ = canopyCz + dz;

                    // Don't overwrite the trunk itself when the canopy column overlaps it.
                    if (worldX == trunkX && worldZ == trunkZ
                        && worldY < trunkBaseY + profile.trunkHeight) continue;

                    float gap = canopyGapChance(variant, absDx, absDz, radius);
                    if (gap > 0f && rng.nextFloat() < gap) continue;

                    placer.placeBlock(worldX, worldY, worldZ, BlockType.LEAVES);
                }
            }

            // For COMPACT the canopy is so short the top layer otherwise reads flat — drop a
            // single cap leaf above the highest layer for a rounded peak.
            if (variant == Variant.COMPACT && dy == profile.canopyTopOffset) {
                int capY = worldY + 1;
                if (capY < WorldConfiguration.WORLD_HEIGHT) {
                    placer.placeBlock(canopyCx, capY, canopyCz, BlockType.LEAVES);
                }
            }
        }
    }

    private static int canopyRadiusFor(Variant variant, float t) {
        return switch (variant) {
            case ROUND        -> t < 0.85f ? 2 : 1;
            case TALL         -> t < 0.5f ? 2 : 1;
            case COMPACT      -> t < 0.6f ? 2 : 1;
            case BIG_BRANCHED -> t < 0.25f ? 2 : (t < 0.75f ? 3 : (t < 0.95f ? 2 : 1));
        };
    }

    private static float canopyGapChance(Variant variant, int absDx, int absDz, int radius) {
        boolean onRim = absDx == radius || absDz == radius;
        return switch (variant) {
            case TALL         -> onRim ? 0.20f : 0.0f;
            case COMPACT      -> onRim ? 0.10f : 0.0f;
            case BIG_BRANCHED -> onRim ? 0.18f : 0.0f;
            case ROUND        -> 0.0f;
        };
    }

    /** Per-tree shape profile. canopyBottomOffset/canopyTopOffset are y-offsets from trunk base. */
    private record Profile(int trunkHeight, int canopyBottomOffset, int canopyTopOffset,
                           boolean canLean) {}
}
