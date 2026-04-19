package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
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

    private final DeterministicRandom rng;
    private final HeightMapGenerator heightMap;
    private final BiomeManager biomeManager;

    public SurfaceDecorationGenerator(DeterministicRandom rng, HeightMapGenerator heightMap,
                                      BiomeManager biomeManager) {
        this.rng = rng;
        this.heightMap = heightMap;
        this.biomeManager = biomeManager;
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
            ctx.chunk.setBlock(x, surface, z, BlockType.SNOW);
            int layers = 1 + rng.getInt(worldX, worldZ, "snow_layers", 3);
            ctx.snowLayerManager.setSnowLayers(worldX, surface, worldZ, layers);
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

    private void placeOnSurface(World world, int worldX, int worldZ, BlockType expected, BlockType replacement) {
        int y = heightMap.generateHeight(worldX, worldZ, biomeManager) - 1;
        if (world.getBlockAt(worldX, y, worldZ) == expected) {
            world.setBlockAt(worldX, y, worldZ, replacement);
        }
    }
}
