package com.stonebreak.world.fastlod;

import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Node-border foundations reach below the lower column (no fixed y=0 floor in a [-384, 640) world). */
class FastLodFoundationTest {

    @Test
    void foundationsReachBelowTheLowerColumnOnA16Grid() {
        for (int top = WorldConfiguration.MIN_Y; top < WorldConfiguration.MAX_Y; top += 7) {
            float bottom = FastLodMesher.foundationBottom(top);
            assertTrue(bottom <= top - FastLodMesher.FOUNDATION_DEPTH || bottom == WorldConfiguration.MIN_Y,
                    "top " + top + " bottom " + bottom);
            assertTrue(bottom >= WorldConfiguration.MIN_Y);
            assertEquals(0f, Math.floorMod((int) bottom, 16), "quantised so walls merge");
        }
        assertEquals(-144f, FastLodMesher.foundationBottom(-100)); // a deep seabed column: floor16(-132)
    }

    @Test
    void theLodYBaseCoversTheWholeWorld() {
        // LODQUAD16: 11 bits of half blocks from the base = 1024 blocks.
        assertEquals(WorldConfiguration.MIN_Y, FastLodMesher.LOD_Y_BASE);
        assertTrue(FastLodMesher.LOD_Y_BASE + 2047 / 2.0 >= WorldConfiguration.MAX_Y - 0.5);
    }
}
