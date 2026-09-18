package com.stonebreak.ui.focusBattle;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/** World → screen projection for world-anchored HUD elements (target cursor, timing ring, floaters). */
public final class WorldProjection {
    private WorldProjection() {}

    /**
     * @param viewProjection projection × view of the live camera
     * @return {@code {x, y}} in window pixels (origin top-left), or null when the point is behind the
     *         camera or outside the view volume by more than {@code marginNdc} (0 = exactly on screen)
     */
    public static float[] toScreen(Matrix4fc viewProjection, Vector3fc world, int windowWidth, int windowHeight,
                                   float marginNdc) {
        if (viewProjection == null || world == null) {
            return null;
        }
        Vector4f clip = new Vector4f(world.x(), world.y(), world.z(), 1f);
        viewProjection.transform(clip);
        if (clip.w <= 1.0e-5f) {
            return null; // behind the camera
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float limit = 1f + Math.max(0f, marginNdc);
        if (Math.abs(ndcX) > limit || Math.abs(ndcY) > limit) {
            return null;
        }
        return new float[]{(ndcX + 1f) * 0.5f * windowWidth, (1f - ndcY) * 0.5f * windowHeight};
    }

    /**
     * Pixels covered on screen by {@code worldSize} blocks at {@code world}'s depth (for sizing a ring
     * around a body). Returns 0 when the point is behind the camera.
     */
    public static float pixelsPerBlock(Matrix4fc viewProjection, Vector3fc world, int windowHeight) {
        if (viewProjection == null || world == null) {
            return 0f;
        }
        Vector4f a = new Vector4f(world.x(), world.y(), world.z(), 1f);
        Vector4f b = new Vector4f(world.x(), world.y() + 1f, world.z(), 1f);
        viewProjection.transform(a);
        viewProjection.transform(b);
        if (a.w <= 1.0e-5f || b.w <= 1.0e-5f) {
            return 0f;
        }
        return Math.abs(b.y / b.w - a.y / a.w) * 0.5f * windowHeight;
    }
}
