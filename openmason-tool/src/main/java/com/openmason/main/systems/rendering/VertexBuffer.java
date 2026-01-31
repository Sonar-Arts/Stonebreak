package com.openmason.main.systems.rendering;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/**
 * Vertex Buffer Object (VBO) wrapper for managing vertex position data.
 * Provides high-level interface for vertex data management with automatic
 * resource cleanup and OpenGL state management.
 */
public class VertexBuffer extends OpenGLBuffer {
    private final int attributeIndex;
    private final int componentCount;
    private final int stride;

    /**
     * Creates a new vertex buffer for the specified attribute location.
     */
    public VertexBuffer(int attributeIndex, int componentCount, String debugName) {
        super(GL15.GL_ARRAY_BUFFER, debugName);
        this.attributeIndex = attributeIndex;
        this.componentCount = componentCount;
        this.stride = componentCount * Float.BYTES;
    }
    
    /**
     * Enables this vertex buffer as a vertex attribute array.
     * This configures OpenGL to use this buffer for vertex data.
     */
    public void enableVertexAttribute() {
        validateBuffer();
        bind();
        GL20.glVertexAttribPointer(attributeIndex, componentCount, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(attributeIndex);
        lastAccessTime = System.currentTimeMillis();
    }

}