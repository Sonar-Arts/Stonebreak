package com.openmason.engine.voxel.mms.mmsRegion;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free-list contract of {@link MmsArenaAllocator}: best-fit alloc with split,
 * coalescing free, and compaction that packs live segments while emitting
 * correct GPU move commands and preserving handle validity.
 */
class MmsArenaAllocatorTest {

    @Test
    void allocSplitsAndFreesCoalesce() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        MmsArenaAllocator.Segment a = arena.alloc(30);
        MmsArenaAllocator.Segment b = arena.alloc(30);
        MmsArenaAllocator.Segment c = arena.alloc(30);
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertEquals(90, arena.used());
        arena.checkInvariants();

        // Segments must not overlap.
        assertTrue(distinctRanges(a, b));
        assertTrue(distinctRanges(b, c));
        assertTrue(distinctRanges(a, c));

        arena.free(b);
        assertEquals(60, arena.used());
        arena.checkInvariants();

        arena.free(a);
        arena.free(c);
        assertEquals(0, arena.used());
        assertTrue(arena.isEmpty());
        arena.checkInvariants();

        // After everything coalesced, a full-size alloc must fit again.
        assertNotNull(arena.alloc(100));
        arena.checkInvariants();
    }

    @Test
    void allocFailsWhenFragmentedBeyondFit() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        MmsArenaAllocator.Segment a = arena.alloc(40);
        MmsArenaAllocator.Segment b = arena.alloc(40);
        arena.alloc(20);
        arena.free(a);
        arena.free(b); // a+b coalesce into one 80-span
        arena.checkInvariants();
        assertNotNull(arena.alloc(80));
        assertNull(arena.alloc(1), "arena is full");
    }

    @Test
    void bestFitPrefersTightestHole() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        MmsArenaAllocator.Segment a = arena.alloc(20);
        MmsArenaAllocator.Segment keep1 = arena.alloc(10);
        MmsArenaAllocator.Segment b = arena.alloc(30);
        MmsArenaAllocator.Segment keep2 = arena.alloc(10);
        assertNotNull(keep1);
        assertNotNull(keep2);
        arena.free(a);  // hole of 20
        arena.free(b);  // hole of 30
        MmsArenaAllocator.Segment fit = arena.alloc(20);
        assertEquals(a.offset(), fit.offset(), "best-fit should reuse the 20-hole exactly");
        arena.checkInvariants();
    }

    @Test
    void compactionPacksLiveSegmentsAndEmitsMoves() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        MmsArenaAllocator.Segment a = arena.alloc(10);
        MmsArenaAllocator.Segment b = arena.alloc(20);
        MmsArenaAllocator.Segment c = arena.alloc(30);
        arena.free(b); // leave a hole between a and c

        // Simulate the data each segment holds.
        int[] oldData = new int[100];
        for (int i = 0; i < 100; i++) oldData[i] = i;
        int aFrom = a.offset(), cFrom = c.offset();

        List<MmsArenaAllocator.Move> moves = arena.compactTo(200);
        assertEquals(200, arena.capacity());
        assertEquals(40, arena.used());
        arena.checkInvariants();

        // Apply moves old->new and verify each segment's data landed at its
        // updated offset.
        int[] newData = new int[200];
        java.util.Arrays.fill(newData, -1);
        for (MmsArenaAllocator.Move m : moves) {
            System.arraycopy(oldData, m.from(), newData, m.to(), m.length());
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(aFrom + i, newData[a.offset() + i], "segment a data at new offset");
        }
        for (int i = 0; i < 30; i++) {
            assertEquals(cFrom + i, newData[c.offset() + i], "segment c data at new offset");
        }

        // Live segments must be packed at the tail in offset order (allocation
        // carves from the END of free spans, so pre-compaction order was
        // c < b < a): free space = 160 at head, then c, then a.
        assertEquals(160, c.offset());
        assertEquals(190, a.offset());

        // New allocations use the compacted head space.
        assertNotNull(arena.alloc(160));
        assertNull(arena.alloc(1));
        arena.checkInvariants();
    }

    @Test
    void compactionMergesAdjacentLiveRuns() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        arena.alloc(10);
        arena.alloc(10);
        arena.alloc(10); // three adjacent live segments, no holes
        List<MmsArenaAllocator.Move> moves = arena.compactTo(120);
        // The three allocations carved 90/80/70 off the end of the free span —
        // one contiguous 70..100 live run → exactly one move command.
        assertEquals(1, moves.size());
        assertEquals(70, moves.getFirst().from());
        assertEquals(90, moves.getFirst().to());
        assertEquals(30, moves.getFirst().length());
        arena.checkInvariants();
    }

    @Test
    void compactRejectsTooSmallCapacity() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        arena.alloc(50);
        assertThrows(IllegalArgumentException.class, () -> arena.compactTo(40));
        assertThrows(IllegalArgumentException.class, () -> arena.compactTo(0));
    }

    @Test
    void doubleFreeThrows() {
        MmsArenaAllocator arena = new MmsArenaAllocator(10);
        MmsArenaAllocator.Segment s = arena.alloc(5);
        arena.free(s);
        assertThrows(IllegalStateException.class, () -> arena.free(s));
    }

    @Test
    void randomizedChurnKeepsInvariantsAndDataIntegrity() {
        Random random = new Random(4242);
        MmsArenaAllocator arena = new MmsArenaAllocator(1 << 12);
        // Track live segments and the "data" tag each should carry.
        Map<MmsArenaAllocator.Segment, Integer> live = new HashMap<>();
        int[] store = new int[1 << 12];
        int nextTag = 1;

        for (int step = 0; step < 3000; step++) {
            boolean doAlloc = live.isEmpty() || random.nextInt(100) < 55;
            if (doAlloc) {
                int len = 1 + random.nextInt(64);
                MmsArenaAllocator.Segment s = arena.alloc(len);
                if (s == null) {
                    // Grow + compact, applying the moves to the simulated store.
                    long newCap = Math.max(arena.used() + len, arena.capacity() * 3 / 2);
                    int[] newStore = new int[(int) newCap];
                    for (MmsArenaAllocator.Move m : arena.compactTo(newCap)) {
                        System.arraycopy(store, m.from(), newStore, m.to(), m.length());
                    }
                    store = newStore;
                    s = arena.alloc(len);
                    assertNotNull(s, "post-compaction alloc must fit");
                }
                int tag = nextTag++;
                for (int i = 0; i < len; i++) {
                    store[s.offset() + i] = tag;
                }
                live.put(s, tag);
            } else {
                MmsArenaAllocator.Segment victim =
                    live.keySet().iterator().next();
                arena.free(victim);
                live.remove(victim);
            }
            arena.checkInvariants();
        }

        // Every surviving segment's data must still be intact at its
        // (possibly moved) offset.
        for (Map.Entry<MmsArenaAllocator.Segment, Integer> e : live.entrySet()) {
            MmsArenaAllocator.Segment s = e.getKey();
            for (int i = 0; i < s.length(); i++) {
                assertEquals(e.getValue().intValue(), store[s.offset() + i],
                    "data corrupted at segment offset " + s.offset() + "+" + i);
            }
        }
        long total = live.keySet().stream().mapToLong(MmsArenaAllocator.Segment::length).sum();
        assertEquals(total, arena.used());
    }

    private static boolean distinctRanges(MmsArenaAllocator.Segment x, MmsArenaAllocator.Segment y) {
        return x.offset() + x.length() <= y.offset() || y.offset() + y.length() <= x.offset();
    }

    // ─── Sparse-mode support: in-place extension + live iteration ─────────

    @org.junit.jupiter.api.Test
    void extendToAddsTailSpaceWithoutMovingAnything() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        MmsArenaAllocator.Segment a = arena.alloc(100); // full
        assertEquals(null, arena.alloc(1));
        int offsetBefore = a.offset();

        arena.extendTo(250);
        arena.checkInvariants();
        assertEquals(250, arena.capacity());
        assertEquals(offsetBefore, a.offset()); // nothing moved
        MmsArenaAllocator.Segment b = arena.alloc(150);
        assertEquals(100, b.offset()); // lands in the new tail span
        arena.checkInvariants();

        // Extending when the tail is already free merges into one span.
        arena.free(b);
        arena.extendTo(400);
        arena.checkInvariants();
        MmsArenaAllocator.Segment c = arena.alloc(300);
        assertEquals(100, c.offset());
        arena.checkInvariants();
    }

    /**
     * Regression for the 2026-08-19 in-game failure ("Region arena grow
     * failed to fit 11064 elements"): extension does not compact, so a
     * fragmented arena needs the request to fit in the TAIL EXTENSION alone.
     * Growing by the plain geometric target left a 10000-element tail against
     * an 11064-element mesh; the sparse extend target must cover it.
     */
    @org.junit.jupiter.api.Test
    void fragmentedArenaNeedsTheRequestToFitInTheTailExtension() {
        MmsArenaAllocator arena = new MmsArenaAllocator(20000);
        MmsArenaAllocator.Segment a = arena.alloc(5000); // carved at the tail
        MmsArenaAllocator.Segment b = arena.alloc(5000);
        MmsArenaAllocator.Segment c = arena.alloc(5000);
        arena.free(b); // fragmented: free spans of 5000, 5000 — used 10000
        assertEquals(null, arena.alloc(11064));

        // The old buggy target (max(needed*1.125, cap*1.5) = 30000) adds a
        // 10000-element tail behind live segment 'a' — still no fit.
        arena.extendTo(30000);
        arena.checkInvariants();
        assertEquals(null, arena.alloc(11064));

        // The fixed target guarantees capacity + length + reserve.
        long target = MmsChunkRegion.sparseExtendTarget(30000, arena.capacity(),
            11064, 0.125, 4);
        arena.extendTo(target);
        arena.checkInvariants();
        MmsArenaAllocator.Segment big = arena.alloc(11064);
        org.junit.jupiter.api.Assertions.assertNotNull(big);
        arena.checkInvariants();
        // The untouched survivors keep their offsets (sparse invariant).
        assertEquals(15000, a.offset());
        assertEquals(5000, c.offset());
    }

    @org.junit.jupiter.api.Test
    void forEachLiveVisitsAllocationsInOffsetOrder() {
        MmsArenaAllocator arena = new MmsArenaAllocator(100);
        MmsArenaAllocator.Segment a = arena.alloc(10);
        MmsArenaAllocator.Segment b = arena.alloc(20);
        MmsArenaAllocator.Segment c = arena.alloc(30);
        arena.free(b);

        java.util.List<int[]> seen = new java.util.ArrayList<>();
        arena.forEachLive((offset, length) -> seen.add(new int[] {offset, length}));
        assertEquals(2, seen.size());
        // Offset order, live only.
        org.junit.jupiter.api.Assertions.assertTrue(seen.get(0)[0] < seen.get(1)[0]);
        long total = seen.stream().mapToLong(r -> r[1]).sum();
        assertEquals(a.length() + c.length(), total);
        assertEquals(arena.used(), total);
    }
}
