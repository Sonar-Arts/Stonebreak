package com.openmason.engine.cenda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native scratch of a retained flex tree (#282 hardening): buffers grow geometrically and an
 * outgrown buffer is freed, so a list that grows one row per frame keeps O(n) native memory.
 */
class FlexLayoutScratchTest {

    @Test
    void growthAtLeastDoubles() {
        assertEquals(4096, FlexLayoutTree.grow(0, 100, 4096));
        assertEquals(8192, FlexLayoutTree.grow(4096, 4100, 4096));
        assertEquals(100_000, FlexLayoutTree.grow(8192, 100_000, 4096));
    }

    @Test
    void aTreeGrowingOneNodeAtATimeKeepsLinearScratch() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        try (FlexLayoutTree tree = CendaFlex.newTree(1)) {
            int root = tree.newNode();
            int n = 4000;
            int[] ids = new int[n];
            float[] rects = new float[n * 4];
            float[] records = new float[FlexRecord.STRIDE * n];
            for (int i = 0; i < n; i++) {
                FlexRecord.clear(records, i * FlexRecord.STRIDE);
            }
            int count = 0;
            for (int i = 0; i < n; i++) {
                int node = tree.newNode();
                tree.insert(root, node, -1);
                ids[count++] = node;
                tree.setStyles(ids, records, count); // every node, every "frame"
                tree.read(root, ids, count, rects);
            }
            long needed = (long) n * FlexRecord.STRIDE * Float.BYTES + (long) n * Integer.BYTES;
            assertTrue(tree.scratchBytes() <= 4 * needed + 8192,
                "held " + tree.scratchBytes() + " bytes for " + needed + " needed");
        }
    }

    @Test
    void closeIsIdempotentAndFreesScratch() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        FlexLayoutTree tree = CendaFlex.newTree(1);
        int root = tree.newNode();
        tree.read(root, new int[]{root}, 1, new float[4]);
        tree.close();
        tree.close();
        assertTrue(tree.isClosed());
        assertEquals(0, tree.scratchBytes());
    }
}
