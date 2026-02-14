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
 *   <li>Face by ID with cached normal, centroid, and area</li>
 *   <li>Vertex smooth normals (area-weighted average of adjacent face normals)</li>
 *   <li>Edges connected to a vertex</li>
 *   <li>Faces adjacent to a vertex</li>
 *   <li>Faces adjacent to a face (sharing an edge)</li>
 *   <li>Shared edge between two adjacent faces</li>
 *   <li>Uniform vs mixed topology detection</li>
 * </ul>
 *
 * <p>All data is immutable after construction. Thread-safe for read access.
 */
public class MeshTopology {

    private final MeshEdge[] edges;
    private final MeshFace[] faces;
    private final Vector3f[] faceNormals;
    private final Vector3f[] faceCentroids;
    private final float[] faceAreas;
    private final Vector3f[] vertexNormals;       // per-unique-vertex smooth normals
    private final Map<Long, Integer> edgeKeyToId;
    private final List<List<Integer>> vertexToEdges;
    private final List<List<Integer>> vertexToFaces;
    private final List<List<Integer>> faceToAdjacentFaces;
    private final Map<Long, Integer> facePairToEdgeId;
    private final boolean uniformTopology;
    private final int uniformVerticesPerFace;

    // Embedded mapper data (consolidated from UniqueVertexMapper + TriangleFaceMapper)
    private final int[] meshToUniqueMapping;      // meshIdx → uniqueIdx
    private final int[][] uniqueToMeshIndices;    // uniqueIdx → meshIdx[]
    private final int uniqueVertexCount;
    private final int[] triangleToFaceId;         // triIdx → faceId
    private final int triangleCount;

    // Dihedral angles (per-edge, radians)
    private final float[] dihedralAngles;

    // Lazy recomputation on vertex move
    private final boolean[] faceDirty;
    private final boolean[] vertexNormalDirty;
    private final boolean[] edgeDirty;
    private float[] verticesRef;

    /**
     * Package-private constructor used by MeshTopologyBuilder.
     */
    MeshTopology(MeshEdge[] edges, MeshFace[] faces,
                 Vector3f[] faceNormals, Vector3f[] faceCentroids, float[] faceAreas,
                 Vector3f[] vertexNormals,
                 Map<Long, Integer> edgeKeyToId,
                 List<List<Integer>> vertexToEdges,
                 List<List<Integer>> vertexToFaces,
                 List<List<Integer>> faceToAdjacentFaces,
                 Map<Long, Integer> facePairToEdgeId,
                 boolean uniformTopology, int uniformVerticesPerFace,
                 int[] meshToUniqueMapping, int[][] uniqueToMeshIndices,
                 int uniqueVertexCount,
                 int[] triangleToFaceId, int triangleCount) {
        this.edges = edges;
        this.faces = faces;
        this.faceNormals = faceNormals;
        this.faceCentroids = faceCentroids;
        this.faceAreas = faceAreas;
        this.vertexNormals = vertexNormals;
        this.edgeKeyToId = edgeKeyToId;
        this.vertexToEdges = vertexToEdges;
        this.vertexToFaces = vertexToFaces;
        this.faceToAdjacentFaces = faceToAdjacentFaces;
        this.facePairToEdgeId = facePairToEdgeId;
        this.uniformTopology = uniformTopology;
        this.uniformVerticesPerFace = uniformVerticesPerFace;
        this.meshToUniqueMapping = meshToUniqueMapping;
        this.uniqueToMeshIndices = uniqueToMeshIndices;
        this.uniqueVertexCount = uniqueVertexCount;
        this.triangleToFaceId = triangleToFaceId;
        this.triangleCount = triangleCount;
        this.faceDirty = new boolean[faces.length];
        this.vertexNormalDirty = new boolean[uniqueVertexCount];
        this.edgeDirty = new boolean[edges.length];

        // Compute initial dihedral angles from precomputed face normals
        this.dihedralAngles = new float[edges.length];
        for (int i = 0; i < edges.length; i++) {
            dihedralAngles[i] = computeDihedralAngleForEdge(edges[i]);
        }
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

    /**
     * Get the dihedral angle at an edge — the angle between the normals of
     * its two adjacent faces, in radians.
     *
     * <ul>
     *   <li>{@code 0} = coplanar (normals point in the same direction)</li>
     *   <li>{@code π} = faces fold 180° apart (normals point in opposite directions)</li>
     *   <li>{@code Float.NaN} = open edge (single face) or non-manifold edge (3+ faces)</li>
     * </ul>
     *
     * <p>Lazily recomputes when adjacent face normals have been invalidated
     * by a vertex position change.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Dihedral angle in radians, or {@code Float.NaN} if undefined or out of range
     */
    public float getDihedralAngle(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return Float.NaN;
        }
        ensureEdgeClean(edgeId);
        return dihedralAngles[edgeId];
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
     * Lazily recomputes if the face was dirtied by a vertex move.
     *
     * @param faceId Face identifier
     * @return The face normal (unit vector), or null if out of range
     */
    public Vector3f getFaceNormal(int faceId) {
        if (faceId < 0 || faceId >= faceNormals.length) {
            return null;
        }
        ensureFaceClean(faceId);
        return faceNormals[faceId];
    }

    /**
     * Get the cached centroid for a face.
     * Lazily recomputes if the face was dirtied by a vertex move.
     *
     * @param faceId Face identifier
     * @return The face centroid (average of vertex positions), or null if out of range
     */
    public Vector3f getFaceCentroid(int faceId) {
        if (faceId < 0 || faceId >= faceCentroids.length) {
            return null;
        }
        ensureFaceClean(faceId);
        return faceCentroids[faceId];
    }

    /**
     * Get the cached area for a face.
     * Lazily recomputes if the face was dirtied by a vertex move.
     *
     * @param faceId Face identifier
     * @return The face area, or {@code Float.NaN} if out of range
     */
    public float getFaceArea(int faceId) {
        if (faceId < 0 || faceId >= faceAreas.length) {
            return Float.NaN;
        }
        ensureFaceClean(faceId);
        return faceAreas[faceId];
    }

    // =========================================================================
    // VERTEX NORMAL QUERIES (smooth shading)
    // =========================================================================

    /**
     * Get the smooth (area-weighted) normal for a unique vertex.
     * Computed as the normalized weighted average of adjacent face normals,
     * where each face normal is weighted by its face area. Lazily recomputes
     * if the vertex normal was dirtied by a vertex position change.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return The vertex normal (unit vector), or null if out of range
     */
    public Vector3f getVertexNormal(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexNormals.length) {
            return null;
        }
        ensureVertexNormalClean(uniqueVertexIdx);
        return vertexNormals[uniqueVertexIdx];
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

    /**
     * Get all face IDs adjacent to a given face (sharing an edge).
     *
     * @param faceId Face identifier
     * @return Unmodifiable list of neighbor face IDs, or empty list if out of range
     */
    public List<Integer> getAdjacentFaces(int faceId) {
        if (faceId < 0 || faceId >= faceToAdjacentFaces.size()) {
            return Collections.emptyList();
        }
        return faceToAdjacentFaces.get(faceId);
    }

    /**
     * Get the edge shared by two adjacent faces.
     *
     * @param faceIdA First face identifier
     * @param faceIdB Second face identifier
     * @return The shared edge, or null if the faces are not adjacent
     */
    public MeshEdge getSharedEdge(int faceIdA, int faceIdB) {
        long key = canonicalFacePairKey(faceIdA, faceIdB);
        Integer edgeId = facePairToEdgeId.get(key);
        if (edgeId == null) {
            return null;
        }
        return edges[edgeId];
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
    // VERTEX MOVE — DIRTY MARKING
    // =========================================================================

    /**
     * Mark all faces adjacent to a moved vertex as dirty.
     * Normals, centroids, areas, and vertex normals will be lazily recomputed on the next query.
     *
     * <p>Vertex normals are dirtied for every vertex on every affected face, because a face
     * normal change propagates to all vertices that reference that face in their weighted average.
     *
     * @param uniqueVertexIndex The unique vertex that moved
     * @param vertices          Current vertex positions (x,y,z interleaved)
     */
    public void onVertexPositionChanged(int uniqueVertexIndex, float[] vertices) {
        this.verticesRef = vertices;
        List<Integer> affectedFaceIds = getFacesForVertex(uniqueVertexIndex);
        for (int faceId : affectedFaceIds) {
            if (faceId >= 0 && faceId < faceDirty.length) {
                faceDirty[faceId] = true;

                MeshFace face = faces[faceId];

                // Dirty vertex normals for every vertex on this face
                for (int v : face.vertexIndices()) {
                    if (v >= 0 && v < vertexNormalDirty.length) {
                        vertexNormalDirty[v] = true;
                    }
                }

                // Dirty dihedral angles for every edge on this face
                for (int eid : face.edgeIds()) {
                    if (eid >= 0 && eid < edgeDirty.length) {
                        edgeDirty[eid] = true;
                    }
                }
            }
        }
    }

    // =========================================================================
    // LAZY RECOMPUTATION
    // =========================================================================

    /**
     * Ensure a face's cached normal, centroid, and area are up-to-date.
     * No-op if the face is already clean.
     */
    private void ensureFaceClean(int faceId) {
        if (faceDirty[faceId] && verticesRef != null) {
            recomputeFace(faceId);
            faceDirty[faceId] = false;
        }
    }

    /**
     * Recompute normal, centroid, and area for a single face.
     */
    private void recomputeFace(int faceId) {
        int[] verts = faces[faceId].vertexIndices();
        faceNormals[faceId] = computeNormal(verts, uniqueToMeshIndices, verticesRef);
        faceCentroids[faceId] = computeCentroid(verts, uniqueToMeshIndices, verticesRef);
        faceAreas[faceId] = computeArea(verts, uniqueToMeshIndices, verticesRef);
    }

    /**
     * Ensure an edge's cached dihedral angle is up-to-date.
     * Forces adjacent faces clean first (dihedral angle depends on face normals).
     * No-op if the edge is already clean.
     */
    private void ensureEdgeClean(int edgeId) {
        if (!edgeDirty[edgeId]) {
            return;
        }
        MeshEdge edge = edges[edgeId];
        for (int faceId : edge.adjacentFaceIds()) {
            ensureFaceClean(faceId);
        }
        dihedralAngles[edgeId] = computeDihedralAngleForEdge(edge);
        edgeDirty[edgeId] = false;
    }

    /**
     * Ensure a vertex's cached smooth normal is up-to-date.
     * Forces all adjacent faces clean first (vertex normal depends on face normals and areas).
     * No-op if the vertex normal is already clean.
     */
    private void ensureVertexNormalClean(int uniqueVertexIdx) {
        if (!vertexNormalDirty[uniqueVertexIdx]) {
            return;
        }
        // Ensure all adjacent faces are clean before recomputing the weighted average
        List<Integer> adjacentFaceIds = getFacesForVertex(uniqueVertexIdx);
        for (int faceId : adjacentFaceIds) {
            ensureFaceClean(faceId);
        }
        recomputeVertexNormal(uniqueVertexIdx, adjacentFaceIds);
        vertexNormalDirty[uniqueVertexIdx] = false;
    }

    /**
     * Recompute the smooth normal for a single vertex as the area-weighted average
     * of its adjacent face normals.
     */
    private void recomputeVertexNormal(int uniqueVertexIdx, List<Integer> adjacentFaceIds) {
        vertexNormals[uniqueVertexIdx] = computeVertexNormal(adjacentFaceIds, faceNormals, faceAreas);
    }

    // =========================================================================
    // STATIC GEOMETRY COMPUTATIONS
    // =========================================================================

    /**
     * Compute a canonical key for a pair of face IDs.
     * Packs the smaller ID into the high bits, the larger into the low bits.
     *
     * @param faceA First face ID
     * @param faceB Second face ID
     * @return Packed long suitable for use as a map key
     */
    static long canonicalFacePairKey(int faceA, int faceB) {
        int min = Math.min(faceA, faceB);
        int max = Math.max(faceA, faceB);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    /**
     * Compute a smooth vertex normal as the area-weighted average of adjacent face normals.
     * Larger faces contribute more to the result, which is the standard weighting approach.
     *
     * @param adjacentFaceIds Face IDs adjacent to the vertex
     * @param faceNormals     Precomputed face normals array
     * @param faceAreas       Precomputed face areas array
     * @return Normalized vertex normal, or zero vector if no valid adjacent faces
     */
    static Vector3f computeVertexNormal(List<Integer> adjacentFaceIds,
                                        Vector3f[] faceNormals, float[] faceAreas) {
        float nx = 0, ny = 0, nz = 0;

        for (int faceId : adjacentFaceIds) {
            if (faceId < 0 || faceId >= faceNormals.length) {
                continue;
            }
            Vector3f fn = faceNormals[faceId];
            float area = faceAreas[faceId];
            nx += fn.x * area;
            ny += fn.y * area;
            nz += fn.z * area;
        }

        Vector3f result = new Vector3f(nx, ny, nz);
        float len = result.length();
        if (len > 1e-8f) {
            result.div(len);
        }
        return result;
    }

    /**
     * Compute a face normal using Newell's method.
     * Works for triangles, quads, and arbitrary planar polygons.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
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

    /**
     * Compute a face centroid (average of vertex positions).
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @return Face centroid
     */
    static Vector3f computeCentroid(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float cx = 0, cy = 0, cz = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int meshIdx = uniqueToMesh[uniqueVertexIndices[i]][0];
            cx += vertices[meshIdx * 3];
            cy += vertices[meshIdx * 3 + 1];
            cz += vertices[meshIdx * 3 + 2];
        }

        return new Vector3f(cx / count, cy / count, cz / count);
    }

    /**
     * Compute a face area using the magnitude of Newell's cross product sum.
     * Works for triangles, quads, and arbitrary planar polygons.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @return Face area (half the magnitude of the Newell normal), or 0 for degenerate faces
     */
    static float computeArea(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
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

        return 0.5f * (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
    }

    /**
     * Compute the dihedral angle for a single edge from the current face normals.
     * Only defined for manifold edges (exactly 2 adjacent faces).
     *
     * @param edge The edge to compute for
     * @return Angle in radians (0..π), or {@code Float.NaN} for non-manifold/open edges
     */
    private float computeDihedralAngleForEdge(MeshEdge edge) {
        int[] adjFaces = edge.adjacentFaceIds();
        if (adjFaces.length != 2) {
            return Float.NaN;
        }
        Vector3f n0 = faceNormals[adjFaces[0]];
        Vector3f n1 = faceNormals[adjFaces[1]];
        float dot = Math.clamp(n0.dot(n1), -1.0f, 1.0f);
        return (float) Math.acos(dot);
    }
}
