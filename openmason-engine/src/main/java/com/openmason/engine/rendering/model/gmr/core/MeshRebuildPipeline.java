package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.RenderMesh;
import com.openmason.engine.rendering.model.gmr.editable.RenderMeshBuilder;
import com.openmason.engine.rendering.model.gmr.mapping.EditableMeshVertexMapper;
import com.openmason.engine.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.engine.rendering.model.gmr.notification.IMeshChangeNotifier;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopologyBuilder;
import com.openmason.engine.rendering.model.gmr.uv.IFaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.IVertexDataManager;
import com.openmason.engine.rendering.model.gmr.geometry.IGeometryDataBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner of the authoritative {@link EditableMesh} and the shared post-mutation
 * rebuild sequence. Every structural or positional edit funnels through
 * {@link #rebuildFromEditable()} / {@link #refreshPositionsFromEditable()}:
 *
 * <p>Sequence: derive render mesh (corners + projected UVs + triangulation) →
 * write GPU-facing caches (vertex manager, face mapper, corner maps) →
 * rebuild topology from authoritative loops → upload GPU buffers →
 * invalidate caches → notify listeners.
 *
 * <p>UVs are produced during derivation ({@link RenderMeshBuilder}), so there
 * is no separate UV-regeneration re-entry and no seam-duplication pass —
 * corners are per-face by construction.
 */
public class MeshRebuildPipeline implements IMeshRebuildPipeline {

    private static final Logger logger = LoggerFactory.getLogger(MeshRebuildPipeline.class);

    private final IVertexDataManager vertexManager;
    private final EditableMeshVertexMapper vertexMapper;
    private final ITriangleFaceMapper faceMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final IGeometryDataBuilder geometryBuilder;
    private final IGPUBufferUploader gpuUploader;

    // UV region/rotation source for render-mesh derivation — set by GMR after
    // the FaceTextureManager is constructed.
    private IFaceTextureManager faceTextureManager;

    // Renderer count callbacks — set by GMR to update its protected fields
    private MeshMutationCoordinator.RendererStateAccess rendererState;

    // Authoritative mesh + latest derivation
    private EditableMesh editableMesh = new EditableMesh();
    private RenderMesh lastRenderMesh;

    // ALL combined-soup vertex indices per editable vertex id, from the last
    // import — the part system's MeshRange index space. Welding crosses part
    // boundaries, so one editable vertex can map into several parts' ranges.
    // Vertices created by ops after the import have no soup indices.
    private int[][] vertexIdToSoupIndices;

    private static final int[] NO_SOUP_INDICES = new int[0];

    // Shared mutable state
    private MeshTopology topology;
    private boolean drawBatchesDirty = true;

    // Cached model bounds — nulled on geometry changes, recomputed lazily by GMR
    private Runnable boundsInvalidator;

    public MeshRebuildPipeline(
            IVertexDataManager vertexManager,
            EditableMeshVertexMapper vertexMapper,
            ITriangleFaceMapper faceMapper,
            IMeshChangeNotifier changeNotifier,
            IGeometryDataBuilder geometryBuilder,
            IGPUBufferUploader gpuUploader) {
        this.vertexManager = vertexManager;
        this.vertexMapper = vertexMapper;
        this.faceMapper = faceMapper;
        this.changeNotifier = changeNotifier;
        this.geometryBuilder = geometryBuilder;
        this.gpuUploader = gpuUploader;
    }

    /** Set the per-face UV mapping source used during render-mesh derivation. */
    public void setFaceTextureManager(IFaceTextureManager faceTextureManager) {
        this.faceTextureManager = faceTextureManager;
    }

    /** Set the renderer count callbacks (vertexCount/indexCount fields on GMR). */
    public void setRendererStateAccess(MeshMutationCoordinator.RendererStateAccess rendererState) {
        this.rendererState = rendererState;
    }

    /**
     * Set the bounds invalidation callback.
     * Called during rebuild to clear cached model bounds.
     */
    public void setBoundsInvalidator(Runnable boundsInvalidator) {
        this.boundsInvalidator = boundsInvalidator;
    }

    // ── Editable mesh ownership ─────────────────────────────────────────────

    /** The authoritative mesh. Never null (empty mesh before any load). */
    public EditableMesh getEditableMesh() {
        return editableMesh;
    }

    /** Replace the authoritative mesh (import/load/snapshot-restore path). */
    public void setEditableMesh(EditableMesh mesh) {
        this.editableMesh = mesh != null ? mesh : new EditableMesh();
        this.vertexIdToSoupIndices = null;
    }

    /** Record the import's vertexId→soup-indices mapping (part index space). */
    public void setImportSoupMapping(int[][] vertexIdToSoupIndices) {
        this.vertexIdToSoupIndices = vertexIdToSoupIndices;
    }

    /** The import's vertexId→soup-indices mapping, or null (snapshot support). */
    public int[][] getImportSoupMapping() {
        return vertexIdToSoupIndices;
    }

    /**
     * ALL combined-soup vertex indices (the part system's {@code MeshRange}
     * index space) welded into the vertex a render corner belongs to. Empty
     * when unknown (no import yet, or the vertex was created by an edit after
     * the import). A shared seam vertex can map into several parts' ranges.
     */
    public int[] cornerToSoupIndices(int cornerIndex) {
        if (lastRenderMesh == null || vertexIdToSoupIndices == null
                || cornerIndex < 0 || cornerIndex >= lastRenderMesh.cornerToVertexId().length) {
            return NO_SOUP_INDICES;
        }
        int vertexId = lastRenderMesh.cornerToVertexId()[cornerIndex];
        return vertexId >= 0 && vertexId < vertexIdToSoupIndices.length
            ? vertexIdToSoupIndices[vertexId] : NO_SOUP_INDICES;
    }

    /** The render mesh from the most recent derivation, or null before it. */
    public RenderMesh getLastRenderMesh() {
        return lastRenderMesh;
    }

    // ── Rebuild entry points ────────────────────────────────────────────────

    @Override
    public void rebuildFromEditable() {
        derive(true);
        logger.trace("Structural rebuild complete: {} corners, {} triangles",
            lastRenderMesh != null ? lastRenderMesh.cornerCount() : 0,
            lastRenderMesh != null ? lastRenderMesh.triangleCount() : 0);
    }

    @Override
    public void refreshPositionsFromEditable() {
        derive(false);
        logger.trace("Position refresh complete");
    }

    /**
     * Shared derivation. {@code structural} controls whether the index buffer
     * is re-uploaded (loop/face changes) or only the VBO (position changes).
     */
    private void derive(boolean structural) {
        invalidateBounds();

        RenderMesh rm = RenderMeshBuilder.build(editableMesh, faceTextureManager);
        this.lastRenderMesh = rm;

        // Write the GPU-facing caches (BaseRenderer, DrawBatchManager, extractors
        // and serialization all keep reading these).
        vertexManager.setData(rm.vertices(), rm.texCoords(), rm.indices());
        if (rm.triangleCount() > 0) {
            faceMapper.setMapping(rm.triangleToFaceId().clone());
        } else {
            faceMapper.clear();
        }
        vertexMapper.update(editableMesh, rm);

        if (rendererState != null) {
            rendererState.setVertexCount(rm.cornerCount());
            rendererState.setIndexCount(rm.indices().length);
        }

        // Topology from authoritative loops — winding is never reconstructed.
        this.topology = MeshTopologyBuilder.build(editableMesh, rm);

        // Upload GPU buffers
        if (gpuUploader.isGPUReady()) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                rm.vertices(), rm.texCoords());
            gpuUploader.uploadVBO(interleavedData);
            if (structural) {
                gpuUploader.uploadEBO(rm.indices());
            }
        }

        drawBatchesDirty = true;

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();
    }

    // ── Shared state ────────────────────────────────────────────────────────

    @Override
    public MeshTopology getTopology() {
        return topology;
    }

    @Override
    public void setTopology(MeshTopology topology) {
        this.topology = topology;
    }

    @Override
    public void markDrawBatchesDirty() {
        drawBatchesDirty = true;
    }

    @Override
    public boolean isDrawBatchesDirty() {
        return drawBatchesDirty;
    }

    /**
     * Clear the dirty flag after batches are rebuilt.
     */
    public void clearDrawBatchesDirty() {
        drawBatchesDirty = false;
    }

    private void invalidateBounds() {
        if (boundsInvalidator != null) {
            boundsInvalidator.run();
        }
    }
}
