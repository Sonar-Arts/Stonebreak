package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TreeGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.biomes.BiomeVariationRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles vegetation generation including trees and flowers.
 * Delegates tree generation to TreeGenerator and handles flower placement.
 *
 * Follows Single Responsibility Principle - only handles vegetation.
 */
public class VegetationGenerator {
    private final DeterministicRandom deterministicRandom;
    private final BiomeVariationRouter variationRouter;
    private final Object treeRandomLock = new Object();

    /**
     * Creates a new vegetation generator with the given seed and variation router.
     *
     * @param seed World seed for deterministic generation
     * @param variationRouter Biome variation router for position-based feature variation
     */
    public VegetationGenerator(long seed, BiomeVariationRouter variationRouter) {
        this.deterministicRandom = new DeterministicRandom(seed);
        this.variationRouter = variationRouter;
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

        // Get surface height cache for performance optimization
        int[][] surfaceCache = chunk.getSurfaceHeightCache();

        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                int worldX = chunkX * chunkSize + x;
                int worldZ = chunkZ * chunkSize + z;

                // Use cached surface height (eliminates O(256) scan per column)
                // Cache stores highest solid block Y, add 1 for first air block above
                int surfaceHeight = (surfaceCache != null)
                    ? surfaceCache[x][z] + 1
                    : findSurfaceHeight(chunk, x, z);
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
     *
     * Phase 4: Added vegetation for TAIGA and TUNDRA biomes.
     */
    private void generateTreesForBiome(World world, Chunk chunk, BiomeType biome, int x, int z,
                                       int worldX, int worldZ, int surfaceHeight) {
        if (biome == BiomeType.PLAINS) {
            if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS) {
                // Base density: 1% (0.01f)
                float baseDensity = 0.01f;

                // Apply variation: [0.4%, 1.6%] range
                float densityMultiplier = variationRouter.getDensityMultiplier(worldX, worldZ);
                float variedDensity = baseDensity * densityMultiplier;

                if (deterministicRandom.shouldGenerate(worldX, worldZ, "tree", variedDensity) && surfaceHeight > 64) {
                    // Base oak probability: 60% (elm = 40%)
                    float baseOakProbability = 0.6f;

                    // Apply type ratio shift: [30%, 90%] range
                    float ratioShift = variationRouter.getTypeRatioShift(worldX, worldZ);
                    float variedOakProbability = Math.max(0.1f, Math.min(0.9f, baseOakProbability + ratioShift));

                    // Determine tree type
                    boolean shouldGenerateOak = deterministicRandom.shouldGenerate(worldX, worldZ, "tree_type", variedOakProbability);

                    if (shouldGenerateOak) {
                        TreeGenerator.generateTree(world, chunk, x, surfaceHeight, z);
                    } else {
                        TreeGenerator.generateElmTree(world, chunk, x, surfaceHeight, z,
                            deterministicRandom.getRandomForPosition(worldX, worldZ, "elm_tree"), treeRandomLock);
                    }
                }
            }
        } else if (biome == BiomeType.SNOWY_PLAINS) {
            if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT) {
                float baseDensity = 0.015f;
                float densityMultiplier = variationRouter.getDensityMultiplier(worldX, worldZ);
                float variedDensity = baseDensity * densityMultiplier;

                if (deterministicRandom.shouldGenerate(worldX, worldZ, "pine_tree", variedDensity) && surfaceHeight > 64) {
                    TreeGenerator.generatePineTree(world, chunk, x, surfaceHeight, z);
                }
            }
        }
        // Phase 4: New biome vegetation
        else if (biome == BiomeType.TAIGA) {
            // Dense pine forest (3% spawn rate - denser than SNOWY_PLAINS)
            if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT) {
                float baseDensity = 0.03f;
                float densityMultiplier = variationRouter.getDensityMultiplier(worldX, worldZ);
                float variedDensity = baseDensity * densityMultiplier;

                if (deterministicRandom.shouldGenerate(worldX, worldZ, "taiga_pine_tree", variedDensity) && surfaceHeight > 64) {
                    TreeGenerator.generatePineTree(world, chunk, x, surfaceHeight, z);
                }
            }
        } else if (biome == BiomeType.TUNDRA) {
            // Very sparse pine trees (0.3% spawn rate - survival trees)
            if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRAVEL ||
                chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.STONE) {
                float baseDensity = 0.003f;
                float densityMultiplier = variationRouter.getDensityMultiplier(worldX, worldZ);
                float variedDensity = baseDensity * densityMultiplier;

                if (deterministicRandom.shouldGenerate(worldX, worldZ, "tundra_pine_tree", variedDensity) && surfaceHeight > 64) {
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
