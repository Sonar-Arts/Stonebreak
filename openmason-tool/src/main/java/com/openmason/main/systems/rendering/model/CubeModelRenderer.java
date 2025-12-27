package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.api.BaseRenderer;
import com.openmason.main.systems.rendering.api.GeometryData;
import com.openmason.main.systems.rendering.api.RenderPass;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.miscComponents.CubeNetMeshGenerator;
import com.openmason.main.systems.rendering.model.miscComponents.FaceNormalCalculator;
import com.openmason.main.systems.rendering.model.miscComponents.FlatTextureMeshGenerator;
import com.openmason.main.systems.rendering.model.miscComponents.TextureLoadResult;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

/**
 * Specialized renderer for editable cube models (.OMO block models).
 * Extends BaseRenderer while providing cube-specific features:
 * - 8 unique vertices mapped to 24 mesh vertices (4 per face × 6 faces)
 * - UV mode switching (CUBE_NET, FLAT)
 * - Face inversion detection and winding correction
 * - Real-time vertex position updates for editing
 */
public class CubeModelRenderer extends BaseRenderer {

    // Cube properties
    private static final int FACES = 6;
    private static final int FLOATS_PER_VERTEX = 5; // x, y, z, u, v
    private static final int INDICES_PER_FACE = 6;  // 2 triangles * 3 vertices
    private static final int TOTAL_INDICES = INDICES_PER_FACE * FACES;
    private static final int VERTICES_PER_FACE = 4;
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES;

    // Current state
    private int textureId = 0;
    private boolean hasTransparency = false;
    private UVMode currentUVMode = UVMode.CUBE_NET;

    // Original face normals for inversion detection
    private Vector3f[] originalFaceNormals = null;

    // Index caching for optimization
    private int[] cachedIndices = null;
    private int cachedInversionPattern = 0;

    // Current mesh data
    private float[] currentMesh = null;

    /**
     * UV mapping mode for texture coordinates.
     */
    public enum UVMode {
        /** 64x48 cube net texture with mapped UVs for each face */
        CUBE_NET,
        /** 16x16 flat texture with simple 0-1 UVs on each face */
        FLAT
    }

    // Mapping from unique vertex indices (0-7) to mesh vertex indices
    private static final int[][] UNIQUE_TO_MESH_INDICES = {
        {5, 12, 20},  // 0: back-bottom-left
        {4, 13, 16},  // 1: back-bottom-right
        {1, 14, 17},  // 2: front-bottom-right
        {0, 15, 21},  // 3: front-bottom-left
        {6, 11, 23},  // 4: back-top-left
        {7, 10, 19},  // 5: back-top-right
        {2, 9, 18},   // 6: front-top-right
        {3, 8, 22}    // 7: front-top-left
    };

    public CubeModelRenderer() {
        logger.debug("CubeModelRenderer created");
    }

    @Override
    public String getDebugName() {
        return "CubeModelRenderer";
    }

    @Override
    public RenderPass getRenderPass() {
        return RenderPass.SCENE;
    }

    @Override
    protected GeometryData createGeometry() {
        // Generate initial cube mesh
        currentMesh = generateMeshForUVMode();
        int[] indices = CubeNetMeshGenerator.generateIndices();

        // Calculate original face normals
        calculateOriginalNormals(currentMesh);

        return GeometryData.indexed(currentMesh, indices, 24, STRIDE);
    }

    @Override
    protected void configureVertexAttributes() {
        // Position attribute (location = 0): 3 floats
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);

        // TexCoord attribute (location = 1): 2 floats
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
    }

    @Override
    protected void doRender(ShaderProgram shader, RenderContext context) {
        // Bind texture if available
        if (textureId > 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
            shader.setBool("uUseTexture", true);
            shader.setInt("uTexture", 0);
        } else {
            shader.setBool("uUseTexture", false);
        }

        // Draw cube
        glDrawElements(GL_TRIANGLES, TOTAL_INDICES, GL_UNSIGNED_INT, 0);
    }

    /**
     * Simplified render method (for compatibility with existing code).
     * Assumes shader is already bound and uniforms are set.
     */
    public void render() {
        if (!initialized) {
            logger.warn("Cannot render - CubeModelRenderer not initialized");
            return;
        }

        // Bind texture
        if (textureId > 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
        }

        // Render cube
        org.lwjgl.opengl.GL30.glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, TOTAL_INDICES, GL_UNSIGNED_INT, 0);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);
    }

    /**
     * Updates vertex positions based on 8 unique corner vertices.
     * Maps 8 unique vertices to all 24 mesh vertices (4 per face × 6 faces).
     *
     * @param uniqueVertexPositions Array of 8 unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     */
    public void updateVertexPositions(float[] uniqueVertexPositions) {
        if (!initialized) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }

        if (uniqueVertexPositions == null || uniqueVertexPositions.length != 24) {
            logger.error("Invalid unique vertex positions array: expected 24 floats, got {}",
                    uniqueVertexPositions == null ? "null" : uniqueVertexPositions.length);
            return;
        }

        try {
            // Generate fresh mesh with current UV mode
            float[] fullMesh = generateMeshForUVMode();

            // Update position coordinates for each unique vertex
            for (int uniqueIdx = 0; uniqueIdx < 8; uniqueIdx++) {
                float x = uniqueVertexPositions[uniqueIdx * 3];
                float y = uniqueVertexPositions[uniqueIdx * 3 + 1];
                float z = uniqueVertexPositions[uniqueIdx * 3 + 2];

                // Update all mesh vertices that correspond to this unique vertex
                for (int meshIdx : UNIQUE_TO_MESH_INDICES[uniqueIdx]) {
                    int offset = meshIdx * FLOATS_PER_VERTEX;
                    fullMesh[offset] = x;
                    fullMesh[offset + 1] = y;
                    fullMesh[offset + 2] = z;
                }
            }

            // Generate corrected indices with dynamic winding
            int previousPattern = cachedInversionPattern;
            int[] correctedIndices = generateCorrectedIndices(fullMesh);
            boolean indicesChanged = (previousPattern != cachedInversionPattern);

            // Upload updated mesh to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, fullMesh, GL_DYNAMIC_DRAW);

            // Only upload indices if winding changed
            if (indicesChanged && ebo != 0) {
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, correctedIndices, GL_DYNAMIC_DRAW);
                logger.trace("Uploaded new indices (inversion pattern changed)");
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            currentMesh = fullMesh;

            logger.trace("Updated CubeModelRenderer vertex positions");

        } catch (Exception e) {
            logger.error("Error updating vertex positions", e);
        }
    }

    /**
     * Sets the texture to use for rendering.
     *
     * @param textureId OpenGL texture ID
     */
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.hasTransparency = false;
        logger.debug("Texture set: {}", textureId);
    }

    /**
     * Sets the texture with transparency info.
     *
     * @param textureLoadResult the texture load result
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
     * Sets the UV mapping mode.
     *
     * @param uvMode the UV mode to use
     */
    public void setUVMode(UVMode uvMode) {
        if (uvMode == null) {
            logger.warn("Null UV mode, ignoring");
            return;
        }

        if (this.currentUVMode == uvMode) {
            return;
        }

        logger.info("Changing UV mode from {} to {}", this.currentUVMode, uvMode);
        this.currentUVMode = uvMode;

        // Invalidate cache
        cachedIndices = null;
        cachedInversionPattern = 0;

        // Regenerate vertex buffer if initialized
        if (initialized) {
            regenerateVertexBuffer();
        }
    }

    /**
     * Get current UV mode.
     */
    public UVMode getUVMode() {
        return currentUVMode;
    }

    // ========== Private helpers ==========

    private float[] generateMeshForUVMode() {
        return currentUVMode == UVMode.CUBE_NET
                ? CubeNetMeshGenerator.generateVertices()
                : FlatTextureMeshGenerator.generateVertices();
    }

    private void calculateOriginalNormals(float[] vertices) {
        originalFaceNormals = new Vector3f[FACES];

        for (int face = 0; face < FACES; face++) {
            int baseVertex = face * VERTICES_PER_FACE;
            int baseIndex = baseVertex * FLOATS_PER_VERTEX;

            Vector3f v0 = new Vector3f(vertices[baseIndex], vertices[baseIndex + 1], vertices[baseIndex + 2]);
            Vector3f v1 = new Vector3f(vertices[baseIndex + 5], vertices[baseIndex + 6], vertices[baseIndex + 7]);
            Vector3f v2 = new Vector3f(vertices[baseIndex + 10], vertices[baseIndex + 11], vertices[baseIndex + 12]);

            originalFaceNormals[face] = FaceNormalCalculator.calculateNormal(v0, v1, v2);
        }
    }

    private int[] generateCorrectedIndices(float[] meshVertices) {
        if (originalFaceNormals == null) {
            return CubeNetMeshGenerator.generateIndices();
        }

        // Calculate current inversion pattern
        int currentInversionPattern = 0;

        for (int face = 0; face < FACES; face++) {
            int baseVertex = face * VERTICES_PER_FACE;
            int v0Index = baseVertex * FLOATS_PER_VERTEX;
            int v1Index = (baseVertex + 1) * FLOATS_PER_VERTEX;
            int v2Index = (baseVertex + 2) * FLOATS_PER_VERTEX;

            Vector3f v0 = new Vector3f(meshVertices[v0Index], meshVertices[v0Index + 1], meshVertices[v0Index + 2]);
            Vector3f v1 = new Vector3f(meshVertices[v1Index], meshVertices[v1Index + 1], meshVertices[v1Index + 2]);
            Vector3f v2 = new Vector3f(meshVertices[v2Index], meshVertices[v2Index + 1], meshVertices[v2Index + 2]);

            Vector3f currentNormal = FaceNormalCalculator.calculateNormal(v0, v1, v2);

            if (FaceNormalCalculator.isFaceInverted(currentNormal, originalFaceNormals[face])) {
                currentInversionPattern |= (1 << face);
            }
        }

        // Check cache
        if (cachedIndices != null && currentInversionPattern == cachedInversionPattern) {
            return cachedIndices;
        }

        // Generate indices with corrected winding
        int[] indices = new int[TOTAL_INDICES];
        int idx = 0;

        for (int face = 0; face < FACES; face++) {
            int baseVertex = face * VERTICES_PER_FACE;
            boolean inverted = (currentInversionPattern & (1 << face)) != 0;

            if (inverted) {
                // Flipped winding
                indices[idx++] = baseVertex;
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex + 1;
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex;
                indices[idx++] = baseVertex + 3;
            } else {
                // Normal winding
                indices[idx++] = baseVertex;
                indices[idx++] = baseVertex + 1;
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex + 2;
                indices[idx++] = baseVertex + 3;
                indices[idx++] = baseVertex;
            }
        }

        cachedIndices = indices;
        cachedInversionPattern = currentInversionPattern;

        return indices;
    }

    private void regenerateVertexBuffer() {
        float[] vertices = generateMeshForUVMode();

        // Recalculate normals
        calculateOriginalNormals(vertices);

        // Update VBO
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        currentMesh = vertices;
        logger.debug("Vertex buffer regenerated for UV mode: {}", currentUVMode);
    }
}
