package com.stonebreak.world.chunk.api.commonChunkOperations.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.*;
import com.stonebreak.world.save.model.ChunkData;

import java.util.Objects;

/**
 * Deserializes save system ChunkData into CCO components.
 * Optimized for fast loading with minimal allocation.
 *
 * Thread-safe static methods.
 */
public final class CcoDeserializer {

    private CcoDeserializer() {
        // Utility class
    }

    /**
     * Converts ChunkData from save system to CCO metadata.
     *
     * @param chunkData Loaded chunk data
     * @return CCO metadata
     */
    public static CcoChunkMetadata toMetadata(ChunkData chunkData) {
        Objects.requireNonNull(chunkData, "chunkData cannot be null");

        return new CcoChunkMetadata(
                chunkData.getChunkX(),
                chunkData.getChunkZ(),
                chunkData.getLastModified(),
                chunkData.isFeaturesPopulated()
        );
    }

    /**
     * Converts ChunkData blocks to CCO block array.
     * Zero-copy: wraps the blocks directly.
     *
     * @param chunkData Loaded chunk data
     * @return CCO block array
     */
    public static CcoBlockArray toBlockArray(ChunkData chunkData) {
        Objects.requireNonNull(chunkData, "chunkData cannot be null");

        BlockType[][][] blocks = chunkData.getBlocks();
        return CcoBlockArray.wrap(blocks);
    }

    /**
     * Full deserialization: converts ChunkData to all CCO components.
     *
     * @param chunkData Loaded chunk data
     * @return Deserialization result with all components
     */
    public static DeserializedChunk deserialize(ChunkData chunkData) {
        Objects.requireNonNull(chunkData, "chunkData cannot be null");

        CcoChunkMetadata metadata = toMetadata(chunkData);
        CcoBlockArray blockArray = toBlockArray(chunkData);

        // Create fresh state and dirty trackers for loaded chunk
        CcoAtomicStateManager stateManager = new CcoAtomicStateManager();
        CcoDirtyTracker dirtyTracker = new CcoDirtyTracker();

        // Set appropriate initial states
        stateManager.addState(CcoChunkState.BLOCKS_POPULATED);
        if (metadata.isFeaturesPopulated()) {
            stateManager.addState(CcoChunkState.FEATURES_POPULATED);
        }
        // Loaded chunks need mesh generation
        stateManager.addState(CcoChunkState.MESH_DIRTY);

        return new DeserializedChunk(metadata, blockArray, stateManager, dirtyTracker);
    }

    /**
     * Result of full deserialization containing all CCO components.
     */
    public static final class DeserializedChunk {
        private final CcoChunkMetadata metadata;
        private final CcoBlockArray blockArray;
        private final CcoAtomicStateManager stateManager;
        private final CcoDirtyTracker dirtyTracker;

        DeserializedChunk(CcoChunkMetadata metadata, CcoBlockArray blockArray,
                          CcoAtomicStateManager stateManager, CcoDirtyTracker dirtyTracker) {
            this.metadata = metadata;
            this.blockArray = blockArray;
            this.stateManager = stateManager;
            this.dirtyTracker = dirtyTracker;
        }

        public CcoChunkMetadata getMetadata() {
            return metadata;
        }

        public CcoBlockArray getBlockArray() {
            return blockArray;
        }

        public CcoAtomicStateManager getStateManager() {
            return stateManager;
        }

        public CcoDirtyTracker getDirtyTracker() {
            return dirtyTracker;
        }
    }
}
