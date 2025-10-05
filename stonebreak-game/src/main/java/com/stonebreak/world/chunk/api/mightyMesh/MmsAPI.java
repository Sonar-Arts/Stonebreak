package com.stonebreak.world.chunk.api.mightyMesh;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.ChunkErrorReporter;
import com.stonebreak.world.chunk.api.commonChunkOperations.core.CcoChunkData;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkMetadata;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsRenderableHandle;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshCache;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferPool;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsAsyncUploader;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsLodLevel;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.stonebreak.world.chunk.api.mightyMesh.mmsMetrics.MmsStatistics;
import com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing.MmsAtlasTextureMapper;
import com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing.MmsTextureMapper;

/**
 * Mighty Mesh System - Main API Facade.
 *
 * Provides a simple, unified interface to the MMS mesh generation system.
 * Hides complexity and coordinates all MMS subsystems.
 *
 * Design Philosophy:
 * - Facade Pattern: Simple interface to complex subsystem
 * - KISS: Easy-to-use API for common operations
 * - Singleton: Single instance coordinates all mesh operations
 *
 * Usage Example:
 * <pre>{@code
 * // Initialize
 * MmsAPI.initialize(textureAtlas, world);
 *
 * // Generate mesh for a chunk
 * MmsMeshData mesh = MmsAPI.getInstance().generateChunkMesh(chunk);
 *
 * // Upload to GPU
 * MmsRenderableHandle handle = MmsAPI.getInstance().uploadMeshToGPU(mesh);
 *
 * // Render
 * handle.render();
 *
 * // Cleanup
 * handle.close();
 * }</pre>
 *
 * @since MMS 1.0
 */
public final class MmsAPI {

    // Singleton instance
    private static volatile MmsAPI instance;
    private static final Object LOCK = new Object();

    // Core components
    private final MmsTextureMapper textureMapper;
    private final MmsCcoAdapter ccoAdapter;
    private final MmsStatistics statistics;
    private World world; // Not final - can be set after initialization

    // Advanced features (MMS 1.1)
    private final MmsMeshCache meshCache;
    private final MmsBufferPool bufferPool;
    private final MmsAsyncUploader asyncUploader;
    private MmsMeshPipeline meshPipeline;

    // Configuration
    private boolean greedyMeshingEnabled = true;
    private boolean lodSystemEnabled = true;
    private boolean meshCachingEnabled = true;
    private boolean bufferPoolingEnabled = true;
    private boolean asyncUploadEnabled = false; // Disabled by default for stability

    // State
    private volatile boolean initialized = false;

    /**
     * Private constructor for singleton pattern.
     *
     * @param textureAtlas Texture atlas for coordinate lookups
     * @param world World instance for neighbor queries (can be null initially, set later)
     */
    private MmsAPI(TextureAtlas textureAtlas, World world) {
        if (textureAtlas == null) {
            throw new IllegalArgumentException("Texture atlas cannot be null");
        }

        this.world = world; // Can be null initially
        this.textureMapper = new MmsAtlasTextureMapper(textureAtlas);
        this.ccoAdapter = new MmsCcoAdapter(textureMapper, world);
        this.statistics = new MmsStatistics();

        // Initialize advanced features
        this.meshCache = new MmsMeshCache();
        this.bufferPool = MmsBufferPool.getInstance();
        this.asyncUploader = MmsAsyncUploader.getInstance();

        this.initialized = true;

        System.out.println("[MmsAPI] Initialized Mighty Mesh System v1.1");
        System.out.println("[MmsAPI]   - Greedy Meshing: " + greedyMeshingEnabled);
        System.out.println("[MmsAPI]   - LOD System: " + lodSystemEnabled);
        System.out.println("[MmsAPI]   - Mesh Caching: " + meshCachingEnabled);
        System.out.println("[MmsAPI]   - Buffer Pooling: " + bufferPoolingEnabled);
        System.out.println("[MmsAPI]   - Async Upload: " + asyncUploadEnabled);
    }

    /**
     * Initializes the MMS API with required dependencies.
     * Must be called before using getInstance().
     *
     * @param textureAtlas Texture atlas
     * @param world World instance
     * @return MMS API instance
     */
    public static MmsAPI initialize(TextureAtlas textureAtlas, World world) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new MmsAPI(textureAtlas, world);
                }
            }
        }
        return instance;
    }

    /**
     * Gets the MMS API singleton instance.
     *
     * @return MMS API instance
     * @throws IllegalStateException if not initialized
     */
    public static MmsAPI getInstance() {
        MmsAPI current = instance;
        if (current == null || !current.initialized) {
            throw new IllegalStateException(
                "MmsAPI not initialized. Call MmsAPI.initialize(textureAtlas, world) first."
            );
        }
        return current;
    }

    /**
     * Checks if the MMS API has been initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {
        MmsAPI current = instance;
        return current != null && current.initialized;
    }

    /**
     * Sets the world instance after initialization.
     * Used when MMS is initialized before World is created.
     *
     * @param world World instance
     */
    public void setWorld(World world) {
        ensureInitialized();
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }
        this.world = world;
        if (ccoAdapter != null) {
            ccoAdapter.setWorld(world);
            System.out.println("[MmsAPI] World instance updated in MmsAPI and CCO adapter");
        }
    }

    // === High-Level API Methods ===

    /**
     * Generates mesh data for a chunk.
     * This is a CPU-intensive operation that can be run on a worker thread.
     *
     * @param chunk Chunk to generate mesh for
     * @return Generated mesh data
     */
    public MmsMeshData generateChunkMesh(Chunk chunk) {
        ensureInitialized();

        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }

        long startTime = System.currentTimeMillis();

        try {
            // Create CcoChunkData wrapper for the chunk
            CcoChunkDataWrapper wrapper = new CcoChunkDataWrapper(chunk);

            // Use CCO adapter to generate mesh
            MmsMeshData meshData = ccoAdapter.generateChunkMesh(
                wrapper,
                chunk.getCcoStateManager(),
                chunk.getCcoDirtyTracker()
            );

            // Record statistics
            long generationTime = System.currentTimeMillis() - startTime;
            statistics.recordMeshGeneration(
                meshData.getVertexCount(),
                meshData.getTriangleCount(),
                generationTime,
                meshData.getMemoryUsageBytes()
            );

            return meshData;

        } catch (Exception e) {
            statistics.recordMeshFailure();
            throw new RuntimeException("Failed to generate mesh for chunk at (" +
                chunk.getChunkX() + ", " + chunk.getChunkZ() + ")", e);
        }
    }


    /**
     * Uploads mesh data to GPU and returns a renderable handle.
     * MUST be called from the OpenGL thread.
     *
     * @param meshData Mesh data to upload
     * @return Renderable handle for GPU resources
     */
    public MmsRenderableHandle uploadMeshToGPU(MmsMeshData meshData) {
        ensureInitialized();

        if (meshData == null) {
            throw new IllegalArgumentException("Mesh data cannot be null");
        }

        long startTime = System.currentTimeMillis();

        try {
            MmsRenderableHandle handle = MmsRenderableHandle.upload(meshData);

            long uploadTime = System.currentTimeMillis() - startTime;
            statistics.recordMeshUpload(uploadTime);

            return handle;

        } catch (Exception e) {
            throw new RuntimeException("Failed to upload mesh to GPU", e);
        }
    }

    /**
     * Generates and uploads a chunk mesh in one call.
     * Mesh generation can be on worker thread, but upload must be on GL thread.
     *
     * @param chunk Chunk to generate and upload
     * @return Renderable handle
     */
    public MmsRenderableHandle generateAndUploadChunkMesh(Chunk chunk) {
        MmsMeshData meshData = generateChunkMesh(chunk);
        return uploadMeshToGPU(meshData);
    }

    // === Advanced Features (MMS 1.1) ===

    /**
     * Generates a mesh with LOD level support.
     * Checks cache first if caching is enabled.
     *
     * @param chunk Chunk to generate mesh for
     * @param lodLevel LOD level to generate
     * @return Generated mesh data
     */
    public MmsMeshData generateChunkMeshWithLod(Chunk chunk, MmsLodLevel lodLevel) {
        ensureInitialized();

        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }
        if (lodLevel == null) {
            throw new IllegalArgumentException("LOD level cannot be null");
        }

        // Try cache first
        if (meshCachingEnabled) {
            MmsMeshData cached = meshCache.get(chunk.getChunkX(), chunk.getChunkZ(), lodLevel);
            if (cached != null) {
                return cached;
            }
        }

        // Generate mesh
        MmsMeshData meshData = generateChunkMesh(chunk);

        // Cache the result
        if (meshCachingEnabled && !meshData.isEmpty()) {
            meshCache.put(chunk.getChunkX(), chunk.getChunkZ(), lodLevel, meshData);
        }

        return meshData;
    }

    /**
     * Submits a mesh for asynchronous upload.
     * Can be called from any thread.
     *
     * @param meshData Mesh data to upload
     * @param priority Upload priority (use MmsAsyncUploader.Priority constants)
     * @return Future for tracking upload completion
     */
    public java.util.concurrent.CompletableFuture<MmsRenderableHandle> uploadMeshAsync(
            MmsMeshData meshData, int priority) {
        ensureInitialized();

        if (!asyncUploadEnabled) {
            throw new IllegalStateException("Async upload is not enabled");
        }

        return asyncUploader.submit(meshData, priority);
    }

    /**
     * Processes pending async uploads for current frame.
     * MUST be called from OpenGL thread once per frame.
     *
     * @return Number of uploads processed
     */
    public int processAsyncUploads() {
        ensureInitialized();

        if (!asyncUploadEnabled) {
            return 0;
        }

        return asyncUploader.processUploads();
    }

    /**
     * Invalidates cached mesh for a chunk.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     */
    public void invalidateMeshCache(int chunkX, int chunkZ) {
        ensureInitialized();

        if (meshCachingEnabled) {
            meshCache.invalidateChunk(chunkX, chunkZ);
        }
    }

    // === Configuration Methods ===

    /**
     * Enables or disables greedy meshing.
     *
     * @param enabled true to enable
     * @return this API instance
     */
    public MmsAPI setGreedyMeshingEnabled(boolean enabled) {
        this.greedyMeshingEnabled = enabled;
        System.out.println("[MmsAPI] Greedy meshing " + (enabled ? "enabled" : "disabled"));
        return this;
    }

    /**
     * Enables or disables LOD system.
     *
     * @param enabled true to enable
     * @return this API instance
     */
    public MmsAPI setLodSystemEnabled(boolean enabled) {
        this.lodSystemEnabled = enabled;
        System.out.println("[MmsAPI] LOD system " + (enabled ? "enabled" : "disabled"));
        return this;
    }

    /**
     * Enables or disables mesh caching.
     *
     * @param enabled true to enable
     * @return this API instance
     */
    public MmsAPI setMeshCachingEnabled(boolean enabled) {
        this.meshCachingEnabled = enabled;
        System.out.println("[MmsAPI] Mesh caching " + (enabled ? "enabled" : "disabled"));
        return this;
    }

    /**
     * Enables or disables buffer pooling.
     *
     * @param enabled true to enable
     * @return this API instance
     */
    public MmsAPI setBufferPoolingEnabled(boolean enabled) {
        this.bufferPoolingEnabled = enabled;
        System.out.println("[MmsAPI] Buffer pooling " + (enabled ? "enabled" : "disabled"));
        return this;
    }

    /**
     * Enables or disables async upload system.
     *
     * @param enabled true to enable
     * @return this API instance
     */
    public MmsAPI setAsyncUploadEnabled(boolean enabled) {
        this.asyncUploadEnabled = enabled;
        System.out.println("[MmsAPI] Async upload " + (enabled ? "enabled" : "disabled"));
        return this;
    }

    /**
     * Checks if greedy meshing is enabled.
     *
     * @return true if enabled
     */
    public boolean isGreedyMeshingEnabled() {
        return greedyMeshingEnabled;
    }

    /**
     * Checks if LOD system is enabled.
     *
     * @return true if enabled
     */
    public boolean isLodSystemEnabled() {
        return lodSystemEnabled;
    }

    /**
     * Checks if mesh caching is enabled.
     *
     * @return true if enabled
     */
    public boolean isMeshCachingEnabled() {
        return meshCachingEnabled;
    }

    /**
     * Checks if buffer pooling is enabled.
     *
     * @return true if enabled
     */
    public boolean isBufferPoolingEnabled() {
        return bufferPoolingEnabled;
    }

    /**
     * Checks if async upload is enabled.
     *
     * @return true if enabled
     */
    public boolean isAsyncUploadEnabled() {
        return asyncUploadEnabled;
    }

    // === Component Access ===

    /**
     * Gets the texture mapper for direct access.
     *
     * @return Texture mapper
     */
    public MmsTextureMapper getTextureMapper() {
        ensureInitialized();
        return textureMapper;
    }

    /**
     * Gets the CCO adapter for direct access.
     *
     * @return CCO adapter
     */
    public MmsCcoAdapter getCcoAdapter() {
        ensureInitialized();
        return ccoAdapter;
    }

    /**
     * Gets performance statistics.
     *
     * @return Statistics instance
     */
    public MmsStatistics getStatistics() {
        ensureInitialized();
        return statistics;
    }

    /**
     * Resets all statistics counters.
     */
    public void resetStatistics() {
        ensureInitialized();
        statistics.reset();
        System.out.println("[MmsAPI] Statistics reset");
    }

    /**
     * Prints current statistics to console.
     */
    public void printStatistics() {
        ensureInitialized();
        System.out.println("[MmsAPI] Core Statistics: " + statistics);
        if (meshCachingEnabled) {
            System.out.println("[MmsAPI] Cache Statistics: " + meshCache.getStatistics());
        }
        if (bufferPoolingEnabled) {
            System.out.println("[MmsAPI] Buffer Pool Statistics: " + bufferPool.getStatistics());
        }
        if (asyncUploadEnabled) {
            System.out.println("[MmsAPI] Async Upload Statistics: " + asyncUploader.getStatistics());
        }
    }

    /**
     * Gets the mesh cache instance.
     *
     * @return Mesh cache
     */
    public MmsMeshCache getMeshCache() {
        ensureInitialized();
        return meshCache;
    }

    /**
     * Gets the buffer pool instance.
     *
     * @return Buffer pool
     */
    public MmsBufferPool getBufferPool() {
        ensureInitialized();
        return bufferPool;
    }

    /**
     * Gets the async uploader instance.
     *
     * @return Async uploader
     */
    public MmsAsyncUploader getAsyncUploader() {
        ensureInitialized();
        return asyncUploader;
    }

    /**
     * Gets the mesh pipeline instance.
     * Will be null until pipeline is created via createMeshPipeline().
     *
     * @return Mesh pipeline or null
     */
    public MmsMeshPipeline getMeshPipeline() {
        ensureInitialized();
        return meshPipeline;
    }

    /**
     * Creates and initializes a mesh pipeline for this world.
     * Should be called once during world initialization.
     * If a pipeline already exists, it will be shut down and replaced.
     *
     * @param world World instance for the pipeline
     * @param config World configuration
     * @param errorReporter Error reporter for diagnostics
     * @return Created mesh pipeline
     */
    public MmsMeshPipeline createMeshPipeline(
            World world,
            com.stonebreak.world.operations.WorldConfiguration config,
            ChunkErrorReporter errorReporter) {
        ensureInitialized();

        if (world == null) {
            throw new IllegalArgumentException("World cannot be null when creating mesh pipeline");
        }

        // Shut down existing pipeline if it exists (world switching)
        if (meshPipeline != null) {
            System.out.println("[MmsAPI] Shutting down existing mesh pipeline for world switch");
            meshPipeline.shutdown();
            meshPipeline = null;
        }

        // Update world reference
        this.world = world;
        if (ccoAdapter != null) {
            ccoAdapter.setWorld(world);
        }

        meshPipeline = new MmsMeshPipeline(world, config, errorReporter);
        System.out.println("[MmsAPI] Created new mesh pipeline for world");
        return meshPipeline;
    }

    // === Lifecycle ===

    /**
     * Ensures the API is initialized.
     *
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MmsAPI not initialized");
        }
    }

    /**
     * Shuts down the MMS API and releases resources.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (instance != null) {
                // Shutdown mesh pipeline first
                if (instance.meshPipeline != null) {
                    instance.meshPipeline.shutdown();
                    instance.meshPipeline = null;
                }

                // Clean up advanced features
                if (instance.meshCache != null) {
                    instance.meshCache.clear();
                }
                if (instance.bufferPool != null) {
                    instance.bufferPool.clear();
                }
                if (instance.asyncUploader != null) {
                    instance.asyncUploader.shutdown();
                }

                instance.initialized = false;
                instance = null;
                System.out.println("[MmsAPI] Mighty Mesh System shut down");
            }
        }
    }

    @Override
    public String toString() {
        return "MmsAPI{initialized=" + initialized + ", " + statistics + "}";
    }

    // === Internal Helper Classes ===

    /**
     * Simple wrapper to adapt Chunk to CcoChunkData interface.
     * This allows MmsCcoAdapter to access chunk data through the standard CCO interface.
     */
    private static class CcoChunkDataWrapper implements CcoChunkData {
        private final Chunk chunk;

        CcoChunkDataWrapper(Chunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public BlockType getBlock(int x, int y, int z) {
            return chunk.getBlock(x, y, z);
        }

        @Override
        public boolean isInBounds(int x, int y, int z) {
            return chunk.getBlockReader().isInBounds(x, y, z);
        }

        @Override
        public CcoChunkMetadata getMetadata() {
            // Create metadata from chunk data
            return new CcoChunkMetadata(
                chunk.getChunkX(),
                chunk.getChunkZ(),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L, // Generation seed
                chunk.areFeaturesPopulated(),
                false, // Needs decoration
                false  // Has entities
            );
        }
    }
}
