package com.stonebreak.world.chunk;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory.ComponentBundle;
import com.openmason.engine.voxel.cco.coordinates.CcoCoordinates;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.cco.data.CcoChunkState;
import com.openmason.engine.voxel.cco.data.CcoDirtyTracker;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot;
import com.openmason.engine.voxel.cco.operations.CcoBlockReader;
import com.openmason.engine.voxel.cco.operations.CcoBlockWriter;
import com.openmason.engine.voxel.cco.state.CcoAtomicStateManager;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.lighting.ChunkHeightMap;
import com.openmason.engine.voxel.lighting.ColumnOpacityProbe;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.lighting.WorldLightingContext;


import java.util.ArrayList;
import java.util.List;
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
    private final CcoBlockStorage blocks;
    private final CcoBlockReader reader;
    private final CcoBlockWriter writer;
    private final CcoAtomicStateManager stateManager;
    private final CcoDirtyTracker dirtyTracker;

    // Mesh data and buffers (MMS-based)
    private MmsMeshData pendingMmsMeshData;
    private ChunkMeshResult pendingChunkMeshResult;
    private MmsRenderableHandle renderableHandle;
    private List<com.openmason.engine.voxel.sbo.SBORenderData> sboRenderDataList;
    private boolean meshGenerated = false;

    // Per-column sky-shadow heightmap. Pure function of block data; maintained
    // incrementally by setBlock. No propagation, no seeding queue.
    private final ChunkHeightMap heightMap = new ChunkHeightMap(
            WorldConfiguration.CHUNK_SIZE, WorldConfiguration.WORLD_HEIGHT, WorldConfiguration.CHUNK_SIZE);
    private final ColumnOpacityProbe opacityProbe = WorldLightingContext.probeFor(this);

    /**
     * Sparse per-block SBO state map (1.3+). Keys are packed local coordinates
     * (see {@link com.stonebreak.world.chunk.utils.LocalBlockKey}) — no string
     * allocation per access. Only blocks with a non-default state are stored —
     * clearing or setting a block to its default state removes the entry to
     * keep memory and save footprint minimal.
     */
    private final java.util.Map<Integer, String> blockStates = new java.util.HashMap<>();

    /**
     * Creates a new chunk at the specified position using CCO API.
     * Paletted storage starts as uniform-air sections — no 65k-reference
     * array allocation/fill.
     */
    public Chunk(int x, int z) {
        this(x, z, null);
    }

    /**
     * Creates a chunk adopting pre-built block storage (zero-copy).
     * Used by terrain generation, which fills storage directly instead of
     * issuing 65k {@code setBlock} calls.
     *
     * @param storage Pre-built storage to adopt, or null for empty (all-air)
     */
    public Chunk(int x, int z, CcoBlockStorage storage) {
        this.x = x;
        this.z = z;

        CcoFactory.Builder builder = CcoFactory.builder()
            .withPosition(x, z)
            .withSeed(0)
            .withInitialState(CcoChunkState.BLOCKS_POPULATED);
        if (storage != null) {
            builder.withStorage(storage);
        } else {
            builder.withEmptyStorage(BlockType.AIR);
        }
        ComponentBundle bundle = builder.build();

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
        return (BlockType) reader.get(x, y, z);
    }

    /**
     * Sets the block type at the specified local position.
     * Automatically marks chunk as dirty for mesh regeneration and saving.
     */
    public void setBlock(int x, int y, int z, BlockType blockType) {
        BlockType previous = (BlockType) reader.get(x, y, z);
        boolean changed = writer.set(x, y, z, blockType);
        if (changed) {
            metadata = metadata.withUpdatedTimestamp();
            // Drop any stale per-block state when the block type itself changes —
            // states are scoped to the block instance, not the cell. Without
            // this, breaking a water-bucket-placed block and replacing it with
            // a different block would leak the bucket's "water" state.
            blockStates.remove(com.stonebreak.world.chunk.utils.LocalBlockKey.pack(x, y, z));
            heightMap.onBlockChanged(x, y, z,
                    BlockOpacity.isOpaque(blockType),
                    BlockOpacity.isOpaque(previous),
                    opacityProbe);
        }
    }

    // ===== Per-block SBO State Operations (1.3+) =====

    /**
     * Returns the SBO state name at the given local cell, or {@code null}
     * if the block carries no non-default state.
     */
    public String getBlockState(int x, int y, int z) {
        return blockStates.get(com.stonebreak.world.chunk.utils.LocalBlockKey.pack(x, y, z));
    }

    /**
     * Sets the SBO state name for a block. Pass {@code null} (or empty) to
     * clear back to the default state. Marks chunk dirty for save & remesh.
     */
    public void setBlockState(int x, int y, int z, String state) {
        int key = com.stonebreak.world.chunk.utils.LocalBlockKey.pack(x, y, z);
        String previous;
        if (state == null || state.isBlank()) {
            previous = blockStates.remove(key);
        } else {
            previous = blockStates.put(key, state);
        }
        if (!java.util.Objects.equals(previous, state)) {
            metadata = metadata.withUpdatedTimestamp();
            dirtyTracker.markBlockChanged();
        }
    }

    /**
     * Read-only view of the per-block state map. Keys are packed local
     * coordinates ({@link com.stonebreak.world.chunk.utils.LocalBlockKey}).
     */
    public java.util.Map<Integer, String> getBlockStates() {
        return java.util.Collections.unmodifiableMap(blockStates);
    }

    /** Returns the engine opacity probe bound to this chunk — used by recomputeAll callers. */
    public ColumnOpacityProbe getOpacityProbe() {
        return opacityProbe;
    }

    /**
     * Highest Y containing a non-air block, or -1 if the chunk is all air.
     * Cheap with paletted storage — used by the mesher to skip empty air space.
     */
    public int getHighestNonAirY() {
        return blocks.getHighestNonAirY();
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

            pendingChunkMeshResult = MmsAPI.getInstance().generateChunkMesh(this);
            pendingMmsMeshData = pendingChunkMeshResult.atlasMesh();

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

            // Upload SBO meshes if present (one per block type)
            if (pendingChunkMeshResult != null && pendingChunkMeshResult.hasSBOMesh()) {
                closeSBORenderData();
                sboRenderDataList = new ArrayList<>(pendingChunkMeshResult.sboEntries().size());
                for (ChunkMeshResult.SBOEntry entry : pendingChunkMeshResult.sboEntries()) {
                    MmsRenderableHandle sboHandle = MmsAPI.getInstance().uploadMeshToGPU(entry.meshData());
                    sboRenderDataList.add(new com.openmason.engine.voxel.sbo.SBORenderData(sboHandle, entry.batches()));
                }
            } else {
                closeSBORenderData();
                sboRenderDataList = null;
            }

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
            pendingChunkMeshResult = null;
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
        // Either CPU-built awaiting upload OR already uploaded — both mean "renderable mesh
        // exists, no rebuild needed". The pipeline transitions CPU_READY → GPU_UPLOADED on
        // upload (mutually exclusive states), so checking only CPU_READY would falsely
        // report "not ready" for every chunk that's finished uploading and is rendering fine.
        return stateManager.hasState(CcoChunkState.MESH_CPU_READY)
            || stateManager.hasState(CcoChunkState.MESH_GPU_UPLOADED);
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
     * Extracts water metadata from the World's WaterSystem and entities from EntityManager.
     *
     * CRITICAL: Creates an ATOMIC snapshot by copying the block storage immediately.
     * This prevents race conditions where the chunk is modified after the snapshot is created
     * but before it's serialized. The copy is cheap with paletted storage (~35 KB,
     * uniform air sections cost almost nothing).
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

        // Copy block storage IMMEDIATELY so the snapshot is immutable and captures
        // the exact state at the moment checkAndClearDataDirty() was called.
        CcoBlockStorage blocksCopy = blocks.copy();

        // Extract water metadata from WaterSystem's sparse cell map — no 65k-cell
        // block scan. Only non-source (flowing) water is saved; source is default.
        java.util.Map<String, com.stonebreak.world.save.model.ChunkData.WaterBlockData> waterMetadata = new java.util.HashMap<>();

        if (world != null && world.getWaterSystem() != null) {
            world.getWaterSystem().forEachCellInChunk(x, z, (worldX, y, worldZ, waterBlock) -> {
                if (waterBlock.isSource()) {
                    return;
                }
                int localX = worldX - x * 16;
                int localZ = worldZ - z * 16;
                // Guard against drift between the cell map and block data: only
                // persist cells whose block (in this atomic copy) is still water.
                if (blocksCopy.get(localX, y, localZ) == BlockType.WATER) {
                    waterMetadata.put(localX + "," + y + "," + localZ,
                        new com.stonebreak.world.save.model.ChunkData.WaterBlockData(
                            waterBlock.level(),
                            waterBlock.falling()
                        ));
                }
            });

            if (!waterMetadata.isEmpty()) {
                logger.log(Level.FINE, String.format(
                    "[WATER-SAVE] Chunk (%d,%d): %d flowing water blocks saved",
                    x, z, waterMetadata.size()
                ));
            }
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

        // Create snapshot with copied block storage, water metadata, entities,
        // entity generation flag, and per-block SBO state map.
        return new CcoSerializableSnapshot(
            metadata.getChunkX(),
            metadata.getChunkZ(),
            blocksCopy,
            metadata.getLastModified(),
            metadata.isFeaturesPopulated(),
            metadata.hasEntities(),
            waterMetadata,
            entities,
            new java.util.HashMap<>(blockStates)
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

        // Copy block data — section-level palette copy, near-free compared to
        // the old 65k-element arraycopy.
        blocks.copyFrom(snapshot.getBlockStorage());

        // Restore per-block SBO state map (1.3+). Empty for v1 saves.
        blockStates.clear();
        blockStates.putAll(snapshot.getBlockStates());

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

        // Load entities from snapshot into THIS world's entity manager. Critically, prefer the
        // world's own manager over the Game singleton: during server world-load the singleton
        // points at the client's manager (or, just after a world switch, the previous session's
        // terminated one), which would reject the deserialization task.
        if (world != null && !snapshot.getEntities().isEmpty()) {
            com.stonebreak.mobs.entities.EntityManager em = world.getEntityManager();
            if (em == null) {
                com.stonebreak.core.Game game = Game.getInstance();
                em = (game != null) ? game.getEntityManager() : null;
            }
            if (em != null) {
                logger.log(Level.FINE, String.format(
                    "[ENTITY-LOAD] Chunk (%d,%d): Loading %d entities",
                    snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getEntities().size()
                ));
                em.loadEntitiesForChunk(snapshot.getEntities(), snapshot.getChunkX(), snapshot.getChunkZ());
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
     * Gets the list of SBO render data entries for blocks rendered with SBO textures.
     * @return SBO render data list, or null if no SBO blocks in this chunk
     */
    public List<com.openmason.engine.voxel.sbo.SBORenderData> getSBORenderDataList() {
        return sboRenderDataList;
    }

    /**
     * Sets the pending chunk mesh result from the mesh pipeline.
     * This ensures the SBO mesh is available for GPU upload in applyPreparedDataToGL().
     */
    public void setPendingChunkMeshResult(ChunkMeshResult result) {
        this.pendingChunkMeshResult = result;
    }

    public ChunkMeshResult getPendingChunkMeshResult() {
        return pendingChunkMeshResult;
    }

    public void setSBORenderDataList(List<com.openmason.engine.voxel.sbo.SBORenderData> dataList) {
        this.sboRenderDataList = dataList;
    }

    private void closeSBORenderData() {
        if (sboRenderDataList != null) {
            for (com.openmason.engine.voxel.sbo.SBORenderData data : sboRenderDataList) {
                data.close();
            }
            sboRenderDataList = null;
        }
    }

    /**
     * Cleans up CPU-side resources. Safe to call from any thread.
     * NOTE: Block array cleanup removed - blocks must remain accessible for
     * collision detection and neighbor chunk meshing during unload.
     * Memory will be released when the Chunk object itself is garbage collected.
     */
    public void cleanupCpuResources() {
        pendingMmsMeshData = null;
        pendingChunkMeshResult = null;

        // Block array intentionally NOT cleared here - it's needed for:
        // 1. Player collision detection during chunk unload
        // 2. Neighbor chunk meshing (edge blocks must be accessible)
        // 3. Saving dirty chunks (requires block data)
        // Memory will be freed when the entire Chunk object is GC'd
    }

    /**
     * Cleans up GPU resources using MMS API. MUST be called from the main OpenGL thread.
     * Also clears any pending CPU-side mesh data to prevent retention after unload.
     */
    public void cleanupGpuResources() {
        if (renderableHandle != null) {
            renderableHandle.close();
            renderableHandle = null;
        }
        closeSBORenderData();
        meshGenerated = false;

        // Clear CPU-side mesh data that may still be pending upload
        pendingMmsMeshData = null;
        pendingChunkMeshResult = null;
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

    /** Access to the sky-shadow heightmap used by the shadow sampler. */
    public ChunkHeightMap getHeightMap() {
        return heightMap;
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
