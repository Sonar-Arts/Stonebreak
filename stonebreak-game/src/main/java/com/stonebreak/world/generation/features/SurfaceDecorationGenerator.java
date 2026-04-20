package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Surface decorations: gravel, ice, snow, clay - biome-specific overlays.
 */
public class SurfaceDecorationGenerator {
    private static final int MIN_SURFACE_Y = 64;
    private static final float GRAVEL_CHANCE = 0.0015f;
    private static final float ICE_CHANCE = 0.03f;
    private static final float SNOW_CHANCE = 0.08f; // cumulative threshold (>= ICE_CHANCE)
    private static final float CLAY_CHANCE = 0.0012f;
    private static final float TUNDRA_ICE_CHANCE = 0.01f;
    private static final float TUNDRA_SNOW_CHANCE = 0.55f; // cumulative threshold (>= TUNDRA_ICE_CHANCE)
    private static final float TAIGA_SNOW_CHANCE = 0.03f;
    private static final float STONY_PEAKS_GRAVEL_CHANCE = 0.05f;
    private static final float STONY_PEAKS_COAL_CHANCE = 0.08f; // cumulative
    private static final float ICE_FIELDS_SNOW_CHANCE = 0.6f;
    private static final float BADLANDS_CLAY_CHANCE = 0.03f;
    private static final float BADLANDS_GRAVEL_CHANCE = 0.05f; // cumulative
    private static final float BADLANDS_COBBLE_CHANCE = 0.06f; // cumulative

    private final DeterministicRandom rng;
    private final HeightMapGenerator heightMap;

    public SurfaceDecorationGenerator(DeterministicRandom rng, HeightMapGenerator heightMap,
                                      long worldSeed) {
        this.rng = rng;
        this.heightMap = heightMap;
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

                switch (biome) {
                    case DESERT -> generateGravel(ctx, x, z, worldX, worldZ, surfaceBlock);
                    case SNOWY_PLAINS -> generateIceAndSnow(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
                    case RED_SAND_DESERT -> generateClay(ctx.world, worldX, worldZ, surfaceBlock);
                    case TUNDRA -> generateTundraIce(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
                    case TAIGA -> generateTaigaSnow(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
                    case STONY_PEAKS -> generateStonyPeakFeatures(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
                    case ICE_FIELDS -> generateGlacialSnow(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
                    case BADLANDS -> generateBadlandsBands(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
                    default -> { /* none */ }
                }
            }
        }
    }

    private void generateGravel(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.SAND ||
            !rng.shouldGenerate(worldX, worldZ, "gravel_patch", GRAVEL_CHANCE)) {
            return;
        }
        int radius = 4 + rng.getInt(worldX, worldZ, "gravel_patch_size", 3);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) continue;

                int px = worldX + dx;
                int pz = worldZ + dz;
                float placeChance = 1.0f - (float) (distance / radius) * 0.35f;
                if (rng.getFloat(px, pz, "gravel_place") < placeChance) {
                    placeOnSurface(ctx.world, px, pz, BlockType.SAND, BlockType.GRAVEL);
                }
            }
        }
    }

    private void generateIceAndSnow(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                    int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.SNOWY_DIRT || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        float roll = rng.getFloat(worldX, worldZ, "snow_ice_feature");
        if (roll < ICE_CHANCE) {
            ctx.chunk.setBlock(x, surface, z, BlockType.ICE);
        } else if (roll < SNOW_CHANCE) {
            placeSnowLayers(ctx, x, z, worldX, worldZ, surface, 1, 3);
        }
    }

    private void generateClay(World world, int worldX, int worldZ, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.RED_SAND ||
            !rng.shouldGenerate(worldX, worldZ, "clay_patch", CLAY_CHANCE)) {
            return;
        }
        int radius = 4 + rng.getInt(worldX, worldZ, "clay_patch_size", 3);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) continue;

                int px = worldX + dx;
                int pz = worldZ + dz;
                float placeChance = 1.0f - (float) (distance / radius) * 0.4f;
                if (rng.getFloat(px, pz, "clay_place") < placeChance) {
                    placeOnSurface(world, px, pz, BlockType.RED_SAND, BlockType.CLAY);
                }
            }
        }
    }

    private void generateTundraIce(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                   int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.SNOWY_DIRT || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        float roll = rng.getFloat(worldX, worldZ, "tundra_feature");
        if (roll < TUNDRA_ICE_CHANCE) {
            ctx.chunk.setBlock(x, surface, z, BlockType.ICE);
        } else if (roll < TUNDRA_SNOW_CHANCE) {
            placeSnowLayers(ctx, x, z, worldX, worldZ, surface, 1, 3);
        }
    }

    private void generateTaigaSnow(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                   int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.SNOWY_DIRT || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        if (rng.shouldGenerate(worldX, worldZ, "taiga_snow", TAIGA_SNOW_CHANCE)) {
            placeSnowLayers(ctx, x, z, worldX, worldZ, surface, 1, 2);
        }
    }

    private void generateStonyPeakFeatures(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                           int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.STONE || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        float roll = rng.getFloat(worldX, worldZ, "stony_peaks_feature");
        if (roll < STONY_PEAKS_GRAVEL_CHANCE) {
            ctx.chunk.setBlock(x, surface - 1, z, BlockType.GRAVEL);
        } else if (roll < STONY_PEAKS_COAL_CHANCE) {
            ctx.chunk.setBlock(x, surface - 1, z, BlockType.COAL_ORE);
        }
    }

    private void generateGlacialSnow(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                     int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.ICE || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        if (rng.getFloat(worldX, worldZ, "glacial_feature") < ICE_FIELDS_SNOW_CHANCE) {
            placeSnowLayers(ctx, x, z, worldX, worldZ, surface, 2, 3);
        }
    }

    private void generateBadlandsBands(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                       int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.RED_SAND || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        float roll = rng.getFloat(worldX, worldZ, "badlands_feature");
        BlockType replacement;
        if (roll < BADLANDS_CLAY_CHANCE) {
            replacement = BlockType.CLAY;
        } else if (roll < BADLANDS_GRAVEL_CHANCE) {
            replacement = BlockType.GRAVEL;
        } else if (roll < BADLANDS_COBBLE_CHANCE) {
            replacement = BlockType.RED_SAND_COBBLESTONE;
        } else {
            return;
        }
        ctx.chunk.setBlock(x, surface - 1, z, replacement);
    }

    private void placeSnowLayers(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                 int surface, int minLayers, int extraBound) {
        ctx.chunk.setBlock(x, surface, z, BlockType.SNOW);
        int layers = minLayers + rng.getInt(worldX, worldZ, "snow_layers", extraBound);
        ctx.snowLayerManager.setSnowLayers(worldX, surface, worldZ, layers);
    }

    private void placeOnSurface(World world, int worldX, int worldZ, BlockType expected, BlockType replacement) {
        int y = heightMap.generateHeight(worldX, worldZ) - 1;
        if (world.getBlockAt(worldX, y, worldZ) == expected) {
            world.setBlockAt(worldX, y, worldZ, replacement);
        }
    }
}
