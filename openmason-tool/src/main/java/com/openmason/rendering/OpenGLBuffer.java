package com.openmason.rendering;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Base class for OpenGL buffer objects with automatic resource management.
 * Provides lifecycle management, validation, and cleanup for all buffer types.
 * 
 * This class ensures proper OpenGL buffer lifecycle management with automatic
 * cleanup and validation to prevent memory leaks and OpenGL errors.
 */
public abstract class OpenGLBuffer implements AutoCloseable {
    protected int bufferId;
    protected final int bufferType;
    protected boolean isValid;
    protected boolean isDisposed;
    protected long lastAccessTime;
    protected String debugName;
    
    // Buffer statistics for memory monitoring
    protected int dataSize;
    protected int usage;
    
    // Cleaner for resource cleanup detection
    private final BufferCleanupState cleanupState;
    private final java.lang.ref.Cleaner.Cleanable cleanable;
    
    /**
     * Creates a new OpenGL buffer of the specified type.
     * 
     * @param bufferType The OpenGL buffer type (GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER, etc.)
     * @param debugName Optional debug name for tracking and logging
     */
    protected OpenGLBuffer(int bufferType, String debugName) {
        this.bufferType = bufferType;
        this.debugName = debugName != null ? debugName : "UnnamedBuffer";
        this.bufferId = GL15.glGenBuffers();
        this.isValid = true;
        this.isDisposed = false;
        this.lastAccessTime = System.currentTimeMillis();
        this.dataSize = 0;
        this.usage = GL15.GL_STATIC_DRAW;
        
        if (this.bufferId == 0) {
            throw new RuntimeException("Failed to generate OpenGL buffer: " + this.debugName);
        }
        
        // Set up cleaner for resource leak detection
        this.cleanupState = new BufferCleanupState(this.debugName);
        this.cleanable = cleaner.register(this, cleanupState);
        
        // Register with buffer manager for tracking
        BufferManager.getInstance().registerBuffer(this);
    }
    
    /**
     * Binds this buffer for use with OpenGL operations.
     * Updates last access time for memory management.
     */
    public void bind() {
        validateBuffer();
        GL15.glBindBuffer(bufferType, bufferId);
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Unbinds this buffer type from OpenGL.
     */
    public void unbind() {
        GL15.glBindBuffer(bufferType, 0);
    }
    
    /**
     * Validates that this buffer is still valid and not disposed.
     * 
     * @throws IllegalStateException if the buffer has been disposed or is invalid
     */
    protected void validateBuffer() {
        if (isDisposed) {
            throw new IllegalStateException("Buffer has been disposed: " + debugName);
        }
        if (!isValid) {
            throw new IllegalStateException("Buffer is invalid: " + debugName);
        }
    }
    
    /**
     * Uploads data to this buffer using the specified usage pattern.
     * 
     * @param data The data to upload
     * @param usage OpenGL usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     */
    protected void uploadData(FloatBuffer data, int usage) {
        validateBuffer();
        bind();
        
        this.dataSize = data.remaining() * Float.BYTES;
        this.usage = usage;
        
        GL15.glBufferData(bufferType, data, usage);
        
        // Validate upload success
        int error = GL15.glGetError();
        if (error != GL15.GL_NO_ERROR) {
            throw new RuntimeException("Failed to upload buffer data: " + debugName + " (Error: " + error + ")");
        }
        
        unbind();
    }
    
    /**
     * Uploads data to this buffer using the specified usage pattern.
     * 
     * @param data The data to upload
     * @param usage OpenGL usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     */
    protected void uploadData(IntBuffer data, int usage) {
        validateBuffer();
        bind();
        
        this.dataSize = data.remaining() * Integer.BYTES;
        this.usage = usage;
        
        GL15.glBufferData(bufferType, data, usage);
        
        // Validate upload success
        int error = GL15.glGetError();
        if (error != GL15.GL_NO_ERROR) {
            throw new RuntimeException("Failed to upload buffer data: " + debugName + " (Error: " + error + ")");
        }
        
        unbind();
    }
    
    /**
     * Updates a portion of the buffer data.
     * 
     * @param offset Offset in bytes where to start updating
     * @param data New data to upload
     */
    protected void updateData(long offset, FloatBuffer data) {
        validateBuffer();
        bind();
        
        GL15.glBufferSubData(bufferType, offset, data);
        
        // Validate update success
        int error = GL15.glGetError();
        if (error != GL15.GL_NO_ERROR) {
            throw new RuntimeException("Failed to update buffer data: " + debugName + " (Error: " + error + ")");
        }
        
        unbind();
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Updates a portion of the buffer data.
     * 
     * @param offset Offset in bytes where to start updating
     * @param data New data to upload
     */
    protected void updateData(long offset, IntBuffer data) {
        validateBuffer();
        bind();
        
        GL15.glBufferSubData(bufferType, offset, data);
        
        // Validate update success
        int error = GL15.glGetError();
        if (error != GL15.GL_NO_ERROR) {
            throw new RuntimeException("Failed to update buffer data: " + debugName + " (Error: " + error + ")");
        }
        
        unbind();
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Disposes of this buffer and releases OpenGL resources.
     * This method is idempotent - calling it multiple times is safe.
     */
    @Override
    public void close() {
        if (!isDisposed && bufferId != 0) {
            GL15.glDeleteBuffers(bufferId);
            BufferManager.getInstance().unregisterBuffer(this);
            
            // Mark as disposed in cleanup state and clean the cleanable
            cleanupState.markDisposed();
            cleanable.clean();
            
            isDisposed = true;
            isValid = false;
            bufferId = 0;
        }
    }
    
    // Getters for monitoring and debugging
    public int getBufferId() { return bufferId; }
    public int getBufferType() { return bufferType; }
    public boolean isValid() { return isValid && !isDisposed; }
    public boolean isDisposed() { return isDisposed; }
    public long getLastAccessTime() { return lastAccessTime; }
    public String getDebugName() { return debugName; }
    public int getDataSize() { return dataSize; }
    public int getUsage() { return usage; }
    
    @Override
    public String toString() {
        return String.format("OpenGLBuffer{name='%s', id=%d, type=%d, size=%d, valid=%b}",
            debugName, bufferId, bufferType, dataSize, isValid());
    }
    
    /**
     * Cleanup detection using Cleaner instead of deprecated finalize().
     * Should not be relied upon - always call close() explicitly.
     */
    private static final java.lang.ref.Cleaner cleaner = java.lang.ref.Cleaner.create();
    
    /**
     * State holder for the cleaner to track buffer disposal.
     */
    private static class BufferCleanupState implements Runnable {
        private final String debugName;
        private volatile boolean disposed = false;
        
        BufferCleanupState(String debugName) {
            this.debugName = debugName;
        }
        
        void markDisposed() {
            disposed = true;
        }
        
        @Override
        public void run() {
            if (!disposed) {
                System.err.println("WARNING: Buffer not properly disposed: " + debugName);
                // Note: Cannot call OpenGL functions from cleaner thread
                // This is just for detection - proper cleanup must happen on main thread
            }
        }
    }
}