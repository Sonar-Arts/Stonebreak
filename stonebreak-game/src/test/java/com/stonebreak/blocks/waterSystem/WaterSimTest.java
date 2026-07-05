package com.stonebreak.blocks.waterSystem;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.ChunkWaterLayer;

import static com.stonebreak.blocks.waterSystem.FakeFlowWorld.tickUntilQuiet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla water mechanics over {@link FakeFlowWorld}: spread diamond, waterfall
 * landing, hole-seeking, recession, the infinite-source rule, convergence,
 * fragile blocks, source protection and world-edge safety.
 */
class WaterSimTest {

    private static final int FALLING = ChunkWaterLayer.FALLING;
    private static final int SETTLE_TICKS = 4000;

    // ===== 1. Flat-ground diamond =====

    @Test
    void sourceOnFlatGroundSpreadsSevenBlockDiamond() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(10, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        // Level == Manhattan distance from the source, out to 7.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                int distance = Math.abs(dx) + Math.abs(dz);
                int x = 20 + dx;
                int z = 20 + dz;
                if (distance <= 7) {
                    assertEquals(BlockType.WATER, world.getBlock(x, 11, z),
                        "expected water at distance " + distance + " (" + dx + "," + dz + ")");
                    assertEquals(distance, world.getWater(x, 11, z),
                        "wrong level at distance " + distance + " (" + dx + "," + dz + ")");
                } else {
                    assertEquals(BlockType.AIR, world.getBlock(x, 11, z),
                        "water overshot to distance " + distance + " (" + dx + "," + dz + ")");
                }
            }
        }
        // 1 source + 4d cells per ring d=1..7
        assertEquals(113, world.countWaterBlocks());
        assertEquals(1, world.countSources());
    }

    // ===== 2. Waterfall: falling column + full-strength puddle, no minted sources =====

    @Test
    void fallingColumnLandsAsFullStrengthPuddle() {
        FakeFlowWorld world = new FakeFlowWorld(40, 20, 40);
        world.fillLayer(4, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 14, 20); // midair source
        tickUntilQuiet(sim, SETTLE_TICKS);

        // Midair source pours straight down — no sideways spread at source height.
        assertEquals(BlockType.AIR, world.getBlock(21, 14, 20));
        assertEquals(BlockType.AIR, world.getBlock(19, 14, 20));

        // The whole column below the source is falling water.
        for (int y = 5; y <= 13; y++) {
            assertEquals(BlockType.WATER, world.getBlock(20, y, 20), "column gap at y=" + y);
            assertEquals(FALLING, world.getWater(20, y, 20), "column cell not falling at y=" + y);
        }

        // The landed column spreads at full source strength: neighbors at
        // Manhattan distance d get level d, reach 7 — the vanilla waterfall puddle.
        for (int d = 1; d <= 7; d++) {
            assertEquals(BlockType.WATER, world.getBlock(20 + d, 5, 20));
            assertEquals(d, world.getWater(20 + d, 5, 20), "puddle level wrong at distance " + d);
        }
        assertEquals(BlockType.AIR, world.getBlock(28, 5, 20));

        // Regression: waterfall bases must never mint new sources.
        assertEquals(1, world.countSources());
    }

    // ===== 3. Hole-seeking =====

    @Test
    void flowPrefersNearestHoleWithinRange() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(0, BlockType.STONE);
        world.fillLayer(10, BlockType.STONE);
        world.setBlock(23, 10, 20, BlockType.AIR); // hole 3 east of the source
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        // Directed flow: east toward the hole only.
        assertEquals(BlockType.WATER, world.getBlock(21, 11, 20));
        assertEquals(BlockType.WATER, world.getBlock(22, 11, 20));
        assertEquals(BlockType.WATER, world.getBlock(23, 11, 20));
        assertEquals(BlockType.AIR, world.getBlock(19, 11, 20), "flowed away from the hole");
        assertEquals(BlockType.AIR, world.getBlock(20, 11, 19), "flowed sideways despite hole");
        assertEquals(BlockType.AIR, world.getBlock(20, 11, 21), "flowed sideways despite hole");

        // And it pours down through the hole.
        assertEquals(FALLING, world.getWater(23, 10, 20));
    }

    @Test
    void holeBeyondSearchRangeMeansAllDirections() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(0, BlockType.STONE);
        world.fillLayer(10, BlockType.STONE);
        world.setBlock(25, 10, 20, BlockType.AIR); // hole 5 east — out of the 4-block scan
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(BlockType.WATER, world.getBlock(19, 11, 20), "west should flow (no hole in range)");
        assertEquals(BlockType.WATER, world.getBlock(20, 11, 19));
        assertEquals(BlockType.WATER, world.getBlock(20, 11, 21));
        assertEquals(BlockType.WATER, world.getBlock(21, 11, 20));
    }

    // ===== 4. Recession =====

    @Test
    void removingSourceDrainsAllFlow() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(10, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);
        assertEquals(113, world.countWaterBlocks());

        world.placeBlock(sim, 20, 11, 20, BlockType.AIR);
        tickUntilQuiet(sim, SETTLE_TICKS);
        assertEquals(0, world.countWaterBlocks(), "flow should fully recede once the source is gone");
    }

    @Test
    void cuttingWaterfallSupplyDrainsColumnAndPuddle() {
        FakeFlowWorld world = new FakeFlowWorld(40, 20, 40);
        world.fillLayer(4, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 14, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);
        assertTrue(world.countWaterBlocks() > 100);

        world.placeBlock(sim, 20, 14, 20, BlockType.AIR);
        tickUntilQuiet(sim, SETTLE_TICKS);
        assertEquals(0, world.countWaterBlocks());
    }

    // ===== 5. Infinite-source rule =====

    @Test
    void gapBetweenTwoSourcesOverSolidBecomesSource() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(10, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        world.placeSource(sim, 22, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(BlockType.WATER, world.getBlock(21, 11, 20));
        assertEquals(0, world.getWater(21, 11, 20), "S _ S gap over solid must become a source");
    }

    @Test
    void gapBetweenTwoSourcesOverAirStaysFlowing() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(0, BlockType.STONE);
        world.fillLayer(10, BlockType.STONE);
        world.setBlock(21, 10, 20, BlockType.AIR); // hole directly under the gap
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        world.placeSource(sim, 22, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(BlockType.WATER, world.getBlock(21, 11, 20));
        assertNotEquals(0, world.getWater(21, 11, 20),
            "gap over a hole must not become a source (below is neither solid nor a source)");
        assertEquals(2, world.countSources());
    }

    // ===== 6. Convergence / no oscillation =====

    @Test
    void unsuppliedFlowIslandDriesUp() {
        FakeFlowWorld world = new FakeFlowWorld(16, 16, 16);
        WaterSim sim = new WaterSim(world);

        world.setBlock(5, 5, 5, BlockType.WATER);
        world.setWater(5, 5, 5, 3); // hand-crafted inconsistent state: flow with no supply
        sim.schedule(5, 5, 5);
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(BlockType.AIR, world.getBlock(5, 5, 5));
    }

    @Test
    void settledWaterfallIsAFixedPoint() {
        FakeFlowWorld world = new FakeFlowWorld(40, 20, 40);
        world.fillLayer(4, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 14, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);
        long settled = world.stateHash();

        // Re-evaluate the landing zone; a correct recompute must not disturb it.
        // (Guards the falling-supplies-at-full-strength rule — if landed falling
        // water didn't sustain its level-1 neighbors, the puddle would oscillate.)
        sim.schedule(20, 5, 20);
        for (int d = 1; d <= 3; d++) {
            sim.schedule(20 + d, 5, 20);
            sim.schedule(20 - d, 5, 20);
            sim.schedule(20, 5, 20 + d);
            sim.schedule(20, 5, 20 - d);
        }
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(settled, world.stateHash(), "settled waterfall must be a fixed point");
    }

    // ===== 7. Fragile blocks =====

    @Test
    void flowingWaterBreaksFlowers() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(10, BlockType.STONE);
        world.setBlock(21, 11, 20, BlockType.ROSE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(BlockType.WATER, world.getBlock(21, 11, 20));
        assertTrue(world.fragileDrops.stream()
                .anyMatch(p -> p[0] == 21 && p[1] == 11 && p[2] == 20),
            "flower should pop as a drop when flooded");
    }

    // ===== 8. Source protection =====

    @Test
    void flowNeverOverwritesSources() {
        FakeFlowWorld world = new FakeFlowWorld(40, 16, 40);
        world.fillLayer(10, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 20, 11, 20);
        world.placeSource(sim, 21, 11, 20);
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(0, world.getWater(20, 11, 20));
        assertEquals(0, world.getWater(21, 11, 20));
        assertEquals(2, world.countSources(), "adjacent sources must survive with none minted");
    }

    @Test
    void fallingWaterConnectsToButDoesNotReplaceSourceBelow() {
        FakeFlowWorld world = new FakeFlowWorld(16, 20, 16);
        world.fillLayer(4, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 8, 5, 8);  // pool source on the ground
        world.placeSource(sim, 8, 10, 8); // source pouring from above
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(0, world.getWater(8, 5, 8), "source below a waterfall must survive");
        for (int y = 6; y <= 9; y++) {
            assertEquals(FALLING, world.getWater(8, y, 8));
        }
    }

    // ===== 9. World edge / unloaded chunks =====

    @Test
    void flowStopsCleanlyAtUnloadedBoundary() {
        FakeFlowWorld world = new FakeFlowWorld(8, 16, 8);
        world.fillLayer(10, BlockType.STONE);
        WaterSim sim = new WaterSim(world);

        world.placeSource(sim, 0, 11, 0); // corner: two directions lead out of the region
        tickUntilQuiet(sim, SETTLE_TICKS);

        assertEquals(BlockType.WATER, world.getBlock(1, 11, 0));
        assertEquals(BlockType.WATER, world.getBlock(0, 11, 1));
        assertEquals(0, world.getWater(0, 11, 0));
        assertFalse(world.countWaterBlocks() == 0);
    }
}
