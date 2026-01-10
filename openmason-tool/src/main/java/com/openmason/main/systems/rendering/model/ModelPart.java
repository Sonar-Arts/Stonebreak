package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.model.gmr.UVCoordinateGenerator;
import org.joml.Vector3f;

/**
 * Defines a single part of a multi-part model.
 * Not locked to cube topology - supports arbitrary geometry.
 *
 * @param name Unique identifier for this part
 * @param origin The origin point of this part (pivot for transforms)
 * @param vertices Vertex positions array (x, y, z interleaved)
 * @param texCoords Texture coordinates array (u, v interleaved)
 * @param indices Index array for indexed drawing (null for non-indexed)
 * @param trianglesPerFace Topology hint: triangles per logical face (2 for quads, 1 for triangles, null for 1:1 default)
 */
public record ModelPart(
        String name,
        Vector3f origin,
        float[] vertices,
        float[] texCoords,
        int[] indices,
        Integer trianglesPerFace
) {
    // Shared UV generator for DRY compliance
    private static final UVCoordinateGenerator UV_GENERATOR = new UVCoordinateGenerator();
    /**
     * Create a cube part with UV mapping based on the specified mode.
     *
     * @param name Part name
     * @param origin Part origin (center point)
     * @param size Size in each axis (width, height, depth)
     * @param uvMode UV mapping mode (CUBE_NET or FLAT)
     * @return A ModelPart representing a cube with appropriate UV coordinates
     */
    public static ModelPart createCube(String name, Vector3f origin, Vector3f size, UVMode uvMode) {
        if (uvMode == UVMode.FLAT) {
            return createCubeFlat(name, origin, size);
        }
        return createCubeCubeNet(name, origin, size);
    }

    /**
     * Create a cube part with proper 24-vertex format for cube net textures.
     * Uses expanded mesh format (4 vertices per face) to support proper UV mapping.
     *
     * @param name Part name
     * @param origin Part origin (center point)
     * @param size Size in each axis (width, height, depth)
     * @return A ModelPart representing a cube with cube net UV coordinates
     */
    public static ModelPart createCube(String name, Vector3f origin, Vector3f size) {
        return createCubeCubeNet(name, origin, size);
    }

    /**
     * Create a cube with cube net UV mapping (64x48 layout).
     * Face order matches ModelDefinition.ModelPart.getVerticesAtOrigin() for subdivision compatibility.
     * Cubes use quad topology: 2 triangles per face = 6 logical faces.
     */
    private static ModelPart createCubeCubeNet(String name, Vector3f origin, Vector3f size) {
        float[] vertices = createCubeVertices(size);
        float[] texCoords = UV_GENERATOR.generateCubeNetTexCoords();
        int[] indices = createCubeIndices();

        return new ModelPart(name, origin, vertices, texCoords, indices, 2); // Quad topology
    }

    /**
     * Create a cube with flat UV mapping (entire texture on each face).
     * Cubes use quad topology: 2 triangles per face = 6 logical faces.
     */
    private static ModelPart createCubeFlat(String name, Vector3f origin, Vector3f size) {
        float[] vertices = createCubeVertices(size);
        float[] texCoords = UV_GENERATOR.generateFlatTexCoords();
        int[] indices = createCubeIndices();

        return new ModelPart(name, origin, vertices, texCoords, indices, 2); // Quad topology
    }

    /**
     * Create cube vertex positions for a 24-vertex cube.
     * Face order: Front, Back, Left, Right, Top, Bottom (4 vertices each)
     */
    private static float[] createCubeVertices(Vector3f size) {
        float hw = size.x / 2.0f; // half width
        float hh = size.y / 2.0f; // half height
        float hd = size.z / 2.0f; // half depth

        return new float[] {
            // FRONT face (facing +Z) - vertices 0-3
            -hw, -hh,  hd,  // 0: bottom-left
             hw, -hh,  hd,  // 1: bottom-right
             hw,  hh,  hd,  // 2: top-right
            -hw,  hh,  hd,  // 3: top-left

            // BACK face (facing -Z) - vertices 4-7
            -hw, -hh, -hd,  // 4: bottom-left
             hw, -hh, -hd,  // 5: bottom-right
             hw,  hh, -hd,  // 6: top-right
            -hw,  hh, -hd,  // 7: top-left

            // LEFT face (facing -X) - vertices 8-11
            -hw, -hh, -hd,  // 8: bottom-back
            -hw, -hh,  hd,  // 9: bottom-front
            -hw,  hh,  hd,  // 10: top-front
            -hw,  hh, -hd,  // 11: top-back

            // RIGHT face (facing +X) - vertices 12-15
             hw, -hh, -hd,  // 12: bottom-back
             hw, -hh,  hd,  // 13: bottom-front
             hw,  hh,  hd,  // 14: top-front
             hw,  hh, -hd,  // 15: top-back

            // TOP face (facing +Y) - vertices 16-19
            -hw,  hh, -hd,  // 16: back-left
             hw,  hh, -hd,  // 17: back-right
             hw,  hh,  hd,  // 18: front-right
            -hw,  hh,  hd,  // 19: front-left

            // BOTTOM face (facing -Y) - vertices 20-23
            -hw, -hh, -hd,  // 20: back-left
             hw, -hh, -hd,  // 21: back-right
             hw, -hh,  hd,  // 22: front-right
            -hw, -hh,  hd   // 23: front-left
        };
    }

    /**
     * Create cube indices for 6 faces (2 triangles each = 36 indices).
     * Counter-clockwise winding for front-facing polygons.
     */
    private static int[] createCubeIndices() {
        int[] indices = new int[36];
        int idx = 0;
        for (int face = 0; face < 6; face++) {
            int baseVertex = face * 4;
            // Triangle 1: vertices 0, 1, 2
            indices[idx++] = baseVertex;
            indices[idx++] = baseVertex + 1;
            indices[idx++] = baseVertex + 2;
            // Triangle 2: vertices 2, 3, 0
            indices[idx++] = baseVertex + 2;
            indices[idx++] = baseVertex + 3;
            indices[idx++] = baseVertex;
        }
        return indices;
    }

    /**
     * Create a part from raw vertex data.
     * Uses 1:1 triangle-to-face mapping (arbitrary topology).
     *
     * @param name Part name
     * @param vertices Raw vertex positions
     * @param texCoords Raw texture coordinates
     * @param indices Raw indices (can be null for non-indexed)
     * @return A ModelPart with the given geometry
     */
    public static ModelPart createFromVertices(String name, float[] vertices, float[] texCoords, int[] indices) {
        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null); // Arbitrary topology
    }

    /**
     * Get vertex count.
     *
     * @return Number of vertices
     */
    public int getVertexCount() {
        return vertices != null ? vertices.length / 3 : 0;
    }

    /**
     * Get index count.
     *
     * @return Number of indices, or 0 if non-indexed
     */
    public int getIndexCount() {
        return indices != null ? indices.length : 0;
    }

    /**
     * Check if this part uses indexed drawing.
     *
     * @return true if indexed
     */
    public boolean isIndexed() {
        return indices != null && indices.length > 0;
    }

    /**
     * Get position of a specific vertex.
     *
     * @param index Vertex index
     * @return Vertex position, or null if invalid
     */
    public Vector3f getVertexPosition(int index) {
        if (vertices == null || index < 0 || index >= getVertexCount()) {
            return null;
        }
        int offset = index * 3;
        return new Vector3f(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
    }

    /**
     * Create a new ModelPart with an updated vertex position.
     * Since records are immutable, this creates a copy.
     *
     * @param index Vertex index to update
     * @param position New position
     * @return New ModelPart with updated vertex, or this if invalid
     */
    public ModelPart withUpdatedVertex(int index, Vector3f position) {
        if (vertices == null || index < 0 || index >= getVertexCount()) {
            return this;
        }

        float[] newVertices = vertices.clone();
        int offset = index * 3;
        newVertices[offset] = position.x;
        newVertices[offset + 1] = position.y;
        newVertices[offset + 2] = position.z;

        return new ModelPart(name, origin, newVertices, texCoords, indices, trianglesPerFace);
    }
}
