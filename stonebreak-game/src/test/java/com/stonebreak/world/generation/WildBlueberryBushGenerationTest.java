package com.stonebreak.world.generation;

import com.openmason.engine.voxel.world.StreamCenter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Wild blueberry bushes come out of the native world generator (SBW ABI 6, Project-Heracles' BlueberryBushGenerator
 * ported to {@code native/src/features.cpp}) in a real streamed world: an open plains grass column that wins the
 * {@code blueberry_group} roll (the Java {@link DeterministicRandom} key the native generator shares) holds a grown
 * bush whose block state is one of the four wild stages, registered in the world's state table, and the bush growth
 * system picks it up as its cube installs. Native-side coverage of every world kind (Standard, DaedalusTGM-Exp profile
 * with and without vegetation channels) is {@code native/tests/blueberry_test.cpp}. Needs libstonebreak_worldgen.
 */
class WildBlueberryBushGenerationTest {

    private static final long SEED = 12345L;
    private static final long TIMEOUT_NANOS = 120_000_000_000L;
    /** {@code BB_GROUP_CHANCE} in features.cpp. */
    private static final float GROUP_CHANCE = 0.001f;
    private static final Set<String> WILD_STAGES = Set.of(
            new BlueberryBushState(BlueberryBushState.Stage.MATURE, 0).toStageString(),
            new BlueberryBushState(BlueberryBushState.Stage.BERRIES_UNRIPE, 0).toStageString(),
            new BlueberryBushState(BlueberryBushState.Stage.BERRIES_RIPENING, 0).toStageString(),
            new BlueberryBushState(BlueberryBushState.Stage.BERRIES_RIPE, 0).toStageString());

    private static void tickUntil(World world, BooleanSupplier done, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (!done.getAsBoolean()) {
            world.tickStream();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; stats " + world.streamStats());
            }
            Thread.sleep(2);
        }
    }

    /** The first open plains grass column (no tree) near the origin that anchors a bush group, or null. */
    private static long[] findAnchor(NativeTerrain terrain) {
        DeterministicRandom rng = new DeterministicRandom(SEED);
        int grass = BlockType.GRASS.getId();
        for (int r = 0; r < 1500; r += 1) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    if (rng.getFloat(x, z, "blueberry_group") >= GROUP_CHANCE) continue;
                    NativeCubeGenerator.ColumnInfo info = terrain.queryColumn(x, z);
                    boolean dry = info.waterLevel() == NativeCubeGenerator.NO_WATER || info.waterLevel() <= info.height();
                    if (info.biome() == BiomeType.PLAINS.ordinal() && info.surfaceBlock() == grass
                            && info.carvedHeight() == info.height() && dry && info.tree() == 0) {
                        return new long[]{x, info.height(), z};
                    }
                }
            }
        }
        return null;
    }

    @Test
    void aStreamedStandardWorldGrowsWildBushesWithTheirStages(@TempDir Path dir) throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: " + NativeCubeGenerator.unavailableReason());
        World world = World.createHeadless(new WorldConfiguration(4, 4), SEED, TerrainGeneratorType.STANDARD,
                TerrainScalePreset.ONE_TO_FOUR, dir);
        try {
            long[] anchor = findAnchor(world.terrain());
            assertNotNull(anchor, "seed " + SEED + " has a plains bush anchor within 1500 blocks");
            long x = anchor[0], y = anchor[1], z = anchor[2];
            world.setStreamCenters(List.of(new StreamCenter(x, y, z, 48, 48, World.BAND_BELOW, World.BAND_ABOVE)));
            tickUntil(world, () -> world.isColumnReadyAt(x, z) && world.isLoaded(x, y, z), "the anchor's column");

            assertEquals(BlockType.BLUEBERRY_BUSH, world.getBlockAt(x, y, z), "a wild bush at " + x + "," + y + "," + z);
            assertEquals(BlockType.GRASS, world.getBlockAt(x, y - 1, z), "standing on plains grass");
            String state = world.getBlockStateAt(x, y, z);
            assertTrue(WILD_STAGES.contains(state), "a grown, wild stage: " + state);
            BlueberryBushState parsed = BlueberryBushState.parse(state);
            int remaining = world.getBlueberryBushGrowth().remainingAt(x, y, z);
            if (parsed.isRipe()) {
                assertEquals(-1, remaining, "a ripe bush waits for a harvest, untracked");
            } else {
                assertTrue(remaining > 0, "the growth system tracks it from its cube's install: " + remaining);
            }
        } finally {
            world.cleanup();
        }
    }
}
