package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NearestLightSourcesTest {
    @Test
    void keepsNearestRegardlessOfInputOrderAndReservesHeldSlot() {
        NearestLightSources sources = new NearestLightSources(4);
        sources.clear(3);
        for (int x : new int[]{9, 2, 1, 8, 3, 4, 0}) sources.offer(new BlockPos(x, 0, 0), x * x);
        assertEquals(3, sources.size());
        for (int i = 0; i < 3; i++) assertEquals(i, sources.get(i).x());
        sources.clear(0);
        sources.offer(new BlockPos(0, 0, 0), 0);
        assertEquals(0, sources.size());
    }

    @Test
    void tiesRemainStableAndClearDropsOldSources() {
        NearestLightSources sources = new NearestLightSources(2);
        sources.clear(2);
        sources.offer(new BlockPos(1, 0, 0), 1);
        sources.offer(new BlockPos(-1, 0, 0), 1);
        assertEquals(-1, sources.get(0).x());
        sources.clear(2);
        assertEquals(0, sources.size());
        sources.offer(new BlockPos(-1, 0, 0), 1);
        sources.offer(new BlockPos(1, 0, 0), 1);
        assertEquals(-1, sources.get(0).x());
    }
}
