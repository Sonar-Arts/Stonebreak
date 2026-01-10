package com.openmason.main.systems.viewport.viewportRendering.mesh;

import com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations.*;
import com.openmason.main.systems.viewport.viewportRendering.mesh.faceOperations.*;
import com.openmason.main.systems.viewport.viewportRendering.mesh.vertexOperations.*;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages mesh operations for the viewport rendering system.
 * This class coordinates mesh operations on data provided by GenericModelRenderer.
 *
 * Architecture:
 * - GenericModelRenderer (GMR) is the single source of truth for mesh data
 * - MeshManager provides operations on GMR's data (buffer updates, mapping building)
 * - Overlay renderers get data from GMR and use MeshManager for operations
 *
 * Responsibilities:
 * - Managing mesh vertex data storage (temporary working data)
 * - Coordinating mesh operations (vertex updates, face operations, edge operations)
 * - Building mappings (face-to-vertex, edge-to-vertex)
 * - Updating GPU buffers (VBO updates for overlays)
 *
 * Data Flow:
 * 1. GMR extracts mesh data → 2. Overlay renderers → 3. MeshManager operations → 4. GPU
 *
 * Note: For mesh data extraction, use GenericModelRenderer methods:
 * - extractFacePositions(), extractEdgePositions(), getAllMeshVertexPositions(), etc.
 */
public class MeshManager {

    private static final Logger logger = LoggerFactory.getLogger(MeshManager.class);

    // Thread-safe singleton using holder pattern (lazy initialization)
    private static class InstanceHolder {
        static final MeshManager INSTANCE = new MeshManager();
    }

    // Mesh data storage
    private float[] allMeshVertices = null; // ALL mesh vertices (e.g., 24 for cube)

    private MeshManager() {
        // Private constructor for singleton
    }

    public static MeshManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Initializes the mesh manager.
     */
    public void initialize() {
        logger.debug("MeshManager initialized");
        clearMeshData();
    }

    /**
     * Set the mesh vertex data.
     *
     * @param meshVertices Array of all mesh vertex positions
     */
    public void setMeshVertices(float[] meshVertices) {
        this.allMeshVertices = meshVertices;
        logger.debug("Set mesh vertices: {} vertices", meshVertices != null ? meshVertices.length / 3 : 0);
    }

    /**
     * Get all mesh vertices.
     *
     * @return Array of all mesh vertex positions, or null if not set
     */
    public float[] getAllMeshVertices() {
        return allMeshVertices;
    }

    /**
     * Clear all mesh data.
     */
    public void clearMeshData() {
        allMeshVertices = null;
        logger.debug("Cleared mesh data");
    }

    /**
     * Add a new mesh vertex to the mesh vertices array.
     * Used when subdivision creates a new vertex that needs to be tracked
     * for proper synchronization with GenericModelRenderer.
     *
     * @param x X coordinate of the new vertex
     * @param y Y coordinate of the new vertex
     * @param z Z coordinate of the new vertex
     * @return The index of the newly added mesh vertex
     */
    public int addMeshVertex(float x, float y, float z) {
        int newIndex;
        if (allMeshVertices == null) {
            allMeshVertices = new float[] { x, y, z };
            newIndex = 0;
        } else {
            int oldVertexCount = allMeshVertices.length / 3;
            newIndex = oldVertexCount;
            int newLength = allMeshVertices.length + 3;
            float[] newMeshVertices = new float[newLength];
            System.arraycopy(allMeshVertices, 0, newMeshVertices, 0, allMeshVertices.length);
            newMeshVertices[allMeshVertices.length] = x;
            newMeshVertices[allMeshVertices.length + 1] = y;
            newMeshVertices[allMeshVertices.length + 2] = z;
            allMeshVertices = newMeshVertices;
        }
        logger.debug("Added mesh vertex {} at ({}, {}, {})", newIndex, x, y, z);
        return newIndex;
    }

    // ========================================
    // Mesh Operations (managed through MeshManager)
    // ========================================

    /**
     * Update vertex position in memory and GPU.
     * Coordinates the update process using MeshVertexPositionUpdater.
     * Note: Mesh instance synchronization is now handled by GenericModelRenderer.
     *
     * @param uniqueIndex Unique vertex index to update
     * @param position New position in model space
     * @param vertexPositions Array of unique vertex positions (will be modified)
     * @param vbo OpenGL VBO handle for GPU update
     * @param vertexCount Total number of unique vertices
     * @return true if update succeeded, false otherwise
     */
    public boolean updateVertexPosition(int uniqueIndex, Vector3f position,
                                       float[] vertexPositions, int vbo, int vertexCount) {
        // Pass null for mapping - GenericModelRenderer now owns mesh instance synchronization
        MeshVertexPositionUpdater updater = new MeshVertexPositionUpdater(
                vertexPositions,
                allMeshVertices,
                null, // Mapping owned by GenericModelRenderer
                vbo,
                vertexCount
        );
        return updater.updatePosition(uniqueIndex, position);
    }

    /**
     * Expand vertex positions to standard cube format (8 vertices).
     * Convenience method using MeshVertexDataTransformer.
     *
     * @param vertexPositions Current vertex positions
     * @param vertexCount Current vertex count
     * @param indexRemapping Mapping from old indices to new indices
     * @return Expanded vertex positions (24 floats for 8 vertices)
     */
    public float[] expandToCubeFormat(float[] vertexPositions, int vertexCount,
                                     Map<Integer, Integer> indexRemapping) {
        MeshVertexDataTransformer transformer = new MeshVertexDataTransformer(vertexPositions, vertexCount);
        return transformer.expandToCubeFormat(indexRemapping);
    }

    /**
     * Merge overlapping vertices by removing duplicates.
     * Coordinates the complete merge process using MeshVertexMerger.
     *
     * @param vertexPositions Current vertex positions
     * @param vertexCount Current vertex count
     * @param epsilon Distance threshold for considering vertices overlapping
     * @param originalToCurrentMapping Persistent mapping from original to current indices
     * @return MergeResult with new positions and mappings, or null if no merge occurred
     */
    public MeshVertexMerger.MergeResult mergeOverlappingVertices(float[] vertexPositions,
                                                                  int vertexCount,
                                                                  float epsilon,
                                                                  Map<Integer, Integer> originalToCurrentMapping) {
        MeshVertexMerger merger = new MeshVertexMerger(
                vertexPositions,
                allMeshVertices,
                vertexCount,
                epsilon,
                originalToCurrentMapping
        );
        return merger.merge();
    }

    // ========================================
    // Face Operations (managed through MeshManager)
    // ========================================

    // Face VBO layout constants (exposed for rendering operations)
    public static final int FLOATS_PER_FACE_POSITION = MeshFaceUpdateOperation.FLOATS_PER_FACE_POSITION;
    public static final int FLOATS_PER_VERTEX = MeshFaceUpdateOperation.FLOATS_PER_VERTEX;
    public static final int VERTICES_PER_FACE = MeshFaceUpdateOperation.VERTICES_PER_FACE;
    public static final int FLOATS_PER_FACE_VBO = MeshFaceUpdateOperation.FLOATS_PER_FACE_VBO;

    /**
     * Build face-to-vertex mapping from unique vertex positions.
     * Coordinates the mapping process using MeshFaceMappingBuilder.
     *
     * @param facePositions Array of face positions
     * @param faceCount Number of faces
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param epsilon Distance threshold for vertex matching
     * @return Map from face index to array of 4 unique vertex indices
     */
    public Map<Integer, int[]> buildFaceToVertexMapping(float[] facePositions, int faceCount,
                                                       float[] uniqueVertexPositions, float epsilon) {
        MeshFaceMappingBuilder builder = new MeshFaceMappingBuilder(epsilon);
        return builder.buildMapping(facePositions, faceCount, uniqueVertexPositions);
    }

    /**
     * Get the 4 corner vertices of a face.
     * Convenience method using MeshFaceCornerExtractor.
     *
     * @param facePositions Array of face positions
     * @param faceIndex Face index
     * @param faceCount Total number of faces
     * @return Array of 4 vertices [v0, v1, v2, v3], or null if invalid
     */
    public Vector3f[] getFaceVertices(float[] facePositions, int faceIndex, int faceCount) {
        MeshFaceCornerExtractor extractor = new MeshFaceCornerExtractor();
        return extractor.getFaceVertices(facePositions, faceIndex, faceCount);
    }

    /**
     * Get face vertex indices for a face from the mapping.
     * Convenience method using MeshFaceCornerExtractor.
     *
     * @param faceIndex Face index
     * @param faceCount Total number of faces
     * @param faceToVertexMapping Map from face index to vertex indices
     * @return Array of 4 vertex indices [v0, v1, v2, v3], or null if invalid
     */
    public int[] getFaceVertexIndices(int faceIndex, int faceCount,
                                     Map<Integer, int[]> faceToVertexMapping) {
        MeshFaceCornerExtractor extractor = new MeshFaceCornerExtractor();
        return extractor.getFaceVertexIndices(faceIndex, faceCount, faceToVertexMapping);
    }

    /**
     * Update a single face's position in both CPU memory and GPU buffer.
     * Coordinates the update process using MeshFaceUpdateOperation.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions CPU-side face position array
     * @param faceCount Total number of faces
     * @param faceIndex Index of the face to update
     * @param vertexIndices Array of 4 unique vertex indices
     * @param newPositions Array of 4 new vertex positions
     * @return true if update succeeded, false otherwise
     */
    public boolean updateFacePosition(int vbo, float[] facePositions, int faceCount,
                                     int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {
        MeshFaceUpdateOperation updater = new MeshFaceUpdateOperation();
        return updater.updateFace(vbo, facePositions, faceCount, faceIndex, vertexIndices, newPositions);
    }

    /**
     * Bulk update all faces with VBO data creation.
     * Coordinates the bulk update process using MeshFaceUpdateOperation.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions
     * @param faceCount Number of faces
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFaces(int vbo, float[] facePositions, int faceCount,
                                 org.joml.Vector4f defaultColor) {
        MeshFaceUpdateOperation updater = new MeshFaceUpdateOperation();
        return updater.updateAllFaces(vbo, facePositions, faceCount, defaultColor);
    }

    // ========================================
    // Edge Operations (managed through MeshManager)
    // ========================================

    // Edge VBO layout constants (exposed for rendering operations)
    public static final int FLOATS_PER_EDGE = 6; // 2 endpoints × 3 coordinates
    public static final int ENDPOINTS_PER_EDGE = 2;

    /**
     * Build edge-to-vertex mapping from unique vertex positions.
     * Coordinates the mapping process using MeshEdgeMappingBuilder.
     *
     * @param edgePositions Array of edge positions [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount Number of edges
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param epsilon Distance threshold for vertex matching
     * @return 2D array mapping edge index to vertex indices [edgeIdx][0=v1, 1=v2]
     */
    public int[][] buildEdgeToVertexMapping(float[] edgePositions, int edgeCount,
                                           float[] uniqueVertexPositions, float epsilon) {
        MeshEdgeMappingBuilder builder = new MeshEdgeMappingBuilder(epsilon);
        return builder.buildMapping(edgePositions, edgeCount, uniqueVertexPositions);
    }

    /**
     * Update edge buffer with interleaved vertex data.
     * Coordinates the buffer update process using MeshEdgeBufferUpdater.
     *
     * @param vbo OpenGL VBO handle
     * @param edgePositions Edge position data
     * @param edgeColor Color to apply to all edges
     * @return UpdateResult with edge count and positions, or null if failed
     */
    public MeshEdgeBufferUpdater.UpdateResult updateEdgeBuffer(int vbo, float[] edgePositions,
                                                               Vector3f edgeColor) {
        MeshEdgeBufferUpdater updater = new MeshEdgeBufferUpdater();
        return updater.updateBuffer(vbo, edgePositions, edgeColor);
    }

    /**
     * Remap edge vertex indices after vertex merging.
     * Coordinates the remapping process using MeshEdgeVertexRemapper.
     *
     * @param edgeToVertexMapping 2D array mapping edge index to vertex indices
     * @param edgeCount Total number of edges
     * @param oldToNewIndexMap Mapping from old to new vertex indices
     * @return RemapResult with statistics, or null if failed
     */
    public MeshEdgeVertexRemapper.RemapResult remapEdgeVertexIndices(int[][] edgeToVertexMapping,
                                                                     int edgeCount,
                                                                     Map<Integer, Integer> oldToNewIndexMap) {
        MeshEdgeVertexRemapper remapper = new MeshEdgeVertexRemapper();
        return remapper.remapIndices(edgeToVertexMapping, edgeCount, oldToNewIndexMap);
    }

    /**
     * Update edge positions by matching old vertex position.
     * Uses position-based matching strategy via MeshEdgePositionUpdater.
     *
     * @param vbo OpenGL VBO handle
     * @param edgePositions Edge position array
     * @param edgeCount Total number of edges
     * @param oldPosition Original position of vertex before dragging
     * @param newPosition New position of vertex after dragging
     * @return UpdateResult with statistics, or null if failed
     */
    public MeshEdgePositionUpdater.UpdateResult updateEdgesByPosition(int vbo, float[] edgePositions,
                                                                      int edgeCount,
                                                                      Vector3f oldPosition,
                                                                      Vector3f newPosition) {
        MeshEdgePositionUpdater updater = new MeshEdgePositionUpdater();
        return updater.updateByPosition(vbo, edgePositions, edgeCount, oldPosition, newPosition);
    }

    /**
     * Update edge positions by vertex indices.
     * Uses index-based matching strategy via MeshEdgePositionUpdater.
     *
     * @param vbo OpenGL VBO handle
     * @param edgePositions Edge position array
     * @param edgeCount Total number of edges
     * @param edgeToVertexMapping 2D array mapping edge indices to vertex indices
     * @param vertexIndex1 First unique vertex index that was moved
     * @param newPosition1 New position for first vertex
     * @param vertexIndex2 Second unique vertex index that was moved
     * @param newPosition2 New position for second vertex
     * @return UpdateResult with statistics, or null if failed
     */
    public MeshEdgePositionUpdater.UpdateResult updateEdgesByIndices(int vbo, float[] edgePositions,
                                                                     int edgeCount,
                                                                     int[][] edgeToVertexMapping,
                                                                     int vertexIndex1, Vector3f newPosition1,
                                                                     int vertexIndex2, Vector3f newPosition2) {
        MeshEdgePositionUpdater updater = new MeshEdgePositionUpdater();
        return updater.updateByIndices(vbo, edgePositions, edgeCount, edgeToVertexMapping,
                                       vertexIndex1, newPosition1, vertexIndex2, newPosition2);
    }

    /**
     * Update edge positions for a single vertex by index.
     * Uses index-based matching strategy via MeshEdgePositionUpdater.
     * More reliable than position-based matching, especially after subdivision.
     *
     * @param vbo OpenGL VBO handle
     * @param edgePositions Edge position array
     * @param edgeCount Total number of edges
     * @param edgeToVertexMapping 2D array mapping edge indices to vertex indices
     * @param vertexIndex Unique vertex index that was moved
     * @param newPosition New position for the vertex
     * @return UpdateResult with statistics, or null if failed
     */
    public MeshEdgePositionUpdater.UpdateResult updateEdgesBySingleVertexIndex(int vbo, float[] edgePositions,
                                                                                int edgeCount,
                                                                                int[][] edgeToVertexMapping,
                                                                                int vertexIndex, Vector3f newPosition) {
        MeshEdgePositionUpdater updater = new MeshEdgePositionUpdater();
        return updater.updateSingleVertexByIndex(vbo, edgePositions, edgeCount, edgeToVertexMapping,
                                                  vertexIndex, newPosition);
    }

    /**
     * Get edge endpoint positions.
     * Convenience method using MeshEdgeGeometryQuery.
     *
     * @param edgeIndex Edge index
     * @param edgePositions Array of edge positions
     * @param edgeCount Total number of edges
     * @return Array of [endpoint1, endpoint2], or null if invalid
     */
    public Vector3f[] getEdgeEndpoints(int edgeIndex, float[] edgePositions, int edgeCount) {
        MeshEdgeGeometryQuery query = new MeshEdgeGeometryQuery();
        return query.getEdgeEndpoints(edgeIndex, edgePositions, edgeCount);
    }

    /**
     * Get edge vertex indices from mapping.
     * Convenience method using MeshEdgeGeometryQuery.
     *
     * @param edgeIndex Edge index
     * @param edgeToVertexMapping Map from edge index to vertex indices
     * @return Array of [vertexIndex1, vertexIndex2], or null if invalid
     */
    public int[] getEdgeVertexIndices(int edgeIndex, int[][] edgeToVertexMapping) {
        MeshEdgeGeometryQuery query = new MeshEdgeGeometryQuery();
        return query.getEdgeVertexIndices(edgeIndex, edgeToVertexMapping);
    }

    // ========================================
    // NOTE: Geometry extraction has been moved to GenericModelRenderer
    // ========================================
    //
    // MeshManager now operates on data extracted from GenericModelRenderer (GMR).
    // The data flow is:
    //   1. GMR extracts mesh data from its internal structures (vertices, indices, face mapping)
    //   2. Overlay renderers call GMR.extractFacePositions(), GMR.extractEdgePositions(), etc.
    //   3. MeshManager provides operations on that data (building mappings, updating buffers)
    //
    // This ensures GMR is the single source of truth for mesh topology.
    //
    // For extraction operations, use:
    //   - GenericModelRenderer.extractFacePositions()
    //   - GenericModelRenderer.extractEdgePositions()
    //   - GenericModelRenderer.getAllMeshVertexPositions()
    //   - GenericModelRenderer.getAllUniqueVertexPositions()
    // ========================================

    /**
     * Cleans up all mesh resources.
     */
    public void cleanup() {
        clearMeshData();
        logger.debug("MeshManager cleaned up");
    }
}
