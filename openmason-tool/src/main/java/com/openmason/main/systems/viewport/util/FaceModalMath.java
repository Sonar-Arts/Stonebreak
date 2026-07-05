package com.openmason.main.systems.viewport.util;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Pure static math for the modal inset (I) and extrude (E) face tools.
 *
 * <p>Covers three concerns, all headless-testable:
 * <ul>
 *   <li><b>Amount from mouse</b> — pixels-per-world-unit probing at the selection
 *       centroid plus the inset/extrude amount conventions (inset grows as the
 *       mouse moves toward the projected centroid; extrude is signed displacement
 *       along the projected normal direction).</li>
 *   <li><b>Preview geometry</b> — per-face overlay line segments for the live
 *       preview. The inset preview shrinks each corner toward the face centroid
 *       (an approximation of the engine's even-thickness inset — fine for a
 *       preview); the extrude preview offsets the loop along the face normal.</li>
 *   <li><b>Face basis</b> — loop centroid and Newell normal.</li>
 * </ul>
 */
public final class FaceModalMath {

    /**
     * Below this pixels-per-world-unit the projection is considered degenerate
     * (e.g. a view-aligned normal collapses to a point on screen) and callers
     * should fall back to another probe direction.
     */
    public static final float MIN_PIXELS_PER_UNIT = 1e-3f;

    /** World-space probe length used for screen-projection measurements. */
    private static final float PROBE_LENGTH = 1.0f;

    private FaceModalMath() {
        throw new AssertionError("FaceModalMath is a utility class and should not be instantiated");
    }

    // =========================================================================
    // FACE BASIS
    // =========================================================================

    /**
     * @param loop Ordered polygon corner positions (model space)
     * @return Average of the corners, or null if the loop is null/empty
     */
    public static Vector3f centroid(Vector3f[] loop) {
        if (loop == null || loop.length == 0) {
            return null;
        }
        Vector3f c = new Vector3f();
        for (Vector3f p : loop) {
            c.add(p);
        }
        return c.div(loop.length);
    }

    /**
     * Newell normal of an ordered polygon loop (winding authoritative:
     * counter-clockwise loops produce a normal toward the viewer).
     *
     * @param loop Ordered polygon corner positions (model space)
     * @return Normalized normal, or a zero vector for degenerate loops
     */
    public static Vector3f newellNormal(Vector3f[] loop) {
        Vector3f n = new Vector3f();
        if (loop == null || loop.length < 3) {
            return n;
        }
        for (int i = 0; i < loop.length; i++) {
            Vector3f cur = loop[i];
            Vector3f next = loop[(i + 1) % loop.length];
            n.x += (cur.y - next.y) * (cur.z + next.z);
            n.y += (cur.z - next.z) * (cur.x + next.x);
            n.z += (cur.x - next.x) * (cur.y + next.y);
        }
        if (n.lengthSquared() < 1e-12f) {
            return new Vector3f();
        }
        return n.normalize();
    }

    // =========================================================================
    // SCREEN PROBING (pixels-per-world-unit + projected direction)
    // =========================================================================

    /**
     * Measure how many screen pixels one world unit along {@code dir} covers at
     * {@code origin}.
     *
     * @return Pixels per world unit, or 0 when the projection is degenerate
     *         (either point behind the camera, or dir view-aligned)
     */
    public static float pixelsPerUnit(Vector3f origin, Vector3f dir, Matrix4f mvp,
                                      int viewportWidth, int viewportHeight) {
        Vector2f a = ScreenProjectionUtil.projectToScreen(origin, mvp, viewportWidth, viewportHeight);
        Vector2f b = ScreenProjectionUtil.projectToScreen(
            new Vector3f(dir).mul(PROBE_LENGTH).add(origin), mvp, viewportWidth, viewportHeight);
        if (a == null || b == null) {
            return 0f;
        }
        return a.distance(b) / PROBE_LENGTH;
    }

    /**
     * Screen-space direction of a world-space direction at {@code origin}.
     *
     * @return Normalized screen direction (Y down), or null when degenerate
     *         (view-aligned dir or points behind the camera)
     */
    public static Vector2f screenDirection(Vector3f origin, Vector3f dir, Matrix4f mvp,
                                           int viewportWidth, int viewportHeight) {
        Vector2f a = ScreenProjectionUtil.projectToScreen(origin, mvp, viewportWidth, viewportHeight);
        Vector2f b = ScreenProjectionUtil.projectToScreen(
            new Vector3f(dir).mul(PROBE_LENGTH).add(origin), mvp, viewportWidth, viewportHeight);
        if (a == null || b == null) {
            return null;
        }
        Vector2f d = new Vector2f(b).sub(a);
        if (d.length() < MIN_PIXELS_PER_UNIT) {
            return null;
        }
        return d.normalize();
    }

    // =========================================================================
    // AMOUNT CONVENTIONS
    // =========================================================================

    /**
     * Inset amount from the mouse position: moving the mouse toward the projected
     * selection centroid increases the inset; moving away clamps to 0.
     *
     * @param d0             Mouse-to-centroid screen distance when the tool started
     * @param mouseX         Current mouse X (viewport pixels)
     * @param mouseY         Current mouse Y (viewport pixels)
     * @param centroidScreen Projected selection centroid (viewport pixels)
     * @param ppu            Pixels per world unit at the centroid
     * @return Inset amount in world units, always {@code >= 0}
     */
    public static float insetAmount(float d0, float mouseX, float mouseY,
                                    Vector2f centroidScreen, float ppu) {
        if (ppu <= MIN_PIXELS_PER_UNIT) {
            return 0f;
        }
        float dx = mouseX - centroidScreen.x;
        float dy = mouseY - centroidScreen.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return Math.max(0f, (d0 - dist) / ppu);
    }

    /**
     * Signed extrude amount from the mouse displacement since the tool started.
     * Projected along {@code screenDirOrNull} (the average face normal's screen
     * direction) when available; otherwise falls back to the vertical mouse
     * delta (mouse up = positive, matching a typical upward-facing extrude).
     *
     * @param mouse0X         Mouse X when the tool started
     * @param mouse0Y         Mouse Y when the tool started
     * @param mouseX          Current mouse X
     * @param mouseY          Current mouse Y
     * @param screenDirOrNull Normalized screen direction of the face normal, or null
     * @param ppu             Pixels per world unit at the centroid
     * @return Signed extrude distance in world units
     */
    public static float extrudeAmount(float mouse0X, float mouse0Y, float mouseX, float mouseY,
                                      Vector2f screenDirOrNull, float ppu) {
        if (ppu <= MIN_PIXELS_PER_UNIT) {
            return 0f;
        }
        float dx = mouseX - mouse0X;
        float dy = mouseY - mouse0Y;
        if (screenDirOrNull != null) {
            return (dx * screenDirOrNull.x + dy * screenDirOrNull.y) / ppu;
        }
        // Screen Y grows downward: dragging up extrudes outward
        return -dy / ppu;
    }

    // =========================================================================
    // PREVIEW GEOMETRY
    // =========================================================================

    /**
     * Preview line segments for insetting one face: the inner polygon (each corner
     * moved {@code amount} toward the face centroid along the normalized corner→
     * centroid direction, clamped at the centroid) plus a spoke from each corner
     * to its inner corner.
     *
     * <p>This is a preview approximation of the engine's even-thickness inset.
     *
     * @param loop   Ordered face corner positions (model space)
     * @param amount Inset amount in world units ({@code >= 0})
     * @return Flat segment array [x1,y1,z1, x2,y2,z2, ...] (2 × loop length
     *         segments), or an empty array for degenerate input
     */
    public static float[] insetPreviewSegments(Vector3f[] loop, float amount) {
        if (loop == null || loop.length < 3) {
            return new float[0];
        }
        Vector3f center = centroid(loop);

        Vector3f[] inner = new Vector3f[loop.length];
        for (int i = 0; i < loop.length; i++) {
            Vector3f toCenter = new Vector3f(center).sub(loop[i]);
            float dist = toCenter.length();
            if (dist < 1e-6f) {
                inner[i] = new Vector3f(loop[i]);
            } else {
                float travel = Math.min(amount, dist);
                inner[i] = new Vector3f(toCenter).mul(travel / dist).add(loop[i]);
            }
        }

        // Inner polygon edges + corner→inner spokes
        float[] segments = new float[loop.length * 2 * 6];
        int o = 0;
        for (int i = 0; i < loop.length; i++) {
            o = putSegment(segments, o, inner[i], inner[(i + 1) % loop.length]);
        }
        for (int i = 0; i < loop.length; i++) {
            o = putSegment(segments, o, loop[i], inner[i]);
        }
        return segments;
    }

    /**
     * Preview line segments for extruding one face: the offset polygon (corners +
     * normal · distance) plus a connecting strut from each corner to its offset
     * corner.
     *
     * @param loop     Ordered face corner positions (model space)
     * @param normal   Face normal (normalized)
     * @param distance Signed extrude distance in world units
     * @return Flat segment array [x1,y1,z1, x2,y2,z2, ...] (2 × loop length
     *         segments), or an empty array for degenerate input
     */
    public static float[] extrudePreviewSegments(Vector3f[] loop, Vector3f normal, float distance) {
        if (loop == null || loop.length < 3 || normal == null) {
            return new float[0];
        }
        Vector3f offset = new Vector3f(normal).mul(distance);

        Vector3f[] moved = new Vector3f[loop.length];
        for (int i = 0; i < loop.length; i++) {
            moved[i] = new Vector3f(loop[i]).add(offset);
        }

        // Offset polygon edges + corner→offset struts
        float[] segments = new float[loop.length * 2 * 6];
        int o = 0;
        for (int i = 0; i < loop.length; i++) {
            o = putSegment(segments, o, moved[i], moved[(i + 1) % loop.length]);
        }
        for (int i = 0; i < loop.length; i++) {
            o = putSegment(segments, o, loop[i], moved[i]);
        }
        return segments;
    }

    private static int putSegment(float[] out, int offset, Vector3f a, Vector3f b) {
        out[offset]     = a.x;
        out[offset + 1] = a.y;
        out[offset + 2] = a.z;
        out[offset + 3] = b.x;
        out[offset + 4] = b.y;
        out[offset + 5] = b.z;
        return offset + 6;
    }
}
