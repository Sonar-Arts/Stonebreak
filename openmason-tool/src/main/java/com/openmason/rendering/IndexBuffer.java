package com.openmason.rendering;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * Element Buffer Object (EBO) wrapper for managing triangle indices.
 * Handles mesh triangulation data with automatic resource management.
 */
public class IndexBuffer extends OpenGLBuffer {
    private int indexCount;
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
     * Uploads index data to the GPU with static draw usage.
     * 
     * @param indices Triangle indices as int array
     */
    public void uploadIndices(int[] indices) {
        uploadIndices(indices, GL15.GL_STATIC_DRAW);
    }
    
    /**
     * Uploads index data to the GPU with specified usage pattern.
     * 
     * @param indices Triangle indices as int array
     * @param usage OpenGL usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     */
    public void uploadIndices(int[] indices, int usage) {
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException(
                "Index count (" + indices.length + ") is not divisible by 3 (triangle indices)");
        }
        
        IntBuffer buffer = MemoryUtil.memAllocInt(indices.length);
        try {
            buffer.put(indices).flip();
            uploadData(buffer, usage);
            this.indexCount = indices.length;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Updates a portion of the index data.
     * 
     * @param startIndex The starting index position
     * @param indices New index data
     */
    public void updateIndices(int startIndex, int[] indices) {
        long offset = (long) startIndex * Integer.BYTES;
        IntBuffer buffer = MemoryUtil.memAllocInt(indices.length);
        try {
            buffer.put(indices).flip();
            updateData(offset, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
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
    
    /**
     * Creates an index buffer from model part index data.
     * This is a convenience method for integrating with the existing model system.
     * 
     * @param indices Index data from ModelPart.getIndices()
     * @param debugName Debug name for the buffer
     * @return Configured index buffer ready for use
     */
    public static IndexBuffer fromModelIndices(int[] indices, String debugName) {
        IndexBuffer buffer = new IndexBuffer(debugName);
        buffer.uploadIndices(indices);
        return buffer;
    }
    
    // Getters
    public int getIndexCount() { return indexCount; }
    public int getTriangleCount() { return indexCount / 3; }
    public int getIndexType() { return indexType; }
    
    @Override
    public String toString() {
        return String.format("IndexBuffer{name='%s', id=%d, indices=%d, triangles=%d}",
            debugName, bufferId, indexCount, getTriangleCount());
    }
}