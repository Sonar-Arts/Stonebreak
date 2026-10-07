package com.openmason.engine.ui.fidelity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry rules, case naming and the gate's verdicts (#296). */
class MigrationGateTest {

    @Test
    void floatScreensMustMatchExactly() {
        Map<String, float[]> legacy = Map.of("panel", new float[]{700, 260, 520, 560});
        assertTrue(GeometryComparator.compare(legacy, Map.of("panel", new float[]{700.0004f, 260, 520, 560}),
            1920, 1080, GeometryRule.FLOAT_EXACT).passed(), "float noise below 0.001 px");
        GeometryComparator.GeometryReport r = GeometryComparator.compare(legacy,
            Map.of("panel", new float[]{700.5f, 260, 520, 560}), 1920, 1080, GeometryRule.FLOAT_EXACT);
        assertFalse(r.passed());
        assertEquals(List.of("panel.x: 700.500 vs legacy 700"), r.problems());
        assertEquals(0.5, r.worst(), 1e-6);
    }

    @Test
    void integerCentredScreensGetOnePixelOnlyOnOddAxes() {
        Map<String, float[]> legacy = Map.of("slot", new float[]{100, 50, 36, 36});
        Map<String, float[]> shifted = Map.of("slot", new float[]{101, 51, 36, 36});
        assertTrue(GeometryComparator.compare(legacy, shifted, 1921, 1081, GeometryRule.INTEGER_CENTRED).passed());
        GeometryComparator.GeometryReport evenY = GeometryComparator.compare(legacy, shifted, 1921, 1080,
            GeometryRule.INTEGER_CENTRED);
        assertEquals(List.of("slot.y: 51 vs legacy 50"), evenY.problems(), "the even axis stays exact");
        assertFalse(GeometryComparator.compare(legacy, Map.of("slot", new float[]{102, 50, 36, 36}), 1921, 1081,
            GeometryRule.INTEGER_CENTRED).passed(), "never more than 1 px");
    }

    @Test
    void missingAndExtraRectsAreProblems() {
        GeometryComparator.GeometryReport r = GeometryComparator.compare(Map.of("resume", new float[4]),
            Map.of("resync", new float[4]), 800, 600, GeometryRule.FLOAT_EXACT);
        assertEquals(List.of("resume: missing", "resync: not in the legacy oracle"), r.problems());
    }

    @Test
    void caseIdsAreStableFileNames() {
        assertEquals("pause-online_1921x1081_s1_25",
            new FidelityCase("pause", "online", new FidelityCase.Viewport(1921, 1081, 1.25f)).id());
        assertEquals("furnace_800x600_s2_dpr1_5",
            new FidelityCase("furnace", "", new FidelityCase.Viewport(800, 600, 2f, 1.5f)).id());
        assertEquals(8, FidelityCase.matrix("pause", List.of("offline", "online"), FidelityCase.STANDARD).size());
        assertThrows(IllegalArgumentException.class, () -> new FidelityCase("Pause Menu", "", FidelityCase.STANDARD.get(0)));
    }

    @Test
    void theGateComparesEveryCaseAndReportsATable() {
        MigrationGate.Renderer legacy = c -> capture(c, 10, 0xFF808080);
        MigrationGate gate = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT);
        List<FidelityCase> cases = FidelityCase.matrix("demo", List.of("a"), List.of(
            new FidelityCase.Viewport(40, 20, 1f), new FidelityCase.Viewport(41, 21, 1.25f)));

        MigrationGate.Report same = gate.run("demo", cases, legacy, legacy);
        assertTrue(same.passed(), same.table());

        MigrationGate.Renderer drifted = c -> c.viewport().width() == 41 ? capture(c, 11, 0xFF808081) : legacy.render(c);
        MigrationGate.Report bad = gate.run("demo", cases, legacy, drifted);
        assertFalse(bad.passed());
        assertTrue(bad.cases().get(0).passed());
        MigrationGate.CaseResult failing = bad.cases().get(1);
        assertFalse(failing.geometry().passed());
        assertFalse(failing.pixels().passed());
        assertTrue(bad.table().contains("FAIL demo-a_41x21_s1_25"), bad.table());
    }

    @Test
    void twoCapturesWithoutRectsProveNothingAndFail() {
        MigrationGate.Renderer blank = c -> new MigrationGate.Capture(capture(c, 10, 0xFF808080).image(), Map.of());
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("demo", List.of(DEMO), blank, blank);
        assertFalse(r.passed());
        assertTrue(r.table().contains("no named rects"), r.table());
    }

    // Controlled regressions (#296 AC: the harness catches a changed hit region, a missing
    // overlay and a stale callback). Each candidate paints the legacy pixels and rects exactly
    // and breaks one channel only.

    @Test
    void aChangedHitRegionFailsEvenWhenThePixelsMatch() {
        MigrationGate.Renderer legacy = c -> interactive(c, new float[]{10, 2, 8, 8}, "resume", false);
        MigrationGate.Renderer shrunk = c -> interactive(c, new float[]{10, 2, 8, 4}, "resume", false);
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("demo", List.of(DEMO), legacy, shrunk);
        assertFalse(r.passed());
        MigrationGate.CaseResult only = r.cases().get(0);
        assertTrue(only.geometry().passed() && only.pixels().passed());
        assertEquals(List.of("hit box.height: 4 vs legacy 8"), only.hits().problems());
    }

    @Test
    void aStaleCallbackFails() {
        MigrationGate.Renderer legacy = c -> interactive(c, new float[]{10, 2, 8, 8}, "resume", false);
        MigrationGate.Renderer stale = c -> interactive(c, new float[]{10, 2, 8, 8}, "quit", false);
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("demo", List.of(DEMO), legacy, stale);
        assertFalse(r.passed());
        assertEquals(List.of("action box: quit vs legacy resume"), r.cases().get(0).hits().problems());
    }

    @Test
    void aMissingOverlayFailsEvenUnderTheDriftTolerance() {
        FidelityCase big = new FidelityCase("demo", "a", new FidelityCase.Viewport(400, 300, 1f));
        MigrationGate.Renderer legacy = c -> interactive(c, new float[]{10, 2, 8, 8}, "resume", true);
        MigrationGate.Renderer noOverlay = c -> interactive(c, new float[]{10, 2, 8, 8}, "resume", false);
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.RASTER_DRIFT)
            .run("demo", List.of(big), legacy, noOverlay);
        assertFalse(r.passed(), "a 10x10 overlay is 0.08 % of the frame: the ratio alone would pass it");
        assertTrue(r.cases().get(0).pixels().largestCluster() >= 100, r.table());
    }

    private static final FidelityCase DEMO = new FidelityCase("demo", "a", new FidelityCase.Viewport(40, 20, 1f));

    /** Grey frame with the box, an optional 10x10 overlay at (20, 5), a hit rect and an action. */
    private static MigrationGate.Capture interactive(FidelityCase c, float[] hit, String action, boolean overlay) {
        MigrationGate.Capture base = capture(c, 10, 0xFF808080);
        int w = c.viewport().width();
        int[] px = base.image().argb().clone();
        if (overlay) {
            for (int y = 5; y < 15; y++) {
                for (int x = 20; x < 30; x++) {
                    px[y * w + x] = 0xFFFFFFFF;
                }
            }
        }
        return new MigrationGate.Capture(new FidelityImage(w, c.viewport().height(), px), base.rects(),
            Map.of("box", hit), Map.of("box", action));
    }

    private static MigrationGate.Capture capture(FidelityCase c, float x, int color) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        int[] px = new int[w * h];
        Arrays.fill(px, color);
        return new MigrationGate.Capture(new FidelityImage(w, h, px), Map.of("box", new float[]{x, 2, 8, 8}));
    }
}
