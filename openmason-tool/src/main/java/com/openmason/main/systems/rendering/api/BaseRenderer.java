package com.openmason.main.systems.rendering.api;

import com.openmason.main.systems.rendering.BufferManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Abstract base class for renderers using Template Method pattern.
 * Handles common operations: VAO/VBO setup, MVP calculation, cleanup.
 * Subclasses override abstract methods for specific behavior.
 *
 * DRY Compliance:
 * - Eliminates ~40 lines of boilerplate per renderer
 * - Common OpenGL patterns extracted to final methods
 *
 * Template Method Pattern:
 * - initialize(): final, calls createGeometry() + configureVertexAttributes()
 * - render(): final, binds shader, uploads MVP, calls doRender()
 * - cleanup(): final, deletes VAO/VBO/EBO
 */
public abstract class BaseRenderer implements IRenderer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // OpenGL resources
    protected int vao = 0;
    protected int vbo = 0;
    protected int ebo = 0;
    protected int vertexCount = 0;
    protected int indexCount = 0;

    // State
    protected boolean initialized = false;
    protected boolean enabled = true;

    // Cached geometry
    protected GeometryData geometryData;

    /**
     * Template method: Initialize GPU resources.
     * Final to ensure consistent initialization pattern.
     */
    @Override
    public final void initialize() {
        if (initialized) {
            logger.debug("{} already initialized", getDebugName());
            return;
        }

        try {
            logger.debug("Initializing {}...", getDebugName());

            // Step 1: Generate VAO
            vao = glGenVertexArrays();
            glBindVertexArray(vao);

            // Step 2: Let subclass create geometry
            geometryData = createGeometry();

            if (geometryData != null && geometryData.isValid()) {
                // Step 3: Create and populate VBO
                vbo = glGenBuffers();
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferData(GL_ARRAY_BUFFER, geometryData.vertices(), GL_DYNAMIC_DRAW);

                // Step 4: Create EBO if indexed
                if (geometryData.isIndexed()) {
                    ebo = glGenBuffers();
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
                    glBufferData(GL_ELEMENT_ARRAY_BUFFER, geometryData.indices(), GL_STATIC_DRAW);
                    indexCount = geometryData.indexCount();
                }

                vertexCount = geometryData.vertexCount();

                // Step 5: Let subclass configure vertex attributes
                configureVertexAttributes();
            }

            // Step 6: Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;
            logger.info("{} initialized successfully", getDebugName());

        } catch (Exception e) {
            logger.error("Failed to initialize {}", getDebugName(), e);
            cleanup();
            throw new RuntimeException(getDebugName() + " initialization failed", e);
        }
    }

    /**
     * Template method: Execute rendering.
     * Final to ensure consistent render pattern.
     */
    @Override
    public final void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("{} not initialized", getDebugName());
            return;
        }

        if (!enabled) {
            return;
        }

        try {
            // Bind shader
            shader.use();

            // Calculate MVP matrix
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Upload MVP
            shader.setMat4("uMVPMatrix", mvpMatrix);

            // Allow subclass to set additional uniforms
            setUniforms(shader, context, modelMatrix);

            // Bind VAO
            glBindVertexArray(vao);

            // Let subclass execute draw calls
            doRender(shader, context);

            // Unbind VAO
            glBindVertexArray(0);

        } catch (Exception e) {
            logger.error("Error rendering {}", getDebugName(), e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Template method: Cleanup GPU resources.
     * Final to ensure all resources are released.
     */
    @Override
    public final void cleanup() {
        if (ebo != 0) {
            glDeleteBuffers(ebo);
            ebo = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }

        vertexCount = 0;
        indexCount = 0;
        initialized = false;
        geometryData = null;

        logger.debug("{} cleanup complete", getDebugName());
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Update the VBO with new vertex data.
     * Call this when geometry changes dynamically.
     *
     * @param vertices New vertex data
     */
    protected void updateVBO(float[] vertices) {
        if (!initialized || vbo == 0) {
            return;
        }

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Update the EBO with new index data.
     * Call this when indices change dynamically.
     *
     * @param indices New index data
     */
    protected void updateEBO(int[] indices) {
        if (!initialized) {
            return;
        }

        // Create EBO on demand if it doesn't exist yet (e.g., model loaded after init)
        if (ebo == 0) {
            glBindVertexArray(vao);
            ebo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_DYNAMIC_DRAW);
            glBindVertexArray(0);
        } else {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        indexCount = indices.length;
    }

    // ========== Abstract methods for subclasses ==========

    /**
     * Create geometry data for this renderer.
     * Called during initialization.
     *
     * @return GeometryData containing vertices and optional indices
     */
    protected abstract GeometryData createGeometry();

    /**
     * Configure vertex attributes for this renderer.
     * Called during initialization after VBO is bound.
     * Use glVertexAttribPointer and glEnableVertexAttribArray.
     */
    protected abstract void configureVertexAttributes();

    /**
     * Execute the actual draw calls.
     * Called during render() after MVP is set and VAO is bound.
     *
     * @param shader The bound shader program
     * @param context The render context
     */
    protected abstract void doRender(ShaderProgram shader, RenderContext context);

    // ========== Optional hooks for subclasses ==========

    /**
     * Set additional shader uniforms.
     * Override to add custom uniforms before rendering.
     * Default implementation does nothing.
     *
     * @param shader The shader program
     * @param context The render context
     * @param modelMatrix The model matrix
     */
    protected void setUniforms(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        // Default: no additional uniforms
    }
}
