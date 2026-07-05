package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.RenderMesh;
import com.openmason.engine.rendering.model.gmr.editable.ops.CreateFaceOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.DeleteFaceOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.ExtrudeFacesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.InsetFacesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.MergeVerticesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.ScaleFacesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.SplitFaceOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.SubdivideEdgeOp;
import com.openmason.engine.rendering.model.gmr.geometry.IGeometryDataBuilder;
import com.openmason.engine.rendering.model.gmr.mapping.IUniqueVertexMapper;
import com.openmason.engine.rendering.model.gmr.notification.IMeshChangeNotifier;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.IVertexDataManager;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates all mesh structural mutation operations over the authoritative
 * {@link EditableMesh}: validate → apply op → rebuild pipeline → notify.
 *
 * <p>"Unique vertex index" parameters are editable vertex ids; "mesh vertex
 * index" parameters are render corner indices (see {@link RenderMesh}).
 */
public class MeshMutationCoordinator implements IMeshMutationCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(MeshMutationCoordinator.class);

    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final IGeometryDataBuilder geometryBuilder;
    private final FaceTextureManager faceTextureManager;
    private final MeshRebuildPipeline rebuildPipeline;
    private final IGPUBufferUploader gpuUploader;

    // Callback to read/write vertexCount on the renderer
    private final RendererStateAccess rendererState;

    /**
     * Callback interface for accessing mutable renderer state.
     * GMR implements this to expose its protected BaseRenderer fields.
     */
    public interface RendererStateAccess {
        int getVertexCount();
        void setVertexCount(int count);
        int getIndexCount();
        void setIndexCount(int count);
        boolean isInitialized();
    }

    public MeshMutationCoordinator(
            IVertexDataManager vertexManager,
            IUniqueVertexMapper uniqueMapper,
            IMeshChangeNotifier changeNotifier,
            IGeometryDataBuilder geometryBuilder,
            FaceTextureManager faceTextureManager,
            MeshRebuildPipeline rebuildPipeline,
            IGPUBufferUploader gpuUploader,
            RendererStateAccess rendererState) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.changeNotifier = changeNotifier;
        this.geometryBuilder = geometryBuilder;
        this.faceTextureManager = faceTextureManager;
        this.rebuildPipeline = rebuildPipeline;
        this.gpuUploader = gpuUploader;
        this.rendererState = rendererState;
    }

    private EditableMesh mesh() {
        return rebuildPipeline.getEditableMesh();
    }

    // ── Vertex moves ────────────────────────────────────────────────────────

    /**
     * Move one vertex (identified by any of its render corners). Light path
     * for interactive drags: updates the editable position and every derived
     * corner copy, re-uploads the VBO, and notifies — topology/normal/UV
     * re-derivation is deferred to the next structural rebuild, exactly as
     * the legacy path deferred it.
     */
    @Override
    public void updateVertexPosition(int globalIndex, Vector3f position) {
        int vertexId = uniqueMapper.getUniqueIndexForMeshVertex(globalIndex);
        if (vertexId < 0) {
            logger.warn("Vertex corner index {} has no mapped vertex", globalIndex);
            return;
        }

        mesh().setPosition(vertexId, position);

        int[] corners = uniqueMapper.getMeshIndicesForUniqueVertex(vertexId);
        vertexManager.updateVertexPositions(corners, position);

        if (gpuUploader.isGPUReady()) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertexManager.getVertices(), vertexManager.getTexCoords());
            gpuUploader.uploadVBO(interleavedData);
        }

        if (changeNotifier.getListenerCount() > 0) {
            changeNotifier.notifyVertexPositionChanged(vertexId, position, corners);
        }

        logger.trace("Moved vertex {} ({} corners) to ({}, {}, {})",
            vertexId, corners.length, position.x, position.y, position.z);
    }

    /**
     * Bulk position update. Input is corner-parallel: triple {@code i} is the
     * new position for the vertex currently rendered at corner {@code i}.
     * Arrays captured BEFORE a structural change are stale (corner indices
     * shift) — re-fetch after any topology mutation.
     */
    @Override
    public void updateVertexPositions(float[] positions) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }
        RenderMesh rm = rebuildPipeline.getLastRenderMesh();
        if (positions == null || rm == null) {
            return;
        }
        if (positions.length / 3 != rm.cornerCount()) {
            logger.warn("Bulk position array has {} vertices but the mesh has {} corners"
                + " — likely a stale pre-mutation capture; applying the common prefix",
                positions.length / 3, rm.cornerCount());
        }

        EditableMesh mesh = mesh();
        int inputVertexCount = Math.min(positions.length / 3, rm.cornerCount());

        // Input is corner-parallel: triple i is the new position for the
        // vertex currently rendered at corner i (a shorter array updates a
        // prefix — legacy compatibility). The corner map makes the vertex
        // resolution exact; no position matching needed.
        Vector3f newPos = new Vector3f();
        for (int i = 0; i < inputVertexCount; i++) {
            int vertexId = rm.cornerToVertexId()[i];
            newPos.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            mesh.setPosition(vertexId, newPos);
        }

        rebuildPipeline.refreshPositionsFromEditable();
        logger.trace("Bulk-updated vertex positions ({} input vertices)", inputVertexCount);
    }

    // ── Topology mutations ──────────────────────────────────────────────────

    /**
     * @deprecated Position-based API kept for the tool's midpoint-subdivide
     *             path; resolve ids and call
     *             {@link #subdivideEdgeAtParameter(int, int, float)} instead.
     * @return the first render corner index of the new vertex, or -1
     */
    @Override
    @Deprecated
    public int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2) {
        if (!rendererState.isInitialized() || midpointPosition == null
                || endpoint1 == null || endpoint2 == null) {
            logger.warn("Cannot apply subdivision: invalid parameters");
            return -1;
        }

        int vA = findVertexAt(endpoint1);
        int vB = findVertexAt(endpoint2);
        if (vA < 0 || vB < 0 || vA == vB) {
            logger.warn("Subdivision endpoints do not match mesh vertices: {} / {}", endpoint1, endpoint2);
            return -1;
        }

        // Parameter from the midpoint's projection onto A→B.
        Vector3f a = mesh().position(vA);
        Vector3f ab = mesh().position(vB).sub(a);
        float lenSq = ab.lengthSquared();
        float t = lenSq > GMRConstants.POSITION_EPSILON * GMRConstants.POSITION_EPSILON
            ? new Vector3f(midpointPosition).sub(a).dot(ab) / lenSq
            : 0.5f;

        int newVertexId = subdivideEdgeAtParameter(vA, vB, t);
        if (newVertexId < 0) {
            return -1;
        }
        int[] corners = uniqueMapper.getMeshIndicesForUniqueVertex(newVertexId);
        return corners.length > 0 ? corners[0] : -1;
    }

    @Override
    public int subdivideEdgeAtParameter(int uniqueVertexA, int uniqueVertexB, float t) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot apply parameterized subdivision: renderer not initialized");
            return -1;
        }

        SubdivideEdgeOp.Result result = SubdivideEdgeOp.apply(mesh(), uniqueVertexA, uniqueVertexB, t);
        if (result == null) {
            logger.warn("Subdivision failed: ({}, {}) is not an edge", uniqueVertexA, uniqueVertexB);
            return -1;
        }

        rebuildPipeline.rebuildFromEditable();

        logger.debug("Subdivided edge ({}, {}) at t={} -> new vertex {} across {} faces",
            uniqueVertexA, uniqueVertexB, t, result.newVertexId(), result.affectedFaceIds().length);
        return result.newVertexId();
    }

    @Override
    public boolean insertEdgeBetweenVertices(int uniqueVertexA, int uniqueVertexB) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot insert edge: renderer not initialized");
            return false;
        }

        List<SplitFaceOp.Split> splits = SplitFaceOp.apply(mesh(), uniqueVertexA, uniqueVertexB);
        if (splits.isEmpty()) {
            logger.warn("Edge insertion failed: vertices {} and {} share no splittable face",
                uniqueVertexA, uniqueVertexB);
            return false;
        }

        for (SplitFaceOp.Split split : splits) {
            faceTextureManager.propagateSplitUV(
                split.parentFaceId(), split.parentFaceId(), split.newFaceId(),
                split.uvT(), split.uvHorizontal());
        }

        rebuildPipeline.rebuildFromEditable();

        logger.debug("Inserted edge between vertices {} and {}: {} face(s) split",
            uniqueVertexA, uniqueVertexB, splits.size());
        return true;
    }

    @Override
    public boolean deleteFace(int faceId) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot delete face: renderer not initialized");
            return false;
        }

        if (!DeleteFaceOp.apply(mesh(), faceId)) {
            logger.warn("Face deletion failed: no face {}", faceId);
            return false;
        }
        faceTextureManager.removeFaceMapping(faceId);

        rebuildPipeline.rebuildFromEditable();

        logger.debug("Deleted face {}", faceId);
        return true;
    }

    @Override
    public boolean createFaceFromVertices(int[] selectedUniqueVertices) {
        return createFaceFromVertices(selectedUniqueVertices, MaterialDefinition.DEFAULT.materialId());
    }

    @Override
    public boolean createFaceFromVertices(int[] selectedUniqueVertices, int activeMaterialId) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot create face: renderer not initialized");
            return false;
        }

        int faceId = CreateFaceOp.apply(mesh(), selectedUniqueVertices);
        if (faceId < 0) {
            logger.warn("Face creation failed: invalid vertex selection");
            return false;
        }
        faceTextureManager.assignDefaultMapping(faceId, activeMaterialId);

        rebuildPipeline.rebuildFromEditable();

        logger.debug("Created face {} from {} vertices", faceId, selectedUniqueVertices.length);
        return true;
    }

    /**
     * Topologically merge vertices into {@code keepVertexId} (the tool's
     * drag-vertex-onto-vertex commit). Faces that degenerate are removed and
     * their texture mappings dropped. Positions are the caller's business —
     * move the vertices together first.
     */
    public boolean mergeVertices(int keepVertexId, int[] mergedVertexIds) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot merge vertices: renderer not initialized");
            return false;
        }
        MergeVerticesOp.Result result = MergeVerticesOp.apply(mesh(), keepVertexId, mergedVertexIds);
        if (result == null) {
            logger.warn("Vertex merge failed: nothing to merge into {}", keepVertexId);
            return false;
        }
        for (int droppedFaceId : result.droppedFaceIds()) {
            faceTextureManager.removeFaceMapping(droppedFaceId);
        }
        rebuildPipeline.rebuildFromEditable();
        logger.debug("Merged {} vertices into {}: {} faces rewritten, {} dropped",
            mergedVertexIds.length, keepVertexId,
            result.rewrittenFaceIds().length, result.droppedFaceIds().length);
        return true;
    }

    // ── Face shaping ops ────────────────────────────────────────────────────

    /**
     * Scale the selected faces' vertices about a pivot (default: area-weighted
     * centroid of the selection). Position-only edit.
     */
    public boolean scaleFaces(int[] faceIds, float factor, Vector3f pivotOrNull) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot scale faces: renderer not initialized");
            return false;
        }
        ScaleFacesOp.Result result = ScaleFacesOp.apply(mesh(), faceIds, factor, pivotOrNull);
        if (result == null) {
            logger.warn("Scale faces failed: no valid faces in selection");
            return false;
        }
        rebuildPipeline.refreshPositionsFromEditable();
        logger.debug("Scaled {} faces by {} ({} vertices moved)",
            faceIds.length, factor, result.movedVertexIds().length);
        return true;
    }

    /**
     * Inset each selected face individually. Border quads inherit the source
     * face's material with a default full-region mapping.
     *
     * @return new border-quad face ids across all insets, or null on failure
     */
    public int[] insetFaces(int[] faceIds, float amount) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot inset faces: renderer not initialized");
            return null;
        }
        List<InsetFacesOp.FaceResult> results = InsetFacesOp.apply(mesh(), faceIds, amount);
        if (results.isEmpty()) {
            logger.warn("Inset failed: no valid faces (amount={})", amount);
            return null;
        }

        List<Integer> newFaces = new ArrayList<>();
        for (InsetFacesOp.FaceResult result : results) {
            int materialId = sourceMaterial(result.faceId());
            for (int borderId : result.borderFaceIds()) {
                faceTextureManager.assignDefaultMapping(borderId, materialId);
                newFaces.add(borderId);
            }
        }

        rebuildPipeline.rebuildFromEditable();
        logger.debug("Inset {} faces (amount={}): {} border quads", results.size(), amount, newFaces.size());
        return toIntArray(newFaces);
    }

    /**
     * Extrude each selected face individually along its normal. Side quads
     * inherit the source face's material with a default full-region mapping.
     *
     * @return new side-quad face ids across all extrusions, or null on failure
     */
    public int[] extrudeFaces(int[] faceIds, float offset) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot extrude faces: renderer not initialized");
            return null;
        }
        List<ExtrudeFacesOp.FaceResult> results = ExtrudeFacesOp.apply(mesh(), faceIds, offset);
        if (results.isEmpty()) {
            logger.warn("Extrude failed: no valid faces (offset={})", offset);
            return null;
        }

        List<Integer> newFaces = new ArrayList<>();
        for (ExtrudeFacesOp.FaceResult result : results) {
            int materialId = sourceMaterial(result.faceId());
            for (int sideId : result.sideFaceIds()) {
                faceTextureManager.assignDefaultMapping(sideId, materialId);
                newFaces.add(sideId);
            }
        }

        rebuildPipeline.rebuildFromEditable();
        logger.debug("Extruded {} faces (offset={}): {} side quads", results.size(), offset, newFaces.size());
        return toIntArray(newFaces);
    }

    private int sourceMaterial(int faceId) {
        var mapping = faceTextureManager.getFaceMapping(faceId);
        return mapping != null ? mapping.materialId() : MaterialDefinition.DEFAULT.materialId();
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /** Editable vertex whose position matches within {@link GMRConstants#POSITION_MATCH_EPSILON}, or -1. */
    private int findVertexAt(Vector3f position) {
        EditableMesh mesh = mesh();
        float epsSq = GMRConstants.POSITION_MATCH_EPSILON * GMRConstants.POSITION_MATCH_EPSILON;
        Vector3f p = new Vector3f();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.position(v, p);
            if (p.distanceSquared(position) < epsSq) {
                return v;
            }
        }
        return -1;
    }
}
