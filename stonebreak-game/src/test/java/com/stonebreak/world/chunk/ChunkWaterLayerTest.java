package com.stonebreak.world.chunk;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encoding and invariant behavior of the chunk-owned water layer. */
class ChunkWaterLayerTest {

    @Test
    void absentCellsReadAsSource() {
        ChunkWaterLayer layer = new ChunkWaterLayer();
        assertEquals(ChunkWaterLayer.SOURCE, layer.get(3, 64, 9));
        assertTrue(layer.isEmpty());
    }

    @Test
    void roundTripsFlowingAndFalling() {
        ChunkWaterLayer layer = new ChunkWaterLayer();
        layer.set(0, 0, 0, 1);
        layer.set(15, 255, 15, 7);
        layer.set(8, 128, 8, ChunkWaterLayer.FALLING);

        assertEquals(1, layer.get(0, 0, 0));
        assertEquals(7, layer.get(15, 255, 15));
        assertEquals(ChunkWaterLayer.FALLING, layer.get(8, 128, 8));
        assertEquals(3, layer.size());
    }

    @Test
    void settingSourceRemovesEntry() {
        ChunkWaterLayer layer = new ChunkWaterLayer();
        layer.set(5, 70, 5, 4);
        assertEquals(1, layer.size());

        layer.set(5, 70, 5, ChunkWaterLayer.SOURCE);
        assertTrue(layer.isEmpty());
        assertEquals(ChunkWaterLayer.SOURCE, layer.get(5, 70, 5));
    }

    @Test
    void removeAndClear() {
        ChunkWaterLayer layer = new ChunkWaterLayer();
        layer.set(1, 10, 1, 2);
        layer.set(2, 10, 2, 3);

        layer.remove(1, 10, 1);
        assertEquals(ChunkWaterLayer.SOURCE, layer.get(1, 10, 1));
        assertEquals(1, layer.size());

        layer.clear();
        assertTrue(layer.isEmpty());
    }

    @Test
    void rejectsOutOfRangeValues() {
        ChunkWaterLayer layer = new ChunkWaterLayer();
        assertThrows(IllegalArgumentException.class, () -> layer.set(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> layer.set(0, 0, 0, 9));
    }

    @Test
    void forEachVisitsEveryEntryWithLocalCoords() {
        ChunkWaterLayer layer = new ChunkWaterLayer();
        layer.set(0, 0, 0, 1);
        layer.set(15, 200, 3, 6);
        layer.set(7, 42, 15, ChunkWaterLayer.FALLING);

        Map<String, Integer> seen = new HashMap<>();
        layer.forEach((x, y, z, value) -> seen.put(x + "," + y + "," + z, value));

        assertEquals(3, seen.size());
        assertEquals(1, seen.get("0,0,0"));
        assertEquals(6, seen.get("15,200,3"));
        assertEquals(ChunkWaterLayer.FALLING, seen.get("7,42,15"));
    }
}
