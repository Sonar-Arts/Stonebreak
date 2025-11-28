package com.openmason.rendering.blockmodel;

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
public class BlockModelRenderer {

    private static final Logger logger = LoggerFactory.getLogger(BlockModelRenderer.class);

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
    public BlockModelRenderer() {
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

        initialized = true;
        logger.info("BlockModelRenderer initialized successfully");
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
