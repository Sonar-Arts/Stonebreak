package com.openmason.engine.voxel.sbo;

/**
 * Computes flat normals for SBO mesh data.
 *
 * <p>SBO meshes parsed from GMR have no normals (GMR computes them
 * procedurally in the shader via dFdx/dFdy). The MMS vertex format
 * requires per-vertex normals, so we compute flat normals from the
 * triangle geometry.
 *
 * <p>Since flat shading requires each triangle to have its own normal,
 * shared vertices are de-indexed (duplicated) so each triangle gets
 * 3 unique vertices with the same face normal.
 */
public final class SBONormalComputer {

    /**
     * Result of normal computation: de-indexed mesh with flat normals.
     *
     * @param vertices   positions (x,y,z interleaved), 3 unique vertices per triangle
     * @param normals    flat normals (nx,ny,nz interleaved), same count as vertices
     * @param texCoords  UVs (u,v interleaved), same vertex count as positions
     * @param indices    sequential indices (0,1,2, 3,4,5, ...) since vertices are de-indexed
     */
    public record ProcessedMesh(float[] vertices, float[] normals, float[] texCoords, int[] indices) {
        public int vertexCount() {
            return vertices.length / 3;
        }

        public int triangleCount() {
            return indices.length / 3;
        }
    }

    /**
     * Compute flat normals and de-index the mesh.
     *
     * @param srcVertices  original positions (x,y,z interleaved)
     * @param srcTexCoords original UVs (u,v interleaved)
     * @param srcIndices   original triangle indices
     * @return processed mesh with de-indexed vertices and flat normals
     */
    public static ProcessedMesh compute(float[] srcVertices, float[] srcTexCoords, int[] srcIndices) {
        int triangleCount = srcIndices.length / 3;
        int newVertexCount = triangleCount * 3;

        float[] vertices = new float[newVertexCount * 3];
        float[] normals = new float[newVertexCount * 3];
        float[] texCoords = new float[newVertexCount * 2];
        int[] indices = new int[newVertexCount];

        for (int tri = 0; tri < triangleCount; tri++) {
            int i0 = srcIndices[tri * 3];
            int i1 = srcIndices[tri * 3 + 1];
            int i2 = srcIndices[tri * 3 + 2];

            // Extract triangle vertex positions
            float v0x = srcVertices[i0 * 3], v0y = srcVertices[i0 * 3 + 1], v0z = srcVertices[i0 * 3 + 2];
            float v1x = srcVertices[i1 * 3], v1y = srcVertices[i1 * 3 + 1], v1z = srcVertices[i1 * 3 + 2];
            float v2x = srcVertices[i2 * 3], v2y = srcVertices[i2 * 3 + 1], v2z = srcVertices[i2 * 3 + 2];

            // Compute face normal: cross(edge2, edge1) for outward-facing normals
            // SBO models from Open Mason use clockwise winding, so we reverse
            // the cross product order to get outward-pointing normals
            float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
            float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;
            float nx = e2y * e1z - e2z * e1y;
            float ny = e2z * e1x - e2x * e1z;
            float nz = e2x * e1y - e2y * e1x;

            // Normalize
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-8f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }

            // Write de-indexed vertices
            int base = tri * 3;
            int vOff = base * 3;
            int tOff = base * 2;

            vertices[vOff]     = v0x; vertices[vOff + 1] = v0y; vertices[vOff + 2] = v0z;
            vertices[vOff + 3] = v1x; vertices[vOff + 4] = v1y; vertices[vOff + 5] = v1z;
            vertices[vOff + 6] = v2x; vertices[vOff + 7] = v2y; vertices[vOff + 8] = v2z;

            normals[vOff]     = nx; normals[vOff + 1] = ny; normals[vOff + 2] = nz;
            normals[vOff + 3] = nx; normals[vOff + 4] = ny; normals[vOff + 5] = nz;
            normals[vOff + 6] = nx; normals[vOff + 7] = ny; normals[vOff + 8] = nz;

            // Copy texture coordinates
            texCoords[tOff]     = srcTexCoords[i0 * 2];     texCoords[tOff + 1] = srcTexCoords[i0 * 2 + 1];
            texCoords[tOff + 2] = srcTexCoords[i1 * 2];     texCoords[tOff + 3] = srcTexCoords[i1 * 2 + 1];
            texCoords[tOff + 4] = srcTexCoords[i2 * 2];     texCoords[tOff + 5] = srcTexCoords[i2 * 2 + 1];

            // Sequential indices
            indices[base]     = base;
            indices[base + 1] = base + 1;
            indices[base + 2] = base + 2;
        }

        return new ProcessedMesh(vertices, normals, texCoords, indices);
    }
}
