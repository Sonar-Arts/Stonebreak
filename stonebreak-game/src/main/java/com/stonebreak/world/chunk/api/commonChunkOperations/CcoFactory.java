package com.stonebreak.world.chunk.api.commonChunkOperations;

import com.stonebreak.world.chunk.api.commonChunkOperations.buffers.CcoBufferAllocator;
import com.stonebreak.world.chunk.api.commonChunkOperations.buffers.CcoBufferPool;
import com.stonebreak.world.chunk.api.commonChunkOperations.coordinates.CcoBounds;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.*;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBlockReader;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBlockWriter;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBulkOperations;
import com.stonebreak.world.chunk.api.commonChunkOperations.serialization.*;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.blocks.BlockType;

/**
 * CCO Factory - Component factory with builder pattern
 * Responsibilities:
 * - Create CCO components with proper initialization
 * - Provide builder pattern for complex component creation
 * - Manage component dependencies and lifecycles
 * - Centralize CCO object creation
 * Design: Factory + Builder pattern for flexible construction
 * Performance: Minimal overhead, focuses on correctness
 * Usage:
 * 1. Simple creation: CcoFactory.createMetadata(...)
 * 2. Builder pattern: CcoFactory.builder().withBlocks(...).build()
 * 3. Component recycling: Use buffer pool for reusable components
 */
public final class CcoFactory {

    // Shared buffer pool for all CCO components
    private static final CcoBufferPool bufferPool = new CcoBufferPool();

    // Prevent instantiation
    private CcoFactory() {}

    /**
     * Create chunk metadata
     *
     * @param chunkX Chunk X position
     * @param chunkZ Chunk Z position
     * @param generationSeed World generation seed
     * @return Immutable chunk metadata
     */
    public static CcoChunkMetadata createMetadata(int chunkX, int chunkZ, long generationSeed) {
        return new CcoChunkMetadata(
            chunkX,
            chunkZ,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            generationSeed,
            false,  // hasStructures
            false,  // needsDecoration
            false   // hasEntities
        );
    }

    /**
     * Create chunk metadata with features
     *
     * @param chunkX Chunk X position
     * @param chunkZ Chunk Z position
     * @param generationSeed World generation seed
     * @param hasStructures Has generated structures
     * @param needsDecoration Needs decoration pass
     * @param hasEntities Has spawned entities
     * @return Immutable chunk metadata
     */
    public static CcoChunkMetadata createMetadata(int chunkX, int chunkZ, long generationSeed,
                                                  boolean hasStructures, boolean needsDecoration,
                                                  boolean hasEntities) {
        return new CcoChunkMetadata(
            chunkX,
            chunkZ,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            generationSeed,
            hasStructures,
            needsDecoration,
            hasEntities
        );
    }

    /**
     * Create block array wrapper
     *
     * @param blocks Block data array (16x256x16)
     * @return Zero-copy block array wrapper
     */
    public static CcoBlockArray createBlockArray(BlockType[][][] blocks) {
        return CcoBlockArray.wrap(blocks);
    }

    /**
     * Create dirty tracker
     *
     * @return Lock-free dirty tracker
     */
    public static CcoDirtyTracker createDirtyTracker() {
        return new CcoDirtyTracker();
    }

    /**
     * Create atomic state manager
     *
     * @param initialState Initial chunk state
     * @return Lock-free state manager
     */
    public static CcoAtomicStateManager createStateManager(CcoChunkState initialState) {
        return new CcoAtomicStateManager(java.util.EnumSet.of(initialState));
    }

    /**
     * Create block reader
     *
     * @param blocks Block array
     * @return Fast block query operations
     */
    public static CcoBlockReader createBlockReader(CcoBlockArray blocks) {
        return new CcoBlockReader(blocks);
    }

    /**
     * Create block writer
     *
     * @param blocks Block array
     * @param dirtyTracker Dirty flag tracker
     * @return Dirty-tracking block writes
     */
    public static CcoBlockWriter createBlockWriter(CcoBlockArray blocks, CcoDirtyTracker dirtyTracker) {
        return new CcoBlockWriter(blocks, dirtyTracker);
    }

    /**
     * Create bulk operations handler
     *
     * @param blocks Block array
     * @param dirtyTracker Dirty flag tracker
     * @return Batch block operations
     */
    public static CcoBulkOperations createBulkOperations(CcoBlockArray blocks, CcoDirtyTracker dirtyTracker) {
        return new CcoBulkOperations(blocks, dirtyTracker);
    }

    /**
     * Create snapshot builder
     *
     * @param blocks Block array
     * @param metadata Chunk metadata
     * @return Lazy snapshot builder
     */
    public static CcoSnapshotBuilder createSnapshotBuilder(CcoBlockArray blocks,
                                                           CcoChunkMetadata metadata) {
        return CcoSnapshotBuilder.from(metadata, blocks);
    }

    /**
     * Create buffer handle for VBO
     *
     * @return VBO handle or INVALID_HANDLE on failure
     */
    public static CcoBufferHandle createVbo() {
        return CcoBufferAllocator.allocateVbo();
    }

    /**
     * Create buffer handle for EBO
     *
     * @return EBO handle or INVALID_HANDLE on failure
     */
    public static CcoBufferHandle createEbo() {
        return CcoBufferAllocator.allocateEbo();
    }

    /**
     * Create mesh data container
     *
     * @param vertexData Vertex positions (x,y,z per vertex)
     * @param textureData Texture coordinates (u,v per vertex)
     * @param normalData Normal vectors (nx,ny,nz per vertex)
     * @param isWaterData Water flags (1 float per vertex)
     * @param isAlphaTestedData Alpha test flags (1 float per vertex)
     * @param indexData Triangle indices
     * @param indexCount Number of valid indices
     * @return Immutable mesh data
     */
    public static CcoMeshData createMeshData(float[] vertexData, float[] textureData, float[] normalData,
                                             float[] isWaterData, float[] isAlphaTestedData,
                                             int[] indexData, int indexCount) {
        return new CcoMeshData(vertexData, textureData, normalData, isWaterData, isAlphaTestedData,
                               indexData, indexCount);
    }

    /**
     * Create empty mesh data
     *
     * @return Empty mesh with no geometry
     */
    public static CcoMeshData createEmptyMeshData() {
        return CcoMeshData.empty();
    }

    /**
     * Get shared buffer pool
     *
     * @return Global buffer pool instance
     */
    public static CcoBufferPool getBufferPool() {
        return bufferPool;
    }

    /**
     * Builder for complex CCO component initialization
     * Usage:
     * <pre>
     * var chunk = CcoFactory.builder()
     *     .withPosition(x, z)
     *     .withBlocks(blockArray)
     *     .withSeed(seed)
     *     .build();
     * </pre>
     */
    public static class Builder {
        // Required fields
        private int chunkX;
        private int chunkZ;
        private BlockType[][][] blocks;

        // Optional fields
        private long generationSeed = 0;
        private boolean hasStructures = false;
        private boolean needsDecoration = false;
        private boolean hasEntities = false;
        private CcoChunkState initialState = CcoChunkState.EMPTY;

        private Builder() {}

        /**
         * Set chunk position
         *
         * @param chunkX Chunk X coordinate
         * @param chunkZ Chunk Z coordinate
         * @return This builder
         */
        public Builder withPosition(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            return this;
        }

        /**
         * Set block data
         *
         * @param blocks Block array (16x256x16)
         * @return This builder
         */
        public Builder withBlocks(BlockType[][][] blocks) {
            this.blocks = blocks;
            return this;
        }

        /**
         * Set generation seed
         *
         * @param seed World generation seed
         * @return This builder
         */
        public Builder withSeed(long seed) {
            this.generationSeed = seed;
            return this;
        }

        /**
         * Set structure flag
         *
         * @param hasStructures Has generated structures
         * @return This builder
         */
        public Builder withStructures(boolean hasStructures) {
            this.hasStructures = hasStructures;
            return this;
        }

        /**
         * Set decoration flag
         *
         * @param needsDecoration Needs decoration pass
         * @return This builder
         */
        public Builder withDecoration(boolean needsDecoration) {
            this.needsDecoration = needsDecoration;
            return this;
        }

        /**
         * Set entities flag
         *
         * @param hasEntities Has spawned entities
         * @return This builder
         */
        public Builder withEntities(boolean hasEntities) {
            this.hasEntities = hasEntities;
            return this;
        }

        /**
         * Set initial state
         *
         * @param initialState Initial chunk state
         * @return This builder
         */
        public Builder withInitialState(CcoChunkState initialState) {
            this.initialState = initialState;
            return this;
        }

        /**
         * Build CCO component bundle
         *
         * @return Component bundle with all initialized components
         * @throws IllegalStateException if required fields not set
         */
        public ComponentBundle build() {
            // Validate required fields
            if (blocks == null) {
                throw new IllegalStateException("Block array is required");
            }

            // Create components
            CcoChunkMetadata metadata = createMetadata(
                chunkX, chunkZ, generationSeed,
                hasStructures, needsDecoration, hasEntities
            );

            CcoBlockArray blockArray = createBlockArray(blocks);
            CcoDirtyTracker dirtyTracker = createDirtyTracker();
            CcoAtomicStateManager stateManager = createStateManager(initialState);

            CcoBlockReader reader = createBlockReader(blockArray);
            CcoBlockWriter writer = createBlockWriter(blockArray, dirtyTracker);
            CcoBulkOperations bulkOps = createBulkOperations(blockArray, dirtyTracker);

            return new ComponentBundle(
                metadata,
                blockArray,
                dirtyTracker,
                stateManager,
                reader,
                writer,
                bulkOps
            );
        }
    }

    /**
     * Create new builder instance
     *
     * @return New builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Bundle of CCO components for a chunk
     * Provides all components needed for chunk operations
     */
    public static class ComponentBundle {
        public final CcoChunkMetadata metadata;
        public final CcoBlockArray blocks;
        public final CcoDirtyTracker dirtyTracker;
        public final CcoAtomicStateManager stateManager;
        public final CcoBlockReader reader;
        public final CcoBlockWriter writer;
        public final CcoBulkOperations bulkOps;

        private ComponentBundle(CcoChunkMetadata metadata, CcoBlockArray blocks,
                               CcoDirtyTracker dirtyTracker, CcoAtomicStateManager stateManager,
                               CcoBlockReader reader, CcoBlockWriter writer,
                               CcoBulkOperations bulkOps) {
            this.metadata = metadata;
            this.blocks = blocks;
            this.dirtyTracker = dirtyTracker;
            this.stateManager = stateManager;
            this.reader = reader;
            this.writer = writer;
            this.bulkOps = bulkOps;
        }

        /**
         * Create snapshot builder for this bundle
         *
         * @return Snapshot builder ready for use
         */
        public CcoSnapshotBuilder createSnapshotBuilder() {
            return CcoFactory.createSnapshotBuilder(blocks, metadata);
        }
    }

    /**
     * Shutdown factory and cleanup resources
     * Call on game shutdown to free pooled buffers
     */
    public static void shutdown() {
        bufferPool.clear();
    }

    /**
     * Get factory statistics
     *
     * @return Human-readable stats
     */
    public static String getStats() {
        return bufferPool.getStats();
    }
}
