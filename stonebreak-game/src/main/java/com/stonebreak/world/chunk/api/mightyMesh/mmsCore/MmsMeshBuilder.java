package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import com.stonebreak.world.chunk.api.commonChunkOperations.core.CcoChunkData;

import java.util.ArrayList;
import java.util.List;

/**
 * Mighty Mesh System - Fluent builder for creating MmsMeshData.
 *
 * Provides a clean, type-safe API for incrementally building mesh data.
 * Automatically handles array resizing and validation.
 *
 * Design Philosophy:
 * - KISS: Simple, intuitive API
 * - Type-safe: Compile-time safety where possible
 * - Efficient: Minimal allocations, smart array growth
 * - Fail-fast: Validation on build()
 *
 * Usage Example:
 * <pre>{@code
 * MmsMeshData mesh = MmsMeshBuilder.create()
 *     .beginFace()
 *         .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
 *         .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
 *         .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
 *         .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
 *     .endFace()
 *     .build();
 * }</pre>
 *
 * @since MMS 1.0
 */
public final class MmsMeshBuilder {

    // Dynamic arrays with capacity management
    private List<Float> positions;
    private List<Float> texCoords;
    private List<Float> normals;
    private List<Float> waterFlags;
    private List<Float> alphaFlags;
    private List<Integer> indices;

    // Face building state
    private int currentFaceVertexStart;
    private int totalVertices;

    // Configuration
    private boolean validateOnBuild = true;
    private int initialCapacity;

    /**
     * Creates a new mesh builder with default capacity.
     */
    private MmsMeshBuilder() {
        this(256); // Default capacity for ~10 faces
    }

    /**
     * Creates a new mesh builder with specified initial capacity.
     *
     * @param initialCapacity Initial capacity hint (number of vertices)
     */
    private MmsMeshBuilder(int initialCapacity) {
        this.initialCapacity = initialCapacity;
        this.positions = new ArrayList<>(initialCapacity * MmsBufferLayout.POSITION_SIZE);
        this.texCoords = new ArrayList<>(initialCapacity * MmsBufferLayout.TEXTURE_SIZE);
        this.normals = new ArrayList<>(initialCapacity * MmsBufferLayout.NORMAL_SIZE);
        this.waterFlags = new ArrayList<>(initialCapacity);
        this.alphaFlags = new ArrayList<>(initialCapacity);
        this.indices = new ArrayList<>(initialCapacity * 2); // Estimate 1.5 indices per vertex
        this.currentFaceVertexStart = -1;
        this.totalVertices = 0;
    }

    /**
     * Creates a new mesh builder.
     *
     * @return New mesh builder instance
     */
    public static MmsMeshBuilder create() {
        return new MmsMeshBuilder();
    }

    /**
     * Creates a new mesh builder with capacity hint.
     *
     * @param estimatedVertexCount Estimated number of vertices
     * @return New mesh builder instance
     */
    public static MmsMeshBuilder createWithCapacity(int estimatedVertexCount) {
        return new MmsMeshBuilder(estimatedVertexCount);
    }

    /**
     * Enables or disables validation when building.
     *
     * @param validate true to validate on build
     * @return this builder for chaining
     */
    public MmsMeshBuilder setValidateOnBuild(boolean validate) {
        this.validateOnBuild = validate;
        return this;
    }

    /**
     * Begins building a quad face (4 vertices).
     * Call addVertex() 4 times, then endFace().
     *
     * @return this builder for chaining
     */
    public MmsMeshBuilder beginFace() {
        currentFaceVertexStart = totalVertices;
        return this;
    }

    /**
     * Adds a vertex with all attributes.
     *
     * @param x Position X
     * @param y Position Y
     * @param z Position Z
     * @param u Texture U
     * @param v Texture V
     * @param nx Normal X
     * @param ny Normal Y
     * @param nz Normal Z
     * @param waterFlag Water height flag
     * @param alphaFlag Alpha test flag
     * @return this builder for chaining
     */
    public MmsMeshBuilder addVertex(float x, float y, float z,
                                     float u, float v,
                                     float nx, float ny, float nz,
                                     float waterFlag, float alphaFlag) {
        // Debug: Log first few vertices with texture coordinates
        if (totalVertices < 3) {
            System.out.println("[MmsMeshBuilder] Adding vertex " + totalVertices +
                ": pos=(" + x + "," + y + "," + z + ") tex=(" + u + "," + v + ") water=" + waterFlag + " alpha=" + alphaFlag);
        }

        // Add position
        positions.add(x);
        positions.add(y);
        positions.add(z);

        // Add texture coordinates
        texCoords.add(u);
        texCoords.add(v);

        // Add normal
        normals.add(nx);
        normals.add(ny);
        normals.add(nz);

        // Add flags
        waterFlags.add(waterFlag);
        alphaFlags.add(alphaFlag);

        totalVertices++;
        return this;
    }

    /**
     * Ends a quad face and generates triangle indices.
     * Must be called after adding exactly 4 vertices.
     *
     * @return this builder for chaining
     * @throws IllegalStateException if not exactly 4 vertices were added
     */
    public MmsMeshBuilder endFace() {
        if (currentFaceVertexStart < 0) {
            throw new IllegalStateException("beginFace() must be called before endFace()");
        }

        int verticesInFace = totalVertices - currentFaceVertexStart;
        if (verticesInFace != MmsBufferLayout.VERTICES_PER_QUAD) {
            throw new IllegalStateException(
                String.format("Expected %d vertices in face, got %d",
                    MmsBufferLayout.VERTICES_PER_QUAD, verticesInFace)
            );
        }

        // Generate quad indices (2 triangles)
        // Triangle 1: v0, v1, v2
        indices.add(currentFaceVertexStart);
        indices.add(currentFaceVertexStart + 1);
        indices.add(currentFaceVertexStart + 2);

        // Triangle 2: v0, v2, v3
        indices.add(currentFaceVertexStart);
        indices.add(currentFaceVertexStart + 2);
        indices.add(currentFaceVertexStart + 3);

        currentFaceVertexStart = -1;
        return this;
    }

    /**
     * Adds a custom index to the mesh.
     * This is useful for cross-section blocks that need custom index patterns.
     *
     * @param index Vertex index to add
     * @return this builder for chaining
     */
    public MmsMeshBuilder addIndex(int index) {
        indices.add(index);
        return this;
    }

    /**
     * Adds a pre-built quad face with all vertex data.
     * This is more efficient than using beginFace()/addVertex()/endFace().
     *
     * @param faceData Array containing all vertex data for 4 vertices
     *                 (each vertex: x,y,z,u,v,nx,ny,nz,water,alpha = 10 floats)
     * @return this builder for chaining
     */
    public MmsMeshBuilder addQuadFace(float[] faceData) {
        if (faceData.length != MmsBufferLayout.VERTICES_PER_QUAD * MmsBufferLayout.VERTEX_SIZE) {
            throw new IllegalArgumentException(
                String.format("Expected %d floats for quad face, got %d",
                    MmsBufferLayout.VERTICES_PER_QUAD * MmsBufferLayout.VERTEX_SIZE, faceData.length)
            );
        }

        int baseVertex = totalVertices;

        // Parse and add all 4 vertices
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            int offset = i * MmsBufferLayout.VERTEX_SIZE;

            // Position
            positions.add(faceData[offset]);
            positions.add(faceData[offset + 1]);
            positions.add(faceData[offset + 2]);

            // Texture
            texCoords.add(faceData[offset + 3]);
            texCoords.add(faceData[offset + 4]);

            // Normal
            normals.add(faceData[offset + 5]);
            normals.add(faceData[offset + 6]);
            normals.add(faceData[offset + 7]);

            // Flags
            waterFlags.add(faceData[offset + 8]);
            alphaFlags.add(faceData[offset + 9]);

            totalVertices++;
        }

        // Add indices for 2 triangles
        indices.add(baseVertex);
        indices.add(baseVertex + 1);
        indices.add(baseVertex + 2);

        indices.add(baseVertex);
        indices.add(baseVertex + 2);
        indices.add(baseVertex + 3);

        return this;
    }

    /**
     * Resets the builder to empty state, reusing allocated arrays.
     *
     * @return this builder for chaining
     */
    public MmsMeshBuilder reset() {
        positions.clear();
        texCoords.clear();
        normals.clear();
        waterFlags.clear();
        alphaFlags.clear();
        indices.clear();
        currentFaceVertexStart = -1;
        totalVertices = 0;
        return this;
    }

    /**
     * Gets the current number of vertices.
     *
     * @return Vertex count
     */
    public int getVertexCount() {
        return totalVertices;
    }

    /**
     * Gets the current number of indices.
     *
     * @return Index count
     */
    public int getIndexCount() {
        return indices.size();
    }

    /**
     * Checks if the mesh is currently empty.
     *
     * @return true if no vertices have been added
     */
    public boolean isEmpty() {
        return totalVertices == 0;
    }

    /**
     * Builds the final immutable mesh data.
     *
     * @return Immutable MmsMeshData
     * @throws IllegalStateException if beginFace() called without endFace()
     * @throws IllegalArgumentException if validation fails
     */
    public MmsMeshData build() {
        // Check for incomplete face
        if (currentFaceVertexStart >= 0) {
            throw new IllegalStateException("Incomplete face: beginFace() called without endFace()");
        }

        // Handle empty mesh
        if (isEmpty()) {
            return MmsMeshData.empty();
        }

        // Convert lists to arrays
        float[] posArray = toFloatArray(positions);
        float[] texArray = toFloatArray(texCoords);
        float[] normArray = toFloatArray(normals);
        float[] waterArray = toFloatArray(waterFlags);
        float[] alphaArray = toFloatArray(alphaFlags);
        int[] indexArray = toIntArray(indices);

        // Create mesh data
        MmsMeshData meshData = new MmsMeshData(
            posArray, texArray, normArray, waterArray, alphaArray, indexArray, indexArray.length
        );

        // Validate if enabled
        if (validateOnBuild) {
            MmsMeshValidator.ValidationResult result = MmsMeshValidator.validate(meshData);
            result.throwIfInvalid();
        }

        return meshData;
    }

    /**
     * Builds the mesh and resets the builder for reuse.
     *
     * @return Immutable MmsMeshData
     */
    public MmsMeshData buildAndReset() {
        MmsMeshData mesh = build();
        reset();
        return mesh;
    }

    // === Helper Methods ===

    /**
     * Converts Float list to primitive float array.
     */
    private static float[] toFloatArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * Converts Integer list to primitive int array.
     */
    private static int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
