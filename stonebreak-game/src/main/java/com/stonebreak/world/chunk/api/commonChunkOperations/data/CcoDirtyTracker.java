package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe dirty flag tracker for CCO chunks.
 * Separates mesh dirty (needs re-rendering) from data dirty (needs saving).
 *
 * Lock-free implementation using atomic booleans.
 * Zero allocations in hot paths.
 */
public final class CcoDirtyTracker {
    private final AtomicBoolean meshDirty;
    private final AtomicBoolean dataDirty;

    public CcoDirtyTracker() {
        this.meshDirty = new AtomicBoolean(false);
        this.dataDirty = new AtomicBoolean(false);
    }

    /**
     * Marks that block data has changed.
     * Sets both mesh dirty (needs re-mesh) and data dirty (needs save).
     */
    public void markBlockChanged() {
        meshDirty.set(true);
        dataDirty.set(true);
    }

    /**
     * Marks only the mesh as dirty without marking data for save.
     * Use when visual changes don't affect persisted data.
     */
    public void markMeshDirtyOnly() {
        meshDirty.set(true);
    }

    /**
     * Marks only the data as dirty without marking mesh.
     * Rare case - typically metadata changes only.
     */
    public void markDataDirtyOnly() {
        dataDirty.set(true);
    }

    /**
     * Clears the mesh dirty flag.
     * Called after mesh has been regenerated and uploaded.
     */
    public void clearMeshDirty() {
        meshDirty.set(false);
    }

    /**
     * Clears the data dirty flag.
     * Called after chunk has been successfully saved.
     */
    public void clearDataDirty() {
        dataDirty.set(false);
    }

    /**
     * Clears both dirty flags.
     * Use with caution - typically after full save and mesh regeneration.
     */
    public void clearAll() {
        meshDirty.set(false);
        dataDirty.set(false);
    }

    /**
     * Checks if mesh needs regeneration.
     */
    public boolean isMeshDirty() {
        return meshDirty.get();
    }

    /**
     * Checks if data needs to be saved.
     */
    public boolean isDataDirty() {
        return dataDirty.get();
    }

    /**
     * Checks if either flag is dirty.
     */
    public boolean isAnyDirty() {
        return meshDirty.get() || dataDirty.get();
    }

    /**
     * Checks if both flags are dirty.
     */
    public boolean areBothDirty() {
        return meshDirty.get() && dataDirty.get();
    }

    /**
     * Atomically checks if mesh is dirty and clears it if true.
     *
     * @return true if mesh was dirty (and is now cleared)
     */
    public boolean checkAndClearMeshDirty() {
        return meshDirty.getAndSet(false);
    }

    /**
     * Atomically checks if data is dirty and clears it if true.
     *
     * @return true if data was dirty (and is now cleared)
     */
    public boolean checkAndClearDataDirty() {
        return dataDirty.getAndSet(false);
    }

    @Override
    public String toString() {
        return String.format("CcoDirtyTracker{mesh=%s, data=%s}", meshDirty.get(), dataDirty.get());
    }
}
