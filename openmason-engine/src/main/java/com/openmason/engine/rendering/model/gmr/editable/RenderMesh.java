package com.openmason.engine.rendering.model.gmr.editable;

/**
 * Immutable render-ready mesh derived from an {@link EditableMesh} by
 * {@link RenderMeshBuilder}: one duplicated corner per face-loop vertex
 * (so every face can carry its own projected UVs), triangulated indices,
 * and exact corner↔vertex maps.
 *
 * <p>Compatibility contract with the legacy GMR API: a "mesh vertex index"
 * is a corner index here, and a "unique vertex index" is an editable vertex
 * id — {@link #cornerToVertexId} / {@link #vertexIdToCorners} are the exact
 * (epsilon-free) replacement for the legacy weld-derived mapping.
 *
 * @param vertices          Corner positions (x,y,z interleaved)
 * @param texCoords         Corner UVs (u,v interleaved)
 * @param indices           Triangle indices into corners
 * @param triangleToFaceId  Face id per triangle
 * @param cornerToVertexId  Editable vertex id per corner
 * @param vertexIdToCorners Corner indices per editable vertex id
 *                          (index = vertex id; orphan vertices have empty arrays)
 */
public record RenderMesh(
    float[] vertices,
    float[] texCoords,
    int[] indices,
    int[] triangleToFaceId,
    int[] cornerToVertexId,
    int[][] vertexIdToCorners) {

    /** Number of corners (render vertices). */
    public int cornerCount() {
        return vertices.length / 3;
    }

    /** Number of triangles. */
    public int triangleCount() {
        return indices.length / 3;
    }
}
