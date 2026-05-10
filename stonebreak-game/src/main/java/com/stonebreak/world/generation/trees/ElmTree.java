package com.stonebreak.world.generation.trees;

import java.util.Random;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Elm tree shape generator.
 *
 * Selects one of four silhouettes deterministically per world position:
 * <ul>
 *   <li>{@link Variant#CLASSIC}    — 4-direction branches with a full rounded canopy</li>
 *   <li>{@link Variant#ASYMMETRIC} — 2-3 randomly-chosen branches with one denser canopy lobe</li>
 *   <li>{@link Variant#CROWN}      — extra-tall branchless trunk topped by a fat round canopy</li>
 *   <li>{@link Variant#WINDSWEPT}  — single-direction curved branches with stretched canopy</li>
 * </ul>
 *
 * All variants stay within {@link #LEAF_RADIUS} so chunk-scheduling guarantees remain valid.
 */
public final class ElmTree {

    public static final int LEAF_RADIUS = 4;
    public static final int MAX_HEIGHT = 18;
    private static final long SEED_TAG = 0x5EED1E1F5EED1E1FL;

    private enum Variant { CLASSIC, ASYMMETRIC, CROWN, WINDSWEPT }

    private ElmTree() {}

    public static void place(World world, int worldX, int worldY, int worldZ) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);
        Random rng = TreeRandom.forPosition(worldX, worldY, worldZ, SEED_TAG);

        Variant variant = Variant.values()[rng.nextInt(Variant.values().length)];
        Profile profile = profileFor(variant, rng);

        if (worldY + profile.trunkHeight + 6 >= WorldConfiguration.WORLD_HEIGHT) return;

        placeTrunk(placer, worldX, worldY, worldZ, profile.trunkHeight);
        placeBranches(placer, rng, variant, profile, worldX, worldY, worldZ);
        placeLowerCanopy(placer, rng, variant, profile, worldX, worldY, worldZ);
        placeUpperCanopy(placer, rng, profile, worldX, worldY, worldZ);
        // Inner branches go AFTER the canopy so a few logs remain visible inside the
        // foliage instead of being silently overwritten by leaf placement.
        placeHighInnerBranches(placer, rng, worldX, worldY, worldZ, profile.trunkHeight);
        placeTopCap(placer, worldX, worldY + profile.trunkHeight + 6, worldZ);

        placer.complete();
    }

    private static Profile profileFor(Variant variant, Random rng) {
        // Trunk heights are deliberately a notch shorter than they used to be so elm forests
        // read as canopy-dominant rather than spindly poles.
        return switch (variant) {
            case CLASSIC    -> new Profile(7 + rng.nextInt(4), 4, 3, -1);
            case ASYMMETRIC -> new Profile(7 + rng.nextInt(4), 4, 3, rng.nextInt(4));
            case CROWN      -> new Profile(9 + rng.nextInt(3), 4, 3, -1);
            case WINDSWEPT  -> new Profile(8 + rng.nextInt(3), 3, 2, rng.nextInt(4));
        };
    }

    private static void placeTrunk(TreeBlockPlacer placer, int wx, int wy, int wz, int trunkHeight) {
        for (int dy = 0; dy < trunkHeight; dy++) {
            placer.placeBlock(wx, wy + dy, wz, BlockType.ELM_WOOD_LOG);
        }
    }

    // --------------------------------------------------------------------------------------
    // Branches
    // --------------------------------------------------------------------------------------

    private static void placeBranches(TreeBlockPlacer placer, Random rng, Variant variant,
                                      Profile profile, int wx, int wy, int wz) {
        int branchLevel = wy + profile.trunkHeight - 3;
        if (branchLevel + 3 >= WorldConfiguration.WORLD_HEIGHT) return;

        switch (variant) {
            case CLASSIC    -> placeClassicBranches(placer, branchLevel, wx, wz);
            case ASYMMETRIC -> placeAsymmetricBranches(placer, rng, branchLevel, wx, wz);
            case CROWN      -> placeCrownBranches(placer, rng, branchLevel, wx, wz);
            case WINDSWEPT  -> placeWindsweptBranches(placer, branchLevel, profile.leanDir, wx, wz);
        }
    }

    /** CROWN's only branches: 2-3 short, 1-block cardinal stubs near the top of the trunk. */
    private static void placeCrownBranches(TreeBlockPlacer placer, Random rng,
                                           int branchLevel, int wx, int wz) {
        int branchCount = 2 + rng.nextInt(2);
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        boolean[] used = new boolean[4];
        int placed = 0;
        while (placed < branchCount) {
            int idx = rng.nextInt(4);
            if (used[idx]) continue;
            used[idx] = true;
            placer.placeBlock(wx + dx[idx], branchLevel + 2, wz + dz[idx], BlockType.ELM_WOOD_LOG);
            placed++;
        }
    }

    /** 1-2 short logs at trunk-top height embedded inside the lower canopy. */
    private static void placeHighInnerBranches(TreeBlockPlacer placer, Random rng,
                                               int wx, int wy, int wz, int trunkHeight) {
        int innerY = wy + trunkHeight;
        if (innerY >= WorldConfiguration.WORLD_HEIGHT) return;

        int branchCount = 1 + rng.nextInt(2);
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        boolean[] used = new boolean[4];
        int placed = 0;
        while (placed < branchCount) {
            int idx = rng.nextInt(4);
            if (used[idx]) continue;
            used[idx] = true;
            placer.placeBlock(wx + dx[idx], innerY, wz + dz[idx], BlockType.ELM_WOOD_LOG);
            placed++;
        }
    }

    private static void placeClassicBranches(TreeBlockPlacer placer, int branchLevel, int wx, int wz) {
        for (int by = branchLevel; by < branchLevel + 3; by++) {
            placer.placeBlock(wx + 1, by, wz, BlockType.ELM_WOOD_LOG);
            placer.placeBlock(wx - 1, by, wz, BlockType.ELM_WOOD_LOG);
            placer.placeBlock(wx, by, wz + 1, BlockType.ELM_WOOD_LOG);
            placer.placeBlock(wx, by, wz - 1, BlockType.ELM_WOOD_LOG);

            if (by == branchLevel + 1) {
                placer.placeBlock(wx + 1, by, wz + 1, BlockType.ELM_WOOD_LOG);
                placer.placeBlock(wx + 1, by, wz - 1, BlockType.ELM_WOOD_LOG);
                placer.placeBlock(wx - 1, by, wz + 1, BlockType.ELM_WOOD_LOG);
                placer.placeBlock(wx - 1, by, wz - 1, BlockType.ELM_WOOD_LOG);
            }
        }
    }

    private static void placeAsymmetricBranches(TreeBlockPlacer placer, Random rng,
                                                int branchLevel, int wx, int wz) {
        // Pick 2-3 cardinal branches at random; skip the rest for a lopsided crown.
        int branchCount = 2 + rng.nextInt(2);
        boolean[] dirs = new boolean[4];
        int placed = 0;
        while (placed < branchCount) {
            int idx = rng.nextInt(4);
            if (!dirs[idx]) { dirs[idx] = true; placed++; }
        }

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int by = branchLevel; by < branchLevel + 3; by++) {
            for (int d = 0; d < 4; d++) {
                if (!dirs[d]) continue;
                placer.placeBlock(wx + dx[d], by, wz + dz[d], BlockType.ELM_WOOD_LOG);
            }
        }
    }

    private static void placeWindsweptBranches(TreeBlockPlacer placer, int branchLevel,
                                               int leanDir, int wx, int wz) {
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        int lx = dx[leanDir], lz = dz[leanDir];

        // Branches curve upward and outward in the lean direction:
        // level+0: 1 out, level+1: 2 out, level+2: 2 out (still within LEAF_RADIUS=4).
        placer.placeBlock(wx + lx, branchLevel, wz + lz, BlockType.ELM_WOOD_LOG);
        placer.placeBlock(wx + 2 * lx, branchLevel + 1, wz + 2 * lz, BlockType.ELM_WOOD_LOG);
        placer.placeBlock(wx + 2 * lx, branchLevel + 2, wz + 2 * lz, BlockType.ELM_WOOD_LOG);

        // A single perpendicular bracing branch keeps the silhouette from looking like a
        // half-eaten lollipop.
        int px = -lz, pz = lx;
        placer.placeBlock(wx + px, branchLevel + 1, wz + pz, BlockType.ELM_WOOD_LOG);
    }

    // --------------------------------------------------------------------------------------
    // Canopy
    // --------------------------------------------------------------------------------------

    private static void placeLowerCanopy(TreeBlockPlacer placer, Random rng, Variant variant,
                                         Profile profile, int wx, int wy, int wz) {
        int radius = profile.lowerRadius;
        for (int dy = profile.trunkHeight - 1; dy <= profile.trunkHeight + 2; dy++) {
            int worldY = wy + dy;
            if (worldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (dist > radius * 0.9f) continue;
                    if (dx == 0 && dz == 0 && dy < profile.trunkHeight + 1) continue;

                    if (skipForVariant(variant, profile, rng, dx, dz, dist, radius)) continue;

                    placer.placeBlock(wx + dx, worldY, wz + dz, BlockType.ELM_LEAVES);
                }
            }
        }
    }

    private static void placeUpperCanopy(TreeBlockPlacer placer, Random rng, Profile profile,
                                         int wx, int wy, int wz) {
        int radius = profile.upperRadius;
        for (int dy = profile.trunkHeight + 3; dy <= profile.trunkHeight + 5; dy++) {
            int worldY = wy + dy;
            if (worldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (dist > radius * 0.8f) continue;
                    if (rng.nextFloat() < 0.20f) continue;
                    placer.placeBlock(wx + dx, worldY, wz + dz, BlockType.ELM_LEAVES);
                }
            }
        }
    }

    private static void placeTopCap(TreeBlockPlacer placer, int wx, int topY, int wz) {
        if (topY >= WorldConfiguration.WORLD_HEIGHT) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) continue;
                placer.placeBlock(wx + dx, topY, wz + dz, BlockType.ELM_LEAVES);
            }
        }
    }

    /**
     * Variant-specific lower-canopy thinning. {@code dist} is the distance from the trunk.
     * Returns true when this leaf should be skipped.
     */
    private static boolean skipForVariant(Variant variant, Profile profile, Random rng,
                                          int dx, int dz, float dist, int radius) {
        // Base rim-thinning: the original elm thinned ~30% on the outer ring.
        boolean onOuterRing = dist > radius * 0.7f;
        switch (variant) {
            case CLASSIC:
                return onOuterRing && rng.nextFloat() < 0.30f;
            case CROWN:
                // Denser canopy compensates for the missing branches.
                return onOuterRing && rng.nextFloat() < 0.18f;
            case ASYMMETRIC: {
                // Heavily thin the side opposite the chosen lobe; lightly thin the others.
                int dot = dotWithDir(dx, dz, profile.leanDir);
                if (dot < 0) return rng.nextFloat() < 0.55f;       // far side: sparse
                if (dot > 0) return onOuterRing && rng.nextFloat() < 0.20f; // near side: dense
                return onOuterRing && rng.nextFloat() < 0.30f;
            }
            case WINDSWEPT: {
                // Stretch the canopy in the lean direction, thin the windward side.
                int dot = dotWithDir(dx, dz, profile.leanDir);
                if (dot < 0) return rng.nextFloat() < 0.65f;
                if (dot > 0) return onOuterRing && rng.nextFloat() < 0.15f;
                return onOuterRing && rng.nextFloat() < 0.35f;
            }
            default:
                return false;
        }
    }

    /** +1 if (dx,dz) leans in {@code dir}, -1 if opposite, 0 if perpendicular. */
    private static int dotWithDir(int dx, int dz, int dir) {
        return switch (dir) {
            case 0 -> Integer.signum(dx);   // +X
            case 1 -> -Integer.signum(dx);  // -X
            case 2 -> Integer.signum(dz);   // +Z
            case 3 -> -Integer.signum(dz);  // -Z
            default -> 0;
        };
    }

    /**
     * Per-tree shape profile.
     *
     * @param leanDir 0..3 cardinal lean direction, or -1 for symmetric variants.
     */
    private record Profile(int trunkHeight, int lowerRadius, int upperRadius, int leanDir) {}
}
