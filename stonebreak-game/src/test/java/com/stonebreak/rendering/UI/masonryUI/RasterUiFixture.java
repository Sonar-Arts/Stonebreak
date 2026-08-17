package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Typeface;

import java.io.InputStream;

/**
 * A complete MasonryUI over a CPU-raster Skija surface — full widget {@code render(MasonryUI)}
 * calls run headlessly and the pixels are read back for assertions. The backend subclass
 * substitutes the raster canvas and the game's real typeface without ever calling
 * {@code initialize()}, so no OpenGL context is touched.
 *
 * <p>Each fixture owns its own bitmap, so two fixtures render independently — the diff helpers
 * exist because MasonryUI painting is fully deterministic (the stone noise is hash-based), which
 * makes "these two states must render differently" a sound assertion.
 */
final class RasterUiFixture {

    static final int BACKGROUND = 0xFF101010;

    private static final Typeface TYPEFACE = loadGameTypeface();

    final Bitmap bitmap;
    final Canvas canvas;
    final MasonryUI ui;

    RasterUiFixture(int width, int height) {
        bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height));
        canvas = new Canvas(bitmap);
        canvas.clear(BACKGROUND);
        ui = new MasonryUI(new RasterBackend(canvas));
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
                throw new IllegalStateException("bundled game font missing: /fonts/Minecraft.ttf");
            }
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("could not load the game typeface", e);
        }
    }

    /** The seam: raster canvas + classpath typeface, no GL, no initialize(). */
    private static final class RasterBackend extends SkijaUIBackend {
        private final Canvas canvas;

        RasterBackend(Canvas canvas) {
            this.canvas = canvas;
        }

        @Override
        public Canvas getCanvas() {
            return canvas;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Typeface getMinecraftTypeface() {
            return TYPEFACE;
        }
    }
}
