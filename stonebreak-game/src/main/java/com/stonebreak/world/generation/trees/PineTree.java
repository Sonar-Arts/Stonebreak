package com.stonebreak.world.generation.trees;

import java.util.Random;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Pine tree shape generator.
 *
 * Selects one of four silhouettes deterministically per world position:
 * <ul>
 *   <li>{@link Variant#CLASSIC} — layered conifer with alternating pinched tiers</li>
 *   <li>{@link Variant#SLENDER} — tall narrow spire with a bare lower trunk</li>
 *   <li>{@link Variant#BUSHY}   — short, wide-skirted christmas-tree shape</li>
 *   <li>{@link Variant#WISPY}   — taller tree with sparser, gappier foliage</li>
 * </ul>
 *
 * All variants stay within {@link #LEAF_RADIUS} so the chunk-loading guarantees in
 * TreeGenerator scheduling remain valid.
 */
public final class PineTree {

    public static final int LEAF_RADIUS = 2;
    public static final int MAX_HEIGHT = 14;
    private static final long SEED_TAG = 0xC0FFEEC0FFEEC0FFL;

    private enum Variant { CLASSIC, SLENDER, BUSHY, WISPY }

    private PineTree() {}

    public static void place(World world, int worldX, int worldY, int worldZ) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);
        Random rng = TreeRandom.forPosition(worldX, worldY, worldZ, SEED_TAG);

        Variant variant = Variant.values()[rng.nextInt(Variant.values().length)];
        Profile profile = profileFor(variant, rng);

        if (worldY + profile.totalHeight() >= WorldConfiguration.WORLD_HEIGHT) return;

        placeTrunk(placer, worldX, worldY, worldZ, profile.trunkHeight);
        placeFoliage(placer, rng, variant, profile, worldX, worldY, worldZ);
        placeTip(placer, worldX, worldY + profile.totalHeight(), worldZ);

        placer.complete();
    }

    private static Profile profileFor(Variant variant, Random rng) {
        return switch (variant) {
            case SLENDER -> new Profile(9 + rng.nextInt(3), 4);
            case BUSHY   -> new Profile(6 + rng.nextInt(2), 2);
            case WISPY   -> new Profile(8 + rng.nextInt(3), 3);
            case CLASSIC -> new Profile(7 + rng.nextInt(2), 2);
        };
    }

    private static void placeTrunk(TreeBlockPlacer placer, int wx, int wy, int wz, int trunkHeight) {
        for (int dy = 0; dy < trunkHeight; dy++) {
            placer.placeBlock(wx, wy + dy, wz, BlockType.PINE);
        }
    }

    private static void placeFoliage(TreeBlockPlacer placer, Random rng, Variant variant,
                                     Profile profile, int wx, int wy, int wz) {
        int layerCount = profile.totalHeight() - profile.foliageStart;
        for (int i = 0; i < layerCount; i++) {
            int worldY = wy + profile.foliageStart + i;
            if (worldY >= WorldConfiguration.WORLD_HEIGHT) break;

            float t = (layerCount <= 1) ? 1f : (float) i / (layerCount - 1);
            int radius = baseRadiusFor(variant, t, i, layerCount);

            // Pinch every other layer for the frilled-conifer profile, but only when radius > 1
            // so we never collapse to an empty layer that would punch a hole through the canopy.
            boolean pinch = (variant == Variant.CLASSIC || variant == Variant.WISPY)
                            && (i & 1) == 1
                            && radius > 1;
            if (pinch) radius--;

            boolean trunkAtCenter = (worldY - wy) < profile.trunkHeight;
            placeFoliageLayer(placer, rng, variant, wx, worldY, wz, radius, trunkAtCenter);
        }
    }

    private static int baseRadiusFor(Variant variant, float t, int i, int layerCount) {
        return switch (variant) {
            case SLENDER -> i >= layerCount - 2 ? 0 : ((i & 1) == 0 ? 1 : 2);
            case BUSHY   -> t < 0.75f ? 2 : (t < 0.95f ? 1 : 0);
            case WISPY   -> t < 0.35f ? 2 : (t < 0.85f ? 1 : 0);
            case CLASSIC -> t < 0.30f ? 2 : (t < 0.85f ? 1 : 0);
        };
    }

    private static void placeFoliageLayer(TreeBlockPlacer placer, Random rng, Variant variant,
                                          int wx, int wy, int wz,
                                          int radius, boolean trunkAtCenter) {
        if (radius <= 0) {
            if (!trunkAtCenter) {
                placer.placeBlock(wx, wy, wz, BlockType.PINE_LEAVES);
            }
            return;
        }

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int absDx = Math.abs(dx), absDz = Math.abs(dz);
                if (absDx == radius && absDz == radius) continue;
                if (dx == 0 && dz == 0 && trunkAtCenter) continue;

                float gapChance = edgeGapChance(variant, absDx, absDz, radius);
                if (gapChance > 0f && rng.nextFloat() < gapChance) continue;

                placer.placeBlock(wx + dx, wy, wz + dz, BlockType.PINE_LEAVES);
            }
        }
    }

    private static float edgeGapChance(Variant variant, int absDx, int absDz, int radius) {
        int rim = absDx + absDz;
        return switch (variant) {
            case WISPY   -> (rim >= radius + 1) ? 0.45f : 0.10f;
            case CLASSIC -> (absDx == radius || absDz == radius) ? 0.18f : 0.0f;
            case SLENDER -> (rim >= radius + 1) ? 0.30f : 0.0f;
            case BUSHY   -> (absDx == radius && absDz == radius - 1) ? 0.15f : 0.0f;
        };
    }

    private static void placeTip(TreeBlockPlacer placer, int wx, int tipY, int wz) {
        if (tipY < WorldConfiguration.WORLD_HEIGHT) {
            placer.placeBlock(wx, tipY, wz, BlockType.PINE_LEAVES);
        }
    }

    /** Per-tree height profile. Foliage extends 2 blocks above trunk top to form the crown. */
    private record Profile(int trunkHeight, int foliageStart) {
        int totalHeight() { return trunkHeight + 2; }
    }
}
