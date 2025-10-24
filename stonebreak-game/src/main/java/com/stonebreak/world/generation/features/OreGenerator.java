package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Handles ore generation in stone layers.
 * Generates coal, iron, and crystal ores based on biome and depth.
 *
 * Follows Single Responsibility Principle - only handles ore placement.
 */
public class OreGenerator {
    private final DeterministicRandom deterministicRandom;

    /**
     * Creates a new ore generator with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public OreGenerator(long seed) {
        this.deterministicRandom = new DeterministicRandom(seed);
    }

    /**
     * Generates ores in the chunk based on biome and depth.
     *
     * @param world The world instance
     * @param chunk The chunk to populate with ores
     * @param biome The biome type
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param surfaceHeight Surface height at current position
     */
    public void generateOres(World world, Chunk chunk, BiomeType biome, int chunkX, int chunkZ, int surfaceHeight) {
        int chunkSize = 16; // WorldConfiguration.CHUNK_SIZE

        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                int worldX = chunkX * chunkSize + x;
                int worldZ = chunkZ * chunkSize + z;

                // Generate ores from y=1 up to just below surface
                for (int y = 1; y < surfaceHeight - 1; y++) {
                    BlockType currentBlock = chunk.getBlock(x, y, z);
                    BlockType oreType = determineOreType(worldX, y, worldZ, currentBlock, biome);

                    if (oreType != null) {
                        world.setBlockAt(worldX, y, worldZ, oreType);
                    }
                }
            }
        }
    }

    /**
     * Determines which ore type (if any) should be placed at the given position.
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @param currentBlock Current block type at this position
     * @param biome Biome type
     * @return Ore block type, or null if no ore should be placed
     */
    private BlockType determineOreType(int worldX, int y, int worldZ, BlockType currentBlock, BiomeType biome) {
        // Coal and iron ores in stone
        if (currentBlock == BlockType.STONE) {
            if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "coal_ore", 0.015f)) {
                return BlockType.COAL_ORE;
            } else if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "iron_ore", 0.008f) && y < 50) {
                return BlockType.IRON_ORE;
            }
        }

        // Crystal generation in volcanic biomes (RED_SAND_DESERT)
        if (biome == BiomeType.RED_SAND_DESERT) {
            if (currentBlock == BlockType.RED_SAND || currentBlock == BlockType.STONE || currentBlock == BlockType.MAGMA) {
                if (y > 20 && deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "crystal", 0.02f)) {
                    return BlockType.CRYSTAL;
                }
            }
        }

        return null;
    }
}
