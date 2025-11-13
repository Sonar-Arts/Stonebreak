package com.openmason.rendering.blockmodel;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renderer for BlockModel instances (editable .OMO models).
 *
 * <p>Renders simple single-cube models with texture mapping from embedded .OMT files.
 * Uses standard OpenGL vertex buffers for efficient rendering.
 *
 * <p>Cube net UV mapping (64x48 texture):
 * <pre>
 *      [TOP]
 * [LEFT][FRONT][RIGHT][BACK]
 *      [BOTTOM]
 * </pre>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>KISS: Simple cube mesh with basic texture mapping</li>
 *   <li>SOLID: Single responsibility - only renders BlockModels</li>
 *   <li>YAGNI: No complex features, just basic cube rendering</li>
 *   <li>DRY: Reuses OpenGL patterns from existing renderers</li>
 * </ul>
 *
 * @since 1.0
 */
public class BlockModelRenderer {

    private static final Logger logger = LoggerFactory.getLogger(BlockModelRenderer.class);

    // OpenGL buffer IDs
    private int vao;  // Vertex Array Object
    private int vbo;  // Vertex Buffer Object
    private int ebo;  // Element Buffer Object

    // Cube properties
    private static final int VERTICES_PER_FACE = 4;
    private static final int FACES = 6;
    private static final int TOTAL_VERTICES = VERTICES_PER_FACE * FACES;
    private static final int FLOATS_PER_VERTEX = 5; // x, y, z, u, v
    private static final int INDICES_PER_FACE = 6;  // 2 triangles * 3 vertices
    private static final int TOTAL_INDICES = INDICES_PER_FACE * FACES;

    // Current state
    private boolean initialized = false;
    private int textureId = 0;

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
        logger.debug("Texture set: {}", textureId);
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
