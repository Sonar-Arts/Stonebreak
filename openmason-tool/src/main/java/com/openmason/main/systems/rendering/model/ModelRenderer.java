package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.model.miscComponents.CubeNetMeshGenerator;
import com.openmason.main.systems.rendering.model.miscComponents.FaceNormalCalculator;
import com.openmason.main.systems.rendering.model.miscComponents.FlatTextureMeshGenerator;
import com.openmason.main.systems.rendering.model.miscComponents.TextureLoadResult;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renderer for BlockModel instances (editable .OMO models).
 */
public class ModelRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ModelRenderer.class);

    // OpenGL buffer IDs
    private int vao;  // Vertex Array Object
    private int vbo;  // Vertex Buffer Object
    private int ebo;  // Element Buffer Object

    // Cube properties
    private static final int FACES = 6;
    private static final int FLOATS_PER_VERTEX = 5; // x, y, z, u, v
    private static final int INDICES_PER_FACE = 6;  // 2 triangles * 3 vertices
    private static final int TOTAL_INDICES = INDICES_PER_FACE * FACES;

    // Current state
    private boolean initialized = false;
    private int textureId = 0;
    private boolean hasTransparency = false;
    private UVMode currentUVMode = UVMode.CUBE_NET;  // Default to cube net

    // Original face normals for inversion detection (calculated once at init)
    private Vector3f[] originalFaceNormals = null;

    /**
     * UV mapping mode for texture coordinates.
     */
    public enum UVMode {
        /** 64x48 cube net texture with mapped UVs for each face */
        CUBE_NET,
        /** 16x16 flat texture with simple 0-1 UVs on each face */
        FLAT
    }

    /**
     * Creates a new BlockModel renderer.
     */
    public ModelRenderer() {
        logger.debug("BlockModelRenderer created");
    }

    /**
     * Initialize OpenGL resources.
     * Creates vertex buffers for a unit cube centered at origin with cube net UV mapping.
     */
    public void initialize() {
        if (initialized) {
            logger.debug("BlockModelRenderer already initialized");
            return;
        }

        logger.info("Initializing BlockModelRenderer");

        // Generate buffers
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        ebo = glGenBuffers();

        // Create cube geometry using DRY-compliant generator
        float[] vertices = CubeNetMeshGenerator.generateVertices();
        int[] indices = CubeNetMeshGenerator.generateIndices();

        // Upload to GPU
        glBindVertexArray(vao);

        // Vertex buffer
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        // Position attribute (location = 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        // Texture coordinate attribute (location = 1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        // Index buffer
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        // Unbind
        glBindVertexArray(0);

        // Calculate original face normals for inversion detection
        originalFaceNormals = new Vector3f[FACES];
        int VERTICES_PER_FACE = 4;

        for (int face = 0; face < FACES; face++) {
            int baseVertex = face * VERTICES_PER_FACE;
            int baseIndex = baseVertex * FLOATS_PER_VERTEX;

            // Extract first 3 vertices of face for normal calculation
            Vector3f v0 = new Vector3f(
                    vertices[baseIndex + 0],
                    vertices[baseIndex + 1],
                    vertices[baseIndex + 2]
            );
            Vector3f v1 = new Vector3f(
                    vertices[baseIndex + 5],
                    vertices[baseIndex + 6],
                    vertices[baseIndex + 7]
            );
            Vector3f v2 = new Vector3f(
                    vertices[baseIndex + 10],
                    vertices[baseIndex + 11],
                    vertices[baseIndex + 12]
            );

            originalFaceNormals[face] = FaceNormalCalculator.calculateNormal(v0, v1, v2);
            logger.trace("Calculated original normal for face {}: ({}, {}, {})",
                    face,
                    String.format("%.3f", originalFaceNormals[face].x),
                    String.format("%.3f", originalFaceNormals[face].y),
                    String.format("%.3f", originalFaceNormals[face].z));
        }

        initialized = true;
        logger.info("BlockModelRenderer initialized successfully");
    }

    /**
     * Updates vertex positions in the cube mesh based on 8 unique corner vertices.
     * Used for live preview when dragging vertices in edit mode.
     * The 8 unique vertices are mapped to all 24 mesh vertices (4 per face × 6 faces).
     *
     * @param uniqueVertexPositions Array of 8 unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     *                              in order: back-bottom-left, back-bottom-right, front-bottom-right, front-bottom-left,
     *                                       back-top-left, back-top-right, front-top-right, front-top-left
     */
    public void updateVertexPositions(float[] uniqueVertexPositions) {
        if (!initialized) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }

        // Validate inputs
        if (uniqueVertexPositions == null || uniqueVertexPositions.length != 24) {
            logger.error("Invalid unique vertex positions array: expected 24 floats (8 vertices × 3 coords), got {}",
                    uniqueVertexPositions == null ? "null" : uniqueVertexPositions.length);
            return;
        }

        // Validate normals initialized
        if (originalFaceNormals == null || originalFaceNormals.length != FACES) {
            logger.warn("Original face normals not initialized, rendering may be incorrect");
            // Continue anyway - generateCorrectedIndices() will use default
        }

        try {
            // Generate full mesh with current UV mode
            float[] fullMesh;
            if (currentUVMode == UVMode.CUBE_NET) {
                fullMesh = CubeNetMeshGenerator.generateVertices();
            } else {
                fullMesh = FlatTextureMeshGenerator.generateVertices();
            }

            // Update only the position coordinates (x, y, z) for each vertex, keep UVs unchanged
            // Mesh format: x, y, z, u, v (5 floats per vertex)
            // 24 vertices total (4 per face × 6 faces)

            // Mapping from unique vertex indices (0-7) to mesh vertex indices
            // Based on cube topology from CubeNetMeshGenerator
            int[][] uniqueToMeshIndices = {
                // Unique vertex 0: back-bottom-left (-0.5, -0.5, -0.5)
                {5, 12, 20},  // BACK face vertex 1, BOTTOM face vertex 0, LEFT face vertex 0
                // Unique vertex 1: back-bottom-right (0.5, -0.5, -0.5)
                {4, 13, 16},  // BACK face vertex 0, BOTTOM face vertex 1, RIGHT face vertex 0
                // Unique vertex 2: front-bottom-right (0.5, -0.5, 0.5)
                {1, 14, 17},  // FRONT face vertex 1, BOTTOM face vertex 2, RIGHT face vertex 1
                // Unique vertex 3: front-bottom-left (-0.5, -0.5, 0.5)
                {0, 15, 21},  // FRONT face vertex 0, BOTTOM face vertex 3, LEFT face vertex 1
                // Unique vertex 4: back-top-left (-0.5, 0.5, -0.5)
                {6, 11, 23},  // BACK face vertex 2, TOP face vertex 3, LEFT face vertex 3
                // Unique vertex 5: back-top-right (0.5, 0.5, -0.5)
                {7, 10, 19},  // BACK face vertex 3, TOP face vertex 2, RIGHT face vertex 3
                // Unique vertex 6: front-top-right (0.5, 0.5, 0.5)
                {2, 9, 18},   // FRONT face vertex 2, TOP face vertex 1, RIGHT face vertex 2
                // Unique vertex 7: front-top-left (-0.5, 0.5, 0.5)
                {3, 8, 22}    // FRONT face vertex 3, TOP face vertex 0, LEFT face vertex 2
            };

            // Update position for each unique vertex's mesh instances
            for (int uniqueIdx = 0; uniqueIdx < 8; uniqueIdx++) {
                float x = uniqueVertexPositions[uniqueIdx * 3 + 0];
                float y = uniqueVertexPositions[uniqueIdx * 3 + 1];
                float z = uniqueVertexPositions[uniqueIdx * 3 + 2];

                // Update all mesh vertices that correspond to this unique vertex
                for (int meshIdx : uniqueToMeshIndices[uniqueIdx]) {
                    int offset = meshIdx * FLOATS_PER_VERTEX; // 5 floats per vertex
                    fullMesh[offset + 0] = x;
                    fullMesh[offset + 1] = y;
                    fullMesh[offset + 2] = z;
                    // fullMesh[offset + 3] and [offset + 4] are UVs, keep unchanged
                }
            }

            // Generate corrected indices with dynamic winding for inverted faces
            int[] correctedIndices = generateCorrectedIndices(fullMesh);

            // Upload updated mesh to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, fullMesh, GL_DYNAMIC_DRAW); // Use DYNAMIC_DRAW for editable mesh

            // Upload corrected indices to EBO
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, correctedIndices, GL_DYNAMIC_DRAW);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            // Note: Don't unbind EBO while VAO is bound (VAO tracks EBO binding)

            logger.trace("Updated BlockModelRenderer vertex positions from 8 unique vertices");

        } catch (Exception e) {
            logger.error("Error updating vertex positions", e);
        }
    }

    /**
     * Generate corrected indices with dynamic winding order based on face orientation.
     * Detects inverted faces and flips triangle winding to maintain proper rendering.
     *
     * @param meshVertices Full mesh vertices (position + UV, interleaved)
     * @return Corrected index array with flipped winding for inverted faces
     */
    private int[] generateCorrectedIndices(float[] meshVertices) {
        if (originalFaceNormals == null) {
            logger.warn("Original normals not initialized, using default indices");
            return CubeNetMeshGenerator.generateIndices();
        }

        int[] indices = new int[TOTAL_INDICES];
        int idx = 0;
        int invertedCount = 0;
        int VERTICES_PER_FACE = 4;

        for (int face = 0; face < FACES; face++) {
            int baseVertex = face * VERTICES_PER_FACE;

            // Extract first 3 vertices of this face (x, y, z only)
            int v0Index = baseVertex * FLOATS_PER_VERTEX;
            int v1Index = (baseVertex + 1) * FLOATS_PER_VERTEX;
            int v2Index = (baseVertex + 2) * FLOATS_PER_VERTEX;

            Vector3f v0 = new Vector3f(
                    meshVertices[v0Index + 0],
                    meshVertices[v0Index + 1],
                    meshVertices[v0Index + 2]
            );
            Vector3f v1 = new Vector3f(
                    meshVertices[v1Index + 0],
                    meshVertices[v1Index + 1],
                    meshVertices[v1Index + 2]
            );
            Vector3f v2 = new Vector3f(
                    meshVertices[v2Index + 0],
                    meshVertices[v2Index + 1],
                    meshVertices[v2Index + 2]
            );

            // Calculate current face normal
            Vector3f currentNormal = FaceNormalCalculator.calculateNormal(v0, v1, v2);

            // Check if face is inverted
            boolean inverted = FaceNormalCalculator.isFaceInverted(currentNormal, originalFaceNormals[face]);

            if (inverted) {
                invertedCount++;
                // INVERTED: Flip winding order (CCW → CW)
                // Triangle 1: 0, 2, 1 (instead of 0, 1, 2)
                indices[idx++] = baseVertex + 0;
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex + 1;

                // Triangle 2: 2, 0, 3 (instead of 2, 3, 0)
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex + 0;
                indices[idx++] = baseVertex + 3;
            } else {
                // NORMAL: Standard CCW winding
                // Triangle 1: 0, 1, 2
                indices[idx++] = baseVertex + 0;
                indices[idx++] = baseVertex + 1;
                indices[idx++] = baseVertex + 2;

                // Triangle 2: 2, 3, 0
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex + 3;
                indices[idx++] = baseVertex + 0;
            }
        }

        if (invertedCount > 0) {
            logger.debug("Generated corrected indices: {} of {} faces inverted", invertedCount, FACES);
        }

        return indices;
    }

    /**
     * Sets the texture to use for rendering.
     *
     * @param textureId OpenGL texture ID
     */
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.hasTransparency = false; // Default to opaque for legacy calls
        logger.debug("Texture set: {}", textureId);
    }

    /**
     * Sets the texture to use for rendering along with transparency info.
     *
     * @param textureLoadResult the texture load result containing ID and transparency info
     */
    public void setTexture(TextureLoadResult textureLoadResult) {
        if (textureLoadResult == null || !textureLoadResult.isSuccess()) {
            logger.warn("Invalid texture load result, clearing texture");
            this.textureId = 0;
            this.hasTransparency = false;
            return;
        }

        this.textureId = textureLoadResult.getTextureId();
        this.hasTransparency = textureLoadResult.hasTransparency();
        logger.debug("Texture set: {} (transparency: {})", textureId, hasTransparency);
    }

    /**
     * Sets the UV mapping mode and regenerates vertex buffer if needed.
     *
     * @param uvMode the UV mode to use (CUBE_NET or FLAT)
     */
    public void setUVMode(UVMode uvMode) {
        if (uvMode == null) {
            logger.warn("Null UV mode, ignoring");
            return;
        }

        if (this.currentUVMode == uvMode) {
            logger.debug("UV mode already set to {}", uvMode);
            return;
        }

        logger.info("Changing UV mode from {} to {}", this.currentUVMode, uvMode);
        this.currentUVMode = uvMode;

        // Regenerate vertex buffer if initialized
        if (initialized) {
            regenerateVertexBuffer();
        }
    }

    /**
     * Regenerates the vertex buffer with the current UV mode.
     */
    private void regenerateVertexBuffer() {
        logger.debug("Regenerating vertex buffer with UV mode: {}", currentUVMode);

        // Generate vertices based on UV mode
        float[] vertices;
        if (currentUVMode == UVMode.CUBE_NET) {
            vertices = CubeNetMeshGenerator.generateVertices();
        } else {
            vertices = FlatTextureMeshGenerator.generateVertices();
        }

        // CRITICAL: Recalculate original normals for new vertex layout
        // Different UV modes may have different vertex orderings
        if (originalFaceNormals != null) {
            int VERTICES_PER_FACE = 4;

            for (int face = 0; face < FACES; face++) {
                int baseVertex = face * VERTICES_PER_FACE;
                int baseIndex = baseVertex * FLOATS_PER_VERTEX;

                Vector3f v0 = new Vector3f(
                        vertices[baseIndex + 0],
                        vertices[baseIndex + 1],
                        vertices[baseIndex + 2]
                );
                Vector3f v1 = new Vector3f(
                        vertices[baseIndex + 5],
                        vertices[baseIndex + 6],
                        vertices[baseIndex + 7]
                );
                Vector3f v2 = new Vector3f(
                        vertices[baseIndex + 10],
                        vertices[baseIndex + 11],
                        vertices[baseIndex + 12]
                );

                originalFaceNormals[face] = FaceNormalCalculator.calculateNormal(v0, v1, v2);
            }
            logger.debug("Recalculated original normals for UV mode: {}", currentUVMode);
        }

        // Update vertex buffer
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Vertex buffer regenerated successfully");
    }

    /**
     * Renders the cube with the current texture.
     * Assumes shader is already bound and uniforms are set.
     */
    public void render() {
        if (!initialized) {
            logger.warn("Cannot render - BlockModelRenderer not initialized");
            return;
        }

        // Bind texture
        if (textureId > 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
        }

        // Render cube
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, TOTAL_INDICES, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        if (!initialized) {
            return;
        }

        logger.info("Cleaning up BlockModelRenderer");

        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);

        vao = 0;
        vbo = 0;
        ebo = 0;
        originalFaceNormals = null;
        initialized = false;

        logger.info("BlockModelRenderer cleaned up");
    }

    /**
     * Checks if the renderer is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
}
