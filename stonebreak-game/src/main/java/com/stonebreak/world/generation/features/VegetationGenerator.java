package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TreeGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles vegetation generation including trees and flowers.
 * Delegates tree generation to TreeGenerator and handles flower placement.
 *
 * Follows Single Responsibility Principle - only handles vegetation.
 */
public class VegetationGenerator {
    private final DeterministicRandom deterministicRandom;
    private final Object treeRandomLock = new Object();

    /**
     * Creates a new vegetation generator with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public VegetationGenerator(long seed) {
        this.deterministicRandom = new DeterministicRandom(seed);
    }

    /**
     * Generates vegetation (trees and flowers) in the chunk based on biome.
     *
     * @param world The world instance
     * @param chunk The chunk to populate with vegetation
     * @param biome The biome type
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     */
    public void generateVegetation(World world, Chunk chunk, BiomeType biome, int chunkX, int chunkZ) {
        int chunkSize = WorldConfiguration.CHUNK_SIZE;

        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                int worldX = chunkX * chunkSize + x;
                int worldZ = chunkZ * chunkSize + z;

                int surfaceHeight = findSurfaceHeight(chunk, x, z);
                if (surfaceHeight == 0) continue;

                // Generate trees based on biome
                generateTreesForBiome(world, chunk, biome, x, z, worldX, worldZ, surfaceHeight);

                // Generate flowers in PLAINS biome
                generateFlowers(world, chunk, biome, x, z, worldX, worldZ, surfaceHeight);
            }
        }
    }

    /**
     * Generates trees based on biome type.
     */
    private void generateTreesForBiome(World world, Chunk chunk, BiomeType biome, int x, int z,
                                       int worldX, int worldZ, int surfaceHeight) {
        if (biome == BiomeType.PLAINS) {
            if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS) {
                if (deterministicRandom.shouldGenerate(worldX, worldZ, "tree", 0.01f) && surfaceHeight > 64) {
                    // Determine tree type: 60% regular oak, 40% elm trees
                    boolean shouldGenerateElm = deterministicRandom.shouldGenerate(worldX, worldZ, "tree_type", 0.4f);

                    if (shouldGenerateElm) {
                        TreeGenerator.generateElmTree(world, chunk, x, surfaceHeight, z,
                            deterministicRandom.getRandomForPosition(worldX, worldZ, "elm_tree"), treeRandomLock);
                    } else {
                        TreeGenerator.generateTree(world, chunk, x, surfaceHeight, z);
                    }
                }
            }
        } else if (biome == BiomeType.SNOWY_PLAINS) {
            if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT) {
                if (deterministicRandom.shouldGenerate(worldX, worldZ, "pine_tree", 0.015f) && surfaceHeight > 64) {
                    TreeGenerator.generatePineTree(world, chunk, x, surfaceHeight, z);
                }
            }
        }
    }

    /**
     * Generates flowers on grass surfaces in PLAINS biome.
     */
    private void generateFlowers(World world, Chunk chunk, BiomeType biome, int x, int z,
                                  int worldX, int worldZ, int surfaceHeight) {
        if (biome != BiomeType.PLAINS || surfaceHeight <= 64 || surfaceHeight >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }

        if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
            if (deterministicRandom.shouldGenerate(worldX, worldZ, "flower", 0.08f)) {
                BlockType flowerType = deterministicRandom.getBoolean(worldX, worldZ, "flower_type")
                    ? BlockType.ROSE
                    : BlockType.DANDELION;
                world.setBlockAt(worldX, surfaceHeight, worldZ, flowerType);
            }
        }
    }

    /**
     * Finds the surface height for a given column in the chunk.
     *
     * @param chunk The chunk to scan
     * @param x Local X coordinate in chunk
     * @param z Local Z coordinate in chunk
     * @return Surface height (first air block above solid ground), or 0 if not found
     */
    private int findSurfaceHeight(Chunk chunk, int x, int z) {
        for (int yScan = WorldConfiguration.WORLD_HEIGHT - 1; yScan >= 0; yScan--) {
            if (chunk.getBlock(x, yScan, z) != BlockType.AIR) {
                return yScan + 1;
            }
        }
        // Edge case: column is solid to y=0
        if (chunk.getBlock(x, 0, z) != BlockType.AIR) {
            return 1;
        }
        return 0;
    }
}
