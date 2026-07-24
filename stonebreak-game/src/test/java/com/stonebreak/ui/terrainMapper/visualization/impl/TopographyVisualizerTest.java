package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two things that make the topography view a topographic map rather than a
 * recoloured heightmap: an absolute elevation ramp with exactly one hard edge (the shoreline),
 * and contour lines whose interval widens with the zoom so they never merge.
 *
 * <p>Neither {@code colorFor} nor {@code postProcess} touches the height source, so the
 * generator is wired to a tile source that fails loudly if anything tries to sample through it.
 */
class TopographyVisualizerTest {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int CEILING = TerrainMapperConfig.TOPO_LAND_CEILING;

    private final TopographyVisualizer visualizer = new TopographyVisualizer(
            new HeightMapGenerator((x, z) -> {
                throw new UnsupportedOperationException("color/contour logic must not sample terrain");
            }));

    // ─────────────────────────────────────────────── Ramp

    @Test
    void waterRampRunsIndigoToLightBlue() {
        int abyss = visualizer.colorFor(0);
        int shore = visualizer.colorFor(SEA_LEVEL);
        // Indigo: blue-dominant and dark. Light blue: bright, blue still dominant.
        assertTrue(blue(abyss) > red(abyss), "abyss should be blue-dominant");
        assertTrue(luma(shore) > luma(abyss) + 60, "shallow water must be far lighter than the abyss");
        assertTrue(blue(shore) > red(shore), "shoreline water should still read as blue");
    }

    @Test
    void landRampRunsGreenToWhiteAndClampsAboveTheCeiling() {
        int lowland = visualizer.colorFor(SEA_LEVEL + 1);
        assertTrue(green(lowland) > red(lowland) && green(lowland) > blue(lowland),
                "land immediately above the waterline should be green");

        int peak = visualizer.colorFor(CEILING);
        assertEquals(0xFFF5F5F5, peak, "the ceiling should be the white stop exactly");
        assertEquals(peak, visualizer.colorFor(CEILING + 500), "heights above the ceiling clamp to white");
        assertEquals(peak, visualizer.colorFor(WorldConfiguration.WORLD_HEIGHT), "world ceiling stays in range");
    }

    @Test
    void shorelineIsTheOnlyHardColourSwitch() {
        int belowShore = visualizer.colorFor(SEA_LEVEL);
        int aboveShore = visualizer.colorFor(SEA_LEVEL + 1);
        assertNotEquals(belowShore, aboveShore);
        assertTrue(distance(belowShore, aboveShore) > 150,
                "green -> light blue must be an abrupt jump at sea level");

        // Everywhere else, one block of elevation is a barely perceptible colour step.
        for (int y = 1; y < WorldConfiguration.WORLD_HEIGHT; y++) {
            if (y == SEA_LEVEL + 1) continue; // the intentional discontinuity
            int step = distance(visualizer.colorFor(y - 1), visualizer.colorFor(y));
            assertTrue(step <= 12, "abrupt colour step at y=" + y + " (distance " + step + ")");
        }
    }

    // ─────────────────────────────────────────────── Contours

    @Test
    void drawsMinorContoursEveryFiveBlocksAndBoldMajorsEveryTen() {
        // One row climbing exactly one block per sample, so a contour must fall on every
        // multiple of 5 and a bold one on every multiple of 10.
        int width = 31;
        float[] raw = new float[width];
        int[] pixels = new int[width];
        for (int x = 0; x < width; x++) {
            raw[x] = x;
            pixels[x] = 0xFF808080;
        }

        visualizer.postProcess(raw, pixels, width, 1, 1f);

        assertEquals(0xFF000000, pixels[10], "10-block boundary should be a major contour");
        assertEquals(0xFF000000, pixels[11], "major contours are two samples wide (bold)");
        assertEquals(0xFF000000, pixels[20], "10-block boundary should be a major contour");
        assertEquals(0xFF2A2A2A, pixels[5], "5-block boundary should be a minor contour");
        assertEquals(0xFF2A2A2A, pixels[15], "5-block boundary should be a minor contour");
        assertEquals(0xFF808080, pixels[7], "terrain between contours is untouched");
        assertEquals(0xFF808080, pixels[13], "terrain between contours is untouched");
    }

    @Test
    void majorContoursOutrankMinorOnesOnTheSameBoundary() {
        // Every 10-block boundary is also a 5-block boundary; the bold black must win.
        float[] raw = {8f, 9f, 10f, 11f};
        int[] pixels = {0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080};

        visualizer.postProcess(raw, pixels, 4, 1, 1f);

        assertEquals(0xFF000000, pixels[2], "shared boundary must be major, not minor");
    }

    @Test
    void widensTheIntervalAsTheSampleCellGrows() {
        // The whole point: one sample standing for more world has to mean fewer lines, or the
        // zoomed-out view fills in solid black.
        assertEquals(5, TopographyVisualizer.minorIntervalFor(1f), "close zoom keeps the classic 5/10");
        assertEquals(5, TopographyVisualizer.minorIntervalFor(2f));
        assertTrue(TopographyVisualizer.minorIntervalFor(8f) > TopographyVisualizer.minorIntervalFor(2f),
                "a coarser sample cell must widen the interval");
        assertEquals(50, TopographyVisualizer.minorIntervalFor(32f),
                "ZOOM_MIN tops out at 50/100 rather than doubling past a readable interval");
        assertEquals(50, TopographyVisualizer.minorIntervalFor(1000f), "the ladder is capped, not open-ended");
    }

    @Test
    void zoomedOutContoursLandOnTheWidenedIntervalOnly() {
        int width = 121;
        float[] raw = new float[width];
        int[] pixels = new int[width];
        for (int x = 0; x < width; x++) {
            raw[x] = x;
            pixels[x] = 0xFF808080;
        }

        visualizer.postProcess(raw, pixels, width, 1, 32f); // ZOOM_MIN: 50/100-block contours

        assertEquals(0xFF808080, pixels[5], "5-block boundaries are unreadable at this zoom");
        assertEquals(0xFF808080, pixels[10], "10-block boundaries are unreadable at this zoom");
        assertEquals(0xFF2A2A2A, pixels[50], "50-block boundary is the minor contour here");
        assertEquals(0xFF000000, pixels[100], "100-block boundary is the major contour here");
        assertEquals(0xFF808080, pixels[101], "majors stay hairline once the ladder is capped out");
    }

    @Test
    void flatTerrainGetsNoContours() {
        // A plateau sitting exactly on a contour multiple must not paint solid black — the line
        // belongs on the boundary between bands, not on every sample whose height divides by 5.
        int width = 8;
        float[] raw = new float[width];
        int[] pixels = new int[width];
        java.util.Arrays.fill(raw, (float) SEA_LEVEL);
        java.util.Arrays.fill(pixels, 0xFF808080);

        visualizer.postProcess(raw, pixels, width, 1, 1f);

        for (int x = 0; x < width; x++) {
            assertEquals(0xFF808080, pixels[x], "flat ground must stay unmarked at x=" + x);
        }
    }

    // ─────────────────────────────────────────────── Helpers

    private static int red(int argb)   { return (argb >>> 16) & 0xFF; }
    private static int green(int argb) { return (argb >>> 8) & 0xFF; }
    private static int blue(int argb)  { return argb & 0xFF; }
    private static int luma(int argb)  { return (red(argb) + green(argb) + blue(argb)) / 3; }

    private static int distance(int a, int b) {
        return Math.abs(red(a) - red(b)) + Math.abs(green(a) - green(b)) + Math.abs(blue(a) - blue(b));
    }
}
