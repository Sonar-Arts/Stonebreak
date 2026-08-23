package com.stonebreak.world.chunk;

import com.stonebreak.blocks.BlockType;
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
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.lighting.ChunkHeightMap;
import com.openmason.engine.voxel.lighting.ColumnOpacityProbe;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.lighting.WorldLightingContext;


import java.util.List;

/**
 * Represents a chunk of the world using the CCO (Common Chunk Operations) API.
 * This is a complete rewrite using CCO components for:
 * - Unified block operations with automatic dirty tracking
 * - Lock-free state management
 * - Optimized GPU buffer operations
 * - Built-in serialization support
 */
public class Chunk {

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

    // Mesh lifecycle + GPU upload (see ChunkMeshLifecycle)
    private final ChunkMeshLifecycle mesh;

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
     * Per-chunk water flow state (single source of truth). Holds entries only
     * for non-source water cells; a WATER block with no entry is a source.
     * See {@link ChunkWaterLayer} for the full invariant.
     */
    private final ChunkWaterLayer waterLayer = new ChunkWaterLayer();

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
        this.mesh = new ChunkMeshLifecycle(x, z, stateManager, dirtyTracker);
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
            // Water layer invariant: only WATER cells may carry a flow entry.
            // Newly-set WATER keeps whatever entry the writer manages (absence
            // = source); anything else must drop stale flow state here so every
            // write path — player, sim, network, worldgen — stays consistent.
            if (blockType != BlockType.WATER) {
                waterLayer.remove(x, y, z);
            }
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

    /** Per-chunk water flow state. Writers: sim, network apply, save hydration. */
    public ChunkWaterLayer getWaterLayer() {
        return waterLayer;
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

    /**
     * Replaces this chunk's entire block contents with a copy of the given
     * storage (section-level palette copy, near-free) and marks the chunk
     * dirty for remesh. Used by the network chunk-install path; the caller
     * must recompute the heightmap afterwards.
     */
    public void replaceAllBlocks(CcoBlockStorage source) {
        blocks.copyFrom(source);
        dirtyTracker.markBlockChanged();
    }

    // ===== Mesh Operations (delegated to ChunkMeshLifecycle) =====

    /**
     * Builds the mesh data for this chunk using MMS API. This is CPU-intensive and can be run on a worker thread.
     */
    public void buildAndPrepareMeshData(World world) {
        mesh.buildAndPrepareMeshData(this, world);
    }

    /**
     * Applies the prepared mesh data to OpenGL using MMS API. This must be called on the main GL thread.
     */
    public void applyPreparedDataToGL() {
        mesh.applyPreparedDataToGL();
    }

    /**
     * Renders the chunk using MMS API.
     */
    public void render() {
        mesh.render();
    }

    /** Region-mode stamp geometry handle, or null. */
    public com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle getRegionStampHandle() {
        return mesh.getRegionStampHandle();
    }

    public void setRegionStampHandle(com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle handle) {
        mesh.setRegionStampHandle(handle);
    }

    /** Legacy per-chunk stamp geometry handle, or null. */
    public MmsRenderableHandle getStampRenderableHandle() {
        return mesh.getStampRenderableHandle();
    }

    public void setStampRenderableHandle(MmsRenderableHandle handle) {
        mesh.setStampRenderableHandle(handle);
    }

    /** Whether this chunk currently has uploaded water geometry. */
    public boolean hasWaterMesh() {
        return mesh.hasWaterMesh();
    }

    /** Whether the current atlas mesh contains any translucent (ice) geometry. */
    public boolean atlasHasTranslucent() {
        return mesh.atlasHasTranslucent();
    }

    public void setAtlasHasTranslucent(boolean hasTranslucent) {
        mesh.setAtlasHasTranslucent(hasTranslucent);
    }

    /** Region-mode atlas geometry handle, or null (legacy mode / no geometry). */
    public com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle getRegionAtlasHandle() {
        return mesh.getRegionAtlasHandle();
    }

    public void setRegionAtlasHandle(com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle handle) {
        mesh.setRegionAtlasHandle(handle);
    }

    /** Region-mode water geometry handle, or null (legacy mode / no water). */
    public com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle getRegionWaterHandle() {
        return mesh.getRegionWaterHandle();
    }

    public void setRegionWaterHandle(com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle handle) {
        mesh.setRegionWaterHandle(handle);
    }

    /**
     * Renders the chunk's water mesh. Called only by the dedicated water
     * renderer (with the water shader bound) — never part of {@link #render()}.
     */
    public void renderWater() {
        mesh.renderWater();
    }

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
        return mesh.isMeshGenerated();
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

    // ===== Serialization (CCO-based; payload conversion in ChunkSaveCodec) =====

    /**
     * Creates a serializable snapshot of this chunk using CCO API.
     * Extracts water metadata from the chunk's water layer and entities from EntityManager.
     *
     * CRITICAL: Creates an ATOMIC snapshot by copying the block storage immediately.
     * This prevents race conditions where the chunk is modified after the snapshot is created
     * but before it's serialized. The copy is O(1) per section: paletted sections share
     * state copy-on-write, so nothing is cloned unless the live chunk mutates afterwards.
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

        var waterMetadata = ChunkSaveCodec.collectWaterMetadata(waterLayer, blocksCopy);
        var entities = ChunkSaveCodec.collectEntities(world, x, z);
        var snowLayers = ChunkSaveCodec.collectSnowLayers(world, x, z);

        // Create snapshot with copied block storage, water metadata, entities,
        // entity generation flag, per-block SBO state map, and snow layers.
        return new CcoSerializableSnapshot(
            metadata.getChunkX(),
            metadata.getChunkZ(),
            blocksCopy,
            metadata.getLastModified(),
            metadata.isFeaturesPopulated(),
            metadata.hasEntities(),
            waterMetadata,
            entities,
            new java.util.HashMap<>(blockStates),
            snowLayers
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

        ChunkSaveCodec.restoreSnowLayers(snapshot, world);

        // Hydrate this chunk's water layer BEFORE the chunk-load listener runs
        // (the sim's load scan schedules — never overwrites — existing flow state).
        ChunkSaveCodec.restoreWaterLayer(snapshot, waterLayer);

        ChunkSaveCodec.restoreEntities(snapshot, world);

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
        return mesh.getSBORenderDataList();
    }

    /**
     * Sets the pending chunk mesh result from the mesh pipeline.
     * This ensures the SBO mesh is available for GPU upload in applyPreparedDataToGL().
     */
    public void setPendingChunkMeshResult(ChunkMeshResult result) {
        mesh.setPendingChunkMeshResult(result);
    }

    public ChunkMeshResult getPendingChunkMeshResult() {
        return mesh.getPendingChunkMeshResult();
    }

    public void setSBORenderDataList(List<com.openmason.engine.voxel.sbo.SBORenderData> dataList) {
        mesh.setSBORenderDataList(dataList);
    }

    /**
     * Cleans up CPU-side resources. Safe to call from any thread.
     * NOTE: Block array cleanup removed - blocks must remain accessible for
     * collision detection and neighbor chunk meshing during unload.
     * Memory will be released when the Chunk object itself is garbage collected.
     */
    public void cleanupCpuResources() {
        mesh.cleanupCpuResources();

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
        mesh.cleanupGpuResources();
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
     * Raw block storage, exposed for bulk snapshotting (native mesh kernels,
     * codecs). Treat as read-only — writes must go through setBlock so dirty
     * tracking and the heightmap stay coherent.
     */
    public CcoBlockStorage getBlockStorageView() {
        return blocks;
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
        return mesh.getMmsRenderableHandle();
    }

    /**
     * Sets the MMS renderable handle for this chunk.
     * Used by MmsMeshPipeline after GPU upload.
     *
     * @param handle Renderable handle
     */
    public void setMmsRenderableHandle(MmsRenderableHandle handle) {
        mesh.setMmsRenderableHandle(handle);
    }

    /**
     * Gets the water mesh handle, or null when the chunk holds no water
     * geometry. Managed by MmsMeshPipeline alongside the atlas handle.
     */
    public MmsRenderableHandle getWaterRenderableHandle() {
        return mesh.getWaterRenderableHandle();
    }

    /**
     * Sets the water mesh handle (null when a rebuild produced no water —
     * mandatory so drained water doesn't ghost with a stale handle).
     */
    public void setWaterRenderableHandle(MmsRenderableHandle handle) {
        mesh.setWaterRenderableHandle(handle);
    }
}
