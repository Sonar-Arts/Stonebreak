package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.cco.state.CcoAtomicStateManager;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.openmason.engine.voxel.sbo.SBORenderData;

import java.util.List;

/**
 * Owns a chunk's mesh state: the GPU-side renderable handles (legacy per-chunk VAOs and region-arena segments for
 * atlas / water / stamp geometry), SBO render data, the {@code meshGenerated}
 * and retired flags, and their draw and cleanup. Building and uploading are
 * driven by {@code MmsMeshPipeline} through the setters below; the pipeline's
 * upload task, not the chunk, owns the CPU-side mesh data in between. The {@link Chunk} facade
 * delegates all mesh-related public methods here so the chunk itself stays a
 * block container.
 */
final class ChunkMeshLifecycle {

    private final CcoAtomicStateManager stateManager;

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
    // Set once on unload (under the chunk lock); the mesh pipeline then refuses
    // to schedule, publish or upload for this chunk. Never cleared: a reloaded
    // chunk is a new Chunk instance.
    private volatile boolean meshRetired = false;

    ChunkMeshLifecycle(CcoAtomicStateManager stateManager) {
        this.stateManager = stateManager;
    }

    // ===== Draw =====

    /**
     * Renders the chunk's legacy (non-region) atlas and stamp handles.
     */
    void render() {
        if (!stateManager.isRenderable() || !meshGenerated) {
            return;
        }
        if (renderableHandle != null) {
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

    /**
     * Closes all GPU resources. MUST be called from the main OpenGL thread.
     */
    void cleanupGpuResources() {
        renderableHandle = close(renderableHandle);
        waterRenderableHandle = close(waterRenderableHandle);
        stampRenderableHandle = close(stampRenderableHandle);
        regionAtlasHandle = close(regionAtlasHandle);
        regionWaterHandle = close(regionWaterHandle);
        regionStampHandle = close(regionStampHandle);
        if (sboRenderDataList != null) {
            for (SBORenderData data : sboRenderDataList) {
                data.close();
            }
            sboRenderDataList = null;
        }
        atlasHasTranslucent = false;
        meshGenerated = false;
    }

    /** Closes {@code handle} if non-null; always returns null for field reassignment. */
    private static <T extends AutoCloseable> T close(T handle) {
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close mesh handle", e);
            }
        }
        return null;
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

    boolean isMeshRetired() {
        return meshRetired;
    }

    void retireMesh() {
        this.meshRetired = true;
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
