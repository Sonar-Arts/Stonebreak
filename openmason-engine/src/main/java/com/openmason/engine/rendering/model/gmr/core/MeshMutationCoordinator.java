package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.gmr.geometry.IGeometryDataBuilder;
import com.openmason.engine.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.engine.rendering.model.gmr.mapping.IUniqueVertexMapper;
import com.openmason.engine.rendering.model.gmr.notification.IMeshChangeNotifier;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;
import com.openmason.engine.rendering.model.gmr.uv.*;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Coordinates all mesh structural mutation operations.
 * Each mutation delegates to the appropriate processor, applies results
 * to shared state, then triggers the rebuild pipeline.
 *
 * Extracted from GenericModelRenderer to satisfy Single Responsibility.
 */
public class MeshMutationCoordinator implements IMeshMutationCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(MeshMutationCoordinator.class);

    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final ITriangleFaceMapper faceMapper;
    private final ISubdivisionProcessor subdivisionProcessor;
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
            ITriangleFaceMapper faceMapper,
            ISubdivisionProcessor subdivisionProcessor,
            IMeshChangeNotifier changeNotifier,
            IGeometryDataBuilder geometryBuilder,
            FaceTextureManager faceTextureManager,
            MeshRebuildPipeline rebuildPipeline,
            IGPUBufferUploader gpuUploader,
            RendererStateAccess rendererState) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.faceMapper = faceMapper;
        this.subdivisionProcessor = subdivisionProcessor;
        this.changeNotifier = changeNotifier;
        this.geometryBuilder = geometryBuilder;
        this.faceTextureManager = faceTextureManager;
        this.rebuildPipeline = rebuildPipeline;
        this.gpuUploader = gpuUploader;
        this.rendererState = rendererState;
    }

    @Override
    public void updateVertexPosition(int globalIndex, Vector3f position) {
        float[] vertices = vertexManager.getVertices();
        if (vertices == null || globalIndex < 0) {
            return;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= vertices.length) {
            logger.warn("Vertex index {} out of bounds", globalIndex);
            return;
        }

        // Get unique index and affected mesh indices
        int uniqueIndex = uniqueMapper.getUniqueIndexForMeshVertex(globalIndex);
        int[] affectedMeshIndices;

        if (uniqueIndex >= 0) {
            affectedMeshIndices = uniqueMapper.getMeshIndicesForUniqueVertex(uniqueIndex);
        } else {
            Vector3f oldPosition = new Vector3f(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
            List<Integer> verticesList = vertexManager.findMeshVerticesAtPosition(oldPosition, 0.001f);
            affectedMeshIndices = verticesList.stream().mapToInt(Integer::intValue).toArray();
        }

        // Update ALL vertices at this position
        vertexManager.updateVertexPositions(affectedMeshIndices, position);

        // Rebuild and upload to GPU
        float[] interleavedData = geometryBuilder.buildInterleavedData(
            vertexManager.getVertices(), vertexManager.getTexCoords());
        gpuUploader.uploadVBO(interleavedData);

        // Notify listeners
        if (uniqueIndex >= 0 && changeNotifier.getListenerCount() > 0) {
            changeNotifier.notifyVertexPositionChanged(uniqueIndex, position, affectedMeshIndices);
        }

        logger.trace("Updated {} mesh vertices for unique vertex {} to ({}, {}, {})",
            affectedMeshIndices.length, uniqueIndex, position.x, position.y, position.z);
    }

    @Override
    public void updateVertexPositions(float[] positions) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }

        float[] currentVertices = vertexManager.getVertices();
        if (positions == null || currentVertices == null) {
            return;
        }

        int expectedLength = currentVertices.length;
        int updateLength = Math.min(positions.length, expectedLength);
        int inputVertexCount = positions.length / 3;
        int meshVertexCount = currentVertices.length / 3;

        try {
            if (inputVertexCount < meshVertexCount) {
                // Post-subdivision: update by position matching
                for (int i = 0; i < inputVertexCount; i++) {
                    int off = i * 3;
                    Vector3f oldPos = new Vector3f(currentVertices[off], currentVertices[off + 1], currentVertices[off + 2]);
                    Vector3f newPos = new Vector3f(positions[off], positions[off + 1], positions[off + 2]);

                    if (oldPos.equals(newPos, 0.0001f)) {
                        continue;
                    }

                    List<Integer> verticesAtPos = vertexManager.findMeshVerticesAtPosition(oldPos, 0.001f);
                    vertexManager.updateVertexPositions(
                        verticesAtPos.stream().mapToInt(Integer::intValue).toArray(), newPos);
                }
            } else {
                // Direct copy
                float[] vertices = vertexManager.getVertices();
                System.arraycopy(positions, 0, vertices, 0, updateLength);
            }

            rebuildPipeline.rebuildPositionsOnly();

            logger.trace("Updated {} of {} vertex positions", updateLength / 3, currentVertices.length / 3);
        } catch (Exception e) {
            logger.error("Error updating vertex positions", e);
        }
    }

    @Override
    public int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2) {
        if (!rendererState.isInitialized() || midpointPosition == null || endpoint1 == null || endpoint2 == null) {
            logger.warn("Cannot apply subdivision: invalid parameters");
            return -1;
        }

        // Log first 8 mesh vertex positions for debugging
        float[] vertices = vertexManager.getVertices();
        if (vertices != null && vertices.length >= 24 && logger.isTraceEnabled()) {
            logger.trace("MeshMutationCoordinator first 8 vertices:");
            for (int i = 0; i < 8 && i * 3 + 2 < vertices.length; i++) {
                logger.trace("  v{}: ({}, {}, {})", i, vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2]);
            }
        }

        // Apply subdivision via processor
        ISubdivisionProcessor.SubdivisionResult result = subdivisionProcessor.applyEdgeSubdivision(
            midpointPosition, endpoint1, endpoint2, vertexManager, faceMapper, rendererState.getVertexCount());

        if (!result.success()) {
            logger.warn("Subdivision failed: {}", result.errorMessage());
            return -1;
        }

        // Apply result to shared state
        applyTopologyChangeResult(result.newIndices(), result.newTriangleToFaceId(),
            rendererState.getVertexCount() + result.newVertexCount());

        logger.debug("Applied subdivision: added {} vertices (first: {}), indices {} -> {}, unique: {}",
            result.newVertexCount(), result.firstNewVertexIndex(),
            (result.newIndices().length - result.newVertexCount() * 6) / 3,
            rendererState.getIndexCount(), uniqueMapper.getUniqueVertexCount());

        return result.firstNewVertexIndex();
    }

    @Override
    public int subdivideEdgeAtParameter(int uniqueVertexA, int uniqueVertexB, float t) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot apply parameterized subdivision: renderer not initialized");
            return -1;
        }

        ISubdivisionProcessor.SubdivisionResult result = subdivisionProcessor.applyEdgeSubdivisionAtParameter(
            uniqueVertexA, uniqueVertexB, t, vertexManager, faceMapper, uniqueMapper, rendererState.getVertexCount());

        if (!result.success()) {
            logger.warn("Parameterized subdivision failed: {}", result.errorMessage());
            return -1;
        }

        // Apply result to shared state
        applyTopologyChangeResult(result.newIndices(), result.newTriangleToFaceId(),
            rendererState.getVertexCount() + result.newVertexCount());

        // Find unique index of the new vertex
        int newUniqueIndex = uniqueMapper.getUniqueIndexForMeshVertex(result.firstNewVertexIndex());

        logger.debug("Applied parameterized subdivision (t={}): unique vertices ({}, {}) -> new unique vertex {}",
            t, uniqueVertexA, uniqueVertexB, newUniqueIndex);

        return newUniqueIndex;
    }

    @Override
    public boolean insertEdgeBetweenVertices(int uniqueVertexA, int uniqueVertexB) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot insert edge: renderer not initialized");
            return false;
        }
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology == null) {
            logger.warn("Cannot insert edge: topology not available");
            return false;
        }

        EdgeInsertionProcessor processor = new EdgeInsertionProcessor();
        EdgeInsertionProcessor.InsertionResult result = processor.insertEdge(
            uniqueVertexA, uniqueVertexB, topology, vertexManager, faceMapper, faceTextureManager);

        if (!result.success()) {
            logger.warn("Edge insertion failed: {}", result.errorMessage());
            return false;
        }

        applyTopologyChangeResult(result.newIndices(), result.newTriangleToFaceId(),
            rendererState.getVertexCount());

        logger.debug("Inserted edge between unique vertices {} and {}: {} triangles, {} faces",
            uniqueVertexA, uniqueVertexB,
            result.newIndices().length / 3, result.newFaceCount());

        return true;
    }

    @Override
    public boolean deleteFace(int faceId) {
        if (!rendererState.isInitialized()) {
            logger.warn("Cannot delete face: renderer not initialized");
            return false;
        }
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology == null) {
            logger.warn("Cannot delete face: topology not available");
            return false;
        }

        FaceDeletionProcessor processor = new FaceDeletionProcessor();
        FaceDeletionProcessor.FaceDeletionResult result = processor.deleteFace(
            faceId, topology, vertexManager, faceMapper, faceTextureManager);

        if (!result.success()) {
            logger.warn("Face deletion failed: {}", result.errorMessage());
            return false;
        }

        applyTopologyChangeResult(result.newIndices(), result.newTriangleToFaceId(),
            rendererState.getVertexCount());

        logger.debug("Deleted face {}: {} triangles, {} faces remaining",
            faceId, result.newIndices().length / 3, result.newFaceCount());

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
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology == null) {
            logger.warn("Cannot create face: topology not available");
            return false;
        }

        FaceCreationProcessor processor = new FaceCreationProcessor();
        FaceCreationProcessor.FaceCreationResult result = processor.createFace(
            selectedUniqueVertices, topology, vertexManager, faceMapper,
            faceTextureManager, activeMaterialId);

        if (!result.success()) {
            logger.warn("Face creation failed: {}", result.errorMessage());
            return false;
        }

        applyTopologyChangeResult(result.newIndices(), result.newTriangleToFaceId(),
            rendererState.getVertexCount());

        logger.debug("Created face {} from {} vertices: {} triangles, {} faces",
            result.newFaceId(), selectedUniqueVertices.length,
            result.newIndices().length / 3, result.newFaceCount());

        return true;
    }

    /**
     * Common method to apply topology-changing results and trigger rebuild.
     * Eliminates the repeated boilerplate across all mutation methods.
     */
    private void applyTopologyChangeResult(int[] newIndices, int[] newTriangleToFaceId, int newVertexCount) {
        // Update shared state
        vertexManager.setIndices(newIndices);
        faceMapper.setMapping(newTriangleToFaceId);

        int newIndexCount = newIndices.length;
        rendererState.setVertexCount(newVertexCount);
        rendererState.setIndexCount(newIndexCount);

        // Trigger full rebuild pipeline
        rebuildPipeline.rebuildFull(newVertexCount, newIndexCount);
    }
}
