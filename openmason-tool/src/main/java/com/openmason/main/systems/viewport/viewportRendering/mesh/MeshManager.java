package com.openmason.main.systems.viewport.viewportRendering.mesh;

import com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations.*;
import com.openmason.main.systems.viewport.viewportRendering.mesh.faceOperations.*;
import com.openmason.main.systems.viewport.viewportRendering.mesh.vertexOperations.*;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages mesh resources for the viewport rendering system.
 * This class handles mesh vertex relationships, unique-to-mesh mapping,
 * and mesh data structure operations.
 *
 * Responsibilities:
 * - Building and maintaining unique vertex to mesh vertex mappings
 * - Managing mesh vertex data structures
 * - Providing mesh geometry information
 */
public class MeshManager {

    private static final Logger logger = LoggerFactory.getLogger(MeshManager.class);
    private static final float EPSILON = 0.0001f; // Tolerance for vertex position matching

    // Thread-safe singleton using holder pattern (lazy initialization)
    private static class InstanceHolder {
        static final MeshManager INSTANCE = new MeshManager();
    }

    // Mesh data storage
    private float[] allMeshVertices = null; // ALL mesh vertices (e.g., 24 for cube)

    /**
     * @deprecated Use GenericModelRenderer's index-based mapping instead.
     * GenericModelRenderer now maintains the authoritative unique-to-mesh mapping
     * via getMeshIndicesForUniqueVertex() and getUniqueIndexForMeshVertex().
     * This field is retained for backward compatibility during migration.
     */
    @Deprecated
    private Map<Integer, List<Integer>> uniqueToMeshMapping = new HashMap<>(); // Maps unique vertex index to mesh vertex indices

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
     * Get the unique-to-mesh mapping.
     *
     * @return Map from unique vertex indices to lists of mesh vertex indices
     * @deprecated Use GenericModelRenderer's getMeshIndicesForUniqueVertex() instead.
     * GenericModelRenderer is now the single source of truth for vertex mappings.
     */
    @Deprecated
    public Map<Integer, List<Integer>> getUniqueToMeshMapping() {
        return uniqueToMeshMapping;
    }

    /**
     * Build mapping from unique vertex indices to mesh vertex indices.
     * For a cube: Each of the 8 unique corner vertices maps to 3 mesh vertex instances.
     *
     * This is the core mesh relationship method that identifies which mesh vertices
     * correspond to each unique geometric vertex.
     *
     * @param uniquePositions Array of unique vertex positions
     * @param meshPositions Array of ALL mesh vertex positions
     * @deprecated Use GenericModelRenderer's buildUniqueVertexMapping() instead.
     * GenericModelRenderer now automatically builds this mapping when geometry is loaded
     * or rebuilt. Access the mapping via getUniqueIndexForMeshVertex() and
     * getMeshIndicesForUniqueVertex().
     */
    @Deprecated
    public void buildUniqueToMeshMapping(float[] uniquePositions, float[] meshPositions) {
        uniqueToMeshMapping.clear();

        if (uniquePositions == null || meshPositions == null) {
            logger.warn("Cannot build mapping: null positions provided");
            return;
        }

        // For each mesh vertex, find its matching unique vertex
        int meshVertexCount = meshPositions.length / 3;
        int uniqueVertexCount = uniquePositions.length / 3;

        for (int meshIndex = 0; meshIndex < meshVertexCount; meshIndex++) {
            int meshPosIndex = meshIndex * 3;
            Vector3f meshPos = new Vector3f(
                    meshPositions[meshPosIndex],
                    meshPositions[meshPosIndex + 1],
                    meshPositions[meshPosIndex + 2]
            );

            // Find which unique vertex this mesh vertex matches
            for (int uniqueIndex = 0; uniqueIndex < uniqueVertexCount; uniqueIndex++) {
                int uniquePosIndex = uniqueIndex * 3;
                Vector3f uniquePos = new Vector3f(
                        uniquePositions[uniquePosIndex],
                        uniquePositions[uniquePosIndex + 1],
                        uniquePositions[uniquePosIndex + 2]
                );

                if (meshPos.distance(uniquePos) < EPSILON) {
                    // Found matching unique vertex - add to mapping
                    uniqueToMeshMapping.computeIfAbsent(uniqueIndex, k -> new ArrayList<>()).add(meshIndex);
                    break;
                }
            }
        }

        logger.debug("Built vertex mapping: {} unique vertices → {} mesh vertices",
                uniqueVertexCount, meshVertexCount);

        // Log mapping for debugging (only for small models like cube)
        if (uniqueVertexCount <= 10) {
            for (Map.Entry<Integer, List<Integer>> entry : uniqueToMeshMapping.entrySet()) {
                logger.trace("Unique vertex {} → mesh vertices {}",
                        entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Clear all mesh data and mappings.
     */
    public void clearMeshData() {
        allMeshVertices = null;
        uniqueToMeshMapping.clear();
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
     * Update vertex position in memory, GPU, and all mesh instances.
     * Coordinates the complete update process using MeshVertexPositionUpdater.
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
        MeshVertexPositionUpdater updater = new MeshVertexPositionUpdater(
                vertexPositions,
                allMeshVertices,
                uniqueToMeshMapping,
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

    /**
     * Subdivide an edge at its midpoint.
     * Creates a new vertex at the midpoint and replaces the original edge
     * with two new edges connecting to the midpoint.
     *
     * @param edgeIndex Index of edge to subdivide
     * @param edgePositions Current edge positions array
     * @param edgeCount Current number of edges
     * @param vertexPositions Current unique vertex positions array
     * @param vertexCount Current number of unique vertices
     * @param edgeToVertexMapping Current edge-to-vertex mapping
     * @return SubdivisionResult with updated data, or null if subdivision failed
     */
    public MeshEdgeSubdivider.SubdivisionResult subdivideEdge(int edgeIndex,
                                                               float[] edgePositions, int edgeCount,
                                                               float[] vertexPositions, int vertexCount,
                                                               int[][] edgeToVertexMapping) {
        MeshEdgeSubdivider subdivider = new MeshEdgeSubdivider();
        return subdivider.subdivide(edgeIndex, edgePositions, edgeCount,
                                    vertexPositions, vertexCount, edgeToVertexMapping);
    }

    // ========================================
    // Geometry Extraction Operations (managed through MeshManager)
    // ========================================

    // Extractor instances (singleton pattern within MeshManager)
    private final MeshVertexExtractor vertexExtractor = new MeshVertexExtractor();
    private final MeshEdgeExtractor edgeExtractor = new MeshEdgeExtractor();
    private final MeshFaceExtractor faceExtractor = new MeshFaceExtractor();

    /**
     * Extract vertex geometry from model parts with transformation applied.
     * Centralizes vertex extraction through MeshManager.
     *
     * @param parts Model parts to extract vertices from
     * @param globalTransform Global transformation matrix
     * @return Array of vertex positions [x1,y1,z1, x2,y2,z2, ...]
     */
    public float[] extractVertexGeometry(Collection<ModelDefinition.ModelPart> parts,
                                        Matrix4f globalTransform) {
        return vertexExtractor.extractGeometry(parts, globalTransform);
    }

    /**
     * Extract unique vertex geometry from model parts with transformation applied.
     * Deduplicates vertices at the same position using epsilon comparison.
     *
     * @param parts Model parts to extract vertices from
     * @param globalTransform Global transformation matrix
     * @return Array of unique vertex positions [x1,y1,z1, x2,y2,z2, ...]
     */
    public float[] extractUniqueVertices(Collection<ModelDefinition.ModelPart> parts,
                                        Matrix4f globalTransform) {
        return vertexExtractor.extractUniqueVertices(parts, globalTransform);
    }

    /**
     * Extract edge geometry from model parts with transformation applied.
     * Centralizes edge extraction through MeshManager.
     * Each face (4 vertices) generates 4 edges forming a quad outline.
     * NOTE: This method returns duplicate edges (shared between faces).
     * For unique edges, use {@link #extractUniqueEdgeGeometry}.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of edge endpoint positions [x1,y1,z1, x2,y2,z2, ...]
     */
    public float[] extractEdgeGeometry(Collection<ModelDefinition.ModelPart> parts,
                                      Matrix4f globalTransform) {
        return edgeExtractor.extractGeometry(parts, globalTransform);
    }

    /**
     * Extract unique edge geometry from model parts, eliminating duplicates.
     * An edge v1<->v2 is considered the same as v2<->v1.
     * For a cube: returns 12 unique edges instead of 24 face-based edges.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @param uniqueVertexPositions Array of unique vertex positions for matching
     * @return Array of unique edge endpoint positions [x1,y1,z1, x2,y2,z2, ...]
     */
    public float[] extractUniqueEdgeGeometry(Collection<ModelDefinition.ModelPart> parts,
                                              Matrix4f globalTransform,
                                              float[] uniqueVertexPositions) {
        return edgeExtractor.extractUniqueEdges(parts, globalTransform, uniqueVertexPositions);
    }

    /**
     * Extract face geometry from model parts with transformation applied.
     * Centralizes face extraction through MeshManager.
     * Each face is represented as 4 vertices (quad corners) with 12 floats per face.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of face vertex positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
     */
    public float[] extractFaceGeometry(Collection<ModelDefinition.ModelPart> parts,
                                      Matrix4f globalTransform) {
        return faceExtractor.extractGeometry(parts, globalTransform);
    }

    /**
     * Cleans up all mesh resources.
     */
    public void cleanup() {
        clearMeshData();
        logger.debug("MeshManager cleaned up");
    }
}
