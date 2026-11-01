package com.openmason.engine.format.mesh;

/**
 * Read-only mesh data extracted from an OMO/SBO file.
 * Engine-level data structure — no editor dependencies.
 *
 * @param vertices          vertex positions (x,y,z interleaved)
 * @param texCoords         texture coordinates (u,v interleaved)
 * @param indices           triangle indices
 * @param triangleToFaceId  maps each triangle to its original face ID
 * @param uvMode            UV mapping mode ("FLAT" or "CUBE_NET"), may be null
 */
public record ParsedMeshData(
        float[] vertices,
        float[] texCoords,
        int[] indices,
        int[] triangleToFaceId,
        String uvMode
) {
    public int getVertexCount() {
        return vertices != null ? vertices.length / 3 : 0;
    }

    public int getTriangleCount() {
        return indices != null ? indices.length / 3 : 0;
    }

    public boolean hasGeometry() {
        return vertices != null && vertices.length > 0;
    }
}
