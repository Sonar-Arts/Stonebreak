package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.NativeCubeGenerator;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.StandardShapeVersion;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.WorldFeatureVersion;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.biomes.ShadeOverlay;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.NoiseRouter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Shade on the terrain mapper: the Standard preview's Biome mode (Java mirror of the native selector) shows it
 * where a new world generates it, the DaedalusTGM-Exp map shows the world's Shade overlay at every zoom (the coarse
 * overview too), and both maps colour it (no magenta "unknown biome").
 */
class TerrainMapperShadeTest {

    private static final long SEED = 31337L;

    /** The Standard preview's biomes at 1:4, built as {@code StandardPreview.build} builds them. */
    @Test
    void theStandardPreviewShowsTheShadeWhereANewWorldGrowsIt() {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: " + NativeCubeGenerator.unavailableReason());
        assertEquals(1.0, NativeStandardColumns.shapeScale(TerrainScalePreset.ONE_TO_FOUR), 1e-12);
        NoiseRouter router = new NoiseRouter(SEED, StandardShapeVersion.CURRENT);
        BiomeManager preview = new BiomeManager(router, new HeightMapGenerator(router)).withShade(SEED, 1.0);

        int count = 48, stride = 256, origin = -count * stride / 2, n = count * count;
        BlockType[] surface = new BlockType[n];
        try (NativeTerrain world = NativeTerrain.create(SEED, TerrainGeneratorType.STANDARD,
                TerrainScalePreset.ONE_TO_FOUR, ClimateLayout.DEFAULT, null, WorldFeatureVersion.CURRENT, null, null)) {
            world.sampleRawColumns(origin, origin, count, stride, new int[n], new int[n], surface);
        }
        int nativeShade = 0, previewShade = 0, agree = 0;
        for (int i = 0; i < n; i++) {
            int x = origin + (i / count) * stride, z = origin + (i % count) * stride;   // [ix * count + iz]
            boolean worldShade = surface[i] == BlockType.DRY_GRASS;   // only the Shade lays dry grass
            boolean mapShade = preview.getBiome(x, z) == BiomeType.SHADE;
            nativeShade += worldShade ? 1 : 0;
            previewShade += mapShade ? 1 : 0;
            agree += worldShade == mapShade ? 1 : 0;
        }
        assertTrue(nativeShade > 0 && previewShade > 0, "Shade in the world " + nativeShade + ", on the map " + previewShade);
        assertTrue(agree >= n * 0.98, "the map shows the Shade where the world has it: " + agree + "/" + n
                + " columns agree (world " + nativeShade + ", map " + previewShade + ")");
    }

    /** Model tiles (sample units at {@code lod}) of plains only. */
    private static TerrainTileSource plainsTiles() {
        int size = 256;
        return (x, z) -> {
            int tx = Math.floorDiv(x, size), tz = Math.floorDiv(z, size);
            short[] h = new short[size * size], b = new short[size * size], w = new short[size * size];
            Arrays.fill(h, (short) 90);
            Arrays.fill(b, (short) 1);   // DaedalusTGM-Exp plains
            Arrays.fill(w, TerrainTile.NO_WATER);
            return new TerrainTile(tx, tz, tx * size, tz * size, tx * size + size, tz * size + size, size, size, h, b, w);
        };
    }

    @Test
    void theDaedalusOverviewShowsTheWorldsShadeWhenZoomedOut() {
        ShadeOverlay shade = DiffusionPreview.newWorldShade(SEED);
        assertTrue(shade.enabled(), "a new world has The Shade");
        TerrainColumns overview = DiffusionPreview.overviewColumns(plainsTiles(), shade);
        float[] out = new float[PreviewChannel.values().length];
        int shadeColumns = 0, samples = 0;
        for (int x = 0; x < 40_000; x += 113) {
            for (int z = 0; z < 8_000; z += 113) {
                overview.sample(x, z, out);
                BiomeType expected = shade.isShadeRegion(x, z) ? BiomeType.SHADE : BiomeType.PLAINS;
                assertEquals(expected.ordinal(), Math.round(out[PreviewChannel.BIOME.ordinal()]),
                        "the overview's biome at " + x + "," + z);
                shadeColumns += expected == BiomeType.SHADE ? 1 : 0;
                samples++;
            }
        }
        assertTrue(shadeColumns > 0 && shadeColumns < samples, shadeColumns + " of " + samples);
    }

    @Test
    void bothMapsColourEveryBiome() {
        var standard = new com.stonebreak.ui.terrainMapper.visualization.impl.BiomeVisualizer(null);
        var daedalus = new com.stonebreak.ui.terrainMapper.visualization.impl.diffusion.BiomeVisualizer(null);
        for (BiomeType biome : BiomeType.values()) {
            int c = daedalus.colorFor(biome.ordinal());
            assertNotEquals(0xFFFF00FF, c, biome + " has a colour on the DaedalusTGM-Exp map");
            assertEquals(standard.colorFor(biome.ordinal()), c, biome + ": one palette for both maps");
        }
    }
}
