package com.stonebreak.rendering.UI.masonryUI;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Placement maths for HUD elements that follow something in the world: a target cursor, a ring
 * around a body, a name tag over a mob, a quest marker, the birthplace of a floating number.
 * Pure functions over JOML types; nothing here draws or knows about any game system.
 *
 * <p>The usual chain for a marker that must always be somewhere:
 * <pre>
 * Anchor a = MWorldMarker.project(viewProjection, bodyPoint, w, h);
 * a = MWorldMarker.withinBand(a, 0, bandTop, w, bandBottom); // hidden under chrome = off-screen
 * a = MWorldMarker.orFallback(a, plateX, plateY);            // pin to a HUD spot instead
 * float ring = MWorldMarker.radiusFor(a, 1.2f, 24f, 160f);
 * </pre>
 */
public final class MWorldMarker {

    private MWorldMarker() {}

    private static final float MIN_W = 1.0e-5f;

    /**
     * A world point on screen.
     *
     * @param x              window pixels, origin top-left
     * @param y              window pixels, origin top-left
     * @param pixelsPerBlock pixels one world unit covers at the point's depth; 0 when the point is
     *                       behind the camera (or the anchor is a pinned fallback)
     * @param onScreen       true when the point itself is visible; false for a point behind the
     *                       camera, outside the view, or replaced by a fallback
     */
    public record Anchor(float x, float y, float pixelsPerBlock, boolean onScreen) {

        /** Nothing to point at: no matrix, no point, or the point is behind the camera. */
        public static final Anchor NONE = new Anchor(0f, 0f, 0f, false);

        /** True when the point is in front of the camera, so {@code x}/{@code y} mean something even off-screen. */
        public boolean inFront() {
            return pixelsPerBlock > 0f;
        }
    }

    /** {@link #project(Matrix4fc, Vector3fc, int, int, float)} with no margin: on screen means inside the window. */
    public static Anchor project(Matrix4fc viewProjection, Vector3fc world, int w, int h) {
        return project(viewProjection, world, w, h, 0f);
    }

    /**
     * Projects {@code world} through {@code viewProjection} (projection × view of the live camera).
     * A null matrix or point, a degenerate window, or a point behind the camera gives
     * {@link Anchor#NONE}. A point in front of the camera but outside the view (by more than
     * {@code marginNdc}, in NDC units) keeps its real off-window coordinates with {@code onScreen}
     * false, so an edge indicator can still {@link #clampToBand clamp} it toward the right side.
     */
    public static Anchor project(Matrix4fc viewProjection, Vector3fc world, int w, int h, float marginNdc) {
        if (viewProjection == null || world == null || w <= 0 || h <= 0) return Anchor.NONE;
        Vector4f clip = viewProjection.transform(new Vector4f(world.x(), world.y(), world.z(), 1f));
        if (!(clip.w > MIN_W)) return Anchor.NONE;   // behind the camera (or NaN)
        float ndcX = clip.x / clip.w, ndcY = clip.y / clip.w;
        if (!finite(ndcX) || !finite(ndcY)) return Anchor.NONE;

        // One world unit straight up, at the same depth: how big a block is where the point stands.
        Vector4f up = viewProjection.transform(new Vector4f(world.x(), world.y() + 1f, world.z(), 1f));
        float perBlock = up.w > MIN_W ? Math.abs(up.y / up.w - ndcY) * 0.5f * h : 0f;
        if (!finite(perBlock)) perBlock = 0f;

        float limit = 1f + Math.max(0f, finite(marginNdc) ? marginNdc : 0f);
        boolean visible = Math.abs(ndcX) <= limit && Math.abs(ndcY) <= limit;
        return new Anchor((ndcX + 1f) * 0.5f * w, (1f - ndcY) * 0.5f * h, perBlock, visible);
    }

    /**
     * The anchor moved inside the rect {@code [minX..maxX] × [minY..maxY]}; {@code onScreen} is kept.
     * For elements that may slide along an edge (floating numbers, edge indicators). An inverted
     * range collapses onto its minimum.
     */
    public static Anchor clampToBand(Anchor anchor, float minX, float minY, float maxX, float maxY) {
        if (anchor == null) return Anchor.NONE;
        return new Anchor(clamp(anchor.x(), minX, maxX), clamp(anchor.y(), minY, maxY),
                anchor.pixelsPerBlock(), anchor.onScreen());
    }

    /**
     * The anchor unchanged when it lies inside the rect, else the same anchor marked off-screen. For
     * elements that must not slide: a cursor hidden under the HUD's own chrome is as good as
     * off-screen, and the next step is {@link #orFallback}.
     */
    public static Anchor withinBand(Anchor anchor, float minX, float minY, float maxX, float maxY) {
        if (anchor == null) return Anchor.NONE;
        if (!anchor.onScreen()) return anchor;
        boolean inside = anchor.x() >= minX && anchor.x() <= maxX && anchor.y() >= minY && anchor.y() <= maxY;
        return inside ? anchor : new Anchor(anchor.x(), anchor.y(), anchor.pixelsPerBlock(), false);
    }

    /**
     * The anchor itself when on screen; otherwise a pinned anchor at the HUD position
     * {@code (fx, fy)} with {@code onScreen} false (so the caller can tell it is pinned) and no
     * world size (so {@link #radiusFor} gives its minimum).
     */
    public static Anchor orFallback(Anchor anchor, float fx, float fy) {
        if (anchor != null && anchor.onScreen()) return anchor;
        return new Anchor(finite(fx) ? fx : 0f, finite(fy) ? fy : 0f, 0f, false);
    }

    /** Pixel radius of something {@code blocks} world units big at the anchor, kept within {@code [minPx, maxPx]}. */
    public static float radiusFor(Anchor anchor, float blocks, float minPx, float maxPx) {
        float min = finite(minPx) ? Math.max(0f, minPx) : 0f;
        if (anchor == null || !finite(blocks)) return min;
        float raw = anchor.pixelsPerBlock() * blocks;
        return finite(raw) ? clamp(raw, min, finite(maxPx) ? maxPx : Float.MAX_VALUE) : min;
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(Math.max(min, max), v));
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
