package com.openmason.engine.ui.masonry;

import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.io.InputStream;

/**
 * A complete MasonryUI over a CPU-raster Skija surface — full widget {@code render(MasonryUI)}
 * calls run headlessly and the pixels are read back for assertions. It is the engine's raster
 * reference path ({@link RasterMasonryBackend}) with the game's pinned font
 * ({@code src/test/resources/fonts/Minecraft.ttf}), so no OpenGL context is touched.
 *
 * <p>Each fixture owns its own bitmap, so two fixtures render independently — the diff helpers
 * exist because MasonryUI painting is fully deterministic (the stone noise is hash-based), which
 * makes "these two states must render differently" a sound assertion.
 */
final class RasterUiFixture {

    static final int BACKGROUND = 0xFF101010;

    private static final Typeface TYPEFACE = loadGameTypeface();

    final RasterMasonryBackend backend;
    final Pixels bitmap;
    final Canvas canvas;
    final MasonryUI ui;

    RasterUiFixture(int width, int height) {
        backend = new RasterMasonryBackend(width, height, TYPEFACE, false);
        bitmap = backend::colorAt;
        canvas = backend.getCanvas();
        canvas.clear(BACKGROUND);
        ui = new MasonryUI(backend);
    }

    /** Unpremultiplied ARGB readback, named like the Skija {@code Bitmap} call the tests use. */
    @FunctionalInterface
    interface Pixels {
        int getColor(int x, int y);
    }

    /** Pixels in the region that differ from the cleared background. */
    int countPainted(int x0, int y0, int x1, int y1) {
        int painted = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (bitmap.getColor(x, y) != BACKGROUND) {
                    painted++;
                }
            }
        }
        return painted;
    }

    /** Pixels in the region colored exactly {@code color} (probe interiors — AA blends edges). */
    int countExactly(int color, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (bitmap.getColor(x, y) == color) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Pixels in the region where this fixture and {@code other} disagree. */
    int diff(RasterUiFixture other, int x0, int y0, int x1, int y1) {
        int differing = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (bitmap.getColor(x, y) != other.bitmap.getColor(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }

    private static Typeface loadGameTypeface() {
        try (InputStream in = RasterUiFixture.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            if (in == null) {
                throw new IllegalStateException("pinned test font missing: /fonts/Minecraft.ttf");
            }
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("could not load the game typeface", e);
        }
    }
}
