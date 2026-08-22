package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.cco.data.CcoChunkState;
import com.openmason.engine.voxel.cco.data.CcoDirtyTracker;
import com.openmason.engine.voxel.cco.state.CcoAtomicStateManager;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.openmason.engine.voxel.sbo.SBORenderData;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns a chunk's mesh lifecycle: the CPU-side pending mesh results, the
 * GPU-side renderable handles (legacy per-chunk VAOs and region-arena
 * segments for atlas / water / stamp geometry), SBO render data, the
 * {@code meshGenerated} flag, and their upload, draw and cleanup. The
 * {@link Chunk} facade delegates all mesh-related public methods here so
 * the chunk itself stays a block container.
 */
final class ChunkMeshLifecycle {

    private static final Logger logger = Logger.getLogger(Chunk.class.getName());

    private static int debugRenderCallCount = 0;
    private static int debugRenderSuccessCount = 0;

    private final int x;
    private final int z;
    private final CcoAtomicStateManager stateManager;
    private final CcoDirtyTracker dirtyTracker;

    // Mesh data and buffers (MMS-based)
    private MmsMeshData pendingMmsMeshData;
    private ChunkMeshResult pendingChunkMeshResult;
    private MmsRenderableHandle renderableHandle;
    // Water geometry lives in its own handle, drawn by the dedicated
    // WaterRenderer after the world's transparent pass (never by render()).
    private MmsRenderableHandle waterRenderableHandle;
    // Region-mode geometry: segments in the shared per-region arenas instead
    // of per-chunk VAOs. Exactly one representation is populated per mesh —
    // region handles when ChunkRegionRenderer is enabled (legacy handles then
    // remain only for the rare mesh that can't join a region).
    private MmsRegionMeshHandle regionAtlasHandle;
    private MmsRegionMeshHandle regionWaterHandle;
    /**
     * Non-quad atlas geometry (SBO stamps, crosses) under a pulled vertex
     * format — drawn in the atlas pass from its own per-vertex mesh. Null
     * when the active format isn't pulled (everything rides the atlas mesh).
     */
    private MmsRenderableHandle stampRenderableHandle;
    private MmsRegionMeshHandle regionStampHandle;
    // Whether the current atlas mesh contains any translucent (ice) geometry —
    // lets the transparent pass skip chunks that would contribute nothing.
    private boolean atlasHasTranslucent;
    private List<SBORenderData> sboRenderDataList;
    private boolean meshGenerated = false;

    ChunkMeshLifecycle(int x, int z, CcoAtomicStateManager stateManager, CcoDirtyTracker dirtyTracker) {
        this.x = x;
        this.z = z;
        this.stateManager = stateManager;
        this.dirtyTracker = dirtyTracker;
    }

    // ===== Build / upload =====

    /**
     * Builds the mesh data for the chunk using MMS API. This is CPU-intensive and can be run on a worker thread.
     */
    void buildAndPrepareMeshData(Chunk chunk, World world) {
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

            pendingChunkMeshResult = MmsAPI.getInstance().generateChunkMesh(chunk);
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
    void applyPreparedDataToGL() {
        if (!stateManager.hasState(CcoChunkState.MESH_CPU_READY)) {
            return; // Data not ready
        }

        try {
            if (pendingMmsMeshData == null || pendingMmsMeshData.isEmpty()) {
                // Empty atlas mesh - clean up existing atlas resources
                if (meshGenerated && renderableHandle != null) {
                    renderableHandle.close();
                    renderableHandle = null;
                    meshGenerated = false;
                }
                if (regionAtlasHandle != null) {
                    regionAtlasHandle.close();
                    regionAtlasHandle = null;
                    meshGenerated = false;
                }
                atlasHasTranslucent = false;
                if (waterRenderableHandle != null) {
                    waterRenderableHandle.close();
                    waterRenderableHandle = null;
                }
                if (regionWaterHandle != null) {
                    regionWaterHandle.close();
                    regionWaterHandle = null;
                }
                // An empty atlas can still carry water geometry (ocean-only
                // chunk) — upload it so this path matches the pipeline path.
                if (pendingChunkMeshResult != null && pendingChunkMeshResult.hasWaterMesh()) {
                    var rr = ChunkRegionRenderer.isEnabled() ? ChunkRegionRenderer.getInstance() : null;
                    if (rr != null) {
                        regionWaterHandle = rr.upload(
                            ChunkRegionRenderer.LAYER_WATER,
                            x, z, pendingChunkMeshResult.waterMesh());
                    }
                    if (regionWaterHandle == null) {
                        waterRenderableHandle = MmsAPI.getInstance().uploadMeshToGPU(pendingChunkMeshResult.waterMesh());
                    }
                    meshGenerated = true;
                }
                // A pulled-format chunk may be stamp-only (e.g. just snow layers).
                uploadStampMesh(pendingChunkMeshResult, ChunkRegionRenderer.isEnabled());
                stateManager.removeState(CcoChunkState.MESH_CPU_READY);
                stateManager.addState(CcoChunkState.BLOCKS_POPULATED);
                return;
            }

            // Upload mesh to GPU — into the shared region arenas when region
            // rendering is enabled, else a legacy per-chunk handle.
            if (meshGenerated && renderableHandle != null) {
                // Clean up old handle before creating new one
                renderableHandle.close();
                renderableHandle = null;
            }
            if (regionAtlasHandle != null) {
                regionAtlasHandle.close();
                regionAtlasHandle = null;
            }

            var regionRenderer = ChunkRegionRenderer.isEnabled() ? ChunkRegionRenderer.getInstance() : null;
            if (regionRenderer != null) {
                regionAtlasHandle = regionRenderer.upload(
                    ChunkRegionRenderer.LAYER_ATLAS,
                    x, z, pendingMmsMeshData);
            }
            if (regionAtlasHandle == null) {
                renderableHandle = MmsAPI.getInstance().uploadMeshToGPU(pendingMmsMeshData);
            }
            atlasHasTranslucent = pendingMmsMeshData.hasTranslucentGeometry();
            meshGenerated = true;
            uploadStampMesh(pendingChunkMeshResult, regionRenderer != null);

            // Upload the water mesh; clear the handle when this rebuild
            // produced no water so drained water can't ghost.
            if (waterRenderableHandle != null) {
                waterRenderableHandle.close();
                waterRenderableHandle = null;
            }
            if (regionWaterHandle != null) {
                regionWaterHandle.close();
                regionWaterHandle = null;
            }
            if (pendingChunkMeshResult != null && pendingChunkMeshResult.hasWaterMesh()) {
                if (regionRenderer != null) {
                    regionWaterHandle = regionRenderer.upload(
                        ChunkRegionRenderer.LAYER_WATER,
                        x, z, pendingChunkMeshResult.waterMesh());
                }
                if (regionWaterHandle == null) {
                    waterRenderableHandle = MmsAPI.getInstance().uploadMeshToGPU(pendingChunkMeshResult.waterMesh());
                }
            }

            // Upload SBO meshes if present (one per block type)
            if (pendingChunkMeshResult != null && pendingChunkMeshResult.hasSBOMesh()) {
                closeSBORenderData();
                sboRenderDataList = new ArrayList<>(pendingChunkMeshResult.sboEntries().size());
                for (ChunkMeshResult.SBOEntry entry : pendingChunkMeshResult.sboEntries()) {
                    MmsRenderableHandle sboHandle = MmsAPI.getInstance().uploadMeshToGPU(entry.meshData());
                    sboRenderDataList.add(new SBORenderData(sboHandle, entry.batches()));
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

    /** Uploads (or clears) the stamp mesh of a pending result. GL thread. */
    private void uploadStampMesh(ChunkMeshResult result, boolean regionMode) {
        if (stampRenderableHandle != null) {
            stampRenderableHandle.close();
            stampRenderableHandle = null;
        }
        if (regionStampHandle != null) {
            regionStampHandle.close();
            regionStampHandle = null;
        }
        if (result == null || !result.hasStampMesh()) {
            return;
        }
        if (regionMode) {
            regionStampHandle = ChunkRegionRenderer.getInstance().upload(ChunkRegionRenderer.LAYER_STAMP, x, z, result.stampMesh());
        }
        if (regionStampHandle == null) {
            stampRenderableHandle = MmsAPI.getInstance().uploadMeshToGPU(result.stampMesh());
        }
        atlasHasTranslucent |= result.stampMesh().hasTranslucentGeometry();
        meshGenerated = true;
    }

    // ===== Draw =====

    /**
     * Renders the chunk using MMS API.
     */
    void render() {
        // Debug: Always log first few chunks
        if (debugRenderCallCount < 5) {
            System.out.println("[Chunk.render] Called for (" + x + "," + z + "): " +
                "renderable=" + stateManager.isRenderable() +
                " meshGen=" + meshGenerated +
                " handle=" + (renderableHandle != null));
            debugRenderCallCount++;
        }

        if (!stateManager.isRenderable() || !meshGenerated) {
            return;
        }
        if (renderableHandle != null) {
            // Debug first few successful renders
            if (debugRenderSuccessCount < 3) {
                System.out.println("[Chunk.render] SUCCESS: Rendering chunk at (" + x + "," + z + ") with " +
                    renderableHandle.getIndexCount() + " indices");
                debugRenderSuccessCount++;
            }
            renderableHandle.render();
        }
        // Legacy stamp geometry draws with the atlas (same shader/pass). Region-
        // resident stamps are drawn by ChunkRegionRenderer's stamp pass instead.
        if (stampRenderableHandle != null) {
            stampRenderableHandle.render();
        }
    }

    /**
     * Renders the chunk's water mesh. Called only by the dedicated water
     * renderer (with the water shader bound) — never part of {@link #render()}.
     */
    void renderWater() {
        if (!stateManager.isRenderable() || !meshGenerated || waterRenderableHandle == null) {
            return;
        }
        waterRenderableHandle.render();
    }

    // ===== Cleanup =====

    private void closeSBORenderData() {
        if (sboRenderDataList != null) {
            for (SBORenderData data : sboRenderDataList) {
                data.close();
            }
            sboRenderDataList = null;
        }
    }

    /** Drops pending CPU-side mesh data. Safe to call from any thread. */
    void cleanupCpuResources() {
        pendingMmsMeshData = null;
        pendingChunkMeshResult = null;
    }

    /**
     * Cleans up GPU resources using MMS API. MUST be called from the main OpenGL thread.
     * Also clears any pending CPU-side mesh data to prevent retention after unload.
     */
    void cleanupGpuResources() {
        if (renderableHandle != null) {
            renderableHandle.close();
            renderableHandle = null;
        }
        if (waterRenderableHandle != null) {
            waterRenderableHandle.close();
            waterRenderableHandle = null;
        }
        if (regionAtlasHandle != null) {
            regionAtlasHandle.close();
            regionAtlasHandle = null;
        }
        if (regionWaterHandle != null) {
            regionWaterHandle.close();
            regionWaterHandle = null;
        }
        if (stampRenderableHandle != null) {
            stampRenderableHandle.close();
            stampRenderableHandle = null;
        }
        if (regionStampHandle != null) {
            regionStampHandle.close();
            regionStampHandle = null;
        }
        atlasHasTranslucent = false;
        closeSBORenderData();
        meshGenerated = false;

        // Clear CPU-side mesh data that may still be pending upload
        pendingMmsMeshData = null;
        pendingChunkMeshResult = null;
    }

    // ===== State / handle accessors (mirrored by the Chunk facade) =====

    boolean isMeshGenerated() {
        return meshGenerated;
    }

    boolean hasWaterMesh() {
        return waterRenderableHandle != null || regionWaterHandle != null;
    }

    boolean atlasHasTranslucent() {
        return atlasHasTranslucent;
    }

    void setAtlasHasTranslucent(boolean hasTranslucent) {
        this.atlasHasTranslucent = hasTranslucent;
    }

    ChunkMeshResult getPendingChunkMeshResult() {
        return pendingChunkMeshResult;
    }

    void setPendingChunkMeshResult(ChunkMeshResult result) {
        this.pendingChunkMeshResult = result;
    }

    List<SBORenderData> getSBORenderDataList() {
        return sboRenderDataList;
    }

    void setSBORenderDataList(List<SBORenderData> dataList) {
        this.sboRenderDataList = dataList;
    }

    MmsRenderableHandle getMmsRenderableHandle() {
        return renderableHandle;
    }

    void setMmsRenderableHandle(MmsRenderableHandle handle) {
        this.renderableHandle = handle;
        if (handle != null) {
            this.meshGenerated = true;
        }
    }

    MmsRenderableHandle getWaterRenderableHandle() {
        return waterRenderableHandle;
    }

    void setWaterRenderableHandle(MmsRenderableHandle handle) {
        this.waterRenderableHandle = handle;
        if (handle != null) {
            // A water-only chunk (empty atlas mesh) must still pass the
            // renderWater() meshGenerated gate.
            this.meshGenerated = true;
        }
    }

    MmsRenderableHandle getStampRenderableHandle() {
        return stampRenderableHandle;
    }

    void setStampRenderableHandle(MmsRenderableHandle handle) {
        this.stampRenderableHandle = handle;
        if (handle != null) {
            this.meshGenerated = true;
        }
    }

    MmsRegionMeshHandle getRegionAtlasHandle() {
        return regionAtlasHandle;
    }

    void setRegionAtlasHandle(MmsRegionMeshHandle handle) {
        this.regionAtlasHandle = handle;
        if (handle != null) {
            this.meshGenerated = true;
        }
    }

    MmsRegionMeshHandle getRegionWaterHandle() {
        return regionWaterHandle;
    }

    void setRegionWaterHandle(MmsRegionMeshHandle handle) {
        this.regionWaterHandle = handle;
        if (handle != null) {
            this.meshGenerated = true;
        }
    }

    MmsRegionMeshHandle getRegionStampHandle() {
        return regionStampHandle;
    }

    void setRegionStampHandle(MmsRegionMeshHandle handle) {
        this.regionStampHandle = handle;
        if (handle != null) {
            this.meshGenerated = true;
        }
    }
}
