package com.openmason.engine.rendering.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexedDrawBatchTest {
    @Test
    void joinsThousandsOfAdjacentFacesWithoutChangingTheirRange() {
        var builder = new IndexedDrawBatch.Builder();
        for (int i = 0; i < 20_000; i++) builder.add(7, i * 6, 6);
        assertEquals(List.of(new IndexedDrawBatch(7, 0, 120_000)), builder.build());
    }

    @Test
    void retainsMaterialChangesGapsOverlapsAndOriginalOrder() {
        List<IndexedDrawBatch> ranges = List.of(
                new IndexedDrawBatch(1, 0, 6),
                new IndexedDrawBatch(2, 6, 3),
                new IndexedDrawBatch(1, 9, 3),
                new IndexedDrawBatch(1, 15, 3),
                new IndexedDrawBatch(1, 15, 6),
                new IndexedDrawBatch(1, 3, 3));
        var builder = new IndexedDrawBatch.Builder();
        for (var r : ranges) builder.add(r.materialId(), r.indexStart(), r.indexCount());
        assertEquals(ranges, builder.build());
    }

    @Test
    void emptyRangesDoNotSubmitGeometryAndResultsAreImmutableSnapshots() {
        var builder = new IndexedDrawBatch.Builder();
        builder.add(0, 0, 0);
        assertTrue(builder.build().isEmpty());
        builder.add(1, 0, 6);
        var first = builder.build();
        builder.add(2, 6, 3);
        assertEquals(1, first.size());
        assertEquals(2, builder.build().size());
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test
    void rejectsInvalidRangesAndUsesLongByteOffsets() {
        var builder = new IndexedDrawBatch.Builder();
        assertThrows(IllegalArgumentException.class, () -> builder.add(0, -1, 3));
        assertThrows(IllegalArgumentException.class, () -> builder.add(0, 0, -3));
        assertThrows(IllegalArgumentException.class, () -> builder.add(0, Integer.MAX_VALUE, 3));
        assertEquals(4_000_000_000L, new IndexedDrawBatch(0, 1_000_000_000, 3).byteOffset());
    }
}
