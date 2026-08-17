package com.stonebreak.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snow-layer ledger behind accumulation, collision height, saves and replication. The rules
 * worth pinning: untracked snow reads as one layer (a placed snow block), stacking caps at eight,
 * the mutation listener fires only on real changes (it drives replication, so a spurious fire is
 * a spurious packet), per-chunk iteration and unload pruning agree on chunk membership, and the
 * key packing survives negative coordinates.
 */
class SnowLayerManagerTest {

    private final SnowLayerManager snow = new SnowLayerManager();

    @Test
    void untrackedSnowReadsAsOneLayer() {
        assertEquals(1, snow.getSnowLayers(5, 70, 5));
        assertEquals(0.125f, snow.getSnowHeight(5, 70, 5), 1e-6f);
    }

    @Test
    void layersStoreAndConvertToHeight() {
        snow.setSnowLayers(5, 70, 5, 6);

        assertEquals(6, snow.getSnowLayers(5, 70, 5));
        assertEquals(0.75f, snow.getSnowHeight(5, 70, 5), 1e-6f);
    }

    @Test
    void negativeCoordinatesKeepTheirOwnEntries() {
        snow.setSnowLayers(-17, 70, -33, 4);

        assertEquals(4, snow.getSnowLayers(-17, 70, -33));
        assertEquals(1, snow.getSnowLayers(17, 70, 33),
                "sign must survive the key packing — these are different columns");
    }

    @Test
    void stackingStopsAtEightLayers() {
        for (int i = 0; i < 7; i++) {
            assertTrue(snow.addSnowLayer(2, 70, 2), "layer " + (i + 2) + " should stack");
        }

        assertEquals(8, snow.getSnowLayers(2, 70, 2));
        assertFalse(snow.addSnowLayer(2, 70, 2), "a full block of snow takes no more");
    }

    @Test
    void outOfRangeLayerCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> snow.setSnowLayers(0, 70, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> snow.setSnowLayers(0, 70, 0, 9));
    }

    @Test
    void removalResetsTheColumnToUntracked() {
        snow.setSnowLayers(3, 70, 3, 5);

        snow.removeSnowLayers(3, 70, 3);

        assertEquals(1, snow.getSnowLayers(3, 70, 3));
    }

    @Test
    void theMutationListenerFiresOnlyOnRealChanges() {
        List<String> events = new ArrayList<>();
        snow.setMutationListener((x, y, z, layers) -> events.add(x + "," + y + "," + z + ":" + layers));

        snow.setSnowLayers(1, 70, 1, 3);
        snow.setSnowLayers(1, 70, 1, 3); // same value again — replication must stay quiet
        snow.setSnowLayers(1, 70, 1, 4);
        snow.removeSnowLayers(1, 70, 1);
        snow.removeSnowLayers(1, 70, 1); // already gone

        assertEquals(List.of("1,70,1:3", "1,70,1:4", "1,70,1:0"), events);
    }

    @Test
    void putRawHydratesQuietlyAndClampsDefensively() {
        List<String> events = new ArrayList<>();
        snow.setMutationListener((x, y, z, layers) -> events.add("fired"));

        snow.putRaw(4, 70, 4, 99);
        snow.putRaw(6, 70, 6, -5);

        assertEquals(8, snow.getSnowLayers(4, 70, 4));
        assertEquals(1, snow.getSnowLayers(6, 70, 6));
        assertTrue(events.isEmpty(), "hydration from disk or wire must not re-replicate");
    }

    @Test
    void perChunkIterationVisitsExactlyThatChunk() {
        snow.setSnowLayers(1, 70, 1, 2);    // chunk (0, 0)
        snow.setSnowLayers(-1, 70, 1, 3);   // chunk (-1, 0)
        snow.setSnowLayers(17, 70, 1, 4);   // chunk (1, 0)

        List<String> visited = new ArrayList<>();
        snow.forEachInChunk(0, 0, (x, y, z, layers) -> visited.add(x + "," + y + "," + z + ":" + layers));

        assertEquals(List.of("1,70,1:2"), visited);
    }

    @Test
    void unloadingAChunkDropsOnlyItsEntries() {
        snow.setSnowLayers(1, 70, 1, 2);    // chunk (0, 0)
        snow.setSnowLayers(-1, 70, 1, 3);   // chunk (-1, 0)

        snow.onChunkUnloaded(0, 0);

        assertEquals(1, snow.getSnowLayers(1, 70, 1), "the unloaded chunk's entry is gone");
        assertEquals(3, snow.getSnowLayers(-1, 70, 1), "the neighbour keeps its snow");
    }

    @Test
    void clearEmptiesTheLedger() {
        snow.setSnowLayers(1, 70, 1, 5);

        snow.clear();

        assertEquals(1, snow.getSnowLayers(1, 70, 1));
    }
}
