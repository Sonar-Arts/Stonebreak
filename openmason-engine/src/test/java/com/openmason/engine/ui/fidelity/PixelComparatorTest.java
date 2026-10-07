package com.openmason.engine.ui.fidelity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pixel half of the #296 fidelity contract, on synthetic frames. */
class PixelComparatorTest {

    private static final int BG = 0xFF203040;
    private static final int INK = 0xFFE0C080;

    /** A 20x10 frame with a 4x4 block of ink at (x, 3). */
    private static FidelityImage block(int x) {
        int[] px = new int[200];
        Arrays.fill(px, BG);
        for (int yy = 3; yy < 7; yy++) {
            for (int xx = x; xx < x + 4; xx++) {
                px[yy * 20 + xx] = INK;
            }
        }
        return new FidelityImage(20, 10, px);
    }

    @Test
    void identicalFramesPassExactly() {
        PixelReport r = PixelComparator.compare(block(5), block(5), PixelTolerance.EXACT);
        assertTrue(r.passed(), r.summary());
        assertEquals(0, r.mismatched());
        assertEquals(0, r.worstDelta());
        assertNull(r.bounds());
        assertNull(r.diff(), "no full-frame diff is kept for a pass");
    }

    @Test
    void aMovedEdgeFailsExactWithBoundsAndDiff() {
        PixelReport r = PixelComparator.compare(block(5), block(6), PixelTolerance.EXACT);
        assertFalse(r.passed());
        assertEquals(8, r.mismatched(), "one column lost, one gained, 4 rows each");
        assertEquals(new PixelTolerance.Region(5, 3, 5, 4), r.bounds());
        assertEquals(0xFFFF2020, r.diff().pixel(5, 3), "mismatches are red in the diff");
    }

    @Test
    void levelsAbsorbSmallChannelNoise() {
        int[] px = block(5).argb().clone();
        px[0] = BG + 2; // blue channel +2
        FidelityImage noisy = new FidelityImage(20, 10, px);
        assertFalse(PixelComparator.compare(block(5), noisy, PixelTolerance.EXACT).passed());
        assertTrue(PixelComparator.compare(block(5), noisy, PixelTolerance.EXACT.withLevels(2)).passed());
        assertEquals(2, PixelComparator.compare(block(5), noisy, PixelTolerance.EXACT).worstDelta());
    }

    @Test
    void mismatchRatioBoundsWhatMayFail() {
        // 8 of 200 pixels = 4 %
        assertFalse(PixelComparator.compare(block(5), block(6), PixelTolerance.EXACT.withMaxMismatchRatio(0.03)).passed());
        assertTrue(PixelComparator.compare(block(5), block(6), PixelTolerance.EXACT.withMaxMismatchRatio(0.04)).passed());
    }

    @Test
    void fractionalAssetScaleAllowsOneTexelInsideAssetRectsOnly() {
        PixelTolerance.Region asset = new PixelTolerance.Region(4, 2, 7, 6);
        PixelTolerance t = PixelTolerance.assetScale(1.25f, List.of(asset));
        assertEquals(2, t.shiftPixels(), "one texel at 1.25x spans up to ceil(1.25) pixels");
        PixelReport inside = PixelComparator.compare(block(5), block(6), t);
        assertTrue(inside.passed(), inside.summary());
        assertEquals(8, inside.shifted());

        PixelTolerance elsewhere = PixelTolerance.assetScale(1.25f, List.of(new PixelTolerance.Region(15, 0, 5, 10)));
        assertFalse(PixelComparator.compare(block(5), block(6), elsewhere).passed(), "outside asset rects stays exact");
    }

    @Test
    void integerAssetScalesAreExact() {
        assertEquals(PixelTolerance.EXACT, PixelTolerance.assetScale(2f, List.of(new PixelTolerance.Region(0, 0, 9, 9))));
        assertEquals(PixelTolerance.EXACT, PixelTolerance.assetScale(1f, List.of()));
    }

    @Test
    void differentSizesFailWithoutADiff() {
        PixelReport r = PixelComparator.compare(block(5), new FidelityImage(10, 10, new int[100]), PixelTolerance.RASTER_DRIFT);
        assertFalse(r.passed());
        assertNull(r.diff());
        assertTrue(r.summary().contains("differs from baseline"), r.summary());
    }

    @Test
    void regionCoversTheTouchedPixelsOfAFloatRect() {
        assertEquals(new PixelTolerance.Region(1, 2, 4, 3), PixelTolerance.Region.covering(new float[]{1.5f, 2f, 3f, 2.5f}));
    }

    @Test
    void goldenStoreWritesThenVerifiesAndExplainsAFailure(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("base");
        Path out = dir.resolve("out");
        new GoldenStore(base, out, true, "t.write").verify("blk", block(5), PixelTolerance.EXACT);
        assertTrue(Files.isRegularFile(base.resolve("blk.png")));

        GoldenStore check = new GoldenStore(base, out, false, "t.write");
        assertTrue(check.verify("blk", block(5), PixelTolerance.EXACT).passed(), "PNG round trip is lossless");
        AssertionError e = assertThrows(AssertionError.class, () -> check.verify("blk", block(6), PixelTolerance.EXACT));
        assertTrue(e.getMessage().contains("-Dt.write=true"), e.getMessage());
        assertTrue(Files.isRegularFile(out.resolve("blk.actual.png")));
        assertTrue(Files.isRegularFile(out.resolve("blk.diff.png")));

        AssertionError missing = assertThrows(AssertionError.class, () -> check.verify("nope", block(5), PixelTolerance.EXACT));
        assertTrue(missing.getMessage().startsWith("missing baseline"), missing.getMessage());
    }
}
