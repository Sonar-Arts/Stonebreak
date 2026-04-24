package com.openmason.engine.voxel.mms.mmsCore;

import java.util.Arrays;

/**
 * Mighty Mesh System - Fluent builder for creating MmsMeshData.
 *
 * Provides a clean, type-safe API for incrementally building mesh data.
 * Uses primitive arrays with manual growth to avoid autoboxing overhead.
 *
 * Design Philosophy:
 * - KISS: Simple, intuitive API
 * - Type-safe: Compile-time safety where possible
 * - Efficient: Zero autoboxing, minimal allocations, smart array growth
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

    // Primitive arrays with manual size tracking (no autoboxing)
    private float[] positions;
    private float[] texCoords;
    private float[] normals;
    private float[] waterFlags;
    private float[] alphaFlags;
    private float[] translucentFlags;
    private float[] lightValues;
    private int[] indices;

    // Current sizes (logical length, not capacity)
    private int posSize;
    private int texSize;
    private int normSize;
    private int waterSize;
    private int alphaSize;
    private int translucentSize;
    private int lightSize;
    private int indexSize;

    // Face building state
    private int currentFaceVertexStart;
    private int totalVertices;

    // Configuration
    private boolean validateOnBuild = true;

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
        this.positions = new float[initialCapacity * MmsBufferLayout.POSITION_SIZE];
        this.texCoords = new float[initialCapacity * MmsBufferLayout.TEXTURE_SIZE];
        this.normals = new float[initialCapacity * MmsBufferLayout.NORMAL_SIZE];
        this.waterFlags = new float[initialCapacity];
        this.alphaFlags = new float[initialCapacity];
        this.translucentFlags = new float[initialCapacity];
        this.lightValues = new float[initialCapacity];
        this.indices = new int[initialCapacity * 2]; // Estimate ~1.5 indices per vertex
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
        return addVertex(x, y, z, u, v, nx, ny, nz, waterFlag, alphaFlag, 0.0f);
    }

    /**
     * Adds a vertex with all attributes including translucent flag.
     *
     * @param translucentFlag Translucent render flag (0.0 = opaque/cutout, 1.0 = translucent blend)
     */
    public MmsMeshBuilder addVertex(float x, float y, float z,
                                     float u, float v,
                                     float nx, float ny, float nz,
                                     float waterFlag, float alphaFlag,
                                     float translucentFlag) {
        return addVertex(x, y, z, u, v, nx, ny, nz, waterFlag, alphaFlag, translucentFlag, 1.0f);
    }

    /**
     * Adds a vertex with all attributes including a per-vertex world-light value.
     *
     * @param light Per-vertex light in [0,1]; 1.0 = fully lit (default when overload omitted)
     */
    public MmsMeshBuilder addVertex(float x, float y, float z,
                                     float u, float v,
                                     float nx, float ny, float nz,
                                     float waterFlag, float alphaFlag,
                                     float translucentFlag, float light) {

        // Ensure capacity for positions (3 floats)
        int posRequired = posSize + 3;
        if (posRequired > positions.length) {
            positions = Arrays.copyOf(positions, grow(positions.length, posRequired));
        }
        positions[posSize++] = x;
        positions[posSize++] = y;
        positions[posSize++] = z;

        // Ensure capacity for texCoords (2 floats)
        int texRequired = texSize + 2;
        if (texRequired > texCoords.length) {
            texCoords = Arrays.copyOf(texCoords, grow(texCoords.length, texRequired));
        }
        texCoords[texSize++] = u;
        texCoords[texSize++] = v;

        // Ensure capacity for normals (3 floats)
        int normRequired = normSize + 3;
        if (normRequired > normals.length) {
            normals = Arrays.copyOf(normals, grow(normals.length, normRequired));
        }
        normals[normSize++] = nx;
        normals[normSize++] = ny;
        normals[normSize++] = nz;

        // Ensure capacity for flags (1 float each)
        if (waterSize >= waterFlags.length) {
            waterFlags = Arrays.copyOf(waterFlags, grow(waterFlags.length, waterSize + 1));
        }
        waterFlags[waterSize++] = waterFlag;

        if (alphaSize >= alphaFlags.length) {
            alphaFlags = Arrays.copyOf(alphaFlags, grow(alphaFlags.length, alphaSize + 1));
        }
        alphaFlags[alphaSize++] = alphaFlag;

        if (translucentSize >= translucentFlags.length) {
            translucentFlags = Arrays.copyOf(translucentFlags, grow(translucentFlags.length, translucentSize + 1));
        }
        translucentFlags[translucentSize++] = translucentFlag;

        if (lightSize >= lightValues.length) {
            lightValues = Arrays.copyOf(lightValues, grow(lightValues.length, lightSize + 1));
        }
        lightValues[lightSize++] = light;

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

        // Ensure capacity for 6 indices (2 triangles)
        int idxRequired = indexSize + 6;
        if (idxRequired > indices.length) {
            indices = Arrays.copyOf(indices, grow(indices.length, idxRequired));
        }

        // Generate quad indices (2 triangles) with correct winding
        // Triangle 1: v0, v2, v1 (reversed for outward-facing normals)
        indices[indexSize++] = currentFaceVertexStart;
        indices[indexSize++] = currentFaceVertexStart + 2;
        indices[indexSize++] = currentFaceVertexStart + 1;

        // Triangle 2: v0, v3, v2 (reversed for outward-facing normals)
        indices[indexSize++] = currentFaceVertexStart;
        indices[indexSize++] = currentFaceVertexStart + 3;
        indices[indexSize++] = currentFaceVertexStart + 2;

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
        if (indexSize >= indices.length) {
            indices = Arrays.copyOf(indices, grow(indices.length, indexSize + 1));
        }
        indices[indexSize++] = index;
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

            addVertex(
                faceData[offset], faceData[offset + 1], faceData[offset + 2],
                faceData[offset + 3], faceData[offset + 4],
                faceData[offset + 5], faceData[offset + 6], faceData[offset + 7],
                faceData[offset + 8], faceData[offset + 9], faceData[offset + 10],
                faceData[offset + 11]
            );
        }

        // Add indices for 2 triangles
        int idxRequired = indexSize + 6;
        if (idxRequired > indices.length) {
            indices = Arrays.copyOf(indices, grow(indices.length, idxRequired));
        }
        indices[indexSize++] = baseVertex;
        indices[indexSize++] = baseVertex + 1;
        indices[indexSize++] = baseVertex + 2;

        indices[indexSize++] = baseVertex;
        indices[indexSize++] = baseVertex + 2;
        indices[indexSize++] = baseVertex + 3;

        return this;
    }

    /**
     * Resets the builder to empty state, reusing allocated arrays.
     *
     * @return this builder for chaining
     */
    public MmsMeshBuilder reset() {
        posSize = 0;
        texSize = 0;
        normSize = 0;
        waterSize = 0;
        alphaSize = 0;
        translucentSize = 0;
        lightSize = 0;
        indexSize = 0;
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
        return indexSize;
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

        // Trim arrays to exact size (no boxing - just array copy)
        float[] posArray = Arrays.copyOf(positions, posSize);
        float[] texArray = Arrays.copyOf(texCoords, texSize);
        float[] normArray = Arrays.copyOf(normals, normSize);
        float[] waterArray = Arrays.copyOf(waterFlags, waterSize);
        float[] alphaArray = Arrays.copyOf(alphaFlags, alphaSize);
        float[] translucentArray = Arrays.copyOf(translucentFlags, translucentSize);
        float[] lightArray = Arrays.copyOf(lightValues, lightSize);
        int[] indexArray = Arrays.copyOf(indices, indexSize);

        // Create mesh data
        MmsMeshData meshData = new MmsMeshData(
            posArray, texArray, normArray, waterArray, alphaArray, translucentArray, lightArray,
            indexArray, indexArray.length
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
     * Computes new capacity using 1.5x growth factor, ensuring at least minRequired.
     */
    private static int grow(int currentCapacity, int minRequired) {
        int newCapacity = currentCapacity + (currentCapacity >> 1); // 1.5x growth
        if (newCapacity < minRequired) {
            newCapacity = minRequired;
        }
        return newCapacity;
    }
}
