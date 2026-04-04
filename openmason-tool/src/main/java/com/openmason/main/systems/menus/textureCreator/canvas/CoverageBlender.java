package com.openmason.main.systems.menus.textureCreator.canvas;

/**
 * Utility for coverage-weighted color blending.
 *
 * <p>When a drawing primitive partially covers a pixel, the coverage value
 * (0.0 = no coverage, 1.0 = full coverage) modulates the paint color's
 * effective alpha before compositing onto the existing pixel. This produces
 * smooth edges on brush strokes, lines, shapes, and polygon mask boundaries
 * without traditional anti-aliasing.
 *
 * @see PixelCanvas#blendColors(int, int)
 */
public final class CoverageBlender {

    private CoverageBlender() {
        // Utility class
    }

    /**
     * Blend a paint color onto an existing pixel using fractional coverage.
     *
     * <p>The paint color's alpha is scaled by the coverage fraction, then
     * standard alpha-over compositing is applied against the destination.
     *
     * @param paintColor    the color being painted (RGBA packed)
     * @param existingColor the current pixel color (RGBA packed)
     * @param coverage      fraction of pixel covered (0.0 to 1.0)
     * @return the blended result color (RGBA packed)
     */
    public static int blendWithCoverage(int paintColor, int existingColor, float coverage) {
        if (coverage <= 0.0f) {
            return existingColor;
        }
        if (coverage >= 1.0f) {
            return paintColor;
        }

        // Scale paint alpha by coverage
        int[] paintRGBA = PixelCanvas.unpackRGBA(paintColor);
        int scaledAlpha = Math.round(paintRGBA[3] * coverage);
        if (scaledAlpha <= 0) {
            return existingColor;
        }

        int modifiedPaint = PixelCanvas.packRGBA(paintRGBA[0], paintRGBA[1], paintRGBA[2], scaledAlpha);
        return PixelCanvas.blendColors(modifiedPaint, existingColor);
    }

    /**
     * Compute a smooth transition value using Hermite interpolation.
     *
     * <p>Returns 0.0 when {@code x <= edge0}, 1.0 when {@code x >= edge1},
     * and a smooth curve between them. Used to create soft falloff at
     * primitive boundaries.
     *
     * @param edge0 lower edge of the transition
     * @param edge1 upper edge of the transition
     * @param x     the value to evaluate
     * @return smoothstep result in [0.0, 1.0]
     */
    public static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Compute the minimum distance from a point to a line segment.
     *
     * <p>Used by polygon masks to determine per-pixel coverage at
     * polygon edges.
     *
     * @param px point X
     * @param py point Y
     * @param ax segment start X
     * @param ay segment start Y
     * @param bx segment end X
     * @param by segment end Y
     * @return unsigned distance from point to closest point on segment
     */
    public static float pointToSegmentDistance(float px, float py,
                                               float ax, float ay,
                                               float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float apx = px - ax;
        float apy = py - ay;

        float abLenSq = abx * abx + aby * aby;
        if (abLenSq < 1e-10f) {
            // Degenerate segment (zero length)
            return (float) Math.sqrt(apx * apx + apy * apy);
        }

        // Project point onto segment, clamped to [0, 1]
        float t = Math.clamp((apx * abx + apy * aby) / abLenSq, 0.0f, 1.0f);

        // Closest point on segment
        float closestX = ax + t * abx;
        float closestY = ay + t * aby;

        float dx = px - closestX;
        float dy = py - closestY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
