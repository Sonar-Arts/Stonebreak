package com.openmason.engine.ui.fidelity;

/**
 * Compares two captures pixel by pixel under a {@link PixelTolerance} (#296). Deterministic and
 * allocation-light apart from the diff image; no GL.
 */
public final class PixelComparator {

    private static final int RED = 0xFFFF2020;
    private static final int AMBER = 0xFFFFB000;

    private PixelComparator() {
    }

    /** Largest per-channel (A, R, G, B) difference of two ARGB pixels. */
    public static int channelDelta(int a, int b) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            max = Math.max(max, Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF)));
        }
        return max;
    }

    public static PixelReport compare(FidelityImage expected, FidelityImage actual, PixelTolerance tolerance) {
        if (expected.width() != actual.width() || expected.height() != actual.height()) {
            return new PixelReport(false, actual.width(), actual.height(), actual.width() * actual.height(), 0, 255,
                null, null, "size " + actual.width() + "x" + actual.height() + " differs from baseline "
                + expected.width() + "x" + expected.height());
        }
        int w = expected.width();
        int h = expected.height();
        int[] e = expected.argb();
        int[] a = actual.argb();
        int mismatched = 0;
        int shifted = 0;
        int worst = 0;
        int worstMismatch = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        boolean[] mask = null;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int d = channelDelta(e[i], a[i]);
                worst = Math.max(worst, d);
                int verdict = classify(expected, actual, x, y, d, tolerance);
                if (verdict == SHIFTED) {
                    shifted++;
                } else if (verdict == MISMATCH) {
                    mismatched++;
                    worstMismatch = Math.max(worstMismatch, d);
                    if (mask == null) {
                        mask = new boolean[e.length];
                    }
                    mask[i] = true;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        int cluster = mask == null ? 0 : largestCluster(mask, w, h);
        long allowed = (long) Math.floor(tolerance.maxMismatchRatio() * e.length);
        String why = "";
        if (mismatched > 0 && mismatched <= allowed) {
            if (worstMismatch > tolerance.maxOutlierDelta()) {
                why = "a mismatched pixel is " + worstMismatch + " levels off (outlier cap "
                    + tolerance.maxOutlierDelta() + ")";
            } else if (cluster > tolerance.maxClusterPixels()) {
                why = cluster + " mismatched pixels touch (clump cap " + tolerance.maxClusterPixels()
                    + "): a missing or moved feature, not drift";
            }
        }
        boolean passed = mismatched <= allowed && why.isEmpty();
        PixelTolerance.Region bounds = maxX < 0 ? null
            : new PixelTolerance.Region(minX, minY, maxX - minX + 1, maxY - minY + 1);
        // The diff is a full frame: built only for a failing comparison, so a passing gate over a
        // 4K matrix holds no images (#296 review).
        FidelityImage diff = passed ? null : diff(expected, actual, tolerance);
        PixelReport base = new PixelReport(passed, w, h, mismatched, shifted, worst, bounds, diff, "", cluster,
            worstMismatch);
        return why.isEmpty() ? base
            : new PixelReport(false, w, h, mismatched, shifted, worst, bounds, diff, base.summary() + "; " + why,
                cluster, worstMismatch);
    }

    /** Size of the largest 8-connected group of {@code true} cells (iterative flood fill). */
    static int largestCluster(boolean[] mask, int w, int h) {
        boolean[] seen = new boolean[mask.length];
        int[] stack = new int[64];
        int best = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int size = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int i = stack[--top];
                size++;
                int x = i % w;
                int y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w || (dx == 0 && dy == 0)) {
                            continue;
                        }
                        int n = ny * w + nx;
                        if (mask[n] && !seen[n]) {
                            seen[n] = true;
                            if (top == stack.length) {
                                stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                            }
                            stack[top++] = n;
                        }
                    }
                }
            }
            best = Math.max(best, size);
        }
        return best;
    }

    private static final int MATCH = 0;
    private static final int SHIFTED = 1;
    private static final int MISMATCH = 2;

    /**
     * A pixel off by more than {@code levels} still matches inside a shift region when the
     * displacement explains it <em>both ways</em>: the capture's pixel appears in the baseline
     * nearby, and the baseline's pixel appears in the capture nearby. One-way matching passed a
     * deleted outline or focus ring: where it vanished the capture shows backdrop, and backdrop is
     * always within reach in the baseline (#296 review).
     */
    private static int classify(FidelityImage expected, FidelityImage actual, int x, int y, int delta,
                                PixelTolerance t) {
        if (delta <= t.levels()) {
            return MATCH;
        }
        return t.shiftAllowedAt(x, y)
            && nearMatch(expected, x, y, actual.pixel(x, y), t)
            && nearMatch(actual, x, y, expected.pixel(x, y), t) ? SHIFTED : MISMATCH;
    }

    /** Grey baseline, red mismatches, amber shift matches. */
    private static FidelityImage diff(FidelityImage expected, FidelityImage actual, PixelTolerance t) {
        int w = expected.width();
        int[] e = expected.argb();
        int[] a = actual.argb();
        int[] out = new int[e.length];
        for (int i = 0; i < e.length; i++) {
            int x = i % w;
            int y = i / w;
            out[i] = switch (classify(expected, actual, x, y, channelDelta(e[i], a[i]), t)) {
                case MATCH -> dim(e[i]);
                case SHIFTED -> AMBER;
                default -> RED;
            };
        }
        return new FidelityImage(w, expected.height(), out);
    }

    /** Whether {@code image} holds a pixel within the shift radius that {@code value} matches. */
    private static boolean nearMatch(FidelityImage image, int x, int y, int value, PixelTolerance t) {
        int r = t.shiftPixels();
        int x0 = Math.max(0, x - r);
        int x1 = Math.min(image.width() - 1, x + r);
        int y0 = Math.max(0, y - r);
        int y1 = Math.min(image.height() - 1, y + r);
        for (int yy = y0; yy <= y1; yy++) {
            for (int xx = x0; xx <= x1; xx++) {
                if (channelDelta(image.pixel(xx, yy), value) <= t.levels()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Grey at a quarter of the luminance, so red and amber stand out. */
    private static int dim(int argb) {
        int r = argb >>> 16 & 0xFF;
        int g = argb >>> 8 & 0xFF;
        int b = argb & 0xFF;
        int l = (r * 77 + g * 150 + b * 29) >>> 10; // luma / 4
        return 0xFF000000 | l << 16 | l << 8 | l;
    }
}
