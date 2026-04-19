package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Places ores within already-generated stone columns.
 * Writes are always within the chunk so we use chunk.setBlock directly.
 */
public class OreGenerator {
    private static final int IRON_MAX_Y = 50;
    private static final int CRYSTAL_MIN_Y = 20;
    private static final float COAL_CHANCE = 0.015f;
    private static final float IRON_CHANCE = 0.008f;
    private static final float CRYSTAL_CHANCE = 0.02f;

    private final DeterministicRandom rng;

    public OreGenerator(DeterministicRandom rng) {
        this.rng = rng;
    }

    public void generate(ChunkGenerationContext ctx) {
        Chunk chunk = ctx.chunk;
        for (int x = 0; x < ChunkGenerationContext.SIZE; x++) {
            for (int z = 0; z < ChunkGenerationContext.SIZE; z++) {
                int worldX = ctx.worldX(x);
                int worldZ = ctx.worldZ(z);
                int columnTop = ctx.height(x, z) - 1;
                int crystalMaxY = columnTop - 4;
                BiomeType biome = ctx.biome(x, z);

                for (int y = 1; y < columnTop; y++) {
                    BlockType current = chunk.getBlock(x, y, z);
                    BlockType ore = pickOre(worldX, y, worldZ, current, biome, crystalMaxY);
                    if (ore != null) {
                        chunk.setBlock(x, y, z, ore);
                    }
                }
            }
        }
    }

    private BlockType pickOre(int worldX, int y, int worldZ, BlockType current, BiomeType biome, int crystalMaxY) {
        if (current == BlockType.STONE) {
            if (rng.shouldGenerate3D(worldX, y, worldZ, "coal_ore", COAL_CHANCE)) {
                return BlockType.COAL_ORE;
            }
            if (y < IRON_MAX_Y && rng.shouldGenerate3D(worldX, y, worldZ, "iron_ore", IRON_CHANCE)) {
                return BlockType.IRON_ORE;
            }
        }

        if (biome == BiomeType.RED_SAND_DESERT && y > CRYSTAL_MIN_Y && y < crystalMaxY &&
            (current == BlockType.RED_SAND || current == BlockType.STONE || current == BlockType.MAGMA) &&
            rng.shouldGenerate3D(worldX, y, worldZ, "crystal", CRYSTAL_CHANCE)) {
            return BlockType.CRYSTAL;
        }

        return null;
    }
}
