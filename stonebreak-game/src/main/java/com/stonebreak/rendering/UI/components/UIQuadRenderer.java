package com.stonebreak.rendering.UI.components;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Specialized renderer for UI quad elements using OpenGL VAO/VBO.
 * Handles the creation and management of OpenGL resources for rendering UI quads.
 */
public class UIQuadRenderer {
    private int uiQuadVao;    // VAO for drawing generic UI quads (positions and UVs)
    private int uiQuadVbo;    // VBO for drawing generic UI quads (positions and UVs)
    private boolean initialized = false;
    
    /**
     * Initializes the UI quad renderer by creating OpenGL resources.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        createUiQuadRenderer();
        initialized = true;
    }
    
    /**
     * Creates the VAO and VBO for rendering generic UI quads.
     */
    private void createUiQuadRenderer() {
        uiQuadVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(uiQuadVao);

        uiQuadVbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, uiQuadVbo);
        // Allocate buffer for 4 vertices, each with 5 floats (x, y, z, u, v).
        // Using GL_DYNAMIC_DRAW as vertex data will change frequently.
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, 4 * 5 * Float.BYTES, GL20.GL_DYNAMIC_DRAW);

        // Vertex attribute for position (location 0)
        // Stride is 5 floats (x,y,z,u,v)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        // Vertex attribute for texture coordinates (location 1)
        // Stride is 5 floats, offset is 3 floats (after x,y,z)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1); // Enable attribute for texCoords

        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Binds the UI quad VAO and VBO for rendering operations.
     */
    public void bind() {
        if (!initialized) {
            throw new IllegalStateException("UIQuadRenderer must be initialized before use");
        }
        GL30.glBindVertexArray(uiQuadVao);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, uiQuadVbo);
    }
    
    /**
     * Unbinds the UI quad VAO and VBO.
     */
    public void unbind() {
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Gets the UI quad VAO ID.
     * @return The OpenGL VAO ID
     */
    public int getUiQuadVao() {
        return uiQuadVao;
    }
    
    /**
     * Gets the UI quad VBO ID.
     * @return The OpenGL VBO ID
     */
    public int getUiQuadVbo() {
        return uiQuadVbo;
    }
    
    /**
     * Checks if the renderer has been initialized.
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Cleans up OpenGL resources.
     */
    public void cleanup() {
        if (uiQuadVao != 0) {
            GL30.glDeleteVertexArrays(uiQuadVao);
            uiQuadVao = 0;
        }
        if (uiQuadVbo != 0) {
            GL20.glDeleteBuffers(uiQuadVbo);
            uiQuadVbo = 0;
        }
        initialized = false;
    }
}