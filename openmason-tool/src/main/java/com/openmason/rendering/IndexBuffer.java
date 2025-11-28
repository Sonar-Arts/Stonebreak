package com.openmason.rendering;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Element Buffer Object (EBO) wrapper for managing triangle indices.
 * Handles mesh triangulation data with automatic resource management.
 */
public class IndexBuffer extends OpenGLBuffer {
    private final int indexCount;
    private final int indexType;
    
    /**
     * Creates a new index buffer.
     * 
     * @param debugName Debug name for tracking
     */
    public IndexBuffer(String debugName) {
        super(GL15.GL_ELEMENT_ARRAY_BUFFER, debugName);
        this.indexCount = 0;
        this.indexType = GL11.GL_UNSIGNED_INT;
    }
    
    /**
     * Renders the mesh using this index buffer.
     * The vertex array must be bound before calling this method.
     * 
     * @param primitiveType The primitive type (typically GL_TRIANGLES)
     */
    public void drawElements(int primitiveType) {
        validateBuffer();
        bind();
        GL11.glDrawElements(primitiveType, indexCount, indexType, 0);
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Renders triangles using this index buffer.
     * Convenience method for the most common use case.
     */
    public void drawTriangles() {
        drawElements(GL11.GL_TRIANGLES);
    }
    
    // Getters
    public int getTriangleCount() { return indexCount / 3; }

    @Override
    public String toString() {
        return String.format("IndexBuffer{name='%s', id=%d, indices=%d, triangles=%d}",
            debugName, bufferId, indexCount, getTriangleCount());
    }
}