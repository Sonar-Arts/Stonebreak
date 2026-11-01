package com.openmason.engine.voxel.mms.mmsRegion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CEARL-policy growth math to the pre-CEARL formula: with the
 * default policy (×1.75, 25% reserve, 4-element alignment), the grown arena
 * capacity must be bit-identical to the old hardcoded
 * {@code max(needed + (needed >> 2), capacity * 7 / 4)} rounded up to 4 —
 * so installing no plan (or the shipped mirror plan) changes nothing.
 */
class MmsChunkRegionGrowthTest {

    private static long legacy(long needed, long capacity) {
        long grown = Math.max(needed + (needed >> 2), capacity * 7L / 4L);
        return (grown + 3L) & ~3L;
    }

    @Test
    void defaultPolicyIsBitIdenticalToTheLegacyFormula() {
        long[] samples = {
            1, 2, 3, 4, 5, 7, 15, 16, 17, 100, 101, 1023, 1024, 1025,
            16 * 1024, 24 * 1024, 100_000, 100_001, 131_071, 131_072,
            1_000_000, 7_777_777, 33_554_431
        };
        for (long needed : samples) {
            for (long capacity : samples) {
                assertEquals(legacy(needed, capacity),
                    MmsChunkRegion.nextCapacity(needed, capacity, 1.75, 0.25, 4),
                    "needed=" + needed + " capacity=" + capacity);
            }
        }
    }

    @Test
    void trimFiresOnlyWhenUnderusedAndWorthIt() {
        // Disabled policy never trims.
        assertEquals(-1, MmsChunkRegion.trimTarget(100, 10_000, 1_000, 0, 0.125, 4));
        // At or below the initial capacity there is nothing to give back.
        assertEquals(-1, MmsChunkRegion.trimTarget(100, 1_000, 1_000, 0.4, 0.125, 4));
        // Above the usage threshold: leave it alone.
        assertEquals(-1, MmsChunkRegion.trimTarget(5_000, 10_000, 1_000, 0.4, 0.125, 4));
        // Underused: shrink to live-plus-reserve, aligned.
        long target = MmsChunkRegion.trimTarget(3_000, 10_000, 1_000, 0.4, 0.125, 4);
        assertEquals(3376, target); // 3000 * 1.125 = 3375, aligned up to 4
        // The target never drops below the initial capacity.
        assertEquals(1_000, MmsChunkRegion.trimTarget(10, 10_000, 1_000, 0.4, 0.125, 4));
    }

    @Test
    void trimHysteresisPreventsOscillationWithGrowth() {
        // At moderate trim thresholds (<= ~55%) the recovery guard can never
        // fire — target <= usage*1.125 <= 0.45*capacity always recovers more
        // than a third. It exists for aggressive thresholds: here a shrink
        // recovering under a third of the arena is skipped (target*3 >
        // capacity*2), so growth (>= x1.5) and trim can never ping-pong.
        assertEquals(-1, MmsChunkRegion.trimTarget(650, 1_000, 100, 0.7, 0.125, 4));
        // The same usage against a bigger arena recovers plenty and trims.
        assertEquals(732, MmsChunkRegion.trimTarget(650, 3_000, 100, 0.7, 0.125, 4));
    }

    @Test
    void sparseExtendTargetAlwaysFitsTheRequestInTheAddedTail() {
        // The in-game failure's shape: geometric growth (30000 from cap 20000)
        // adds only 10000 — under the 11064-element request. The target must
        // put length + reserve into the extension itself.
        long target = MmsChunkRegion.sparseExtendTarget(30000, 20000, 11064, 0.125, 4);
        assertTrue(target - 20000 >= 11064, "tail addition must fit the request");
        assertEquals(0, target % 4);
        // When geometric growth is already generous, it wins unchanged.
        assertEquals(50000, MmsChunkRegion.sparseExtendTarget(50000, 20000, 1000, 0.125, 4));
        // Sweep: the invariant holds across sizes.
        for (long cap : new long[] {1024, 16384, 131072}) {
            for (long len : new long[] {1, 4097, 22878, 200000}) {
                long t = MmsChunkRegion.sparseExtendTarget(
                    MmsChunkRegion.nextCapacity(len, cap, 1.5, 0.125, 4), cap, len, 0.125, 4);
                assertTrue(t - cap >= len, "cap=" + cap + " len=" + len);
            }
        }
    }

    @Test
    void sparseVirtualReservationsArePageAlignedAndBounded() {
        long page = 65536;
        // Small arenas reserve the 16 MiB floor; big ones scale x32 up to 128 MiB.
        assertEquals(16L << 20, MmsChunkRegion.virtualBytes(48L << 10, page));
        assertEquals(20L << 20, MmsChunkRegion.virtualBytes(640L << 10, page));
        assertEquals(128L << 20, MmsChunkRegion.virtualBytes(5L << 20, page));
        // Never below twice the initial ask, and always a page multiple.
        assertEquals(MmsChunkRegion.alignUp(400L << 20, page),
            MmsChunkRegion.virtualBytes(200L << 20, page));
        assertEquals(0, MmsChunkRegion.virtualBytes(123_456, page) % page);
        // alignUp basics.
        assertEquals(65536, MmsChunkRegion.alignUp(1, page));
        assertEquals(65536, MmsChunkRegion.alignUp(65536, page));
        assertEquals(131072, MmsChunkRegion.alignUp(65537, page));
    }

    @Test
    void customPoliciesGrowAndAlignAsDeclared() {
        // Factor dominates when the arena is large relative to the need.
        assertEquals(2048, MmsChunkRegion.nextCapacity(100, 1024, 2.0, 0.1, 1));
        // Need-plus-reserve dominates when the allocation is the big thing.
        assertEquals(1100, MmsChunkRegion.nextCapacity(1000, 100, 2.0, 0.1, 1));
        // Alignment rounds up.
        long grown = MmsChunkRegion.nextCapacity(1000, 100, 2.0, 0.1, 64);
        assertEquals(0, grown % 64);
        assertTrue(grown >= 1100);
    }
}
