package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #345: a chunk's water load scan runs on the sim's next logical tick, not inside the
 * chunk install — a reload installs hundreds of chunks in one server tick, and scanning each
 * inline pushed that tick past 50 ms. The scan must still find exposed water, skip chunks that
 * left before it ran, and skip sections that hold no water without missing any that do.
 */
@Tag("regression")
class WaterLoadScanDeferralTest {

    private static Chunk chunkWithExposedWater() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(5, 40, 5, BlockType.STONE);
        chunk.setBlock(5, 41, 5, BlockType.WATER); // air beside it: exposed
        return chunk;
    }

    @Test
    void scanRunsOnTheNextTick() {
        WaterSim sim = new WaterSim(new FakeFlowWorld(16, 256, 16));
        sim.onChunkLoaded(chunkWithExposedWater());
        assertEquals(0, sim.getQueuedUpdateCount(), "the install itself schedules nothing");

        sim.advanceTicks(1);
        assertTrue(sim.getQueuedUpdateCount() > 0, "the deferred scan registers the exposed water");
    }

    @Test
    void chunkUnloadedBeforeItsScanIsSkipped() {
        WaterSim sim = new WaterSim(new FakeFlowWorld(16, 256, 16));
        Chunk chunk = chunkWithExposedWater();
        sim.onChunkLoaded(chunk);
        sim.onChunkUnloaded(chunk);

        sim.advanceTicks(1);
        assertEquals(0, sim.getQueuedUpdateCount());
    }

    @Test
    void waterBodyOverAirExposesOnlyItsBottomLayer() {
        // Section 3 (y 48..63) entirely water over air, every other section dry: only the
        // bottom layer can flow, and skipping the dry sections must not lose it.
        Chunk chunk = new Chunk(0, 0);
        for (int y = 48; y < 64; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    chunk.setBlock(x, y, z, BlockType.WATER);
        WaterSim sim = new WaterSim(new FakeFlowWorld(16, 256, 16));
        sim.onChunkLoaded(chunk);
        sim.advanceTicks(1);
        assertEquals(256, sim.getQueuedUpdateCount());
    }
}
