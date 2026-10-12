package com.openmason.main.systems.menus.textureCreator.filters.noise;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noise filter and its generators: seeded determinism, value range, and that
 * the filter only touches RGB inside the selection.
 */
class NoiseFilterTest {

    private static final List<LongFunction<NoiseGenerator>> GENERATORS = List.of(
            SimplexNoiseGenerator::new, ValueNoiseGenerator::new, WhiteNoiseGenerator::new);

    private static NoiseConfig config(NoiseGenerator generator, float strength) {
        return new NoiseConfig(generator, strength, false, 0.37f, 0f, 1, 0.5f, 0f);
    }

    private static PixelCanvas mid(int w, int h, int alpha) {
        PixelCanvas canvas = new PixelCanvas(w, h);
        canvas.fill(PixelCanvas.packRGBA(128, 100, 160, alpha));
        return canvas;
    }

    @Test
    @Tag("regression")
    void everyGeneratorIsDeterministicForASeed() {
        for (LongFunction<NoiseGenerator> factory : GENERATORS) {
            NoiseGenerator a = factory.apply(42L);
            NoiseGenerator b = factory.apply(42L);
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    float fx = x * 0.73f, fy = y * 1.31f;
                    assertEquals(a.generate(fx, fy), b.generate(fx, fy), a.getName() + " at " + x + "," + y);
                }
            }
            // Order of evaluation must not matter (no hidden stream state).
            float first = a.generate(3.5f, 2.25f);
            a.generate(100f, 7f);
            assertEquals(first, a.generate(3.5f, 2.25f), a.getName() + " is not a pure function of (x, y)");
        }
    }

    @Test
    @Tag("regression")
    void differentSeedsGiveDifferentPatternsForEveryGenerator() {
        // White noise used to ignore its seed entirely ("Randomize Seed" did nothing).
        for (LongFunction<NoiseGenerator> factory : GENERATORS) {
            NoiseGenerator a = factory.apply(1L);
            NoiseGenerator b = factory.apply(2L);
            int differing = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (a.generate(x * 0.61f + 0.2f, y * 0.61f + 0.3f) != b.generate(x * 0.61f + 0.2f, y * 0.61f + 0.3f)) {
                        differing++;
                    }
                }
            }
            assertTrue(differing > 128, a.getName() + ": only " + differing + "/256 samples differ between seeds");
        }
    }

    @Test
    void valueAndWhiteNoiseStayInTheUnitRange() {
        for (NoiseGenerator generator : List.of(new ValueNoiseGenerator(9), new WhiteNoiseGenerator(9))) {
            for (int y = -20; y < 40; y++) {
                for (int x = -20; x < 40; x++) {
                    float v = generator.generate(x * 0.37f, y * 0.53f);
                    assertTrue(v >= 0f && v <= 1f, generator.getName() + " produced " + v);
                }
            }
        }
    }

    @Test
    void simplexNoiseIsCentredAroundHalf() {
        NoiseGenerator simplex = new SimplexNoiseGenerator(5);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float v = simplex.generate(x * 0.17f, y * 0.17f);
                assertTrue(v > -0.05f && v < 1.05f, "out of range: " + v);
                sum += v;
                n++;
            }
        }
        assertEquals(0.5, sum / n, 0.1);
    }

    @Test
    @Tag("regression")
    void filterIsDeterministicAndPreservesAlpha() {
        for (LongFunction<NoiseGenerator> factory : GENERATORS) {
            PixelCanvas a = mid(12, 9, 77);
            PixelCanvas b = mid(12, 9, 77);
            new NoiseFilter(config(factory.apply(123L), 0.4f)).apply(a, null);
            new NoiseFilter(config(factory.apply(123L), 0.4f)).apply(b, null);

            assertArrayEquals(a.getPixels(), b.getPixels());
            boolean changed = false;
            for (int p : a.getPixels()) {
                assertEquals(77, PixelCanvas.unpackRGBA(p)[3], "alpha must be preserved");
                changed |= p != PixelCanvas.packRGBA(128, 100, 160, 77);
            }
            assertTrue(changed, "a 40% noise must change something");
        }
    }

    @Test
    void filterOnlyTouchesPixelsInsideTheSelection() {
        PixelCanvas canvas = mid(10, 10, 255);
        int untouched = canvas.getPixel(0, 0);
        RectangularSelection selection = new RectangularSelection(2, 2, 6, 6);

        NoiseConfig blurry = new NoiseConfig(new WhiteNoiseGenerator(8), 1f, true, 1f, 1f, 4, 0.9f, 1f);
        new NoiseFilter(blurry).apply(canvas, selection);

        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                if (!selection.contains(x, y)) {
                    assertEquals(untouched, canvas.getPixel(x, y), "(" + x + "," + y + ") is outside the selection");
                }
            }
        }
    }

    @Test
    void zeroStrengthIsTheIdentity() {
        PixelCanvas canvas = mid(6, 6, 200);
        int[] before = canvas.getPixels().clone();
        new NoiseFilter(config(new SimplexNoiseGenerator(1), 0f)).apply(canvas, null);
        assertArrayEquals(before, canvas.getPixels());
    }

    @Test
    void fullStrengthClampsChannelsToTheValidRange() {
        PixelCanvas canvas = new PixelCanvas(16, 16);
        canvas.fill(PixelCanvas.packRGBA(250, 5, 128, 255));
        new NoiseFilter(config(new WhiteNoiseGenerator(77), 1f)).apply(canvas, null);
        boolean sawMax = false, sawMin = false;
        for (int p : canvas.getPixels()) {
            int[] c = PixelCanvas.unpackRGBA(p);
            sawMax |= c[0] == 255;
            sawMin |= c[1] == 0;
        }
        assertTrue(sawMax && sawMin, "+/-255 noise must saturate at both ends without wrapping");
    }

    @Test
    void configClampsItsParameters() {
        NoiseConfig c = new NoiseConfig(new ValueNoiseGenerator(1), 5f, false, -3f, -1f, 99, 2f, -2f);
        assertEquals(1f, c.strength());
        assertEquals(0.1f, c.scale());
        assertEquals(0f, c.blur());
        assertEquals(8, c.octaves());
        assertEquals(1f, c.spread());
        assertEquals(0f, c.edgeSoftness());
        assertEquals(1, new NoiseConfig(new ValueNoiseGenerator(1), 0.5f, false, 1f, 0f, 0, 0.5f, 0f).octaves());
    }
}
