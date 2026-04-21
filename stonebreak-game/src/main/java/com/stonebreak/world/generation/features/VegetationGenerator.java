package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.TreeGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Plants trees and flowers on the surface column.
 */
public class VegetationGenerator {
    public static final int MIN_SURFACE_Y = 64;
    public static final float TREE_CHANCE = 0.01f;
    public static final float ELM_TREE_CHANCE = 0.4f;
    public static final float PINE_TREE_CHANCE = 0.015f;
    public static final float TAIGA_PINE_CHANCE = 0.03f;
    public static final float TUNDRA_PINE_CHANCE = 0.003f;
    public static final float MEADOW_TREE_CHANCE = 0.002f;
    private static final float FLOWER_CHANCE = 0.08f;
    private static final float MEADOW_FLOWER_CHANCE = 0.25f;

    /** Tree variety identifier, carries the trunk + leaf block types it renders with. */
    public enum TreeKind {
        OAK(BlockType.WOOD, BlockType.LEAVES),
        ELM(BlockType.ELM_WOOD_LOG, BlockType.ELM_LEAVES),
        PINE(BlockType.PINE, BlockType.PINE_LEAVES);

        private final BlockType trunk;
        private final BlockType leaves;
        TreeKind(BlockType trunk, BlockType leaves) { this.trunk = trunk; this.leaves = leaves; }
        public BlockType trunkBlock() { return trunk; }
        public BlockType leavesBlock() { return leaves; }
    }

    /** Result of {@link #probeTree}; {@code trunkHeight} follows the real generator's logic. */
    public record TreeSample(TreeKind kind, int trunkHeight) {}

    /**
     * Deterministically decides whether a tree exists at this column without
     * mutating any chunk. Shared with distant-terrain LOD so both paths agree on
     * placement. Matches {@link #placeTree} but without feature queue / chunk writes.
     */
    public static TreeSample probeTree(int worldX, int worldZ, BiomeType biome,
                                       BlockType surfaceBlock, DeterministicRandom rng) {
        if (biome == BiomeType.PLAINS && surfaceBlock == BlockType.GRASS &&
                rng.shouldGenerate(worldX, worldZ, "tree", TREE_CHANCE)) {
            if (rng.shouldGenerate(worldX, worldZ, "tree_type", ELM_TREE_CHANCE)) {
                int h = 8 + rng.getRandomForPosition(worldX, worldZ, "elm_tree").nextInt(5);
                return new TreeSample(TreeKind.ELM, h);
            }
            return new TreeSample(TreeKind.OAK, 5);
        }
        if (biome == BiomeType.SNOWY_PLAINS && surfaceBlock == BlockType.SNOWY_DIRT &&
                rng.shouldGenerate(worldX, worldZ, "pine_tree", PINE_TREE_CHANCE)) {
            return new TreeSample(TreeKind.PINE, 7);
        }
        if (biome == BiomeType.TAIGA && surfaceBlock == BlockType.SNOWY_DIRT &&
                rng.shouldGenerate(worldX, worldZ, "taiga_pine_tree", TAIGA_PINE_CHANCE)) {
            return new TreeSample(TreeKind.PINE, 7);
        }
        if (biome == BiomeType.MEADOW && surfaceBlock == BlockType.GRASS &&
                rng.shouldGenerate(worldX, worldZ, "meadow_tree", MEADOW_TREE_CHANCE)) {
            int h = 8 + rng.getRandomForPosition(worldX, worldZ, "meadow_elm").nextInt(5);
            return new TreeSample(TreeKind.ELM, h);
        }
        return null;
    }

    private final DeterministicRandom rng;
    private final Object treeRandomLock = new Object();

    public VegetationGenerator(DeterministicRandom rng) {
        this.rng = rng;
    }

    public void generate(ChunkGenerationContext ctx) {
        Chunk chunk = ctx.chunk;
        for (int x = 0; x < ChunkGenerationContext.SIZE; x++) {
            for (int z = 0; z < ChunkGenerationContext.SIZE; z++) {
                int surface = ctx.height(x, z);
                if (surface <= MIN_SURFACE_Y || surface >= WorldConfiguration.WORLD_HEIGHT) {
                    continue;
                }
                int worldX = ctx.worldX(x);
                int worldZ = ctx.worldZ(z);
                BiomeType biome = ctx.biome(x, z);
                BlockType surfaceBlock = chunk.getBlock(x, surface - 1, z);

                placeTree(ctx, x, z, worldX, worldZ, surface, biome, surfaceBlock);
                placeFlower(ctx, x, z, worldX, worldZ, surface, biome, surfaceBlock);
            }
        }
    }

    private void placeTree(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                           int surface, BiomeType biome, BlockType surfaceBlock) {
        FeatureQueue queue = ctx.world.getFeatureQueue();
        if (biome == BiomeType.PLAINS && surfaceBlock == BlockType.GRASS &&
            rng.shouldGenerate(worldX, worldZ, "tree", TREE_CHANCE)) {
            if (rng.shouldGenerate(worldX, worldZ, "tree_type", ELM_TREE_CHANCE)) {
                TreeGenerator.generateElmTree(ctx.world, queue, ctx.chunk, x, surface, z,
                    rng.getRandomForPosition(worldX, worldZ, "elm_tree"), treeRandomLock);
            } else {
                TreeGenerator.generateTree(ctx.world, queue, ctx.chunk, x, surface, z);
            }
        } else if (biome == BiomeType.SNOWY_PLAINS && surfaceBlock == BlockType.SNOWY_DIRT &&
                   rng.shouldGenerate(worldX, worldZ, "pine_tree", PINE_TREE_CHANCE)) {
            TreeGenerator.generatePineTree(ctx.world, queue, ctx.chunk, x, surface, z);
        } else if (biome == BiomeType.TAIGA && surfaceBlock == BlockType.SNOWY_DIRT &&
                   rng.shouldGenerate(worldX, worldZ, "taiga_pine_tree", TAIGA_PINE_CHANCE)) {
            TreeGenerator.generatePineTree(ctx.world, queue, ctx.chunk, x, surface, z);
        } else if (biome == BiomeType.TUNDRA && surfaceBlock == BlockType.GRAVEL &&
                   rng.shouldGenerate(worldX, worldZ, "tundra_pine_tree", TUNDRA_PINE_CHANCE)) {
            TreeGenerator.generatePineTree(ctx.world, queue, ctx.chunk, x, surface, z);
        } else if (biome == BiomeType.MEADOW && surfaceBlock == BlockType.GRASS &&
                   rng.shouldGenerate(worldX, worldZ, "meadow_tree", MEADOW_TREE_CHANCE)) {
            TreeGenerator.generateElmTree(ctx.world, queue, ctx.chunk, x, surface, z,
                rng.getRandomForPosition(worldX, worldZ, "meadow_elm"), treeRandomLock);
        }
    }

    private void placeFlower(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                             int surface, BiomeType biome, BlockType surfaceBlock) {
        float chance = flowerChance(biome);
        if (chance <= 0f || surfaceBlock != BlockType.GRASS) {
            return;
        }
        if (ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        if (!rng.shouldGenerate(worldX, worldZ, "flower", chance)) {
            return;
        }
        BlockType flower;
        if (rng.getBoolean(worldX, worldZ, "flower_type")) {
            flower = rng.getBoolean(worldX, worldZ, "flower_subtype")
                ? BlockType.ROSE
                : BlockType.WILDGRASS;
        } else {
            flower = BlockType.DANDELION;
        }
        ctx.chunk.setBlock(x, surface, z, flower);
    }

    private static float flowerChance(BiomeType biome) {
        return switch (biome) {
            case PLAINS -> FLOWER_CHANCE;
            case MEADOW -> MEADOW_FLOWER_CHANCE;
            default -> 0f;
        };
    }
}
