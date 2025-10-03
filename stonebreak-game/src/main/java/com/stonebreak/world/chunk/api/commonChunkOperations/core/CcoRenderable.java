package com.stonebreak.world.chunk.api.commonChunkOperations.core;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoMeshData;

/**
 * CCO Renderable Interface - Chunk rendering capability
 *
 * Provides mesh data access for rendering system.
 * Implementations must manage OpenGL buffer lifecycle.
 *
 * Design: Interface for rendering system integration
 * Performance: Fast mesh access, expensive mesh generation
 */
public interface CcoRenderable {

    /**
     * Get mesh data for rendering
     *
     * @return Immutable mesh data or null if no mesh
     *
     * Thread-safety: Must be thread-safe
     */
    CcoMeshData getMesh();

    /**
     * Check if mesh needs regeneration
     *
     * @return true if mesh dirty
     */
    boolean needsMeshRegen();

    /**
     * Generate or regenerate mesh
     *
     * Thread-safety: Should handle concurrent calls gracefully
     * Performance: Expensive operation (1-10ms typical)
     */
    void generateMesh();

    /**
     * Check if chunk has renderable mesh
     *
     * @return true if mesh exists and valid
     */
    default boolean hasValidMesh() {
        CcoMeshData mesh = getMesh();
        return mesh != null && mesh.isValid();
    }

    /**
     * Free mesh resources (on unload)
     *
     * Thread-safety: Must be GL context thread
     */
    void freeMesh();
}
