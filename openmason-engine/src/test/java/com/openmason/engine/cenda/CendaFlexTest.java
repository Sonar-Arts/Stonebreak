package com.openmason.engine.cenda;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract of the retained Yoga tree through FFM (#287): loud load failures, batched styles,
 * change counting, measure upcalls and tree-edit validation. Skips only when the library is
 * not built; the failure-diagnostic tests run either way.
 */
@Tag("regression")
class CendaFlexTest {

    private static void assumeFlex() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    private static float[] record() {
        float[] r = new float[FlexRecord.STRIDE];
        FlexRecord.clear(r, 0);
        return r;
    }

    @Test
    void missingLibraryFailsLoudlyWithThePath(@TempDir Path dir) {
        Path missing = dir.resolve("libcenda_kernels.so");
        CendaFlexUnavailableException e = assertThrows(CendaFlexUnavailableException.class,
            () -> CendaFlex.verify(missing, CendaFlex.EXPECTED_ABI));
        assertTrue(e.getMessage().contains(missing.toString()), e.getMessage());
    }

    @Test
    void nonLibraryFileFailsLoudly(@TempDir Path dir) throws IOException {
        Path junk = Files.writeString(dir.resolve("libcenda_kernels.so"), "not an ELF");
        assertThrows(CendaFlexUnavailableException.class, () -> CendaFlex.verify(junk, CendaFlex.EXPECTED_ABI));
    }

    @Test
    void wrongAbiNamesBothVersions() {
        Path lib = CendaKernels.locateLibrary();
        assumeTrue(lib != null, "Cenda library not built");
        CendaFlexUnavailableException e = assertThrows(CendaFlexUnavailableException.class,
            () -> CendaFlex.verify(lib, 999));
        assertTrue(e.getMessage().contains("ABI " + CendaFlex.EXPECTED_ABI) && e.getMessage().contains("expects 999"),
            e.getMessage());
    }

    @Test
    void libraryWithoutFlexHostIsRejected() {
        Path libm = Path.of("/usr/lib/libm.so.6");
        assumeTrue(Files.isRegularFile(libm), "no libm at the Linux path");
        CendaFlexUnavailableException e = assertThrows(CendaFlexUnavailableException.class,
            () -> CendaFlex.verify(libm, CendaFlex.EXPECTED_ABI));
        assertTrue(e.getMessage().contains("cf_abi_version"), e.getMessage());
    }

    @Test
    void retainedTreeLaysOutAndCountsOnlyRealChanges() {
        assumeFlex();
        try (FlexLayoutTree tree = CendaFlex.newTree(1f)) {
            int root = tree.newNode();
            int a = tree.newNode();
            int b = tree.newNode();
            tree.insert(root, a, -1);
            tree.insert(root, b, -1);
            float[] recs = new float[FlexRecord.STRIDE * 3];
            FlexRecord.clear(recs, 0);
            FlexRecord.clear(recs, FlexRecord.STRIDE);
            FlexRecord.clear(recs, FlexRecord.STRIDE * 2);
            recs[FlexRecord.WIDTH] = 100;
            recs[FlexRecord.DIRECTION] = 2; // row
            recs[FlexRecord.STRIDE + FlexRecord.WIDTH] = 30;
            recs[FlexRecord.STRIDE * 2 + FlexRecord.GROW] = 1;
            int[] ids = {root, a, b};
            tree.setStyles(ids, recs, 3);
            assertEquals(3, tree.layout(root, Float.NaN, Float.NaN, null));
            float[] out = new float[12];
            tree.read(root, ids, 3, out);
            assertEquals(30, out[4 + 2], 1e-3);
            assertEquals(30, out[8], 1e-3);
            assertEquals(70, out[8 + 2], 1e-3);

            tree.setStyles(ids, recs, 3);
            assertEquals(0, tree.layout(root, Float.NaN, Float.NaN, null), "identical styles change nothing");
            assertEquals(3, tree.nodeCount());
        }
    }

    @Test
    void measuredLeavesCallBackAndRemeasureWhenDirtied() {
        assumeFlex();
        int[] calls = {0};
        float[] width = {12};
        FlexMeasure measure = (id, w, wm, h, hm, out) -> {
            calls[0]++;
            out[0] = width[0];
            out[1] = 7 + id;
        };
        try (FlexLayoutTree tree = CendaFlex.newTree(1f)) {
            int root = tree.newNode();
            int leaf = tree.newNode();
            tree.insert(root, leaf, 0);
            float[] r = record();
            r[FlexRecord.ALIGN_ITEMS] = 1; // flex-start
            float[] l = record();
            l[FlexRecord.MEASURE_ID] = 3;
            float[] both = new float[FlexRecord.STRIDE * 2];
            System.arraycopy(r, 0, both, 0, FlexRecord.STRIDE);
            System.arraycopy(l, 0, both, FlexRecord.STRIDE, FlexRecord.STRIDE);
            tree.setStyles(new int[]{root, leaf}, both, 2);
            tree.layout(root, 500, Float.NaN, measure);
            float[] out = new float[4];
            tree.read(root, new int[]{leaf}, 1, out);
            assertEquals(12, out[2], 1e-3);
            assertEquals(10, out[3], 1e-3);
            int before = calls[0];
            tree.layout(root, 500, Float.NaN, measure);
            assertEquals(before, calls[0], "a clean leaf is not measured again");
            width[0] = 40;
            tree.markDirty(leaf);
            tree.layout(root, 500, Float.NaN, measure);
            tree.read(root, new int[]{leaf}, 1, out);
            assertEquals(40, out[2], 1e-3);
        }
    }

    @Test
    void invalidEditsThrowAndClosedTreesRefuseWork() {
        assumeFlex();
        FlexLayoutTree tree = CendaFlex.newTree(1f);
        int a = tree.newNode();
        int b = tree.newNode();
        tree.insert(a, b, -1);
        IllegalStateException cycle = assertThrows(IllegalStateException.class, () -> tree.insert(b, a, -1));
        assertTrue(cycle.getMessage().contains("break the tree"), cycle.getMessage());
        tree.freeNode(b);
        IllegalStateException stale = assertThrows(IllegalStateException.class, () -> tree.markDirty(b));
        assertTrue(stale.getMessage().contains("freed"), stale.getMessage());
        tree.close();
        tree.close();
        assertTrue(tree.isClosed());
        assertThrows(IllegalStateException.class, tree::newNode);
    }
}
