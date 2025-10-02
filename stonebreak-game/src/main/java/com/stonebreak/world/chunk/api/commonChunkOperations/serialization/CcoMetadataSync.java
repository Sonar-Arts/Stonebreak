package com.stonebreak.world.chunk.api.commonChunkOperations.serialization;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkMetadata;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Synchronizes metadata with chunk state for save/load operations.
 * Ensures timestamps and flags are properly maintained.
 *
 * Thread-safe static methods.
 */
public final class CcoMetadataSync {

    private CcoMetadataSync() {
        // Utility class
    }

    /**
     * Updates metadata timestamp to current time.
     *
     * @param metadata Current metadata
     * @return New metadata with updated timestamp
     */
    public static CcoChunkMetadata updateTimestamp(CcoChunkMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        return metadata.withUpdatedTimestamp();
    }

    /**
     * Marks features as populated in metadata.
     *
     * @param metadata Current metadata
     * @return New metadata with features populated
     */
    public static CcoChunkMetadata markFeaturesPopulated(CcoChunkMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        return metadata.withFeaturesPopulated();
    }

    /**
     * Synchronizes metadata with state manager.
     * Updates features flag based on state.
     *
     * @param metadata Current metadata
     * @param stateManager State manager to sync from
     * @return Updated metadata
     */
    public static CcoChunkMetadata syncWithState(CcoChunkMetadata metadata,
                                                  CcoAtomicStateManager stateManager) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(stateManager, "stateManager cannot be null");

        boolean featuresPopulated = stateManager.hasState(CcoChunkState.FEATURES_POPULATED);

        if (featuresPopulated && !metadata.isFeaturesPopulated()) {
            return metadata.withFeaturesPopulated();
        }

        return metadata;
    }

    /**
     * Creates metadata for saving with current timestamp.
     * Syncs features flag from state manager.
     *
     * @param metadata Base metadata
     * @param stateManager State manager
     * @return Save-ready metadata
     */
    public static CcoChunkMetadata prepareSaveMetadata(CcoChunkMetadata metadata,
                                                        CcoAtomicStateManager stateManager) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(stateManager, "stateManager cannot be null");

        // Update timestamp
        CcoChunkMetadata updated = metadata.withUpdatedTimestamp();

        // Sync features flag
        return syncWithState(updated, stateManager);
    }

    /**
     * Checks if chunk needs saving based on metadata and state manager.
     * Uses the state manager's integrated dirty tracker.
     *
     * @param metadata Chunk metadata
     * @param stateManager State manager with integrated dirty tracker
     * @return true if chunk should be saved
     */
    public static boolean needsSave(CcoChunkMetadata metadata, CcoAtomicStateManager stateManager) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(stateManager, "stateManager cannot be null");

        // Check if data modified flag is set (delegates to integrated dirty tracker)
        if (stateManager.needsSave()) {
            return true;
        }

        // Check if features were populated but not yet saved
        if (stateManager.hasState(CcoChunkState.FEATURES_POPULATED) &&
                !metadata.isFeaturesPopulated()) {
            return true;
        }

        return false;
    }

    /**
     * Updates state manager after successful save.
     * Clears data modified flag via integrated dirty tracker.
     *
     * @param stateManager State manager to update
     */
    public static void markSaved(CcoAtomicStateManager stateManager) {
        Objects.requireNonNull(stateManager, "stateManager cannot be null");
        stateManager.getDirtyTracker().clearDataDirty();
    }

    /**
     * Creates metadata from coordinates with current timestamp.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return New metadata
     */
    public static CcoChunkMetadata createFresh(int chunkX, int chunkZ) {
        return CcoChunkMetadata.forNewChunk(chunkX, chunkZ);
    }
}
