package com.stonebreak.fx.bindings;

import java.awt.image.BufferedImage;

/**
 * The colour an FX takes from a block's texture ({@code tintFromTexture}, Stonebreak#437): the coverage-weighted
 * mean of its opaque-ish texels, so debris from stone is grey and from grass green, and transparent cut-out
 * pixels (leaf gaps) don't wash it out. Pure, GL-free.
 */
public final class BlockTint {

    /** Texels below this alpha (0..255) are holes, not material. */
    static final int MIN_ALPHA = 32;
    /** Used when a texture has no material texels at all. */
    public static final int FALLBACK = 0xFFFFFFFF;

    private BlockTint() {
    }

    /** {@code 0xRRGGBBAA} (alpha 0xFF) for {@code image}, {@link #FALLBACK} for null or all-transparent. */
    public static int of(BufferedImage image) {
        if (image == null) {
            return FALLBACK;
        }
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        return ofArgb(argb);
    }

    /** {@link #of} over every texel of several images (a block's material textures together). */
    public static int ofAll(java.util.List<BufferedImage> images) {
        if (images == null || images.isEmpty()) {
            return FALLBACK;
        }
        int total = 0;
        for (BufferedImage img : images) {
            total += img == null ? 0 : img.getWidth() * img.getHeight();
        }
        int[] all = new int[total];
        int at = 0;
        for (BufferedImage img : images) {
            if (img == null) continue;
            int w = img.getWidth(), h = img.getHeight();
            img.getRGB(0, 0, w, h, all, at, w);
            at += w * h;
        }
        return ofArgb(all);
    }

    /** {@link #of} over packed {@code 0xAARRGGBB} texels. */
    public static int ofArgb(int[] argb) {
        long r = 0, g = 0, b = 0, weight = 0;
        for (int p : argb) {
            int a = p >>> 24;
            if (a < MIN_ALPHA) {
                continue;
            }
            r += (long) ((p >> 16) & 0xFF) * a;
            g += (long) ((p >> 8) & 0xFF) * a;
            b += (long) (p & 0xFF) * a;
            weight += a;
        }
        if (weight == 0) {
            return FALLBACK;
        }
        int rr = (int) (r / weight), gg = (int) (g / weight), bb = (int) (b / weight);
        return (rr << 24) | (gg << 16) | (bb << 8) | 0xFF;
    }
}
