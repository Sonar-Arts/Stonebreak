package com.stonebreak.world.chunk.api.commonChunkOperations;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.buffers.CcoBufferAllocator;
import com.openmason.engine.voxel.cco.buffers.CcoBufferPool;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.cco.data.CcoChunkState;
import com.openmason.engine.voxel.cco.data.CcoDirtyTracker;
import com.openmason.engine.voxel.cco.data.CcoBufferHandle;
import com.openmason.engine.voxel.cco.data.CcoMeshData;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.openmason.engine.voxel.cco.operations.CcoBlockReader;
import com.openmason.engine.voxel.cco.operations.CcoBlockWriter;
import com.openmason.engine.voxel.cco.state.CcoAtomicStateManager;

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
 * 2. Builder pattern: CcoFactory.builder().withEmptyStorage(...).build()
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
     * Create empty paletted block storage for a standard chunk.
     *
     * @param airBlock Block type used as the uniform fill (air)
     * @return Paletted storage, all sections uniform-air
     */
    public static CcoBlockStorage createEmptyStorage(IBlockType airBlock) {
        return CcoPalettedChunkStorage.createEmpty(CcoBounds.getConfig(), airBlock);
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
     * Create atomic state manager with integrated dirty tracker
     *
     * @param initialState Initial chunk state
     * @param dirtyTracker Dirty flag tracker to integrate
     * @return Lock-free state manager
     */
    public static CcoAtomicStateManager createStateManager(CcoChunkState initialState, CcoDirtyTracker dirtyTracker) {
        return new CcoAtomicStateManager(java.util.EnumSet.of(initialState), dirtyTracker);
    }

    /**
     * Create block reader
     *
     * @param blocks Block storage
     * @return Fast block query operations
     */
    public static CcoBlockReader createBlockReader(CcoBlockStorage blocks) {
        return new CcoBlockReader(blocks);
    }

    /**
     * Create block writer
     *
     * @param blocks Block storage
     * @param dirtyTracker Dirty flag tracker
     * @return Dirty-tracking block writes
     */
    public static CcoBlockWriter createBlockWriter(CcoBlockStorage blocks, CcoDirtyTracker dirtyTracker) {
        return new CcoBlockWriter(blocks, dirtyTracker);
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
     *     .withEmptyStorage(BlockType.AIR)
     *     .withSeed(seed)
     *     .build();
     * </pre>
     */
    public static class Builder {
        // Required fields
        private int chunkX;
        private int chunkZ;
        private CcoBlockStorage storage;
        private IBlockType emptyFill;

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
         * Use fresh empty (all-air) paletted storage.
         *
         * @param airBlock Block type used as the uniform fill (air)
         * @return This builder
         */
        public Builder withEmptyStorage(IBlockType airBlock) {
            this.emptyFill = airBlock;
            return this;
        }

        /**
         * Use existing block storage (zero-copy adoption).
         *
         * @param storage Block storage to adopt
         * @return This builder
         */
        public Builder withStorage(CcoBlockStorage storage) {
            this.storage = storage;
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
            if (storage == null && emptyFill == null) {
                throw new IllegalStateException("Block storage is required (withStorage or withEmptyStorage)");
            }

            // Create components
            CcoChunkMetadata metadata = createMetadata(
                chunkX, chunkZ, generationSeed,
                hasStructures, needsDecoration, hasEntities
            );

            CcoBlockStorage blockStorage = storage != null ? storage : createEmptyStorage(emptyFill);
            CcoDirtyTracker dirtyTracker = createDirtyTracker();
            CcoAtomicStateManager stateManager = createStateManager(initialState, dirtyTracker);

            CcoBlockReader reader = createBlockReader(blockStorage);
            CcoBlockWriter writer = createBlockWriter(blockStorage, dirtyTracker);

            return new ComponentBundle(
                metadata,
                blockStorage,
                dirtyTracker,
                stateManager,
                reader,
                writer
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
        public final CcoBlockStorage blocks;
        public final CcoDirtyTracker dirtyTracker;
        public final CcoAtomicStateManager stateManager;
        public final CcoBlockReader reader;
        public final CcoBlockWriter writer;

        private ComponentBundle(CcoChunkMetadata metadata, CcoBlockStorage blocks,
                               CcoDirtyTracker dirtyTracker, CcoAtomicStateManager stateManager,
                               CcoBlockReader reader, CcoBlockWriter writer) {
            this.metadata = metadata;
            this.blocks = blocks;
            this.dirtyTracker = dirtyTracker;
            this.stateManager = stateManager;
            this.reader = reader;
            this.writer = writer;
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
