package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Read-only topology structure metadata queries.
 *
 * <p>Reports whether all faces have the same vertex count (uniform topology),
 * computes per-face vertex counts, and packed position offsets.
 * No dirty tracking, no dependencies on other sub-services.
 *
 * @see MeshTopologyBuilder
 */
public final class TopologyMetadataQuery {

    private final boolean uniformTopology;
    private final int uniformVerticesPerFace;
    private final MeshFace[] faces;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     */
    TopologyMetadataQuery(boolean uniformTopology, int uniformVerticesPerFace, MeshFace[] faces) {
        this.uniformTopology = uniformTopology;
        this.uniformVerticesPerFace = uniformVerticesPerFace;
        this.faces = faces;
    }

    /** @return true if all faces have the same vertex count */
    public boolean isUniformTopology() {
        return uniformTopology;
    }

    /** @return Vertices per face if uniform, or -1 if mixed */
    public int getUniformVerticesPerFace() {
        return uniformTopology ? uniformVerticesPerFace : -1;
    }

    /** @return Array of vertex counts per face */
    public int[] getVerticesPerFace() {
        int[] result = new int[faces.length];
        for (int i = 0; i < faces.length; i++) {
            result[i] = faces[i].vertexCount();
        }
        return result;
    }

    /** @return Array of float offsets into a packed face positions array */
    public int[] computeFacePositionOffsets() {
        int[] offsets = new int[faces.length];
        int cumulative = 0;
        for (int i = 0; i < faces.length; i++) {
            offsets[i] = cumulative;
            cumulative += faces[i].vertexCount() * 3;
        }
        return offsets;
    }
}
