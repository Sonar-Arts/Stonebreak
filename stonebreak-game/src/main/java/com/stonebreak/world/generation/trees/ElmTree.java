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
 *
 * <p><b>Leaf-decay contract.</b> Every leaf must sit within
 * {@code LeafDecaySystem.DECAY_RADIUS} orthogonal leaf/log steps of a log, or the decay
 * system removes it on the next chunk-load rescan. The canopy spans seven layers above
 * the visible trunk top, so a hidden {@link #CORE_TOP} log column continues the trunk up
 * through the canopy core (placed after the leaves so they do not overwrite it), and the
 * canopy's centre column is never randomly thinned, keeping that chain deterministic.
 * Leaf islands that random side-thinning still isolates are pruned by
 * {@link TreeShapeBuffer#flushAnchored} before the tree is written.
 */
public final class ElmTree {

    public static final int LEAF_RADIUS = 4;
    public static final int MAX_HEIGHT = 18;

    /**
     * Highest canopy layer (relative to the trunk top) that gets a core log. Leaves the
     * top cap at +6 three steps away, the outer upper-canopy ring ({@code upperRadius} 3,
     * offsets like (2,1) at +5) five steps away, and the widest lower-canopy ring
     * ({@code lowerRadius} 4, offsets like (3,2) at +2) five steps away — all inside a
     * decay radius of 6 with one step of slack for a thinned neighbour.
     */
    private static final int CORE_TOP = 3;
    private static final long SEED_TAG = 0x5EED1E1F5EED1E1FL;

    private enum Variant { CLASSIC, ASYMMETRIC, CROWN, WINDSWEPT }

    private ElmTree() {}

    public static void place(World world, int worldX, int worldY, int worldZ) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);
        place(placer, worldX, worldY, worldZ);
        placer.complete();
    }

    /**
     * Shape generation against any sink — the seam tests use to inspect generated elms.
     * The shape is buffered and leaves the decay system could never anchor are pruned
     * before anything is written (see {@link TreeShapeBuffer}).
     */
    static void place(TreeBlockSink out, int worldX, int worldY, int worldZ) {
        TreeShapeBuffer placer = new TreeShapeBuffer();
        generate(placer, worldX, worldY, worldZ);
        placer.flushAnchored(out);
    }

    private static void generate(TreeBlockSink placer, int worldX, int worldY, int worldZ) {
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
        placeCanopyCore(placer, worldX, worldY, worldZ, profile.trunkHeight);
        placeTopCap(placer, worldX, worldY + profile.trunkHeight + 6, worldZ);
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

    private static void placeTrunk(TreeBlockSink placer, int wx, int wy, int wz, int trunkHeight) {
        for (int dy = 0; dy < trunkHeight; dy++) {
            placer.placeBlock(wx, wy + dy, wz, BlockType.ELM_WOOD_LOG);
        }
    }

    // --------------------------------------------------------------------------------------
    // Branches
    // --------------------------------------------------------------------------------------

    private static void placeBranches(TreeBlockSink placer, Random rng, Variant variant,
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
    private static void placeCrownBranches(TreeBlockSink placer, Random rng,
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
    private static void placeHighInnerBranches(TreeBlockSink placer, Random rng,
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

    /**
     * Hidden core: continues the trunk from its visible top up through the canopy so
     * every leaf stays within leaf-decay reach (see class javadoc). Placed after the
     * canopy because leaf placement overwrites, and fully enclosed by the lower canopy
     * (radius 4) and upper canopy (radius 3) so it is not visible from outside.
     */
    private static void placeCanopyCore(TreeBlockSink placer, int wx, int wy, int wz, int trunkHeight) {
        for (int dy = trunkHeight; dy <= trunkHeight + CORE_TOP; dy++) {
            int worldY = wy + dy;
            if (worldY >= WorldConfiguration.WORLD_HEIGHT) return;
            placer.placeBlock(wx, worldY, wz, BlockType.ELM_WOOD_LOG);
        }
    }

    private static void placeClassicBranches(TreeBlockSink placer, int branchLevel, int wx, int wz) {
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

    private static void placeAsymmetricBranches(TreeBlockSink placer, Random rng,
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

    private static void placeWindsweptBranches(TreeBlockSink placer, int branchLevel,
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

    private static void placeLowerCanopy(TreeBlockSink placer, Random rng, Variant variant,
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

    private static void placeUpperCanopy(TreeBlockSink placer, Random rng, Profile profile,
                                         int wx, int wy, int wz) {
        int radius = profile.upperRadius;
        for (int dy = profile.trunkHeight + 3; dy <= profile.trunkHeight + 5; dy++) {
            int worldY = wy + dy;
            if (worldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (dist > radius * 0.8f) continue;
                    // The centre column is the decay-support spine — never thin it. The
                    // draw still happens so the rest of the silhouette keeps its seed shape.
                    boolean thin = rng.nextFloat() < 0.20f;
                    if (thin && !(dx == 0 && dz == 0)) continue;
                    placer.placeBlock(wx + dx, worldY, wz + dz, BlockType.ELM_LEAVES);
                }
            }
        }
    }

    private static void placeTopCap(TreeBlockSink placer, int wx, int topY, int wz) {
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
