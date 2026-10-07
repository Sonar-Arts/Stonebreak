package com.openmason.engine.ui.fidelity;

import java.util.List;
import java.util.Objects;

/**
 * How far a capture may stray from its baseline (#296). The numbers are the #283 version-one
 * contract: {@link #EXACT} at integer asset scales, {@link #assetScale ±1 texel inside asset
 * rects} at fractional ones, and {@link #RASTER_DRIFT} for goldens that only guard against
 * accidental change of the same renderer.
 *
 * @param levels           per-channel difference a pixel may have and still match
 * @param maxMismatchRatio share of pixels allowed to fail after every other allowance (0 = none)
 * @param shiftPixels      inside {@code shiftRegions}, a pixel also matches when the baseline has a
 *                         matching pixel within this many pixels (resampling moves texel edges)
 * @param shiftRegions     where the shift allowance applies, in framebuffer pixels
 * @param maxOutlierDelta  how far (per channel) a pixel counted against {@code maxMismatchRatio}
 *                         may stray; a worse pixel fails the comparison however few there are
 * @param maxClusterPixels largest 8-connected group of mismatched pixels allowed: drift scatters
 *                         single pixels along glyph edges, a dropped icon or label is one blob
 */
public record PixelTolerance(int levels, double maxMismatchRatio, int shiftPixels, List<Region> shiftRegions,
                             int maxOutlierDelta, int maxClusterPixels) {

    /** Identical pixels: same renderer, integer asset scale. */
    public static final PixelTolerance EXACT = new PixelTolerance(0, 0, 0, List.of());

    /**
     * Guard for goldens of one renderer across Skia point releases: 2 levels per channel, at most
     * 0.1 % of pixels beyond (glyph coverage drifts), none of them more than 96 levels off and no
     * clump larger than 12 pixels. The caps are what keep the ratio from hiding a dropped 16 px
     * icon or a short label (#296 review): drift is scattered anti-aliasing, a missing feature
     * is a solid region. What the #287/#294/#295 goldens use.
     */
    public static final PixelTolerance RASTER_DRIFT = new PixelTolerance(2, 0.001, 0, List.of(), 96, 12);

    /** No outlier or cluster cap: only the ratio limits the mismatches. */
    public PixelTolerance(int levels, double maxMismatchRatio, int shiftPixels, List<Region> shiftRegions) {
        this(levels, maxMismatchRatio, shiftPixels, shiftRegions, 255, Integer.MAX_VALUE);
    }

    /** An axis-aligned pixel rectangle, origin top-left. */
    public record Region(int x, int y, int width, int height) {

        public Region {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("negative region");
            }
        }

        /** The pixels a float rect {@code [x, y, w, h]} touches. */
        public static Region covering(float[] rect) {
            int x0 = (int) Math.floor(rect[0]);
            int y0 = (int) Math.floor(rect[1]);
            int x1 = (int) Math.ceil(rect[0] + rect[2]);
            int y1 = (int) Math.ceil(rect[1] + rect[3]);
            return new Region(x0, y0, x1 - x0, y1 - y0);
        }

        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    public PixelTolerance {
        if (levels < 0 || levels > 255 || !(maxMismatchRatio >= 0 && maxMismatchRatio <= 1) || shiftPixels < 0
                || maxOutlierDelta < levels || maxOutlierDelta > 255 || maxClusterPixels < 1) {
            throw new IllegalArgumentException("invalid pixel tolerance");
        }
        shiftRegions = List.copyOf(Objects.requireNonNull(shiftRegions, "shiftRegions"));
    }

    /**
     * The contract for a screen drawn at {@code assetScale}: exact when it is an integer (sprites
     * map texel to pixel blocks), else ±1 texel ({@code ceil(scale)} pixels) inside the asset
     * rects, where resampling may move a texel edge by one source texel, and exact elsewhere.
     */
    public static PixelTolerance assetScale(float assetScale, List<Region> assetRects) {
        if (!(assetScale > 0)) {
            throw new IllegalArgumentException("asset scale must be positive");
        }
        if (assetScale == Math.rint(assetScale)) {
            return EXACT;
        }
        return new PixelTolerance(0, 0, (int) Math.ceil(assetScale), assetRects);
    }

    public PixelTolerance withLevels(int perChannel) {
        return new PixelTolerance(perChannel, maxMismatchRatio, shiftPixels, shiftRegions,
            Math.max(perChannel, maxOutlierDelta), maxClusterPixels);
    }

    public PixelTolerance withMaxMismatchRatio(double ratio) {
        return new PixelTolerance(levels, ratio, shiftPixels, shiftRegions, maxOutlierDelta, maxClusterPixels);
    }

    /** Caps the outliers the ratio admits: per-channel delta and 8-connected clump size. */
    public PixelTolerance withOutlierCaps(int maxDelta, int maxCluster) {
        return new PixelTolerance(levels, maxMismatchRatio, shiftPixels, shiftRegions, maxDelta, maxCluster);
    }

    boolean shiftAllowedAt(int x, int y) {
        if (shiftPixels == 0) {
            return false;
        }
        for (int i = 0; i < shiftRegions.size(); i++) {
            if (shiftRegions.get(i).contains(x, y)) {
                return true;
            }
        }
        return false;
    }
}
