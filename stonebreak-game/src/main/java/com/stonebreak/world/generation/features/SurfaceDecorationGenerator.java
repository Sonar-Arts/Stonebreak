package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Surface decorations: gravel, ice, snow, clay - biome-specific overlays.
 */
public class SurfaceDecorationGenerator {
    private static final int MIN_SURFACE_Y = 64;
    private static final float GRAVEL_CHANCE = 0.01f;
    private static final float ICE_CHANCE = 0.03f;
    private static final float SNOW_CHANCE = 0.08f; // cumulative threshold (>= ICE_CHANCE)
    private static final float CLAY_CHANCE = 0.008f;
    private static final float TUNDRA_ICE_CHANCE = 0.01f;
    private static final float TAIGA_SNOW_CHANCE = 0.03f;
    private static final float STONY_PEAKS_GRAVEL_CHANCE = 0.05f;
    private static final float STONY_PEAKS_COAL_CHANCE = 0.08f; // cumulative
    // Beach banding: simplex-noise thresholds produce smooth gravel/sand waves
    // instead of salt-and-pepper mixing. Scale sets wave wavelength (~1/scale blocks);
    // threshold shifts the gravel/sand balance (positive = more gravel).
    private static final float BEACH_NOISE_SCALE = 0.09f;
    private static final float BEACH_SAND_THRESHOLD = 0.1f;
    private static final long BEACH_NOISE_SEED_SALT = 0x8EAC51A8D1L;
    private static final float ICE_FIELDS_SNOW_CHANCE = 0.6f;
    private static final float BADLANDS_CLAY_CHANCE = 0.03f;
    private static final float BADLANDS_GRAVEL_CHANCE = 0.05f; // cumulative
    private static final float BADLANDS_COBBLE_CHANCE = 0.06f; // cumulative

    private final DeterministicRandom rng;
    private final HeightMapGenerator heightMap;
    private final BiomeManager biomeManager;
    private final NoiseGenerator beachNoise;

    public SurfaceDecorationGenerator(DeterministicRandom rng, HeightMapGenerator heightMap,
                                      BiomeManager biomeManager, long worldSeed) {
        this.rng = rng;
        this.heightMap = heightMap;
        this.biomeManager = biomeManager;
        this.beachNoise = new NoiseGenerator(worldSeed ^ BEACH_NOISE_SEED_SALT);
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
                    case GRAVEL_BEACH -> generateBeachSand(ctx, x, z, worldX, worldZ, surface, surfaceBlock);
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
        int patchSize = rng.getBoolean(worldX, worldZ, "gravel_patch_size") ? 2 : 3;
        int half = patchSize / 2;
        for (int dx = 0; dx < patchSize; dx++) {
            for (int dz = 0; dz < patchSize; dz++) {
                int px = worldX + dx - half;
                int pz = worldZ + dz - half;
                placeOnSurface(ctx.world, px, pz, BlockType.SAND, BlockType.GRAVEL);
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
        int radius = 1 + (rng.getBoolean(worldX, worldZ, "clay_patch_size") ? 1 : 0);
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
        if (surfaceBlock != BlockType.GRAVEL || ctx.chunk.getBlock(x, surface, z) != BlockType.AIR) {
            return;
        }
        if (rng.getFloat(worldX, worldZ, "tundra_feature") < TUNDRA_ICE_CHANCE) {
            ctx.chunk.setBlock(x, surface, z, BlockType.ICE);
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

    private void generateBeachSand(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ,
                                   int surface, BlockType surfaceBlock) {
        if (surfaceBlock != BlockType.GRAVEL) {
            return;
        }
        float n = beachNoise.noise(worldX * BEACH_NOISE_SCALE, worldZ * BEACH_NOISE_SCALE);
        if (n > BEACH_SAND_THRESHOLD) {
            ctx.chunk.setBlock(x, surface - 1, z, BlockType.SAND);
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
        int y = heightMap.generateHeight(worldX, worldZ, biomeManager) - 1;
        if (world.getBlockAt(worldX, y, worldZ) == expected) {
            world.setBlockAt(worldX, y, worldZ, replacement);
        }
    }
}
