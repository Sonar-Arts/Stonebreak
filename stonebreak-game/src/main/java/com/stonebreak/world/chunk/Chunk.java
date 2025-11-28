package com.stonebreak.world.chunk;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory.ComponentBundle;
import com.stonebreak.world.chunk.api.commonChunkOperations.coordinates.CcoCoordinates;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.*;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBlockReader;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBlockWriter;
import com.stonebreak.world.chunk.api.commonChunkOperations.serialization.CcoSnapshotBuilder;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsRenderableHandle;
import com.stonebreak.world.chunk.utils.ChunkPosition;


import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a chunk of the world using the CCO (Common Chunk Operations) API.
 * This is a complete rewrite using CCO components for:
 * - Unified block operations with automatic dirty tracking
 * - Lock-free state management
 * - Optimized GPU buffer operations
 * - Built-in serialization support
 */
public class Chunk {

    private static final Logger logger = Logger.getLogger(Chunk.class.getName());

    // Position (immutable)
    private final int x;
    private final int z;

    // CCO Components
    private CcoChunkMetadata metadata;
    private final CcoBlockArray blocks;
    private final CcoBlockReader reader;
    private final CcoBlockWriter writer;
    private final CcoAtomicStateManager stateManager;
    private final CcoDirtyTracker dirtyTracker;

    // Mesh data and buffers (MMS-based)
    private MmsMeshData pendingMmsMeshData;
    private MmsRenderableHandle renderableHandle;
    private boolean meshGenerated = false;

    // Surface height cache for performance optimization
    // Caches the highest non-air block Y coordinate for each X,Z column
    // Eliminates ~131,000 redundant block accesses per chunk during feature generation
    private int[][] surfaceHeightCache;

    /**
     * Creates a new chunk at the specified position using CCO API.
     */
    public Chunk(int x, int z) {
        this.x = x;
        this.z = z;

        // Initialize block array (16x256x16)
        // Optimized: Y→X→Z loop order for cache locality + Arrays.fill() for Z dimension
        BlockType[][][] blockArray = new BlockType[16][256][16];
        for (int iy = 0; iy < 256; iy++) {
            for (int ix = 0; ix < 16; ix++) {
                java.util.Arrays.fill(blockArray[ix][iy], BlockType.AIR);
            }
        }

        // Build CCO components using factory
        ComponentBundle bundle = CcoFactory.builder()
            .withPosition(x, z)
            .withBlocks(blockArray)
            .withSeed(0)
            .withInitialState(CcoChunkState.BLOCKS_POPULATED)
            .build();

        // Extract components
        this.metadata = bundle.metadata;
        this.blocks = bundle.blocks;
        this.reader = bundle.reader;
        this.writer = bundle.writer;
        this.stateManager = bundle.stateManager;
        this.dirtyTracker = bundle.dirtyTracker;
    }

    // ===== Block Operations (CCO-based) =====

    /**
     * Gets the block type at the specified local position.
     */
    public BlockType getBlock(int x, int y, int z) {
        return reader.get(x, y, z);
    }

    /**
     * Sets the block type at the specified local position.
     * Automatically marks chunk as dirty for mesh regeneration and saving.
     */
    public void setBlock(int x, int y, int z, BlockType blockType) {
        boolean changed = writer.set(x, y, z, blockType);
        if (changed) {
            metadata = metadata.withUpdatedTimestamp();
        }
    }

    // ===== Mesh Operations (CCO-based) =====

    /**
     * Builds the mesh data for this chunk using MMS API. This is CPU-intensive and can be run on a worker thread.
     */
    public void buildAndPrepareMeshData(World world) {
        try {
            // Update loading progress
            Game game = Game.getInstance();
            if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
                game.getLoadingScreen().updateProgress("Meshing Chunk");
            }

            // Generate mesh data using MMS API
            if (!MmsAPI.isInitialized()) {
                logger.log(Level.SEVERE, "MMS API not initialized for chunk (" + x + ", " + z + ")");
                stateManager.removeState(CcoChunkState.MESH_GENERATING);
                dirtyTracker.markMeshDirtyOnly();
                return;
            }

            pendingMmsMeshData = MmsAPI.getInstance().generateChunkMesh(this);

            // MMS API already updates state, but ensure consistency
            // Mark mesh as ready for GPU upload
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            stateManager.addState(CcoChunkState.MESH_CPU_READY);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "CRITICAL: Exception during mesh generation for chunk (" + x + ", " + z + "): "
                + e.getMessage(), e);
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            dirtyTracker.markMeshDirtyOnly();
        }
    }

    /**
     * Applies the prepared mesh data to OpenGL using MMS API. This must be called on the main GL thread.
     */
    public void applyPreparedDataToGL() {
        if (!stateManager.hasState(CcoChunkState.MESH_CPU_READY)) {
            return; // Data not ready
        }

        try {
            if (pendingMmsMeshData == null || pendingMmsMeshData.isEmpty()) {
                // Empty mesh - clean up existing resources
                if (meshGenerated && renderableHandle != null) {
                    renderableHandle.close();
                    renderableHandle = null;
                    meshGenerated = false;
                }
                stateManager.removeState(CcoChunkState.MESH_CPU_READY);
                stateManager.addState(CcoChunkState.BLOCKS_POPULATED);
                return;
            }

            // Upload mesh to GPU using MMS API
            if (meshGenerated && renderableHandle != null) {
                // Clean up old handle before creating new one
                renderableHandle.close();
            }

            renderableHandle = MmsAPI.getInstance().uploadMeshToGPU(pendingMmsMeshData);
            meshGenerated = true;

            stateManager.removeState(CcoChunkState.MESH_CPU_READY);
            stateManager.addState(CcoChunkState.MESH_GPU_UPLOADED);

            // Clear dirty flags after successful upload
            dirtyTracker.clearMeshDirty();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "CRITICAL: Error during GL buffer upload for chunk (" + x + ", " + z + ")", e);
            stateManager.removeState(CcoChunkState.MESH_CPU_READY);
            dirtyTracker.markMeshDirtyOnly();
        } finally {
            pendingMmsMeshData = null;
        }
    }

    /**
     * Renders the chunk using MMS API.
     */
    public void render() {
        // Debug: Always log first few chunks
        if (debugRenderCallCount < 5) {
            System.out.println("[Chunk.render] Called for (" + x + "," + z + "): " +
                "renderable=" + stateManager.isRenderable() +
                " meshGen=" + meshGenerated +
                " handle=" + (renderableHandle != null));
            debugRenderCallCount++;
        }

        if (!stateManager.isRenderable() || !meshGenerated || renderableHandle == null) {
            return;
        }

        // Debug first few successful renders
        if (debugRenderSuccessCount < 3) {
            System.out.println("[Chunk.render] SUCCESS: Rendering chunk at (" + x + "," + z + ") with " +
                renderableHandle.getIndexCount() + " indices");
            debugRenderSuccessCount++;
        }

        renderableHandle.render();
    }

    private static int debugRenderCallCount = 0;
    private static int debugRenderSuccessCount = 0;

    // ===== Coordinate Operations =====

    /**
     * Gets the position of this chunk as a ChunkPosition object.
     * This is the preferred method for accessing chunk coordinates following SOLID principles.
     */
    public ChunkPosition getPosition() {
        return new ChunkPosition(x, z);
    }

    /**
     * Converts a local X coordinate to a world X coordinate.
     */
    public int getWorldX(int localX) {
        return CcoCoordinates.localToWorldX(x, localX);
    }

    /**
     * Converts a local Z coordinate to a world Z coordinate.
     */
    public int getWorldZ(int localZ) {
        return CcoCoordinates.localToWorldZ(z, localZ);
    }

    public int getChunkX() {
        return this.x;
    }

    public int getChunkZ() {
        return this.z;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    // ===== State Management (CCO-based) =====

    public boolean areFeaturesPopulated() {
        return stateManager.hasState(CcoChunkState.FEATURES_POPULATED) ||
               metadata.hasStructures();
    }

    public void setFeaturesPopulated(boolean featuresPopulated) {
        if (featuresPopulated) {
            stateManager.addState(CcoChunkState.FEATURES_POPULATED);
            metadata = metadata.withFeaturesPopulated();
        }
    }

    public boolean isMeshGenerated() {
        return meshGenerated;
    }

    public boolean isDataReadyForGL() {
        return stateManager.hasState(CcoChunkState.MESH_CPU_READY);
    }

    public boolean isMeshDataGenerationScheduledOrInProgress() {
        return stateManager.hasState(CcoChunkState.MESH_GENERATING);
    }

    // ===== Surface Height Cache =====

    /**
     * Gets the cached surface heights for this chunk.
     * Returns a 16x16 array where each element is the Y coordinate of the highest non-air block.
     * @return The surface height cache, or null if not yet computed
     */
    public int[][] getSurfaceHeightCache() {
        return surfaceHeightCache;
    }

    /**
     * Sets the surface height cache for this chunk.
     * This should be called during terrain generation to cache surface heights.
     * @param cache A 16x16 array of surface heights
     */
    public void setSurfaceHeightCache(int[][] cache) {
        this.surfaceHeightCache = cache;
    }

    // ===== Dirty Tracking (CCO-based) =====

    /**
     * Checks if the chunk has been modified since last save.
     */
    public boolean isDirty() {
        return dirtyTracker.isDataDirty();
    }

    /**
     * Marks the chunk as dirty (needing to be saved).
     */
    public void markDirty() {
        dirtyTracker.markDataDirtyOnly();
        metadata = metadata.withUpdatedTimestamp();
    }

    /**
     * Marks the chunk as clean (saved to disk).
     */
    public void markClean() {
        dirtyTracker.clearDataDirty();
    }

    // ===== Serialization (CCO-based) =====

    /**
     * Creates a serializable snapshot of this chunk using CCO API.
     * Extracts water metadata from the World's WaterSystem and entities from EntityManager.
     *
     * CRITICAL: Creates an ATOMIC snapshot by deep-copying the block array immediately.
     * This prevents race conditions where the chunk is modified after the snapshot is created
     * but before it's serialized.
     *
     * @param world World instance to extract water metadata and entities from
     * @return Immutable snapshot including blocks, water metadata, and entities
     */
    public CcoSerializableSnapshot createSnapshot(World world) {
        // CRITICAL VALIDATION: Verify metadata coordinates match chunk coordinates
        // This catches corruption bugs before writing corrupted data to disk
        if (metadata.getChunkX() != this.x || metadata.getChunkZ() != this.z) {
            throw new IllegalStateException(String.format(
                "CRITICAL: Metadata coordinate mismatch! Chunk fields=(%d,%d) but metadata=(%d,%d)",
                this.x, this.z, metadata.getChunkX(), metadata.getChunkZ()
            ));
        }

        // CRITICAL FIX: Deep copy blocks array IMMEDIATELY to prevent race conditions
        // This ensures the snapshot is truly immutable and captures the exact state
        // at the moment checkAndClearDataDirty() was called.
        BlockType[][][] blocksCopy = blocks.deepCopy();

        // Extract water metadata from WaterSystem
        // Use Long keys for performance (eliminates string allocation overhead)
        java.util.Map<Long, com.stonebreak.world.save.model.ChunkData.WaterBlockData> waterMetadata = new java.util.HashMap<>();

        int totalWaterBlocks = 0;
        int sourceBlocks = 0;
        int flowingBlocks = 0;
        int missingFromWaterSystem = 0;

        if (world != null && world.getWaterSystem() != null) {
            // Scan all water blocks in this chunk (using the deep copy)
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int y = 0; y < 256; y++) {
                        if (blocksCopy[localX][y][localZ] == BlockType.WATER) {
                            totalWaterBlocks++;
                            int worldX = x * 16 + localX;
                            int worldZ = z * 16 + localZ;

                            // Get water state from WaterSystem
                            var waterBlock = world.getWaterSystem().getWaterBlock(worldX, y, worldZ);
                            if (waterBlock == null) {
                                missingFromWaterSystem++;
                            } else if (waterBlock.isSource()) {
                                sourceBlocks++;
                            } else {
                                flowingBlocks++;
                                // Only save non-source water (source is default)
                                // Pack coordinates into long: (x << 16) | (y << 8) | z
                                // Eliminates 100-500 string allocations per ocean chunk
                                long key = ((long)localX << 16) | ((long)y << 8) | (long)localZ;
                                waterMetadata.put(key, new com.stonebreak.world.save.model.ChunkData.WaterBlockData(
                                    waterBlock.level(),
                                    waterBlock.falling()
                                ));
                            }
                        }
                    }
                }
            }

            // Log water metadata extraction results
            if (totalWaterBlocks > 0) {
                logger.log(Level.FINE, String.format(
                    "[WATER-SAVE] Chunk (%d,%d): %d water blocks total | %d sources | %d flowing (saved) | %d missing from WaterSystem",
                    x, z, totalWaterBlocks, sourceBlocks, flowingBlocks, missingFromWaterSystem
                ));
            }
        } else {
            // Don't log warning - null world is expected in unit tests
            // In production, world should never be null when saving
        }

        // Extract entities in this chunk from EntityManager
        java.util.List<com.stonebreak.world.save.model.EntityData> entities = new java.util.ArrayList<>();
        if (world != null) {
            com.stonebreak.core.Game game = Game.getInstance();
            if (game != null && game.getEntityManager() != null) {
                entities = game.getEntityManager().getEntitiesInChunk(x, z);
                logger.log(Level.FINE, String.format(
                    "[ENTITY-SAVE] Chunk (%d,%d): Saving %d entities",
                    x, z, entities.size()
                ));
            }
        }

        // Create snapshot with deep-copied blocks, water metadata, entities, and entity generation flag
        return new CcoSerializableSnapshot(
            metadata.getChunkX(),
            metadata.getChunkZ(),
            blocksCopy,  // Use deep copy instead of reference
            metadata.getLastModified(),
            metadata.isFeaturesPopulated(),
            metadata.hasEntities(),  // Preserve entity generation flag
            waterMetadata,
            entities
        );
    }

    /**
     * Loads chunk data from a CCO snapshot.
     * Applies block data, water metadata, and entities from the snapshot.
     *
     * @param snapshot Snapshot to load from
     * @param world World instance to apply water metadata and entities to
     */
    public void loadFromSnapshot(CcoSerializableSnapshot snapshot, World world) {
        logger.log(Level.FINE, String.format(
            "[LOAD-SEQUENCE] Chunk (%d,%d): loadFromSnapshot() called with %d water metadata entries and %d entities",
            snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getWaterMetadata().size(), snapshot.getEntities().size()
        ));

        // Update metadata from snapshot
        this.metadata = new CcoChunkMetadata(
            snapshot.getChunkX(),
            snapshot.getChunkZ(),
            metadata.getCreatedTime(),
            snapshot.getLastModified().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            metadata.getGenerationSeed(),
            metadata.hasStructures(),
            snapshot.isFeaturesPopulated(),
            snapshot.hasEntitiesGenerated() // Restore entity generation flag from snapshot
        );

        // Copy block data
        BlockType[][][] snapshotBlocks = snapshot.getBlocks();
        BlockType[][][] currentBlocks = blocks.getUnderlyingArray();
        for (int ix = 0; ix < 16; ix++) {
            for (int iy = 0; iy < 256; iy++) {
                System.arraycopy(snapshotBlocks[ix][iy], 0, currentBlocks[ix][iy], 0, 16);
            }
        }

        // Apply water metadata to WaterSystem BEFORE onChunkLoaded is called
        if (world != null && world.getWaterSystem() != null && !snapshot.getWaterMetadata().isEmpty()) {
            logger.log(Level.FINE, String.format(
                "[WATER-LOAD-SEQUENCE] Chunk (%d,%d): Calling loadWaterMetadata() with %d entries",
                snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getWaterMetadata().size()
            ));
            world.getWaterSystem().loadWaterMetadata(snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getWaterMetadata());
            logger.log(Level.FINE, String.format(
                "[WATER-LOAD-SEQUENCE] Chunk (%d,%d): loadWaterMetadata() completed",
                snapshot.getChunkX(), snapshot.getChunkZ()
            ));
        } else {
            logger.log(Level.FINE, String.format(
                "[WATER-LOAD-SEQUENCE] Chunk (%d,%d): Skipping loadWaterMetadata() - world=%s, waterSystem=%s, metadataSize=%d",
                snapshot.getChunkX(), snapshot.getChunkZ(),
                world != null ? "present" : "null",
                (world != null && world.getWaterSystem() != null) ? "present" : "null",
                snapshot.getWaterMetadata().size()
            ));
        }

        // Load entities from snapshot
        if (world != null && !snapshot.getEntities().isEmpty()) {
            com.stonebreak.core.Game game = Game.getInstance();
            if (game != null && game.getEntityManager() != null) {
                logger.log(Level.FINE, String.format(
                    "[ENTITY-LOAD] Chunk (%d,%d): Loading %d entities",
                    snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getEntities().size()
                ));
                game.getEntityManager().loadEntitiesForChunk(snapshot.getEntities(), snapshot.getChunkX(), snapshot.getChunkZ());
            }
        }

        dirtyTracker.markBlockChanged();
        stateManager.removeState(CcoChunkState.MESH_GPU_UPLOADED);
        stateManager.removeState(CcoChunkState.MESH_CPU_READY);
    }


    /**
     * Gets the last modification timestamp.
     */
    public java.time.LocalDateTime getLastModified() {
        return metadata.getLastModified();
    }

    /**
     * Sets the last modification timestamp. Used by save system.
     */
    public void setLastModified(java.time.LocalDateTime lastModified) {
        // Convert LocalDateTime to millis and update metadata
        long millis = lastModified.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        this.metadata = new CcoChunkMetadata(
            metadata.getChunkX(),
            metadata.getChunkZ(),
            metadata.getCreatedTime(),
            millis,
            metadata.getGenerationSeed(),
            metadata.hasStructures(),
            metadata.needsDecoration(),
            metadata.hasEntities()
        );
    }

    // ===== Resource Cleanup =====

    /**
     * Cleans up CPU-side resources. Safe to call from any thread.
     * NOTE: Block array cleanup removed - blocks must remain accessible for
     * collision detection and neighbor chunk meshing during unload.
     * Memory will be released when the Chunk object itself is garbage collected.
     */
    public void cleanupCpuResources() {
        pendingMmsMeshData = null;

        // Block array intentionally NOT cleared here - it's needed for:
        // 1. Player collision detection during chunk unload
        // 2. Neighbor chunk meshing (edge blocks must be accessible)
        // 3. Saving dirty chunks (requires block data)
        // Memory will be freed when the entire Chunk object is GC'd
    }

    /**
     * Cleans up GPU resources using MMS API. MUST be called from the main OpenGL thread.
     */
    public void cleanupGpuResources() {
        if (renderableHandle != null) {
            renderableHandle.close();
            renderableHandle = null;
        }
        meshGenerated = false;
    }

    // ===== CCO Component Access =====

    /**
     * Gets the CCO state manager for this chunk.
     */
    public CcoAtomicStateManager getCcoStateManager() {
        return stateManager;
    }

    /**
     * Gets the CCO block reader for efficient block access.
     * Prefer this over getBlocks() for performance-critical read operations.
     */
    public CcoBlockReader getBlockReader() {
        return reader;
    }

    /**
     * Gets the CCO dirty tracker for this chunk.
     */
    public CcoDirtyTracker getCcoDirtyTracker() {
        return dirtyTracker;
    }

    /**
     * Gets the CCO metadata for this chunk.
     * Provides access to chunk metadata including entity generation tracking.
     */
    public CcoChunkMetadata getCcoMetadata() {
        return metadata;
    }

    /**
     * Marks the chunk as having entities generated.
     * This prevents duplicate entity spawning when chunks are saved and reloaded.
     */
    public void setEntitiesGenerated(boolean generated) {
        metadata = metadata.withEntities(generated);
        if (generated) {
            // Mark dirty to ensure entity data is saved
            markDirty();
        }
    }

    // ===== MMS Mesh Handle Management =====

    /**
     * Gets the MMS renderable handle for this chunk.
     * Used by MmsMeshPipeline for managing GPU resources.
     *
     * @return Renderable handle or null if not uploaded
     */
    public MmsRenderableHandle getMmsRenderableHandle() {
        return renderableHandle;
    }

    /**
     * Sets the MMS renderable handle for this chunk.
     * Used by MmsMeshPipeline after GPU upload.
     *
     * @param handle Renderable handle
     */
    public void setMmsRenderableHandle(MmsRenderableHandle handle) {
        this.renderableHandle = handle;
        if (handle != null) {
            this.meshGenerated = true;
        }
    }
}
