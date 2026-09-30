package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Places sparse cacti on the surface column of deserts and red sand deserts.
 *
 * <p>Roll-and-write in the shape of {@code OreGenerator.generateCrystals}: a per-column
 * loop gated on the biome, a ground check against the real surface block, and
 * deterministic per-position rolls. Deserts are otherwise treeless (the vegetation
 * generator has no desert branch), so this is the only vegetation that grows there.
 *
 * <p>A cactus is <b>per-cell stacked</b> — every cell of the formation holds the block,
 * following the same rule flowers ride — so a height-3 cactus is three stamps and the
 * mesher's instance cull policy drops the lower stamp's top face when a cactus sits
 * above it. Heights roll between 2 and 3 and are truncated by headroom (a cell in the
 * way stops the stack, so carvers or water above can never clip through a cactus).
 */
public class CactusGenerator {
    /** Same as {@link VegetationGenerator#TUNDRA_PINE_CHANCE} — the codebase's sparse reference. */
    public static final float CACTUS_CHANCE = 0.003f;
    /** Formation heights, in cells above the surface block. */
    public static final int MIN_HEIGHT = 2;
    public static final int MAX_HEIGHT = 3;

    /** Second roll salt, so formation height varies independently of the placement roll. */
    private static final String HEIGHT_SALT = "cactus_height";

    private final DeterministicRandom rng;

    public CactusGenerator(DeterministicRandom rng) {
        this.rng = rng;
    }

    public void generate(ChunkGenerationContext ctx) {
        Chunk chunk = ctx.chunk;
        for (int x = 0; x < ChunkGenerationContext.SIZE; x++) {
            for (int z = 0; z < ChunkGenerationContext.SIZE; z++) {
                BiomeType biome = ctx.biome(x, z);
                if (biome != BiomeType.DESERT && biome != BiomeType.RED_SAND_DESERT) {
                    continue;
                }
                int surface = ctx.height(x, z);
                // Same surface gate VegetationGenerator uses: this terrain's surfaces run
                // hundreds of blocks up, so the old y > 64 floor means nothing here, and a
                // submerged column is what actually has to be skipped.
                if (surface >= WorldConfiguration.WORLD_HEIGHT || ctx.isSubmerged(x, z)) {
                    continue;
                }
                BlockType ground = chunk.getBlock(x, surface - 1, z);
                if (ground != BlockType.SAND && ground != BlockType.RED_SAND) {
                    continue;
                }
                int worldX = ctx.worldX(x);
                int worldZ = ctx.worldZ(z);
                if (rng.getFloat(worldX, worldZ, "cactus") >= CACTUS_CHANCE) {
                    continue;
                }
                int height = rollHeight(worldX, worldZ);
                for (int i = 0; i < height && surface + i < WorldConfiguration.WORLD_HEIGHT; i++) {
                    if (chunk.getBlock(x, surface + i, z) != BlockType.AIR) {
                        break; // headroom truncated — never clip through a carver or water
                    }
                    chunk.setBlock(x, surface + i, z, BlockType.CACTUS);
                }
            }
        }
    }

    /**
     * Deterministic formation height between {@link #MIN_HEIGHT} and {@link #MAX_HEIGHT}.
     * Even split: the two-tall and three-tall forms read as the same plant at different
     * growth, and a deterministic roll keeps LOD probes and real placement agreeing.
     */
    private int rollHeight(int worldX, int worldZ) {
        return rng.getFloat(worldX, worldZ, HEIGHT_SALT) < 0.5f ? MIN_HEIGHT : MAX_HEIGHT;
    }
}
