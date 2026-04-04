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
 * Thin coordinator for mesh GPU operations.
 * Delegates vertex, face, and edge buffer updates to specialized operation classes.
 * Does NOT own topology queries or adjacency data — those live in {@code MeshTopology}.
 *
 * <p>Architecture:
 * <ul>
 *   <li>GenericModelRenderer (GMR) is the single source of truth for mesh data</li>
 *   <li>MeshTopology owns adjacency queries, uniform/mixed detection, face offsets</li>
 *   <li>MeshManager coordinates GPU buffer updates via operation delegates</li>
 * </ul>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Temporary mesh vertex data storage (working copy)</li>
 *   <li>Vertex position updates (MeshVertexPositionUpdater)</li>
 *   <li>Face VBO bulk creation — uniform and mixed topology (MeshFaceUpdateOperation)</li>
 *   <li>Edge buffer updates and position-based matching (MeshEdgeBufferUpdater, MeshEdgePositionUpdater)</li>
 *   <li>Vertex merging and expansion (MeshVertexMerger, MeshVertexDataTransformer)</li>
 * </ul>
 *
 * <p>Data Flow:
 * 1. GMR extracts mesh data → 2. Overlay renderers → 3. MeshManager operations → 4. GPU
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
     * Bulk update all faces with VBO data creation (uniform topology).
     * Coordinates the bulk update process using MeshFaceUpdateOperation.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions
     * @param faceCount Number of faces
     * @param verticesPerFace Vertex count per face (uniform across all faces)
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFaces(int vbo, float[] facePositions, int faceCount,
                                 int verticesPerFace, org.joml.Vector4f defaultColor) {
        MeshFaceUpdateOperation updater = new MeshFaceUpdateOperation();

        com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern pattern =
            com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern.forNGon(verticesPerFace);

        return updater.updateAllFaces(vbo, facePositions, faceCount,
                                      verticesPerFace, pattern, defaultColor);
    }

    /**
     * Bulk update all faces with pre-computed topology (mixed topology).
     * Caller provides per-face vertex counts and float offsets, avoiding re-derivation.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions (packed sequentially, variable vertices per face)
     * @param faceCount Number of faces
     * @param verticesPerFace Array of vertex counts per face
     * @param faceOffsets Pre-computed float offsets into facePositions per face
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFacesMixed(int vbo, float[] facePositions, int faceCount,
                                       int[] verticesPerFace, int[] faceOffsets,
                                       org.joml.Vector4f defaultColor) {
        MeshFaceUpdateOperation updater = new MeshFaceUpdateOperation();
        return updater.updateAllFacesMixed(vbo, facePositions, faceCount,
                                            verticesPerFace, faceOffsets, defaultColor);
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

    // ========================================
    // NOTE: Topology and extraction live elsewhere
    // ========================================
    //
    // - MeshTopology: adjacency queries, uniform/mixed detection, face offsets
    // - GMRFaceExtractor / GMREdgeExtractor: geometry extraction (topology-aware)
    // - GenericModelRenderer: single source of truth, delegates to extractors
    //
    // MeshManager is a thin coordinator for GPU buffer operations only.
    // ========================================

    /**
     * Cleans up all mesh resources.
     */
    public void cleanup() {
        clearMeshData();
        logger.debug("MeshManager cleaned up");
    }
}
