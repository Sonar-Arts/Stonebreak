package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import com.stonebreak.world.chunk.Chunk;
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
     * Returns true if any part of the chunk's bounding box is inside the frustum.
     * The box spans the full world height since chunks are 16x16xWORLD_HEIGHT.
     */
    public boolean isChunkVisible(Chunk chunk) {
        float minX = chunk.getWorldX(0) - RenderOrigin.x();
        float minZ = chunk.getWorldZ(0) - RenderOrigin.z();
        float maxX = minX + WorldConfiguration.CHUNK_SIZE;
        float maxZ = minZ + WorldConfiguration.CHUNK_SIZE;
        return frustum.testAab(minX, 0.0f, minZ, maxX, WorldConfiguration.WORLD_HEIGHT, maxZ);
    }

    /**
     * Tests an arbitrary axis-aligned box against the frustum. Used by the
     * FastLOD pass with each node's tight mesh Y bounds — full-height boxes
     * would barely cull vertically when looking at the horizon.
     */
    public boolean isBoxVisible(float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        float ox = RenderOrigin.x();
        float oz = RenderOrigin.z();
        return frustum.testAab(minX - ox, minY, minZ - oz, maxX - ox, maxY, maxZ - oz);
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
        float ox = RenderOrigin.x();
        float oz = RenderOrigin.z();
        return frustum.intersectAab(minX - ox, minY, minZ - oz, maxX - ox, maxY, maxZ - oz);
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
