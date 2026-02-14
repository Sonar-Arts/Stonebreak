package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Read-only index space translations between mesh vertices, unique vertices, and triangles.
 *
 * <p>Owns the mapping arrays extracted from {@link MeshTopology}. No dirty tracking,
 * no dependencies on other sub-services.
 *
 * @see MeshTopologyBuilder
 */
public final class IndexMappingQuery {

    private final int[] meshToUniqueMapping;
    private final int[][] uniqueToMeshIndices;
    private final int uniqueVertexCount;
    private final int[] triangleToFaceId;
    private final int triangleCount;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     */
    IndexMappingQuery(int[] meshToUniqueMapping, int[][] uniqueToMeshIndices,
                      int uniqueVertexCount, int[] triangleToFaceId, int triangleCount) {
        this.meshToUniqueMapping = meshToUniqueMapping;
        this.uniqueToMeshIndices = uniqueToMeshIndices;
        this.uniqueVertexCount = uniqueVertexCount;
        this.triangleToFaceId = triangleToFaceId;
        this.triangleCount = triangleCount;
    }

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

    /** @return Number of unique geometric vertex positions */
    public int getUniqueVertexCount() {
        return uniqueVertexCount;
    }

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

    /** @return Total number of triangles in the mesh */
    public int getTriangleCount() {
        return triangleCount;
    }

    /**
     * Direct reference to the unique-to-mesh mapping array.
     * Package-private for sibling sub-services that need raw access (e.g. FaceGeometryCache).
     *
     * @return The unique-to-mesh index array (not cloned)
     */
    int[][] uniqueToMeshArray() {
        return uniqueToMeshIndices;
    }
}
