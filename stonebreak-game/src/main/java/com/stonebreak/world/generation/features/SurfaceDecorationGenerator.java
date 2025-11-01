package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Random;

/**
 * Handles surface decoration generation including gravel patches, clay patches,
 * ice patches, and snow layers based on biome.
 *
 * Follows Single Responsibility Principle - only handles surface decorations.
 */
public class SurfaceDecorationGenerator {
    private final DeterministicRandom deterministicRandom;
    private final Random random;
    private final Object randomLock = new Object();

    /**
     * Creates a new surface decoration generator with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public SurfaceDecorationGenerator(long seed) {
        this.deterministicRandom = new DeterministicRandom(seed);
        this.random = new Random(seed);
    }

    /**
     * Generates surface decorations in the chunk based on biome.
     *
     * @param world The world instance
     * @param chunk The chunk to populate with decorations
     * @param biome The biome type
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param snowLayerManager Manager for snow layer tracking
     */
    public void generateDecorations(World world, Chunk chunk, BiomeType biome, int chunkX, int chunkZ,
                                     SnowLayerManager snowLayerManager) {
        int chunkSize = WorldConfiguration.CHUNK_SIZE;

        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                int worldX = chunkX * chunkSize + x;
                int worldZ = chunkZ * chunkSize + z;

                int surfaceHeight = findSurfaceHeight(chunk, x, z);
                if (surfaceHeight == 0 || surfaceHeight >= WorldConfiguration.WORLD_HEIGHT) continue;

                // Generate biome-specific decorations
                switch (biome) {
                    case DESERT:
                        generateGravelPatches(world, chunk, x, z, worldX, worldZ, surfaceHeight);
                        break;
                    case SNOWY_PLAINS:
                        generateIceAndSnow(world, chunk, x, z, worldX, worldZ, surfaceHeight, snowLayerManager);
                        break;
                    case RED_SAND_DESERT:
                        generateClayPatches(world, chunk, x, z, worldX, worldZ, surfaceHeight);
                        break;

                    // Phase 4: New biome decorations
                    case TUNDRA:
                        generateTundraFeatures(world, chunk, x, z, worldX, worldZ, surfaceHeight, snowLayerManager);
                        break;
                    case TAIGA:
                        generateTaigaSnow(world, chunk, x, z, worldX, worldZ, surfaceHeight, snowLayerManager);
                        break;
                    case STONY_PEAKS:
                        generateStonesAndOres(world, chunk, x, z, worldX, worldZ, surfaceHeight);
                        break;
                    case GRAVEL_BEACH:
                        generateBeachMixture(world, chunk, x, z, worldX, worldZ, surfaceHeight);
                        break;
                    case ICE_FIELDS:
                        generateGlacialFeatures(world, chunk, x, z, worldX, worldZ, surfaceHeight, snowLayerManager);
                        break;
                    case BADLANDS:
                        generateBadlandsFeatures(world, chunk, x, z, worldX, worldZ, surfaceHeight);
                        break;

                    default:
                        // No decorations for other biomes currently
                        break;
                }
            }
        }
    }

    /**
     * Generates gravel patches on sand surfaces in DESERT biome.
     */
    private void generateGravelPatches(World world, Chunk chunk, int x, int z,
                                       int worldX, int worldZ, int surfaceHeight) {
        if (surfaceHeight <= 64) return;

        if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SAND) {
            if (deterministicRandom.shouldGenerate(worldX, worldZ, "gravel_patch", 0.01f)) {
                // Create small gravel patches (2x2 or 3x3)
                int patchSize = deterministicRandom.getBoolean(worldX, worldZ, "gravel_patch_size") ? 2 : 3;

                for (int dx = 0; dx < patchSize; dx++) {
                    for (int dz = 0; dz < patchSize; dz++) {
                        int patchWorldX = worldX + dx - patchSize / 2;
                        int patchWorldZ = worldZ + dz - patchSize / 2;
                        int patchY = findHeightAt(world, patchWorldX, patchWorldZ) - 1;

                        // Only place gravel if the spot is sand
                        if (world.getBlockAt(patchWorldX, patchY, patchWorldZ) == BlockType.SAND) {
                            world.setBlockAt(patchWorldX, patchY, patchWorldZ, BlockType.GRAVEL);
                        }
                    }
                }
            }
        }
    }

    /**
     * Generates ice patches and snow layers in SNOWY_PLAINS biome.
     */
    private void generateIceAndSnow(World world, Chunk chunk, int x, int z,
                                    int worldX, int worldZ, int surfaceHeight,
                                    SnowLayerManager snowLayerManager) {
        if (surfaceHeight <= 64) return;

        if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
            float featureChance = deterministicRandom.getFloat(worldX, worldZ, "snow_ice_feature");

            if (featureChance < 0.03f) { // 3% chance for ice patches
                world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.ICE);
            } else if (featureChance < 0.08f) { // Additional 5% chance for snow layers
                world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                // Set initial snow layer count (1-3 layers randomly)
                int layers = 1 + deterministicRandom.getInt(worldX, worldZ, "snow_layers", 3);
                snowLayerManager.setSnowLayers(worldX, surfaceHeight, worldZ, layers);
            }
        }
    }

    /**
     * Generates clay patches in RED_SAND_DESERT biome.
     */
    private void generateClayPatches(World world, Chunk chunk, int x, int z,
                                     int worldX, int worldZ, int surfaceHeight) {
        if (surfaceHeight <= 64) return;

        if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.RED_SAND) {
            float clayChance;
            synchronized (randomLock) {
                clayChance = random.nextFloat();
            }

            if (clayChance < 0.008f) { // 0.8% chance for clay patches (much rarer)
                // Create organic-looking clay patches
                int centerX = worldX;
                int centerZ = worldZ;
                int radius;
                synchronized (randomLock) {
                    radius = 1 + random.nextInt(2); // Radius 1-2 blocks
                }

                // Generate circular/organic patch with some randomness
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int patchWorldX = centerX + dx;
                        int patchWorldZ = centerZ + dz;

                        // Calculate distance from center
                        double distance = Math.sqrt(dx * dx + dz * dz);

                        // Only place clay within radius with some randomness for organic shape
                        if (distance <= radius) {
                            float placeChance;
                            synchronized (randomLock) {
                                // Higher chance near center, lower chance at edges
                                placeChance = 1.0f - (float) (distance / radius) * 0.4f;
                                if (random.nextFloat() < placeChance) {
                                    int patchY = findHeightAt(world, patchWorldX, patchWorldZ) - 1;

                                    // Only place clay if the spot is red sand
                                    if (world.getBlockAt(patchWorldX, patchY, patchWorldZ) == BlockType.RED_SAND) {
                                        world.setBlockAt(patchWorldX, patchY, patchWorldZ, BlockType.CLAY);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== Phase 4: New Biome Decoration Methods ====================

    /**
     * Generates tundra features: snow layers on snowy dirt surface, sparse ice patches.
     */
    private void generateTundraFeatures(World world, Chunk chunk, int x, int z,
                                        int worldX, int worldZ, int surfaceHeight,
                                        SnowLayerManager snowLayerManager) {
        if (surfaceHeight <= 64) return;

        BlockType surfaceBlock = chunk.getBlock(x, surfaceHeight - 1, z);
        if (surfaceBlock == BlockType.SNOWY_DIRT &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {

            float featureChance = deterministicRandom.getFloat(worldX, worldZ, "tundra_feature");

            if (featureChance < 0.01f) { // 1% chance for ice patches
                world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.ICE);
            } else if (featureChance < 0.55f) { // 54% chance for snow layers (guaranteed coverage)
                world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                // Variable snow layer heights (1-3 layers)
                int layers = 1 + deterministicRandom.getInt(worldX, worldZ, "tundra_snow_layers", 3);
                snowLayerManager.setSnowLayers(worldX, surfaceHeight, worldZ, layers);
            }
        }
    }

    /**
     * Generates taiga snow layers (less dense than SNOWY_PLAINS).
     */
    private void generateTaigaSnow(World world, Chunk chunk, int x, int z,
                                   int worldX, int worldZ, int surfaceHeight,
                                   SnowLayerManager snowLayerManager) {
        if (surfaceHeight <= 64) return;

        if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {

            if (deterministicRandom.shouldGenerate(worldX, worldZ, "taiga_snow", 0.03f)) {
                world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                // 1-2 snow layers (less than SNOWY_PLAINS)
                int layers = 1 + deterministicRandom.getInt(worldX, worldZ, "taiga_snow_layers", 2);
                snowLayerManager.setSnowLayers(worldX, surfaceHeight, worldZ, layers);
            }
        }
    }

    /**
     * Generates stony peaks features: gravel slopes and exposed ores.
     */
    private void generateStonesAndOres(World world, Chunk chunk, int x, int z,
                                       int worldX, int worldZ, int surfaceHeight) {
        if (surfaceHeight <= 64) return;

        BlockType surfaceBlock = chunk.getBlock(x, surfaceHeight - 1, z);
        if ((surfaceBlock == BlockType.STONE || surfaceBlock == BlockType.COBBLESTONE) &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {

            float featureChance = deterministicRandom.getFloat(worldX, worldZ, "stony_peaks_feature");

            // 5% chance for gravel slopes
            if (featureChance < 0.05f) {
                world.setBlockAt(worldX, surfaceHeight - 1, worldZ, BlockType.GRAVEL);
            }
            // Surface coal ore exposure (more common in mountains)
            else if (featureChance < 0.08f && surfaceBlock == BlockType.STONE) {
                world.setBlockAt(worldX, surfaceHeight - 1, worldZ, BlockType.COAL_ORE);
            }
        }
    }

    /**
     * Generates gravel beach mixture of gravel and sand.
     */
    private void generateBeachMixture(World world, Chunk chunk, int x, int z,
                                      int worldX, int worldZ, int surfaceHeight) {
        if (surfaceHeight <= 60 || surfaceHeight >= 68) return; // Beach elevation range

        if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRAVEL) {
            // 40% of gravel patches become sand for mixture
            if (deterministicRandom.shouldGenerate(worldX, worldZ, "beach_sand_mix", 0.4f)) {
                world.setBlockAt(worldX, surfaceHeight - 1, worldZ, BlockType.SAND);
            }
        }
    }

    /**
     * Generates glacial features: dense ice blocks and multi-layer snow.
     */
    private void generateGlacialFeatures(World world, Chunk chunk, int x, int z,
                                         int worldX, int worldZ, int surfaceHeight,
                                         SnowLayerManager snowLayerManager) {
        if (surfaceHeight <= 64) return;

        BlockType surfaceBlock = chunk.getBlock(x, surfaceHeight - 1, z);
        if ((surfaceBlock == BlockType.ICE || surfaceBlock == BlockType.SNOW) &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {

            float featureChance = deterministicRandom.getFloat(worldX, worldZ, "glacial_feature");

            // 60% chance for heavy snow layers (glacial environment)
            if (featureChance < 0.6f) {
                world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                // 2-4 snow layers (deeper than other biomes)
                int layers = 2 + deterministicRandom.getInt(worldX, worldZ, "glacial_snow_layers", 3);
                snowLayerManager.setSnowLayers(worldX, surfaceHeight, worldZ, layers);
            }
        }
    }

    /**
     * Generates badlands features: clay bands and red sand cobblestone formations.
     */
    private void generateBadlandsFeatures(World world, Chunk chunk, int x, int z,
                                          int worldX, int worldZ, int surfaceHeight) {
        if (surfaceHeight <= 64) return;

        BlockType surfaceBlock = chunk.getBlock(x, surfaceHeight - 1, z);
        if ((surfaceBlock == BlockType.RED_SAND || surfaceBlock == BlockType.CLAY) &&
            chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {

            float featureChance = deterministicRandom.getFloat(worldX, worldZ, "badlands_feature");

            // 3% chance for clay bands (sedimentary layers)
            if (featureChance < 0.03f && surfaceBlock == BlockType.RED_SAND) {
                world.setBlockAt(worldX, surfaceHeight - 1, worldZ, BlockType.CLAY);
            }
            // 2% chance for gravel deposits
            else if (featureChance < 0.05f) {
                world.setBlockAt(worldX, surfaceHeight - 1, worldZ, BlockType.GRAVEL);
            }
            // 1% chance for red sand cobblestone (weathered rock)
            else if (featureChance < 0.06f && surfaceBlock == BlockType.RED_SAND) {
                world.setBlockAt(worldX, surfaceHeight - 1, worldZ, BlockType.RED_SAND_COBBLESTONE);
            }
        }
    }

    // ==================== Helper Methods ====================

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

    /**
     * Finds the terrain height at world coordinates.
     * Used for cross-chunk feature placement.
     *
     * @param world The world instance
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Terrain height at the given position
     */
    private int findHeightAt(World world, int worldX, int worldZ) {
        for (int y = WorldConfiguration.WORLD_HEIGHT - 1; y >= 0; y--) {
            if (world.getBlockAt(worldX, y, worldZ) != BlockType.AIR) {
                return y + 1;
            }
        }
        return 1;
    }
}
