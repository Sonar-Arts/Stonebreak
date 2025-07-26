package com.openmason.rendering;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Vertex Buffer Object (VBO) wrapper for managing vertex position data.
 * Provides high-level interface for vertex data management with automatic
 * resource cleanup and OpenGL state management.
 */
public class VertexBuffer extends OpenGLBuffer {
    private final int attributeIndex;
    private final int componentCount;
    private final int stride;
    private int vertexCount;
    
    /**
     * Creates a new vertex buffer for the specified attribute location.
     * 
     * @param attributeIndex The vertex attribute index (typically 0 for position)
     * @param componentCount Number of components per vertex (3 for XYZ positions)
     * @param debugName Optional debug name for tracking
     */
    public VertexBuffer(int attributeIndex, int componentCount, String debugName) {
        super(GL15.GL_ARRAY_BUFFER, debugName);
        this.attributeIndex = attributeIndex;
        this.componentCount = componentCount;
        this.stride = componentCount * Float.BYTES;
        this.vertexCount = 0;
    }
    
    /**
     * Convenience constructor for position data (3 components).
     * 
     * @param debugName Debug name for tracking
     */
    public VertexBuffer(String debugName) {
        this(0, 3, debugName);
    }
    
    /**
     * Uploads vertex data to the GPU with static draw usage.
     * 
     * @param vertices Vertex data as float array
     */
    public void uploadVertices(float[] vertices) {
        uploadVertices(vertices, GL15.GL_STATIC_DRAW);
    }
    
    /**
     * Uploads vertex data to the GPU with specified usage pattern.
     * 
     * @param vertices Vertex data as float array
     * @param usage OpenGL usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     */
    public void uploadVertices(float[] vertices, int usage) {
        if (vertices.length % componentCount != 0) {
            throw new IllegalArgumentException(
                "Vertex data length (" + vertices.length + ") is not divisible by component count (" + componentCount + ")");
        }
        
        FloatBuffer buffer = MemoryUtil.memAllocFloat(vertices.length);
        try {
            buffer.put(vertices).flip();
            uploadData(buffer, usage);
            this.vertexCount = vertices.length / componentCount;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Updates a portion of the vertex data.
     * 
     * @param startVertex The starting vertex index
     * @param vertices New vertex data
     */
    public void updateVertices(int startVertex, float[] vertices) {
        if (vertices.length % componentCount != 0) {
            throw new IllegalArgumentException(
                "Vertex data length (" + vertices.length + ") is not divisible by component count (" + componentCount + ")");
        }
        
        long offset = (long) startVertex * stride;
        FloatBuffer buffer = MemoryUtil.memAllocFloat(vertices.length);
        try {
            buffer.put(vertices).flip();
            updateData(offset, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
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
    
    /**
     * Disables this vertex attribute array.
     */
    public void disableVertexAttribute() {
        GL20.glDisableVertexAttribArray(attributeIndex);
    }
    
    /**
     * Creates a vertex buffer from model part vertex data.
     * This is a convenience method for integrating with the existing model system.
     * 
     * @param vertices Vertex data from ModelPart.getVertices()
     * @param debugName Debug name for the buffer
     * @return Configured vertex buffer ready for use
     */
    public static VertexBuffer fromModelVertices(float[] vertices, String debugName) {
        VertexBuffer buffer = new VertexBuffer(debugName);
        buffer.uploadVertices(vertices);
        return buffer;
    }
    
    // Getters
    public int getAttributeIndex() { return attributeIndex; }
    public int getComponentCount() { return componentCount; }
    public int getStride() { return stride; }
    public int getVertexCount() { return vertexCount; }
    
    @Override
    public String toString() {
        return String.format("VertexBuffer{name='%s', id=%d, vertices=%d, components=%d, attr=%d}",
            debugName, bufferId, vertexCount, componentCount, attributeIndex);
    }
}