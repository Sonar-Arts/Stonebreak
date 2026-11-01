package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.growth.BlueberryBushGrowthSystem;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Places wild blueberry bushes in plains, in groups of 1 to 3.
 *
 * <p>Same roll-and-write shape as {@link CactusGenerator}: a per-column loop gated on the biome
 * and the real ground block, with deterministic per-position rolls. A column that wins the
 * group roll anchors a group; its other members are rolled around it and kept only where they
 * land on open plains grass inside this chunk (so a group at a chunk edge may come out smaller).
 *
 * <p>Wild bushes are already grown. Each one starts at a random berry stage
 * (Mature through Ripe) with its timer written into its block state, so the growth system
 * carries on from there; it picks the bushes up when the chunk's features finish
 * ({@code World.onChunkFeaturesPopulated}).
 */
public class BlueberryBushGenerator {
    /** Chance a plains grass column anchors a group (about one group per four plains chunks). */
    public static final float GROUP_CHANCE = 0.001f;
    public static final int MIN_GROUP = 1;
    public static final int MAX_GROUP = 3;
    /** Members land within this many blocks of the anchor on each axis. */
    static final int GROUP_RADIUS = 2;

    /** Stages a wild bush can start in: grown, at any point of its berry cycle. */
    private static final Stage[] WILD_STAGES = {
        Stage.MATURE, Stage.BERRIES_UNRIPE, Stage.BERRIES_RIPENING, Stage.BERRIES_RIPE
    };

    private final DeterministicRandom rng;

    public BlueberryBushGenerator(DeterministicRandom rng) {
        this.rng = rng;
    }

    public void generate(ChunkGenerationContext ctx) {
        for (int x = 0; x < ChunkGenerationContext.SIZE; x++) {
            for (int z = 0; z < ChunkGenerationContext.SIZE; z++) {
                if (!canHoldBush(ctx, x, z)) {
                    continue;
                }
                int worldX = ctx.worldX(x);
                int worldZ = ctx.worldZ(z);
                if (rng.getFloat(worldX, worldZ, "blueberry_group") >= GROUP_CHANCE) {
                    continue;
                }
                placeGroup(ctx, x, z, worldX, worldZ);
            }
        }
    }

    private void placeGroup(ChunkGenerationContext ctx, int x, int z, int worldX, int worldZ) {
        int size = MIN_GROUP + rng.getInt(worldX, worldZ, "blueberry_group_size", MAX_GROUP - MIN_GROUP + 1);
        placeBush(ctx, x, z);
        int span = GROUP_RADIUS * 2 + 1;
        for (int i = 1; i < size; i++) {
            int dx = rng.getInt(worldX, worldZ, "blueberry_dx" + i, span) - GROUP_RADIUS;
            int dz = rng.getInt(worldX, worldZ, "blueberry_dz" + i, span) - GROUP_RADIUS;
            int mx = x + dx;
            int mz = z + dz;
            if ((dx != 0 || dz != 0) && inChunk(mx, mz) && canHoldBush(ctx, mx, mz)) {
                placeBush(ctx, mx, mz);
            }
        }
    }

    /** Open plains grass with two free cells above it (the grown bush stands 1.25 tall). */
    private static boolean canHoldBush(ChunkGenerationContext ctx, int x, int z) {
        if (ctx.biome(x, z) != BiomeType.PLAINS) {
            return false;
        }
        int surface = ctx.height(x, z);
        if (surface + 1 >= WorldConfiguration.WORLD_HEIGHT || ctx.isSubmerged(x, z)) {
            return false;
        }
        Chunk chunk = ctx.chunk;
        return chunk.getBlock(x, surface - 1, z) == BlockType.GRASS
            && chunk.getBlock(x, surface, z) == BlockType.AIR
            && chunk.getBlock(x, surface + 1, z) == BlockType.AIR;
    }

    private void placeBush(ChunkGenerationContext ctx, int x, int z) {
        int surface = ctx.height(x, z);
        int worldX = ctx.worldX(x);
        int worldZ = ctx.worldZ(z);
        Stage stage = WILD_STAGES[rng.getInt(worldX, worldZ, "blueberry_stage", WILD_STAGES.length)];
        int timer = BlueberryBushGrowthSystem.timerFor(stage, rng.getFloat(worldX, worldZ, "blueberry_timer"));
        ctx.chunk.setBlock(x, surface, z, BlockType.BLUEBERRY_BUSH);
        ctx.chunk.setBlockState(x, surface, z, new BlueberryBushState(stage, timer).toStateString());
    }

    private static boolean inChunk(int x, int z) {
        return x >= 0 && x < ChunkGenerationContext.SIZE && z >= 0 && z < ChunkGenerationContext.SIZE;
    }
}
