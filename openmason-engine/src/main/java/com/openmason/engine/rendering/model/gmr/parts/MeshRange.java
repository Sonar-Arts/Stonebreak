package com.openmason.engine.rendering.model.gmr.parts;

/**
 * Defines the vertex/index/face range a part occupies in the combined mesh buffer.
 * Used to map between part-local and global vertex/face indices.
 *
 * <p>Immutable record — all fields are final and assigned at construction time.
 * A new MeshRange is created whenever the combined buffer is rebuilt.
 *
 * @param vertexStart First vertex index in the combined buffer
 * @param vertexCount Number of vertices this part owns
 * @param indexStart  First index position in the combined index buffer
 * @param indexCount  Number of indices this part owns
 * @param faceStart   First face ID this part owns
 * @param faceCount   Number of logical faces this part owns
 */
public record MeshRange(
        int vertexStart,
        int vertexCount,
        int indexStart,
        int indexCount,
        int faceStart,
        int faceCount
) {

    /**
     * Check if a global vertex index belongs to this part.
     *
     * @param globalIndex Global vertex index
     * @return true if the vertex is within this part's range
     */
    public boolean containsVertex(int globalIndex) {
        return globalIndex >= vertexStart && globalIndex < vertexStart + vertexCount;
    }

    /**
     * Check if a face ID belongs to this part.
     *
     * @param faceId Global face ID
     * @return true if the face is within this part's range
     */
    public boolean containsFace(int faceId) {
        return faceId >= faceStart && faceId < faceStart + faceCount;
    }

    /**
     * Convert a global vertex index to a part-local index.
     *
     * @param globalIndex Global vertex index
     * @return Local vertex index within this part
     * @throws IllegalArgumentException if the index is outside this part's range
     */
    public int toLocalVertex(int globalIndex) {
        if (!containsVertex(globalIndex)) {
            throw new IllegalArgumentException(
                    "Global vertex " + globalIndex + " is outside range [" + vertexStart + ", " + (vertexStart + vertexCount) + ")");
        }
        return globalIndex - vertexStart;
    }

    /**
     * Convert a part-local vertex index to a global index.
     *
     * @param localIndex Local vertex index within this part
     * @return Global vertex index in the combined buffer
     * @throws IllegalArgumentException if the local index is out of bounds
     */
    public int toGlobalVertex(int localIndex) {
        if (localIndex < 0 || localIndex >= vertexCount) {
            throw new IllegalArgumentException(
                    "Local vertex " + localIndex + " is outside range [0, " + vertexCount + ")");
        }
        return vertexStart + localIndex;
    }

    /**
     * Convert a global face ID to a part-local face ID.
     *
     * @param faceId Global face ID
     * @return Local face ID within this part
     * @throws IllegalArgumentException if the face is outside this part's range
     */
    public int toLocalFace(int faceId) {
        if (!containsFace(faceId)) {
            throw new IllegalArgumentException(
                    "Global face " + faceId + " is outside range [" + faceStart + ", " + (faceStart + faceCount) + ")");
        }
        return faceId - faceStart;
    }

    /**
     * Convert a part-local face ID to a global face ID.
     *
     * @param localFaceId Local face ID within this part
     * @return Global face ID in the combined buffer
     * @throws IllegalArgumentException if the local face ID is out of bounds
     */
    public int toGlobalFace(int localFaceId) {
        if (localFaceId < 0 || localFaceId >= faceCount) {
            throw new IllegalArgumentException(
                    "Local face " + localFaceId + " is outside range [0, " + faceCount + ")");
        }
        return faceStart + localFaceId;
    }
}
