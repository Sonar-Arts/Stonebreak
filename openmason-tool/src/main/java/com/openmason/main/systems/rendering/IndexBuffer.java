package com.openmason.main.systems.rendering;

import org.lwjgl.opengl.GL15;

/**
 * Element Buffer Object (EBO) wrapper for managing triangle indices.
 * Handles mesh triangulation data with automatic resource management.
 */
public class IndexBuffer extends OpenGLBuffer {
    private final int indexCount;

    /**
     * Creates a new index buffer.
     * 
     * @param debugName Debug name for tracking
     */
    public IndexBuffer(String debugName) {
        super(GL15.GL_ELEMENT_ARRAY_BUFFER, debugName);
        this.indexCount = 0;
    }

    
    // Getters
    public int getTriangleCount() { return indexCount / 3; }

    @Override
    public String toString() {
        return String.format("IndexBuffer{name='%s', id=%d, indices=%d, triangles=%d}",
            debugName, bufferId, indexCount, getTriangleCount());
    }
}