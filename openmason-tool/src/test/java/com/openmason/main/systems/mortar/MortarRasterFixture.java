package com.openmason.main.systems.mortar;

import com.openmason.main.systems.mortar.core.MortarFonts;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;

/**
 * A {@link MortarPainter} over a CPU-raster Skija bitmap with a {@link MortarTheme} captured from a
 * short-lived headless ImGui context, so Mortar parts paint for real without a window or GL. The
 * bitmap starts fully transparent; opaque fills therefore read back as their exact token colour.
 *
 * <p>The ImGui context exists only for the {@link MortarTheme#capture()} call (the theme is an
 * immutable snapshot) and is destroyed before the constructor returns.</p>
 */
public final class MortarRasterFixture implements AutoCloseable {

    /** Cleared pixel value (transparent black). */
    public static final int CLEAR = 0x00000000;

    public final MortarTheme theme;
    public final MortarFonts fonts = new MortarFonts();
    public final Bitmap bitmap;
    public final Canvas canvas;
    public final MortarPainter painter;

    public MortarRasterFixture(int width, int height) {
        this.theme = captureDarkTheme();
        this.bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height));
        bitmap.erase(CLEAR);
        this.canvas = new Canvas(bitmap);
        this.painter = new MortarPainter(canvas, theme, fonts);
    }

    /** A fixed, fully opaque dark palette with a distinct colour per token source. */
    public static MortarTheme captureDarkTheme() {
        ImGui.createContext();
        try {
            ImGui.getIO().setIniFilename(null);
            ImGuiStyle style = ImGui.getStyle();
            style.setColor(ImGuiCol.WindowBg, 0.10f, 0.10f, 0.10f, 1f);
            style.setColor(ImGuiCol.FrameBg, 0.20f, 0.20f, 0.20f, 1f);
            style.setColor(ImGuiCol.FrameBgHovered, 0.30f, 0.30f, 0.30f, 1f);
            style.setColor(ImGuiCol.HeaderActive, 0.20f, 0.40f, 0.80f, 1f);
            style.setColor(ImGuiCol.Text, 0.90f, 0.90f, 0.90f, 1f);
            style.setColor(ImGuiCol.TextDisabled, 0.60f, 0.60f, 0.60f, 1f);
            style.setColor(ImGuiCol.Border, 0.50f, 0.50f, 0.50f, 1f);
            style.setColor(ImGuiCol.Separator, 0.40f, 0.40f, 0.40f, 1f);
            return MortarTheme.capture();
        } finally {
            ImGui.destroyContext();
        }
    }

    /** Unpremultiplied ARGB at a pixel. */
    public int at(int x, int y) {
        return bitmap.getColor(x, y);
    }

    public int alphaAt(int x, int y) {
        return at(x, y) >>> 24;
    }

    /** Erase back to {@link #CLEAR} so one fixture can paint several states. */
    public void clear() {
        canvas.clear(CLEAR);
    }

    /** All pixels, row-major, for whole-image equality checks. */
    public int[] snapshot() {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = at(x, y);
            }
        }
        return out;
    }

    /** True when every channel of {@code actual} is within {@code tolerance} of {@code expected}. */
    public static boolean near(int expected, int actual, int tolerance) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int e = (expected >>> shift) & 0xFF;
            int a = (actual >>> shift) & 0xFF;
            if (Math.abs(e - a) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /** True when the RGB channels (alpha ignored) are within {@code tolerance}. */
    public static boolean nearRgb(int expected, int actual, int tolerance) {
        return near(expected | 0xFF000000, actual | 0xFF000000, tolerance);
    }

    /** Source-over of a (possibly translucent) {@code src} onto an opaque {@code dst}. */
    public static int over(int dst, int src) {
        float a = (src >>> 24) / 255f;
        int r = Math.round(((src >>> 16) & 0xFF) * a + ((dst >>> 16) & 0xFF) * (1 - a));
        int g = Math.round(((src >>> 8) & 0xFF) * a + ((dst >>> 8) & 0xFF) * (1 - a));
        int b = Math.round((src & 0xFF) * a + (dst & 0xFF) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public void close() {
        fonts.close();
        canvas.close();
        bitmap.close();
    }
}
