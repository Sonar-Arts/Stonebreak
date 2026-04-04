package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;

/**
 * Interface for the shared post-mutation rebuild pipeline.
 * Encapsulates the sequence: rebuild mappings → rebuild topology →
 * regenerate UVs → upload GPU buffers → notify listeners.
 *
 * Eliminates the 6x copy-pasted rebuild boilerplate in GenericModelRenderer.
 */
public interface IMeshRebuildPipeline {

    /**
     * Full rebuild after topology-changing operations (subdivision, edge insertion,
     * face creation/deletion, snapshot restore, mesh load).
     * Rebuilds unique vertex mapping, topology, UVs, and uploads both VBO and EBO.
     *
     * @param newVertexCount Updated vertex count to set on the renderer
     * @param newIndexCount  Updated index count to set on the renderer
     */
    void rebuildFull(int newVertexCount, int newIndexCount);

    /**
     * Partial rebuild after vertex position changes only (no EBO change).
     * Rebuilds unique vertex mapping, topology, UVs, and uploads VBO only.
     */
    void rebuildPositionsOnly();

    /**
     * Get the most recently built topology.
     *
     * @return The current mesh topology, or null if not yet built
     */
    MeshTopology getTopology();

    /**
     * Set the topology directly (e.g. from external load).
     *
     * @param topology The topology to set
     */
    void setTopology(MeshTopology topology);

    /**
     * Mark draw batches as dirty so they are rebuilt on next render.
     */
    void markDrawBatchesDirty();

    /**
     * Check if draw batches need rebuilding.
     *
     * @return true if dirty
     */
    boolean isDrawBatchesDirty();
}
