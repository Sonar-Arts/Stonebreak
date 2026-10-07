package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityImage;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.LegacyUiClock;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

import java.io.InputStream;

/**
 * A pinned CPU-raster stage for capturing legacy screens (#296): an opaque checkerboard backdrop
 * (so a translucent scrim's strength shows in every pixel), the game's bundled typeface, the UI
 * scale set for the capture, and {@link LegacyUiClock} pinned. A {@link SkijaUIBackend} subclass
 * hands the legacy renderers this canvas without {@code initialize()}, the
 * {@code BattleRasterFixture} seam, so no GL context is touched.
 *
 * <p>Close it to restore the UI scale and release the clock. Not a test class.
 */
public final class LegacyUiRaster implements AutoCloseable {

    /** Backdrop squares: a dim grass green and a dim sky blue, 64 px. */
    public static final int CHECK_A = 0xFF4E6B34;
    public static final int CHECK_B = 0xFF34506B;
    public static final int CHECK_SIZE = 64;

    /** Pinned UI time and seed of every legacy capture. */
    public static final double PINNED_SECONDS = 1.25;
    public static final long PINNED_SEED = 296L;

    private static final Typeface TYPEFACE = loadGameTypeface();

    public final int width;
    public final int height;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final SkijaUIBackend backend;
    private final float previousScale;

    public LegacyUiRaster(int width, int height, float uiScale) {
        this.width = width;
        this.height = height;
        bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height));
        canvas = new Canvas(bitmap);
        drawBackdrop();
        backend = new RasterBackend(canvas);
        previousScale = Settings.getInstance().getUiScale();
        Settings.getInstance().setUiScale(uiScale);
        LegacyUiClock.pin(PINNED_SECONDS, PINNED_SEED);
    }

    public SkijaUIBackend backend() {
        return backend;
    }

    private void drawBackdrop() {
        canvas.clear(CHECK_A);
        try (Paint p = new Paint().setColor(CHECK_B)) {
            for (int y = 0; y < height; y += CHECK_SIZE) {
                for (int x = (y / CHECK_SIZE % 2) * CHECK_SIZE; x < width; x += 2 * CHECK_SIZE) {
                    canvas.drawRect(Rect.makeXYWH(x, y, CHECK_SIZE, CHECK_SIZE), p);
                }
            }
        }
    }

    /** The frame so far. The backdrop is opaque, so premultiplied and straight alpha agree. */
    public FidelityImage capture() {
        byte[] raw = bitmap.readPixels();
        int stride = (int) bitmap.getRowBytes();
        boolean bgra = bitmap.getImageInfo().getColorType() == ColorType.BGRA_8888;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * stride + x * 4;
                int c0 = raw[i] & 0xFF;
                int g = raw[i + 1] & 0xFF;
                int c2 = raw[i + 2] & 0xFF;
                int a = raw[i + 3] & 0xFF;
                int r = bgra ? c2 : c0;
                int b = bgra ? c0 : c2;
                argb[y * width + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return new FidelityImage(width, height, argb);
    }

    @Override
    public void close() {
        Settings.getInstance().setUiScale(previousScale);
        LegacyUiClock.release();
        canvas.close();
        bitmap.close();
    }

    private static Typeface loadGameTypeface() {
        try (InputStream in = LegacyUiRaster.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            if (in == null) {
                throw new IllegalStateException("bundled game font missing: /fonts/Minecraft.ttf");
            }
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("could not load the game typeface", e);
        }
    }

    /** Raster canvas + classpath typeface, no GL, no initialize(). */
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
