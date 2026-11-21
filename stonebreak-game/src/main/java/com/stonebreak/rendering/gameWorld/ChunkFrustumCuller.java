package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * Tests chunk bounding boxes against the camera view frustum so chunks outside
 * the player's field of view can be skipped during rendering.
 *
 * <p>The frustum planes are extracted from the combined projection-view matrix
 * via {@link FrustumIntersection} (Gribb/Hartmann method). Reusable matrix and
 * frustum instances avoid per-frame allocations.
 *
 * <p>The view matrix is in render space ({@link RenderOrigin}), so the planes
 * are too. Callers still pass <em>world</em> boxes — every test below rebases
 * its arguments — which keeps chunk and LOD-node bounds in the one coordinate
 * system the rest of the world code uses. {@link #projectionViewWorld()} is the
 * matrix for consumers that cannot be rebased caller-side, notably the GPU cull
 * pass, whose per-mesh AABBs live in world space on the GPU.
 */
public class ChunkFrustumCuller {

    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f projectionViewMatrix = new Matrix4f();
    private final Matrix4f projectionViewWorldMatrix = new Matrix4f();

    /**
     * Updates the frustum planes from the current camera matrices.
     * Call once per frame before testing chunks.
     */
    public void update(Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        projectionMatrix.mul(viewMatrix, projectionViewMatrix);
        frustum.set(projectionViewMatrix);
    }

    /**
     * Returns true if any part of the cube's 32^3 box is inside the frustum (render space on all axes).
     */
    public boolean isCubeVisible(Cube cube) {
        float minX = RenderOrigin.relativeX(cube.minX());
        float minY = RenderOrigin.relativeY(cube.minY());
        float minZ = RenderOrigin.relativeZ(cube.minZ());
        float edge = WorldConfiguration.CUBE_EDGE;
        return frustum.testAab(minX, minY, minZ, minX + edge, minY + edge, minZ + edge);
    }

    /**
     * Tests an arbitrary axis-aligned box against the frustum. Used by the
     * FastLOD pass with each node's tight mesh Y bounds — full-height boxes
     * would barely cull vertically when looking at the horizon.
     */
    public boolean isBoxVisible(float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        float x0 = RenderOrigin.relativeX((double) minX), y0 = RenderOrigin.relativeY((double) minY);
        float z0 = RenderOrigin.relativeZ((double) minZ);
        return frustum.testAab(x0, y0, z0, x0 + (maxX - minX), y0 + (maxY - minY), z0 + (maxZ - minZ));
    }

    /**
     * Classifying box test for the region-level pre-cull: returns
     * {@link FrustumIntersection#INSIDE} (every chunk in the box is visible —
     * per-chunk tests can be skipped), {@link FrustumIntersection#INTERSECT}
     * (fall through to per-chunk tests), or {@code >= 0} (the index of a
     * culling plane — the whole box is outside).
     */
    public int intersectAab(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        float x0 = RenderOrigin.relativeX((double) minX), y0 = RenderOrigin.relativeY((double) minY);
        float z0 = RenderOrigin.relativeZ((double) minZ);
        return frustum.intersectAab(x0, y0, z0, x0 + (maxX - minX), y0 + (maxY - minY), z0 + (maxZ - minZ));
    }

    /**
     * The combined projection-view matrix from the last {@link #update}, in
     * render space — it consumes {@link RenderOrigin}-relative positions.
     */
    public Matrix4f projectionView() {
        return projectionViewMatrix;
    }

    /**
     * The same clip matrix rebased to consume <em>world</em> positions
     * ({@code P * V * T(-origin)}) — what the GPU cull pass needs, since the
     * per-mesh AABBs it tests were uploaded in world space.
     */
    public Matrix4f projectionViewWorld() {
        return RenderOrigin.acceptWorldSpace(
            projectionViewWorldMatrix.set(projectionViewMatrix));
    }
}
