package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;

/**
 * Interface for the shared post-mutation rebuild pipeline.
 * Encapsulates the sequence: derive render mesh (corners + UVs + triangulation)
 * → write GPU-facing caches → rebuild topology → upload GPU buffers →
 * notify listeners.
 */
public interface IMeshRebuildPipeline {

    /**
     * Full re-derivation after topology-changing operations (subdivision,
     * face split/creation/deletion, snapshot restore, mesh load).
     * Uploads both VBO and EBO.
     */
    void rebuildFromEditable();

    /**
     * Re-derivation after vertex position changes only — uploads the VBO but
     * not the index buffer (loops unchanged).
     */
    void refreshPositionsFromEditable();

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
