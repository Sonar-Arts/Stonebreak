package com.openmason.engine.ui.rendering;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.Typeface;

/**
 * Masonry on the CPU: the raster reference path. Same widget code, Skia's software rasterizer,
 * no GL context. Used by headless tests (pixel assertions and GPU parity checks) and by editor
 * previews that upload pixels with {@code glTexSubImage2D}, the path that does not flicker in
 * popped-out ImGui windows on Mesa/XWayland.
 *
 * <p>Pixels are 8-bit premultiplied, stored top-down with {@link #rowBytes()} per row, in Skia's
 * native N32 order ({@link #isBgra()}: BGRA on little-endian machines) and untagged, exactly the
 * surface every Masonry raster baseline was recorded on. Skia's CPU rasterizer does not produce
 * bit-identical anti-aliasing for RGBA and BGRA surfaces, so the format is part of the contract;
 * uploads pass {@code GL_BGRA} when {@link #isBgra()}. The canvas persists between frames
 * (nothing is cleared implicitly) and is usable outside {@code beginFrame}/{@code endFrame} once
 * allocated.
 */
public final class RasterMasonryBackend extends MasonryBackend {

    private final Typeface typeface;
    private final boolean ownsTypeface;
    private Bitmap bitmap;
    private Canvas canvas;
    private int width;
    private int height;
    private int frameDepth;

    /** @param ownsTypeface close {@code typeface} on {@link #dispose()} */
    public RasterMasonryBackend(Typeface typeface, boolean ownsTypeface) {
        this.typeface = typeface;
        this.ownsTypeface = ownsTypeface;
    }

    /** Allocated at {@code width x height} right away. */
    public RasterMasonryBackend(int width, int height, Typeface typeface, boolean ownsTypeface) {
        this(typeface, ownsTypeface);
        allocate(width, height);
    }

    @Override
    public void beginFrame(int newWidth, int newHeight, float pixelRatio) {
        if (frameDepth == 0 && newWidth > 0 && newHeight > 0 && (newWidth != width || newHeight != height)) {
            allocate(newWidth, newHeight);
        }
        if (canvas != null) {
            frameDepth++;
        }
    }

    @Override
    public void endFrame() {
        if (frameDepth > 0) {
            frameDepth--;
        }
    }

    @Override
    public Canvas getCanvas() {
        if (canvas == null) {
            throw new IllegalStateException("Raster backend has no surface yet");
        }
        return canvas;
    }

    @Override
    public boolean isAvailable() {
        return canvas != null;
    }

    @Override
    public Typeface typeface() {
        return typeface;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Unpremultiplied ARGB of one pixel (for assertions). */
    public int colorAt(int x, int y) {
        return bitmap.getColor(x, y);
    }

    /** True when pixels are stored B, G, R, A (Skia N32 on little-endian); else R, G, B, A. */
    public boolean isBgra() {
        return bitmap.getImageInfo().getColorType() == ColorType.BGRA_8888;
    }

    /** Premultiplied pixels, top-down, packed as {@code 0xRRGGBBAA} whatever the storage order. */
    public int[] premultipliedRgba() {
        byte[] raw = bitmap.readPixels();
        int[] out = new int[width * height];
        int stride = rowBytes();
        boolean bgra = isBgra();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * stride + x * 4;
                int c0 = raw[i] & 0xFF;
                int c2 = raw[i + 2] & 0xFF;
                int r = bgra ? c2 : c0;
                int b = bgra ? c0 : c2;
                out[y * width + x] = r << 24 | (raw[i + 1] & 0xFF) << 16 | b << 8 | (raw[i + 3] & 0xFF);
            }
        }
        return out;
    }

    /** Native address of the pixels, valid until the next resize or {@link #dispose()}. */
    public long pixelAddress() {
        Pixmap pixmap = bitmap.peekPixels();
        try {
            return pixmap.getAddr();
        } finally {
            pixmap.close();
        }
    }

    public int rowBytes() {
        return (int) bitmap.getRowBytes();
    }

    @Override
    public void dispose() {
        release();
        if (ownsTypeface && typeface != null) {
            typeface.close();
        }
    }

    private void allocate(int w, int h) {
        release();
        bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(w, h));
        bitmap.erase(0);
        canvas = new Canvas(bitmap);
        width = w;
        height = h;
    }

    private void release() {
        frameDepth = 0;
        if (canvas != null) {
            canvas.close();
            canvas = null;
        }
        if (bitmap != null) {
            bitmap.close();
            bitmap = null;
        }
        width = 0;
        height = 0;
    }
}
