package com.stonebreak.world.leaves;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf-decay mechanics over {@link FakeLeafWorld}: detached foliage decays fast
 * (but staggered, not instant), foliage still anchored to a log survives, the
 * radius bound is inclusive, hand-placed floating leaves follow the same rule,
 * a re-placed log rescues a scheduled leaf, and unloaded chunks purge pending
 * work.
 */
class LeafDecaySystemTest {

    private static final int SETTLE_TICKS = 100;

    // ===== 1. An intact tree never decays =====

    @Test
    void intactTreeNeverDecays() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(10, 11, 10, BlockType.WOOD);
        world.setBlock(10, 12, 10, BlockType.LEAVES);
        world.setBlock(10, 13, 10, BlockType.LEAVES);
        world.setBlock(10, 14, 10, BlockType.LEAVES);
        world.setBlock(11, 12, 10, BlockType.LEAVES);
        int total = 4;

        sim.advanceTicks(SETTLE_TICKS);
        assertEquals(total, world.countLeaves());
        assertEquals(0, world.removals.size());
        assertEquals(0, sim.getQueuedRecomputeCount());
    }

    // ===== 2. Chopping the trunk decays only the orphaned canopy =====

    @Test
    void choppingTrunkDecaysOrphanedCanopyOnly() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // Four-log trunk with a canopy sitting orthogonally on top of it.
        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(10, 11, 10, BlockType.WOOD);
        world.setBlock(10, 12, 10, BlockType.WOOD);
        world.setBlock(10, 13, 10, BlockType.WOOD);
        world.setBlock(10, 14, 10, BlockType.LEAVES);
        world.setBlock(10, 15, 10, BlockType.LEAVES);
        world.setBlock(11, 14, 10, BlockType.LEAVES);
        world.setBlock(9, 14, 10, BlockType.LEAVES);

        // A second, untouched tree far enough that its logs only anchor its own leaves.
        world.setBlock(16, 10, 10, BlockType.WOOD);
        world.setBlock(16, 11, 10, BlockType.LEAVES);
        world.setBlock(17, 10, 10, BlockType.LEAVES);

        // Chop the first tree down to the ground.
        world.placeBlock(sim, 10, 13, 10, BlockType.AIR);
        world.placeBlock(sim, 10, 12, 10, BlockType.AIR);
        world.placeBlock(sim, 10, 11, 10, BlockType.AIR);
        world.placeBlock(sim, 10, 10, 10, BlockType.AIR);
        sim.advanceTicks(SETTLE_TICKS);

        assertFalse(world.isLeaf(10, 14, 10), "orphaned canopy must decay");
        assertFalse(world.isLeaf(10, 15, 10), "orphaned canopy must decay");
        assertFalse(world.isLeaf(11, 14, 10), "orphaned canopy must decay");
        assertFalse(world.isLeaf(9, 14, 10), "orphaned canopy must decay");

        assertTrue(world.isLeaf(16, 11, 10), "leaves anchored to the remaining tree must survive");
        assertTrue(world.isLeaf(17, 10, 10), "leaves anchored to the remaining tree must survive");
    }

    // ===== 3. Diagonal-only contact never anchors a leaf =====

    @Test
    void diagonalOnlyLeafToLogDecays() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(10, 10, 10, BlockType.WOOD);
        // (11,11,10) is diagonal to the log; no orthogonal leaf/log chain connects it.
        world.placeBlock(sim, 11, 11, 10, BlockType.LEAVES);
        sim.advanceTicks(SETTLE_TICKS);

        assertFalse(world.isLeaf(11, 11, 10), "a leaf only diagonal to a log must decay");
    }

    @Test
    void diagonalLeafConnectedThroughLeafChainSurvives() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(11, 10, 10, BlockType.LEAVES); // orthogonal bridge to the log
        // (11,11,10) is diagonal to the log but orthogonally chained through the bridge.
        world.placeBlock(sim, 11, 11, 10, BlockType.LEAVES);
        sim.advanceTicks(SETTLE_TICKS);

        assertTrue(world.isLeaf(11, 11, 10), "a leaf orthogonally chained to a log must survive");
        assertTrue(world.isLeaf(11, 10, 10), "the bridge leaf must survive");
    }

    // ===== 4. The reach radius is inclusive: k == radius survives, k == radius+1 decays =====

    @Test
    void radiusBoundaryIsInclusive() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // Distance 4 survives: a leaf column resting four cells above the log.
        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(10, 11, 10, BlockType.LEAVES);
        world.setBlock(10, 12, 10, BlockType.LEAVES);
        world.setBlock(10, 13, 10, BlockType.LEAVES);
        world.placeBlock(sim, 10, 14, 10, BlockType.LEAVES); // distance 4 from the log
        sim.advanceTicks(SETTLE_TICKS);
        assertTrue(world.isLeaf(10, 14, 10), "exactly DECAY_RADIUS from a log must survive");

        // Distance 5 decays: same column one cell taller (trigger leaf placed at the top).
        world.setBlock(20, 10, 10, BlockType.WOOD);
        world.setBlock(20, 11, 10, BlockType.LEAVES);
        world.setBlock(20, 12, 10, BlockType.LEAVES);
        world.setBlock(20, 13, 10, BlockType.LEAVES);
        world.setBlock(20, 14, 10, BlockType.LEAVES);
        world.placeBlock(sim, 20, 15, 10, BlockType.LEAVES); // distance 5 from the log
        sim.advanceTicks(SETTLE_TICKS);

        assertFalse(world.isLeaf(20, 15, 10), "one past the radius must decay");
        assertTrue(world.isLeaf(20, 14, 10), "the cell at the radius boundary must survive");
        assertTrue(world.isLeaf(20, 12, 10), "closer column cells must survive");
    }

    // ===== 5. Detached leaves decay; leaves touching a log never do =====

    @Test
    void detachedLeafDecaysButLeafBesideLogSurvives() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.placeBlock(sim, 11, 10, 10, BlockType.LEAVES);   // adjacent — supported
        world.placeBlock(sim, 20, 20, 20, BlockType.LEAVES);   // floating — detached

        sim.advanceTicks(SETTLE_TICKS);

        assertTrue(world.isLeaf(11, 10, 10), "leaf touching a log must survive");
        assertFalse(world.isLeaf(20, 20, 20), "hand-placed floating leaf must decay");
    }

    // ===== 5. Fast but not instant: the canopy collapses staggered =====

    @Test
    void detachedCanopyDecaysStaggeredNotInstant() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(10, 11, 10, BlockType.LEAVES);
        world.setBlock(11, 11, 10, BlockType.LEAVES);
        world.setBlock(9, 11, 10, BlockType.LEAVES);
        world.setBlock(10, 12, 10, BlockType.LEAVES);
        world.setBlock(10, 13, 10, BlockType.LEAVES);
        world.setBlock(11, 12, 10, BlockType.LEAVES);
        int total = 6;

        // Cut the only log.
        world.placeBlock(sim, 10, 10, 10, BlockType.AIR);

        // Nothing may vanish before the eviction pass + base delay have elapsed.
        sim.advanceTicks(10);
        assertEquals(0, world.removals.size(), "decay must not be instant");

        // Step forward one tick at a time and watch the cascade.
        int firstRemovalTick = -1;
        int distinctRemovalTicks = 0;
        int previous = 0;
        for (int t = 11; t <= 60 && world.removals.size() < total; t++) {
            sim.advanceTicks(1);
            int now = world.removals.size();
            assertTrue(now >= previous, "decay must be monotone");
            if (now > previous) {
                distinctRemovalTicks++;
                if (firstRemovalTick < 0) {
                    firstRemovalTick = t;
                    assertTrue(now < total,
                        "all " + total + " leaves vanished on tick " + t + " — decay is not staggered");
                }
            }
            previous = now;
        }

        assertEquals(total, world.removals.size(), "all detached leaves must decay");
        assertTrue(firstRemovalTick >= 11, "decay started too early (tick " + firstRemovalTick + ")");
        assertTrue(firstRemovalTick <= 40, "decay should have started by then (tick " + firstRemovalTick + ")");
        assertTrue(distinctRemovalTicks >= 2, "leaves should disappear across multiple ticks");
        assertEquals(0, sim.getQueuedDecayCount());
    }

    // ===== 6. A log placed nearby rescues a leaf already scheduled to decay =====

    @Test
    void replacedLogRescuesScheduledLeaf() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.placeBlock(sim, 20, 20, 20, BlockType.LEAVES); // detached — will be scheduled
        sim.advanceTicks(4); // let the eviction pass run and schedule the decay
        assertTrue(sim.getQueuedDecayCount() > 0, "detached leaf should be scheduled for decay");

        // A log is placed within reach before the leaf's due tick.
        world.placeBlock(sim, 20, 20, 19, BlockType.WOOD);
        world.placeBlock(sim, 20, 19, 20, BlockType.WOOD);
        sim.advanceTicks(SETTLE_TICKS);

        assertTrue(world.isLeaf(20, 20, 20), "leaf must be rescued once a log stands beside it");
        assertEquals(0, sim.getQueuedDecayCount());
    }

    // ===== 7. Non-supporting block changes never schedule work =====

    @Test
    void nonSupportingChangesCauseNoWork() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(10, 12, 10, BlockType.LEAVES);

        world.placeBlock(sim, 10, 10, 11, BlockType.STONE);
        world.placeBlock(sim, 10, 10, 12, BlockType.WATER);
        sim.advanceTicks(SETTLE_TICKS);

        assertEquals(0, sim.getQueuedRecomputeCount());
        assertEquals(0, world.removals.size(), "no support-changing update happened");
    }

    // ===== 8. Breaking a leaf can orphan the canopy it bridged =====

    @Test
    void breakingBridgeLeafDecaysTheRestOfTheCanopy() {
        FakeLeafWorld world = new FakeLeafWorld(21, 24, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // Support routes THROUGH foliage: (12..13) only reach the log via (11).
        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(11, 10, 10, BlockType.LEAVES);
        world.setBlock(12, 10, 10, BlockType.LEAVES);
        world.setBlock(13, 10, 10, BlockType.LEAVES);

        // The player breaks the bridge leaf, not a log.
        world.placeBlock(sim, 11, 10, 10, BlockType.AIR);
        sim.advanceTicks(SETTLE_TICKS);

        assertFalse(world.isLeaf(12, 10, 10), "leaves cut off by a broken leaf must decay");
        assertFalse(world.isLeaf(13, 10, 10), "leaves cut off by a broken leaf must decay");
        assertTrue(world.isLog(10, 10, 10), "the log itself is untouched");
    }

    // ===== 9. Chunk-load rescan resumes an interrupted collapse =====

    @Test
    void chunkLoadRescanResumesInterruptedCollapse() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // State as if a collapse was interrupted by eviction/quit: orphaned
        // leaves already in the blocks, with no pending work (direct setBlock —
        // the funnel never saw these).
        world.setBlock(5, 10, 5, BlockType.LEAVES);
        world.setBlock(5, 11, 5, BlockType.LEAVES);
        world.setBlock(6, 10, 5, BlockType.LEAVES);
        // A healthy tree in the same chunk must not be disturbed.
        world.setBlock(10, 10, 10, BlockType.WOOD);
        world.setBlock(10, 11, 10, BlockType.LEAVES);
        world.setBlock(10, 12, 10, BlockType.LEAVES);

        sim.onChunkLoaded(0, 0); // (5,5) and (10,10) both live in chunk (0,0)
        sim.advanceTicks(SETTLE_TICKS);

        assertFalse(world.isLeaf(5, 10, 5), "orphaned leaves must decay after the rescan");
        assertFalse(world.isLeaf(5, 11, 5), "orphaned leaves must decay after the rescan");
        assertFalse(world.isLeaf(6, 10, 5), "orphaned leaves must decay after the rescan");
        assertTrue(world.isLeaf(10, 11, 10), "the healthy tree must survive the rescan");
        assertTrue(world.isLeaf(10, 12, 10), "the healthy tree must survive the rescan");
        assertEquals(3, world.removals.size());
    }

    @Test
    void chunkLoadRescanTrustsCrossSeamSupport() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // Trunk in chunk (1,0); part of its canopy hangs into chunk (0,0). The
        // rescan of (0,0) sees no in-chunk log for these leaves and schedules
        // recomputes — whose cross-seam flood must find the trunk and keep them.
        world.setBlock(16, 10, 10, BlockType.WOOD);
        world.setBlock(15, 10, 10, BlockType.LEAVES);
        world.setBlock(14, 10, 10, BlockType.LEAVES);

        sim.onChunkLoaded(0, 0);
        sim.advanceTicks(SETTLE_TICKS);

        assertTrue(world.isLeaf(15, 10, 10), "seam-supported leaves must survive the rescan");
        assertTrue(world.isLeaf(14, 10, 10), "seam-supported leaves must survive the rescan");
        assertEquals(0, world.removals.size());
    }

    @Test
    void rescanOfLeaflessChunkSchedulesNothing() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.setBlock(3, 10, 3, BlockType.STONE);
        world.setBlock(4, 10, 3, BlockType.WOOD); // a log alone schedules nothing either

        sim.onChunkLoaded(0, 0);

        assertEquals(0, sim.getQueuedRecomputeCount());
        assertEquals(0, sim.getQueuedDecayCount());
    }

    // ===== 10. Partial information never destroys a block =====

    @Test
    void decayRefusesToActBesideUnloadedChunks() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // Detached leaf whose radius-4 anchor neighborhood pokes outside the
        // loaded region (x-4 = -2): a log could be sitting in that unloaded
        // space, so the decay must refuse rather than guess.
        world.placeBlock(sim, 2, 10, 10, BlockType.LEAVES);
        sim.advanceTicks(SETTLE_TICKS);

        assertTrue(world.isLeaf(2, 10, 10),
            "a leaf must never decay while part of its anchor range is unloaded");
        assertEquals(0, world.removals.size());
        assertEquals(0, sim.getQueuedDecayCount(), "the refused decay is consumed, not retried");
    }

    // ===== 11. Unloading a chunk purges its pending work =====

    @Test
    void chunkUnloadPurgesPendingWork() {
        FakeLeafWorld world = new FakeLeafWorld(25, 25, 25);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        world.placeBlock(sim, 20, 20, 20, BlockType.LEAVES); // detached
        sim.advanceTicks(4);
        assertTrue(sim.getQueuedDecayCount() > 0);

        // (20,20,20) lives in chunk (1,1) with the world's 16-wide chunks.
        sim.onChunkUnloaded(1, 1);

        assertEquals(0, sim.getQueuedDecayCount());
        assertEquals(0, sim.getQueuedRecomputeCount());
    }

    // ===== 12. The reachability flood never descends below the world floor =====

    @Test
    void floodDoesNotDescendBelowWorldFloor() {
        FakeLeafWorld world = new FakeLeafWorld(21, 16, 21);
        LeafDecaySystem sim = new LeafDecaySystem(world);

        // A detached leaf sitting on the floor (y=0): the flood must not try to
        // pack a negative y ("y=-1" is un-packable by the navigation key).
        world.placeBlock(sim, 10, 0, 10, BlockType.LEAVES);
        sim.advanceTicks(SETTLE_TICKS);

        assertFalse(world.isLeaf(10, 0, 10), "floor leaf detached from any log must decay");
    }
}
