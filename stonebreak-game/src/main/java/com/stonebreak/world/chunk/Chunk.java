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

    /**
     * Creates a new chunk at the specified position using CCO API.
     */
    public Chunk(int x, int z) {
        this.x = x;
        this.z = z;

        // Initialize block array (16x256x16)
        BlockType[][][] blockArray = new BlockType[16][256][16];
        for (int ix = 0; ix < 16; ix++) {
            for (int iy = 0; iy < 256; iy++) {
                for (int iz = 0; iz < 16; iz++) {
                    blockArray[ix][iy][iz] = BlockType.AIR;
                }
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
     * Extracts water metadata from the World's WaterSystem to ensure flow levels persist.
     *
     * @param world World instance to extract water metadata from
     * @return Immutable snapshot including blocks and water metadata
     */
    public CcoSerializableSnapshot createSnapshot(World world) {
        // Extract water metadata from WaterSystem
        java.util.Map<String, com.stonebreak.world.save.model.ChunkData.WaterBlockData> waterMetadata = new java.util.HashMap<>();

        int totalWaterBlocks = 0;
        int sourceBlocks = 0;
        int flowingBlocks = 0;
        int missingFromWaterSystem = 0;

        if (world != null && world.getWaterSystem() != null) {
            // Scan all water blocks in this chunk
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int y = 0; y < 256; y++) {
                        if (reader.get(localX, y, localZ) == BlockType.WATER) {
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
                                String key = localX + "," + y + "," + localZ;
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
            logger.log(Level.WARNING, String.format(
                "[WATER-SAVE] Chunk (%d,%d): Cannot extract water metadata - world or WaterSystem is null",
                x, z
            ));
        }

        // Create snapshot with water metadata
        return new CcoSerializableSnapshot(
            metadata.getChunkX(),
            metadata.getChunkZ(),
            blocks.getUnderlyingArray(),
            metadata.getLastModified(),
            metadata.isFeaturesPopulated(),
            waterMetadata
        );
    }

    /**
     * Loads chunk data from a CCO snapshot.
     * Applies both block data and water metadata from the snapshot.
     *
     * @param snapshot Snapshot to load from
     * @param world World instance to apply water metadata to
     */
    public void loadFromSnapshot(CcoSerializableSnapshot snapshot, World world) {
        logger.log(Level.FINE, String.format(
            "[WATER-LOAD-SEQUENCE] Chunk (%d,%d): loadFromSnapshot() called with %d water metadata entries",
            snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getWaterMetadata().size()
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
            metadata.hasEntities()
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
