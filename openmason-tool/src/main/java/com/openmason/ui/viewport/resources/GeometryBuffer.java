package com.openmason.ui.viewport.resources;

import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Wrapper for OpenGL VAO/VBO geometry buffer.
 * Manages vertex data and attribute configuration.
 * Follows RAII pattern for automatic resource cleanup.
 */
public class GeometryBuffer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GeometryBuffer.class);

    private final String name;
    private int vaoId = -1;
    private int vboId = -1;
    private int vertexCount = 0;
    private boolean initialized = false;

    /**
     * Create named geometry buffer.
     */
    public GeometryBuffer(String name) {
        this.name = name;
    }

    /**
     * Upload vertex data as vec3 positions (x, y, z).
     * Configures vertex attribute pointer for location 0.
     */
    public void uploadPositionData(float[] vertices) {
        if (initialized) {
            logger.warn("Geometry buffer '{}' already initialized, recreating...", name);
            cleanup();
        }

        try {
            // Create VAO and VBO
            vaoId = glGenVertexArrays();
            vboId = glGenBuffers();

            if (vaoId == 0 || vboId == 0) {
                throw new RuntimeException("Failed to generate VAO/VBO for: " + name);
            }

            glBindVertexArray(vaoId);

            // Upload vertex data
            FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();

            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);

            // Configure position attribute (location 0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

            // Unbind
            glBindVertexArray(0);

            this.vertexCount = vertices.length / 3;
            this.initialized = true;

            logger.debug("Geometry buffer '{}' created: VAO={}, VBO={}, vertices={}",
                        name, vaoId, vboId, vertexCount);

        } catch (Exception e) {
            logger.error("Failed to create geometry buffer: {}", name, e);
            cleanup();
            throw new RuntimeException("Geometry buffer creation failed: " + name, e);
        }
    }

    /**
     * Bind this geometry buffer for rendering.
     */
    public void bind() {
        if (!initialized) {
            throw new IllegalStateException("Geometry buffer '" + name + "' not initialized");
        }
        glBindVertexArray(vaoId);
    }

    /**
     * Unbind geometry buffer.
     */
    public void unbind() {
        glBindVertexArray(0);
    }

    /**
     * Clean up OpenGL resources.
     */
    private void cleanup() {
        if (vboId != -1) {
            glDeleteBuffers(vboId);
            vboId = -1;
        }
        if (vaoId != -1) {
            glDeleteVertexArrays(vaoId);
            vaoId = -1;
        }
        vertexCount = 0;
        initialized = false;
    }

    @Override
    public void close() {
        if (initialized) {
            logger.debug("Closing geometry buffer: {}", name);
            cleanup();
        }
    }

    // Getters
    public String getName() { return name; }
    public int getVaoId() { return vaoId; }
    public int getVboId() { return vboId; }
    public int getVertexCount() { return vertexCount; }
    public boolean isInitialized() { return initialized; }

    /**
     * Validate that geometry buffer is ready for use.
     */
    public void validate() {
        if (!initialized) {
            throw new IllegalStateException("Geometry buffer '" + name + "' not initialized");
        }
        if (vaoId == -1 || vboId == -1) {
            throw new IllegalStateException("Geometry buffer '" + name + "' resources invalid");
        }
    }

    @Override
    public String toString() {
        return String.format("GeometryBuffer{name='%s', vao=%d, vbo=%d, vertices=%d}",
                           name, vaoId, vboId, vertexCount);
    }
}
