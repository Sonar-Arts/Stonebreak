package com.stonebreak.world.generation;

import com.openmason.engine.voxel.world.StreamCenter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Shade in Standard worlds (native SBW_SHADE, Project-Heracles, WorldFeatureVersion V5): a world created with it
 * has Shade land - dry grass over dirt, grown with dry oaks, reported as {@link BiomeType#SHADE} to the game (mob
 * spawning, the night fog) - while a V4 world of the same seed has none and is otherwise unchanged. Needs
 * libstonebreak_worldgen; the native rules themselves are covered by {@code native/tests/shade_test.cpp}.
 */
class StandardShadeGenerationTest {

    private static final long SEED = 31337L;
    private static final int COUNT = 48;
    private static final int STRIDE = 256;
    private static final int ORIGIN = -COUNT * STRIDE / 2;
    private static final long TIMEOUT_NANOS = 120_000_000_000L;

    private static NativeTerrain terrain(WorldFeatureVersion features) {
        return NativeTerrain.create(SEED, TerrainGeneratorType.STANDARD, TerrainScalePreset.ONE_TO_FOUR,
                ClimateLayout.DEFAULT, null, features, null, null);
    }

    private static BlockType[] rawSurfaces(NativeTerrain t) {
        int n = COUNT * COUNT;
        BlockType[] surface = new BlockType[n];
        t.sampleRawColumns(ORIGIN, ORIGIN, COUNT, STRIDE, new int[n], new int[n], surface);
        return surface;
    }

    @Test
    void aV5WorldHasShadeLandAndAV4WorldOfTheSameSeedHasNone() {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: " + NativeCubeGenerator.unavailableReason());
        try (NativeTerrain v5 = terrain(WorldFeatureVersion.V5); NativeTerrain v4 = terrain(WorldFeatureVersion.V4)) {
            BlockType[] with = rawSurfaces(v5);
            BlockType[] without = rawSurfaces(v4);
            int shade = 0, land = 0, differ = 0;
            int first = -1;
            for (int i = 0; i < with.length; i++) {
                assertNotEquals(BlockType.DRY_GRASS, without[i], "a V4 world grows no dry grass");
                if (with[i] == BlockType.DRY_GRASS) {
                    shade++;
                    if (first < 0) first = i;
                } else {
                    differ += with[i] != without[i] ? 1 : 0;
                }
                if (with[i] != BlockType.WATER && with[i] != BlockType.SAND) land++;
            }
            assertEquals(0, differ, "only Shade columns change");
            // Native shade_regions = Java ShadeRegions bit for bit: every native Shade column is in a Java Shade region.
            for (int i = 0; i < with.length; i++) {
                if (with[i] != BlockType.DRY_GRASS) continue;
                long px = ORIGIN + (long) (i / COUNT) * STRIDE, pz = ORIGIN + (long) (i % COUNT) * STRIDE;
                assertTrue(com.stonebreak.world.generation.biomes.ShadeRegions.isShadeRegion(SEED, px, pz),
                        "native Shade at " + px + "," + pz + " outside the Java region");
            }
            assertTrue(shade > 0, "seed " + SEED + " has Shade land");
            assertTrue(shade < land / 4, "a regional biome: " + shade + " of " + land);

            // Grids are indexed [ix * count + iz] (sbw_sample_columns).
            int x = ORIGIN + (first / COUNT) * STRIDE;
            int z = ORIGIN + (first % COUNT) * STRIDE;
            assertEquals(BiomeType.SHADE, v5.getBiomeAt(x, z));
            assertNotEquals(BiomeType.SHADE, v4.getBiomeAt(x, z));

            // Dry oaks in the Shade, nowhere else, around that column.
            int n = 64 * 64;
            BlockType[] surface = new BlockType[n];
            VegetationGenerator.TreeSample[] trees = new VegetationGenerator.TreeSample[n];
            v5.sampleColumns(x - 32, z - 32, 64, 1, new int[n], new int[n], surface, trees);
            int dryOaks = 0;
            for (int i = 0; i < n; i++) {
                if (trees[i] == null) continue;
                boolean shadeColumn = surface[i] == BlockType.DRY_GRASS;
                boolean dryOak = trees[i].kind() == VegetationGenerator.TreeKind.DRY_OAK;
                assertEquals(shadeColumn, dryOak, "dry oaks grow on the Shade's dry grass only");
                dryOaks += dryOak ? 1 : 0;
            }
            assertTrue(dryOaks > 0, "the Shade is wooded");
        }
    }

    @Test
    void aStreamedV5WorldLaysDryGrassOverDirt(@TempDir Path dir) throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: " + NativeCubeGenerator.unavailableReason());
        long x, z;
        try (NativeTerrain v5 = terrain(WorldFeatureVersion.V5)) {
            BlockType[] with = rawSurfaces(v5);
            int pick = -1;
            for (int i = 0; i < with.length && pick < 0; i++) {
                if (with[i] != BlockType.DRY_GRASS) continue;
                int cx = ORIGIN + (i / COUNT) * STRIDE, cz = ORIGIN + (i % COUNT) * STRIDE;
                NativeCubeGenerator.ColumnInfo info = v5.queryColumn(cx, cz);
                if (info.carvedHeight() == info.height() && info.tree() == 0) pick = i;
            }
            assertTrue(pick >= 0, "an uncarved, treeless Shade column");
            x = ORIGIN + (pick / COUNT) * STRIDE;
            z = ORIGIN + (pick % COUNT) * STRIDE;
        }
        World world = World.createHeadless(new WorldConfiguration(4, 4), SEED, TerrainGeneratorType.STANDARD,
                TerrainScalePreset.ONE_TO_FOUR, ClimateLayout.DEFAULT, null, WorldFeatureVersion.V5, dir, false);
        try {
            long h = world.terrain().queryColumn(x, z).height();
            world.setStreamCenters(List.of(new StreamCenter(x, h, z, 48, 48, World.BAND_BELOW, World.BAND_ABOVE)));
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            BooleanSupplier ready = () -> world.isColumnReadyAt(x, z) && world.isLoaded(x, h - 1, z);
            while (!ready.getAsBoolean()) {
                world.tickStream();
                assertTrue(System.nanoTime() < deadline, "timed out streaming the Shade column");
                Thread.sleep(2);
            }
            assertEquals(BlockType.DRY_GRASS, world.getBlockAt(x, h - 1, z), "the Shade's ground");
            assertEquals(BlockType.DIRT, world.getBlockAt(x, h - 2, z), "over dirt");
            assertEquals(BiomeType.SHADE, world.terrain().getBiomeAt((int) x, (int) z));
        } finally {
            world.cleanup();
        }
    }
}
