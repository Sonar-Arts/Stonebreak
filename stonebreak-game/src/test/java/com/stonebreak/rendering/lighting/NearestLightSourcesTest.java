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

    @Test
    void heldLightsRankWithPlacedSourcesByDistance() {
        com.stonebreak.mobs.entities.Entity near = org.mockito.Mockito.mock(com.stonebreak.mobs.entities.Entity.class);
        com.stonebreak.mobs.entities.Entity far = org.mockito.Mockito.mock(com.stonebreak.mobs.entities.Entity.class);
        NearestLightSources sources = new NearestLightSources(3);
        sources.clear(3);
        sources.offer(far, 50);
        sources.offer(new BlockPos(3, 0, 0), 9);
        sources.offer(near, 1);
        sources.offer(new BlockPos(9, 0, 0), 81);

        assertEquals(3, sources.size());
        assertSame(near, sources.holder(0));
        assertNull(sources.get(0));
        assertEquals(3, sources.get(1).x());
        assertNull(sources.holder(1));
        assertSame(far, sources.holder(2));
    }

    @Test
    void aPlacedSourceWinsATieWithAHeldLight() {
        com.stonebreak.mobs.entities.Entity holder = org.mockito.Mockito.mock(com.stonebreak.mobs.entities.Entity.class);
        NearestLightSources sources = new NearestLightSources(2);
        sources.clear(2);
        sources.offer(holder, 4);
        sources.offer(new BlockPos(2, 0, 0), 4);

        assertEquals(2, sources.get(0).x());
        assertSame(holder, sources.holder(1));
        sources.clear(2);
        assertNull(sources.holder(0));
    }
}
