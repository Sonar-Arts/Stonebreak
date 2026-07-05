package com.stonebreak.world.fastlod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Band-selection math for the LOD ring: preload zone, band edges, ring
 * boundaries and degenerate configurations. These values are load-bearing —
 * {@link FastLodManager#updateRing} and its upload-time re-validation both
 * derive "is this node still wanted" from {@code levelFor}.
 */
class FastLodBandPolicyTest {

    @Test
    void disabledRangeIsNullEverywhere() {
        for (int d = 0; d < 64; d++) {
            assertNull(FastLodBandPolicy.levelFor(d, 8, 0));
            assertNull(FastLodBandPolicy.levelFor(d, 8, -3));
        }
    }

    @Test
    void nullOutsideRing() {
        int inner = 8, range = 24;
        int outer = inner + range;
        int preloadInner = inner - FastLodBandPolicy.PRELOAD_RING;

        for (int d = 0; d <= preloadInner; d++) {
            assertNull(FastLodBandPolicy.levelFor(d, inner, range), "inside preloadInner d=" + d);
        }
        assertNotNull(FastLodBandPolicy.levelFor(outer, inner, range), "outer edge is inclusive");
        assertNull(FastLodBandPolicy.levelFor(outer + 1, inner, range));
        assertNull(FastLodBandPolicy.levelFor(outer + 100, inner, range));
    }

    @Test
    void preloadZoneIsFinest() {
        int inner = 8, range = 24;
        for (int d = inner - FastLodBandPolicy.PRELOAD_RING + 1; d <= inner; d++) {
            assertEquals(FastLodLevel.finest(), FastLodBandPolicy.levelFor(d, inner, range),
                    "preload zone d=" + d);
        }
    }

    @Test
    void bandEdgesAtDefaultSettings() {
        // inner=8, range=24 → bandWidth = ceil(24/5) = 5.
        int inner = 8, range = 24;
        assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(9, inner, range));
        assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(13, inner, range));
        assertEquals(FastLodLevel.L1, FastLodBandPolicy.levelFor(14, inner, range));
        assertEquals(FastLodLevel.L1, FastLodBandPolicy.levelFor(18, inner, range));
        assertEquals(FastLodLevel.L2, FastLodBandPolicy.levelFor(19, inner, range));
        assertEquals(FastLodLevel.L2, FastLodBandPolicy.levelFor(23, inner, range));
        assertEquals(FastLodLevel.L3, FastLodBandPolicy.levelFor(24, inner, range));
        assertEquals(FastLodLevel.L3, FastLodBandPolicy.levelFor(28, inner, range));
        assertEquals(FastLodLevel.L4, FastLodBandPolicy.levelFor(29, inner, range));
        assertEquals(FastLodLevel.L4, FastLodBandPolicy.levelFor(32, inner, range));
        assertNull(FastLodBandPolicy.levelFor(33, inner, range));
    }

    @Test
    void coarsenessIsMonotonicInDistance() {
        int[][] configs = { {4, 1}, {4, 4}, {8, 24}, {24, 48}, {0, 5}, {1, 10} };
        for (int[] cfg : configs) {
            int inner = cfg[0], range = cfg[1];
            int last = -1;
            for (int d = 0; d <= inner + range; d++) {
                FastLodLevel level = FastLodBandPolicy.levelFor(d, inner, range);
                if (level == null) continue;
                assertTrue(level.index() >= last,
                        "level regressed at d=" + d + " for inner=" + inner + " range=" + range);
                last = level.index();
            }
            // The ring's outermost node must exist for every enabled config.
            assertNotNull(FastLodBandPolicy.levelFor(inner + range, inner, range),
                    "outer edge missing for inner=" + inner + " range=" + range);
        }
    }

    @Test
    void tinyRangesClampToValidLevels() {
        int inner = 8;
        // range=1: single LOD band, always finest.
        assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(9, inner, 1));
        assertNull(FastLodBandPolicy.levelFor(10, inner, 1));
        // range=2..4: one chunk per band, never past the range's own depth.
        assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(9, inner, 2));
        assertEquals(FastLodLevel.L1, FastLodBandPolicy.levelFor(10, inner, 2));
        assertEquals(FastLodLevel.L2, FastLodBandPolicy.levelFor(11, inner, 3));
        assertEquals(FastLodLevel.L3, FastLodBandPolicy.levelFor(12, inner, 4));
    }

    @Test
    void degenerateInnerDoesNotUnderflow() {
        // inner=0: no native disk, no preload zone; ring starts at d=1.
        assertNull(FastLodBandPolicy.levelFor(0, 0, 5));
        assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(1, 0, 5));
        // inner=1: preloadInner clamps to 0, d=1 is the whole preload zone.
        assertNull(FastLodBandPolicy.levelFor(0, 1, 5));
        assertEquals(FastLodLevel.finest(), FastLodBandPolicy.levelFor(1, 1, 5));
    }
}
