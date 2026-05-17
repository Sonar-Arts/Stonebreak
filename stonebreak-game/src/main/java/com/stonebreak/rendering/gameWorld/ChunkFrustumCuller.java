package com.stonebreak.rendering.gameWorld;

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
 */
public class ChunkFrustumCuller {

    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f projectionViewMatrix = new Matrix4f();

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
        float minX = chunk.getWorldX(0);
        float minZ = chunk.getWorldZ(0);
        float maxX = minX + WorldConfiguration.CHUNK_SIZE;
        float maxZ = minZ + WorldConfiguration.CHUNK_SIZE;
        return frustum.testAab(minX, 0.0f, minZ, maxX, WorldConfiguration.WORLD_HEIGHT, maxZ);
    }
}
