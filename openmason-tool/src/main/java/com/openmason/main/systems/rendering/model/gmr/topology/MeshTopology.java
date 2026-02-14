package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lightweight topology index providing O(1) adjacency queries.
 * Wraps existing flat vertex/index arrays without replacing them.
 *
 * <p>Built by {@link MeshTopologyBuilder} from GMR's flat arrays, this class
 * provides efficient lookups for:
 * <ul>
 *   <li>Edge by ID or vertex pair</li>
 *   <li>Face by ID</li>
 *   <li>Edges connected to a vertex</li>
 *   <li>Faces adjacent to a vertex</li>
 *   <li>Uniform vs mixed topology detection</li>
 * </ul>
 *
 * <p>All data is immutable after construction. Thread-safe for read access.
 */
public class MeshTopology {

    private final MeshEdge[] edges;
    private final MeshFace[] faces;
    private final Vector3f[] faceNormals;
    private final Map<Long, Integer> edgeKeyToId;
    private final List<List<Integer>> vertexToEdges;
    private final List<List<Integer>> vertexToFaces;
    private final boolean uniformTopology;
    private final int uniformVerticesPerFace;

    // Embedded mapper data (consolidated from UniqueVertexMapper + TriangleFaceMapper)
    private final int[] meshToUniqueMapping;      // meshIdx → uniqueIdx
    private final int[][] uniqueToMeshIndices;    // uniqueIdx → meshIdx[]
    private final int uniqueVertexCount;
    private final int[] triangleToFaceId;         // triIdx → faceId
    private final int triangleCount;

    /**
     * Package-private constructor used by MeshTopologyBuilder.
     */
    MeshTopology(MeshEdge[] edges, MeshFace[] faces, Vector3f[] faceNormals,
                 Map<Long, Integer> edgeKeyToId,
                 List<List<Integer>> vertexToEdges,
                 List<List<Integer>> vertexToFaces,
                 boolean uniformTopology, int uniformVerticesPerFace,
                 int[] meshToUniqueMapping, int[][] uniqueToMeshIndices,
                 int uniqueVertexCount,
                 int[] triangleToFaceId, int triangleCount) {
        this.edges = edges;
        this.faces = faces;
        this.faceNormals = faceNormals;
        this.edgeKeyToId = edgeKeyToId;
        this.vertexToEdges = vertexToEdges;
        this.vertexToFaces = vertexToFaces;
        this.uniformTopology = uniformTopology;
        this.uniformVerticesPerFace = uniformVerticesPerFace;
        this.meshToUniqueMapping = meshToUniqueMapping;
        this.uniqueToMeshIndices = uniqueToMeshIndices;
        this.uniqueVertexCount = uniqueVertexCount;
        this.triangleToFaceId = triangleToFaceId;
        this.triangleCount = triangleCount;
    }

    // =========================================================================
    // EDGE QUERIES
    // =========================================================================

    /**
     * Get an edge by its stable ID.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return The edge, or null if out of range
     */
    public MeshEdge getEdge(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return null;
        }
        return edges[edgeId];
    }

    /**
     * Get an edge by its two vertex endpoints (O(1) via hash map).
     * Order of v0/v1 does not matter; canonical ordering is applied internally.
     *
     * @param v0 First vertex index
     * @param v1 Second vertex index
     * @return The edge, or null if no edge connects these vertices
     */
    public MeshEdge getEdgeByVertices(int v0, int v1) {
        long key = MeshEdge.canonicalKey(v0, v1);
        Integer id = edgeKeyToId.get(key);
        if (id == null) {
            return null;
        }
        return edges[id];
    }

    /**
     * Get the total number of edges.
     *
     * @return Edge count
     */
    public int getEdgeCount() {
        return edges.length;
    }

    // =========================================================================
    // FACE QUERIES
    // =========================================================================

    /**
     * Get a face by its ID.
     *
     * @param faceId Face identifier
     * @return The face, or null if out of range
     */
    public MeshFace getFace(int faceId) {
        if (faceId < 0 || faceId >= faces.length) {
            return null;
        }
        return faces[faceId];
    }

    /**
     * Get the total number of faces.
     *
     * @return Face count
     */
    public int getFaceCount() {
        return faces.length;
    }

    /**
     * Get the cached normal for a face.
     *
     * @param faceId Face identifier
     * @return The face normal (unit vector), or null if out of range
     */
    public Vector3f getFaceNormal(int faceId) {
        if (faceId < 0 || faceId >= faceNormals.length) {
            return null;
        }
        return faceNormals[faceId];
    }

    // =========================================================================
    // ADJACENCY QUERIES
    // =========================================================================

    /**
     * Get all edge IDs connected to a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of edge IDs, or empty list if out of range
     */
    public List<Integer> getEdgesForVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return Collections.emptyList();
        }
        return vertexToEdges.get(uniqueVertexIdx);
    }

    /**
     * Get all face IDs adjacent to a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of face IDs, or empty list if out of range
     */
    public List<Integer> getFacesForVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToFaces.size()) {
            return Collections.emptyList();
        }
        return vertexToFaces.get(uniqueVertexIdx);
    }

    // =========================================================================
    // TOPOLOGY QUERIES
    // =========================================================================

    /**
     * Check if all faces have the same vertex count.
     *
     * @return true if topology is uniform
     */
    public boolean isUniformTopology() {
        return uniformTopology;
    }

    /**
     * Get the uniform vertex count per face (only valid when {@link #isUniformTopology()} is true).
     *
     * @return Vertices per face, or -1 if mixed topology
     */
    public int getUniformVerticesPerFace() {
        return uniformTopology ? uniformVerticesPerFace : -1;
    }

    /**
     * Get per-face vertex counts as an array.
     * Useful for mixed-topology rendering paths.
     *
     * @return Array of vertex counts per face
     */
    public int[] getVerticesPerFace() {
        int[] result = new int[faces.length];
        for (int i = 0; i < faces.length; i++) {
            result[i] = faces[i].vertexCount();
        }
        return result;
    }

    /**
     * Compute float offsets into a packed face positions array.
     * Each face's positions occupy {@code vertexCount * 3} floats.
     *
     * @return Array of float offsets (one per face)
     */
    public int[] computeFacePositionOffsets() {
        int[] offsets = new int[faces.length];
        int cumulative = 0;
        for (int i = 0; i < faces.length; i++) {
            offsets[i] = cumulative;
            cumulative += faces[i].vertexCount() * 3;
        }
        return offsets;
    }

    // =========================================================================
    // VERTEX MAPPING QUERIES (consolidated from UniqueVertexMapper)
    // =========================================================================

    /**
     * Get the unique vertex index for a given mesh vertex index.
     *
     * @param meshIdx Mesh vertex index
     * @return Unique vertex index, or -1 if out of range
     */
    public int getUniqueIndexForMeshVertex(int meshIdx) {
        if (meshToUniqueMapping == null || meshIdx < 0 || meshIdx >= meshToUniqueMapping.length) {
            return -1;
        }
        return meshToUniqueMapping[meshIdx];
    }

    /**
     * Get all mesh vertex indices that share the same unique geometric position.
     *
     * @param uniqueIdx Unique vertex index
     * @return Clone of mesh indices array, or empty array if out of range
     */
    public int[] getMeshIndicesForUniqueVertex(int uniqueIdx) {
        if (uniqueToMeshIndices == null || uniqueIdx < 0 || uniqueIdx >= uniqueToMeshIndices.length) {
            return new int[0];
        }
        return uniqueToMeshIndices[uniqueIdx].clone();
    }

    /**
     * Get the number of unique geometric vertex positions.
     *
     * @return Unique vertex count
     */
    public int getUniqueVertexCount() {
        return uniqueVertexCount;
    }

    // =========================================================================
    // TRIANGLE-FACE MAPPING QUERIES (consolidated from TriangleFaceMapper)
    // =========================================================================

    /**
     * Get the original face ID for a given triangle index.
     *
     * @param triIdx Triangle index (0-based)
     * @return Original face ID, or -1 if out of range
     */
    public int getOriginalFaceIdForTriangle(int triIdx) {
        if (triangleToFaceId == null || triIdx < 0 || triIdx >= triangleToFaceId.length) {
            return -1;
        }
        return triangleToFaceId[triIdx];
    }

    /**
     * Get the total number of triangles in the mesh.
     *
     * @return Triangle count
     */
    public int getTriangleCount() {
        return triangleCount;
    }

    // =========================================================================
    // FACE NORMAL UPDATES
    // =========================================================================

    /**
     * Recompute normals for all faces adjacent to a moved vertex.
     * Call this from {@code onVertexPositionChanged} to keep normals in sync.
     *
     * @param uniqueVertexIndex The unique vertex that moved
     * @param vertices          Current vertex positions (x,y,z interleaved)
     */
    public void recomputeAffectedFaceNormals(int uniqueVertexIndex, float[] vertices) {
        List<Integer> affectedFaceIds = getFacesForVertex(uniqueVertexIndex);
        for (int faceId : affectedFaceIds) {
            if (faceId >= 0 && faceId < faces.length) {
                faceNormals[faceId] = computeNormal(faces[faceId].vertexIndices(),
                        uniqueToMeshIndices, vertices);
            }
        }
    }

    /**
     * Compute a face normal using Newell's method.
     * Works for triangles, quads, and arbitrary planar polygons.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices            Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @return Normalized face normal, or zero vector for degenerate faces
     */
    static Vector3f computeNormal(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float normalX = 0, normalY = 0, normalZ = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int currMesh = uniqueToMesh[uniqueVertexIndices[i]][0];
            int nextMesh = uniqueToMesh[uniqueVertexIndices[(i + 1) % count]][0];

            float cx = vertices[currMesh * 3],     cy = vertices[currMesh * 3 + 1],     cz = vertices[currMesh * 3 + 2];
            float nx = vertices[nextMesh * 3],     ny = vertices[nextMesh * 3 + 1],     nz = vertices[nextMesh * 3 + 2];

            normalX += (cy - ny) * (cz + nz);
            normalY += (cz - nz) * (cx + nx);
            normalZ += (cx - nx) * (cy + ny);
        }

        Vector3f result = new Vector3f(normalX, normalY, normalZ);
        float len = result.length();
        if (len > 1e-8f) {
            result.div(len);
        }
        return result;
    }
}
