package com.openmason.engine.rendering.shadow;

import com.openmason.engine.rendering.RenderOrigin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame computed state for one shadow cascade: the light-space matrices used
 * for both the depth pass and receiver sampling, plus the world-space bounding
 * sphere of the cascade volume so callers can cull casters cheaply.
 *
 * <p>Instances are reused frame to frame; {@link CascadeCalculator} rewrites all
 * fields each update. Not thread-safe — render-thread only.
 */
public final class ShadowCascade {

    /** Rotation-only view matrix looking along the light direction (eye at world origin). */
    public final Matrix4f lightView = new Matrix4f();

    /** Orthographic projection fitted (and texel-snapped) around the frustum slice. */
    public final Matrix4f lightProj = new Matrix4f();

    /** {@code lightProj * lightView} — transform from world space to light clip space. */
    public final Matrix4f lightViewProj = new Matrix4f();

    /**
     * {@link #lightView} rebased to consume render-space positions. The cascade
     * is fitted and texel-snapped in world coordinates — keeping that stable
     * across {@link RenderOrigin} steps, so the shadow volume never shifts under
     * the snap grid — but the geometry drawn into it, and the fragments that
     * sample it, are in render space. These two are that bridge.
     */
    public final Matrix4f lightViewRender = new Matrix4f();

    /** {@link #lightViewProj} rebased to consume render-space positions. */
    public final Matrix4f lightViewProjRender = new Matrix4f();

    /** View-space distance where this cascade ends (used for cascade selection). */
    public float splitFar;

    /** World-space center of the cascade's bounding sphere. */
    public final Vector3f centerWorld = new Vector3f();

    /** Radius of the cascade's bounding sphere in world units. */
    public float radius;

    /** World-space size of one shadow-map texel (drives receiver normal-offset bias). */
    public float texelWorldSize;

    /**
     * Refreshes {@link #lightViewRender} / {@link #lightViewProjRender} from the
     * world-space matrices against the current {@link RenderOrigin}. Call once
     * per frame after the cascade is fitted, and again whenever the origin
     * steps — a cascade that skipped its staggered refit still needs this, or
     * it would keep drawing against the previous origin.
     */
    public void rebaseToRenderSpace() {
        RenderOrigin.acceptRenderSpace(lightViewRender.set(lightView));
        RenderOrigin.acceptRenderSpace(lightViewProjRender.set(lightViewProj));
    }

    /**
     * Whether a world-space axis-aligned box could cast into this cascade. The test
     * is a conservative sphere-vs-AABB check in the XZ plane against the cascade
     * sphere swept toward the sun by {@code sweep} world units — directional light
     * means casters offset toward the sun still matter.
     */
    public boolean intersectsXZ(float minX, float minZ, float maxX, float maxZ,
                                float sunDirX, float sunDirZ, float sweep) {
        // Segment from the cascade center toward the sun.
        float ax = centerWorld.x, az = centerWorld.z;
        float bx = ax + sunDirX * sweep, bz = az + sunDirZ * sweep;
        // Closest point on the AABB (XZ) to the segment, approximated by testing
        // the segment's closest point to the box center then clamping into the box.
        float cx = (minX + maxX) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float abx = bx - ax, abz = bz - az;
        float lenSq = abx * abx + abz * abz;
        float t = lenSq > 1e-6f ? ((cx - ax) * abx + (cz - az) * abz) / lenSq : 0.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float px = ax + abx * t, pz = az + abz * t;
        float qx = Math.max(minX, Math.min(maxX, px));
        float qz = Math.max(minZ, Math.min(maxZ, pz));
        float dx = px - qx, dz = pz - qz;
        return dx * dx + dz * dz <= radius * radius;
    }
}
