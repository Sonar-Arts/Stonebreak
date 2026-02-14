package com.openmason.main.systems.rendering.model.gmr.mesh;

import com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations.*;
import com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.MeshFaceUpdateOperation;
import com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations.MeshVertexDataTransformer;
import com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations.MeshVertexMerger;
import com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations.MeshVertexPositionUpdater;
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
    private float[] allMeshVertices = null; // ALL mesh vertices (count determined by GMR)

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
     * Expand vertex positions to a target vertex count.
     * After merging, we may have fewer vertices than the original mesh structure.
     * This expands back to the target count using index remapping.
     *
     * This is a shape-blind method that works with any target vertex count
     * determined by GMR's data model.
     *
     * @param vertexPositions Current vertex positions
     * @param vertexCount Current vertex count
     * @param indexRemapping Mapping from old indices to new indices
     * @param targetVertexCount Target number of vertices (data-driven from GMR)
     * @return Expanded vertex positions array
     */
    public float[] expandPositions(float[] vertexPositions, int vertexCount,
                                  Map<Integer, Integer> indexRemapping,
                                  int targetVertexCount) {
        MeshVertexDataTransformer transformer = new MeshVertexDataTransformer(vertexPositions, vertexCount);
        return transformer.expandPositions(indexRemapping, targetVertexCount);
    }

    /**
     * Expand vertex positions to cube format (8 vertices).
     * Convenience method for backwards compatibility with cube-based code.
     *
     * @param vertexPositions Current vertex positions
     * @param vertexCount Current vertex count
     * @param indexRemapping Mapping from old indices to new indices
     * @return Expanded vertex positions (24 floats for 8 vertices)
     * @deprecated Use {@link #expandPositions(float[], int, Map, int)} with explicit target count from GMR
     */
    @Deprecated
    public float[] expandToCubeFormat(float[] vertexPositions, int vertexCount,
                                     Map<Integer, Integer> indexRemapping) {
        // Backwards compatibility: assume 8 vertices for legacy cube-based code
        return expandPositions(vertexPositions, vertexCount, indexRemapping, 8);
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

    /**
     * Update a single face's position in both CPU memory and GPU buffer.
     * Coordinates the update process using MeshFaceUpdateOperation.
     * Shape-blind: Uses topology determined by GMR's data model.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions CPU-side face position array
     * @param faceCount Total number of faces
     * @param faceIndex Index of the face to update
     * @param vertexIndices Array of unique vertex indices
     * @param newPositions Array of new vertex positions
     * @return true if update succeeded, false otherwise
     */
    public boolean updateFacePosition(int vbo, float[] facePositions, int faceCount,
                                     int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {
        MeshFaceUpdateOperation updater = new MeshFaceUpdateOperation();
        // Shape-blind: derive topology from actual data
        int verticesPerFace = newPositions.length;

        // Topology-aware: use appropriate triangulation pattern for vertex count
        com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern pattern =
            com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern.forNGon(verticesPerFace);

        return updater.updateFace(vbo, facePositions, faceCount, faceIndex,
                                  verticesPerFace, vertexIndices, newPositions, pattern);
    }

    /**
     * Bulk update all faces with VBO data creation.
     * Coordinates the bulk update process using MeshFaceUpdateOperation.
     * Shape-blind: Uses topology determined by GMR's data model.
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
        // Legacy: assumes uniform quad topology (4 vertices per face)
        int verticesPerFace = 4;

        com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern pattern =
            com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern.QUAD;

        return updater.updateAllFaces(vbo, facePositions, faceCount,
                                      verticesPerFace, pattern, defaultColor);
    }

    /**
     * Bulk update all faces with topology-aware VBO data creation.
     * Supports mixed-topology meshes where faces have different vertex counts.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions (packed sequentially, variable vertices per face)
     * @param faceCount Number of faces
     * @param verticesPerFace Array of vertex counts per face
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFaces(int vbo, float[] facePositions, int faceCount,
                                 int[] verticesPerFace, org.joml.Vector4f defaultColor) {
        MeshFaceUpdateOperation updater = new MeshFaceUpdateOperation();

        // Check if all faces have the same vertex count (fast path)
        boolean uniform = true;
        int firstCount = verticesPerFace.length > 0 ? verticesPerFace[0] : 4;
        for (int i = 1; i < verticesPerFace.length; i++) {
            if (verticesPerFace[i] != firstCount) {
                uniform = false;
                break;
            }
        }

        if (uniform) {
            // Fast path: single pattern for all faces
            com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern pattern =
                com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern.forNGon(firstCount);
            return updater.updateAllFaces(vbo, facePositions, faceCount,
                                          firstCount, pattern, defaultColor);
        }

        // Mixed topology: delegate to per-face pattern method
        int[] faceOffsets = computeFaceOffsets(verticesPerFace);
        return updater.updateAllFacesMixed(vbo, facePositions, faceCount,
                                            verticesPerFace, faceOffsets, defaultColor);
    }

    /**
     * Compute float offsets into a packed face positions array from per-face vertex counts.
     * Each face's positions occupy verticesPerFace[i] * 3 floats.
     *
     * @param verticesPerFace Per-face vertex counts
     * @return Array of float offsets (one per face)
     */
    private int[] computeFaceOffsets(int[] verticesPerFace) {
        int[] offsets = new int[verticesPerFace.length];
        int cumulative = 0;
        for (int i = 0; i < verticesPerFace.length; i++) {
            offsets[i] = cumulative;
            cumulative += verticesPerFace[i] * 3; // 3 floats per vertex position
        }
        return offsets;
    }

    // ========================================
    // Edge Operations (managed through MeshManager)
    // ========================================


    /**
     * Update edge buffer with interleaved vertex data.
     * Coordinates the buffer update process using MeshEdgeBufferUpdater.
     *
     * @param vbo OpenGL VBO handle
     * @param edgePositions Edge position data
     * @param verticesPerEdge Number of vertices per edge (derived from GMR topology)
     * @param edgeColor Color to apply to all edges
     * @return UpdateResult with edge count and positions, or null if failed
     */
    public MeshEdgeBufferUpdater.UpdateResult updateEdgeBuffer(int vbo, float[] edgePositions,
                                                               int verticesPerEdge, Vector3f edgeColor) {
        MeshEdgeBufferUpdater updater = new MeshEdgeBufferUpdater();
        return updater.updateBuffer(vbo, edgePositions, verticesPerEdge, edgeColor);
    }

    /**
     * Update edge positions by matching old vertex position.
     * Uses position-based matching strategy via MeshEdgePositionUpdater.
     *
     * @param vbo OpenGL VBO handle
     * @param edgePositions Edge position array
     * @param edgeCount Total number of edges
     * @param verticesPerEdge Number of vertices per edge (derived from GMR topology)
     * @param oldPosition Original position of vertex before dragging
     * @param newPosition New position of vertex after dragging
     * @return UpdateResult with statistics, or null if failed
     */
    public MeshEdgePositionUpdater.UpdateResult updateEdgesByPosition(int vbo, float[] edgePositions,
                                                                      int edgeCount, int verticesPerEdge,
                                                                      Vector3f oldPosition,
                                                                      Vector3f newPosition) {
        MeshEdgePositionUpdater updater = new MeshEdgePositionUpdater();
        return updater.updateByPosition(vbo, edgePositions, verticesPerEdge, oldPosition, newPosition);
    }

    /**
     * Get edge vertex positions.
     * Convenience method using MeshEdgeGeometryQuery.
     *
     * @param edgeIndex Edge index
     * @param edgePositions Array of edge positions
     * @param verticesPerEdge Number of vertices per edge (derived from GMR topology)
     * @return Array of vertices, or null if invalid
     */
    public Vector3f[] getEdgeVertices(int edgeIndex, float[] edgePositions, int verticesPerEdge) {
        MeshEdgeGeometryQuery query = new MeshEdgeGeometryQuery();
        return query.getEdgeVertices(edgeIndex, edgePositions, verticesPerEdge);
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
