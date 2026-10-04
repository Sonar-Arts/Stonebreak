package com.stonebreak.world.fastlod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Band-selection math for the LOD ring: preload and handover zones,
 * distance-proportional band edges per quality preset, ring boundaries and
 * degenerate configurations. These values are load-bearing —
 * {@link FastLodManager#updateRing} and its upload-time re-validation both
 * derive "is this node still wanted" from {@code levelFor}.
 */
class FastLodBandPolicyTest {

    private static final FastLodQuality Q = FastLodQuality.MEDIUM;

    @Test
    void disabledRangeIsNullEverywhere() {
        for (FastLodQuality q : FastLodQuality.values()) {
            for (int d = 0; d < 64; d++) {
                assertNull(FastLodBandPolicy.levelFor(d, 8, 0, q));
                assertNull(FastLodBandPolicy.levelFor(d, 8, -3, q));
            }
        }
    }

    @Test
    void nullOutsideRing() {
        int inner = 8, range = 24;
        int outer = inner + range;
        int preloadInner = inner - FastLodBandPolicy.PRELOAD_RING;

        for (int d = 0; d <= preloadInner; d++) {
            assertNull(FastLodBandPolicy.levelFor(d, inner, range, Q), "inside preloadInner d=" + d);
        }
        assertNotNull(FastLodBandPolicy.levelFor(outer, inner, range, Q), "outer edge is inclusive");
        assertNull(FastLodBandPolicy.levelFor(outer + 1, inner, range, Q));
        assertNull(FastLodBandPolicy.levelFor(outer + 100, inner, range, Q));
    }

    @Test
    void preloadAndHandoverZonesAreFinest() {
        for (FastLodQuality q : FastLodQuality.values()) {
            for (int inner : new int[]{4, 8, 24}) {
                for (int d = inner - FastLodBandPolicy.PRELOAD_RING + 1;
                     d <= inner + FastLodBandPolicy.HANDOVER_RING; d++) {
                    assertEquals(FastLodLevel.finest(), FastLodBandPolicy.levelFor(d, inner, 48, q),
                        q + " inner=" + inner + " d=" + d);
                }
            }
        }
    }

    @Test
    void mediumBandEdgesFollowDistanceNotTheSlider() {
        // MEDIUM: L1 from 128 blocks (d=8), L2 from 256 (16), L3 from 512 (32), L4 from 1024 (64).
        int inner = 4;
        for (int range : new int[]{60, 64}) {
            assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(7, inner, range, Q));
            assertEquals(FastLodLevel.L1, FastLodBandPolicy.levelFor(8, inner, range, Q));
            assertEquals(FastLodLevel.L1, FastLodBandPolicy.levelFor(15, inner, range, Q));
            assertEquals(FastLodLevel.L2, FastLodBandPolicy.levelFor(16, inner, range, Q));
            assertEquals(FastLodLevel.L2, FastLodBandPolicy.levelFor(31, inner, range, Q));
            assertEquals(FastLodLevel.L3, FastLodBandPolicy.levelFor(32, inner, range, Q));
            assertEquals(FastLodLevel.L3, FastLodBandPolicy.levelFor(63, inner, range, Q));
            assertEquals(FastLodLevel.L4, FastLodBandPolicy.levelFor(64, inner, range, Q));
        }
    }

    @Test
    void finerPresetsNeverPickACoarserLevel() {
        FastLodQuality[] qs = FastLodQuality.values();
        for (int d = 1; d <= 88; d++) {
            for (int i = 1; i < qs.length; i++) {
                FastLodLevel coarse = FastLodBandPolicy.levelFor(d, 8, 80, qs[i - 1]);
                FastLodLevel fine = FastLodBandPolicy.levelFor(d, 8, 80, qs[i]);
                if (coarse == null) continue;
                assertTrue(fine.index() <= coarse.index(), qs[i] + " coarser than " + qs[i - 1] + " at d=" + d);
            }
        }
    }

    @Test
    void worstCellStaysWithinThePresetsBudget() {
        // The near edge of a level-k band is 2^k × blocksPerCell blocks away, so
        // cellSize / distance never exceeds 1 / blocksPerCell beyond the handover zone.
        for (FastLodQuality q : FastLodQuality.values()) {
            for (int d = 8 + FastLodBandPolicy.HANDOVER_RING + 1; d <= 72; d++) {
                FastLodLevel l = FastLodBandPolicy.levelFor(d, 8, 64, q);
                assertTrue((double) l.cellSize() / (d * 16) <= 1.0 / q.blocksPerCell() + 1e-9,
                    q + " d=" + d + " " + l);
            }
        }
    }

    @Test
    void coarsenessIsMonotonicInDistance() {
        int[][] configs = { {4, 1}, {4, 4}, {8, 24}, {24, 48}, {0, 5}, {1, 10}, {8, 64} };
        for (FastLodQuality q : FastLodQuality.values()) {
            for (int[] cfg : configs) {
                int inner = cfg[0], range = cfg[1];
                int last = -1;
                for (int d = 0; d <= inner + range; d++) {
                    FastLodLevel level = FastLodBandPolicy.levelFor(d, inner, range, q);
                    if (level == null) continue;
                    assertTrue(level.index() >= last,
                        q + ": level regressed at d=" + d + " for inner=" + inner + " range=" + range);
                    last = level.index();
                }
                // The ring's outermost node must exist for every enabled config.
                assertNotNull(FastLodBandPolicy.levelFor(inner + range, inner, range, q),
                    "outer edge missing for inner=" + inner + " range=" + range);
            }
        }
    }

    @Test
    void degenerateInnerDoesNotUnderflow() {
        // inner=0: no native disk, no preload zone; ring starts at d=1.
        assertNull(FastLodBandPolicy.levelFor(0, 0, 5, Q));
        assertEquals(FastLodLevel.L0, FastLodBandPolicy.levelFor(1, 0, 5, Q));
        // inner=1: preloadInner clamps to 0, d=1 is the whole preload zone.
        assertNull(FastLodBandPolicy.levelFor(0, 1, 5, Q));
        assertEquals(FastLodLevel.finest(), FastLodBandPolicy.levelFor(1, 1, 5, Q));
    }

    @Test
    void belowUltraOnlyTheFinestThreeLevelsAreCarved() {
        for (FastLodQuality q : FastLodQuality.values()) {
            assertTrue(q.carves(FastLodLevel.L0) && q.carves(FastLodLevel.L1) && q.carves(FastLodLevel.L2), q.name());
            boolean coarse = q.carves(FastLodLevel.L3) || q.carves(FastLodLevel.L4);
            assertEquals(q == FastLodQuality.ULTRA, coarse, q + " carves the coarse levels");
        }
    }

    @Test
    void unknownQualityNamesFallBackToDefault() {
        assertEquals(FastLodQuality.ULTRA, FastLodQuality.parse("ultra"));
        assertEquals(FastLodQuality.DEFAULT, FastLodQuality.parse("bogus"));
        assertEquals(FastLodQuality.DEFAULT, FastLodQuality.parse(null));
    }
}
