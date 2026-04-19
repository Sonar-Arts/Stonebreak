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
    private static final int MIN_SURFACE_Y = 64;
    private static final float TREE_CHANCE = 0.01f;
    private static final float ELM_TREE_CHANCE = 0.4f;
    private static final float PINE_TREE_CHANCE = 0.015f;
    private static final float FLOWER_CHANCE = 0.08f;

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
        if (biome == BiomeType.PLAINS && surfaceBlock == BlockType.GRASS &&
            rng.shouldGenerate(worldX, worldZ, "tree", TREE_CHANCE)) {
            if (rng.shouldGenerate(worldX, worldZ, "tree_type", ELM_TREE_CHANCE)) {
                TreeGenerator.generateElmTree(ctx.world, ctx.chunk, x, surface, z,
                    rng.getRandomForPosition(worldX, worldZ, "elm_tree"), treeRandomLock);
            } else {
                TreeGenerator.generateTree(ctx.world, ctx.chunk, x, surface, z);
            }
        } else if (biome == BiomeType.SNOWY_PLAINS && surfaceBlock == BlockType.SNOWY_DIRT &&
                   rng.shouldGenerate(worldX, worldZ, "pine_tree", PINE_TREE_CHANCE)) {
            TreeGenerator.generatePineTree(ctx.world, ctx.chunk, x, surface, z);
        }
    }

    private void placeFlower(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                             int surface, BiomeType biome, BlockType surfaceBlock) {
        if (biome != BiomeType.PLAINS || surfaceBlock != BlockType.GRASS) {
            return;
        }
        if (ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        if (!rng.shouldGenerate(worldX, worldZ, "flower", FLOWER_CHANCE)) {
            return;
        }
        BlockType flower = rng.getBoolean(worldX, worldZ, "flower_type")
            ? BlockType.ROSE
            : BlockType.DANDELION;
        ctx.chunk.setBlock(x, surface, z, flower);
    }
}
