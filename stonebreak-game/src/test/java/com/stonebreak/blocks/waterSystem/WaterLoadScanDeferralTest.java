package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #345: a cube's water load scan runs on the sim's next logical tick, not inside the cube
 * install — a reload installs hundreds of cubes in one server tick, and scanning each inline pushed
 * that tick past 50 ms. The scan must still find exposed water, skip cubes that left before it ran,
 * and not miss water in an otherwise dry cube.
 */
@Tag("regression")
class WaterLoadScanDeferralTest {

    /** One 32^3 cube (0, 0, 0) plus a cube of headroom above, everything else unloaded. */
    private static FakeFlowWorld worldWithExposedWater() {
        FakeFlowWorld world = new FakeFlowWorld(32, 64, 32);
        world.setBlock(5, 20, 5, BlockType.STONE);
        world.setBlock(5, 21, 5, BlockType.WATER); // air beside it: exposed
        return world;
    }

    @Test
    void scanRunsOnTheNextTick() {
        FakeFlowWorld world = worldWithExposedWater();
        WaterSim sim = new WaterSim(world);
        sim.onCubeInstalled(0, 0, 0);
        assertEquals(0, sim.getQueuedUpdateCount(), "the install itself schedules nothing");

        sim.advanceTicks(1);
        assertTrue(sim.getQueuedUpdateCount() > 0, "the deferred scan registers the exposed water");
    }

    @Test
    void cubeUnloadedBeforeItsScanIsSkipped() {
        WaterSim sim = new WaterSim(worldWithExposedWater());
        sim.onCubeInstalled(0, 0, 0);
        sim.onCubeUnloaded(0, 0, 0);

        sim.advanceTicks(1);
        assertEquals(0, sim.getQueuedUpdateCount());
    }

    @Test
    void waterBodyOverAirExposesOnlyItsBottomLayer() {
        // y 16..31 of the cube is water over air: only its bottom layer can flow.
        FakeFlowWorld world = new FakeFlowWorld(32, 32, 32);
        for (int y = 16; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    world.setBlock(x, y, z, BlockType.WATER);
        WaterSim sim = new WaterSim(world);
        sim.onCubeInstalled(0, 0, 0);
        sim.advanceTicks(1);
        assertEquals(32 * 32, sim.getQueuedUpdateCount());
    }

    @Test
    void flowingValuesAreScheduledEvenWhenEnclosed() {
        FakeFlowWorld world = new FakeFlowWorld(32, 32, 32);
        world.fillLayer(0, BlockType.STONE);
        world.fillLayer(1, BlockType.WATER);
        world.fillLayer(2, BlockType.STONE);
        world.setWater(7, 1, 7, 3); // flowing, enclosed by water and stone: not exposed, but has a value
        WaterSim sim = new WaterSim(world);
        sim.onCubeInstalled(0, 0, 0);
        sim.advanceTicks(1);
        assertEquals(1, sim.getQueuedUpdateCount());
    }
}
