package com.openmason.rendering;

import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertex Array Object (VAO) wrapper that manages the complete vertex specification
 * for a renderable object. Coordinates vertex buffers, texture coordinate buffers,
 * and index buffers into a single rendering unit.
 */
public class VertexArray implements AutoCloseable {
    private int vaoId;
    private boolean isValid;
    private boolean isDisposed;
    private final String debugName;
    
    // Associated buffers for lifecycle management
    private VertexBuffer vertexBuffer;
    private IndexBuffer indexBuffer;
    private final List<OpenGLBuffer> additionalBuffers;
    
    // Rendering statistics
    private long lastAccessTime;
    private final int renderCount;
    
    /**
     * Creates a new Vertex Array Object.
     * Exception-safe construction with guaranteed cleanup on failure.
     * 
     * @param debugName Debug name for tracking and logging
     * @throws RuntimeException if VAO creation or registration fails
     */
    public VertexArray(String debugName) {
        this.debugName = debugName != null ? debugName : "UnnamedVAO";
        this.vaoId = GL30.glGenVertexArrays();
        this.isValid = true;
        this.isDisposed = false;
        this.lastAccessTime = System.currentTimeMillis();
        this.renderCount = 0;
        this.additionalBuffers = new ArrayList<>();
        
        if (this.vaoId == 0) {
            throw new RuntimeException("Failed to generate Vertex Array Object: " + this.debugName);
        }
        
        // Register with buffer manager for tracking with exception safety
        try {
            BufferManager.getInstance().registerVertexArray(this);
        } catch (Exception e) {
            // Clean up the OpenGL VAO if registration fails
            try {
                GL30.glDeleteVertexArrays(this.vaoId);
            } catch (Exception cleanupEx) {
                System.err.println("WARNING: Failed to cleanup VAO during constructor exception handling: " + cleanupEx.getMessage());
            }
            
            // Mark as invalid to prevent further use
            this.isValid = false;
            this.isDisposed = true;
            this.vaoId = 0;
            
            throw new RuntimeException("Failed to register VertexArray with BufferManager: " + this.debugName + 
                ". VAO has been cleaned up.", e);
        }
    }
    
    /**
     * Binds this VAO for rendering operations.
     */
    public void bind() {
        validateVAO();
        GL30.glBindVertexArray(vaoId);
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Unbinds the current VAO.
     */
    public void unbind() {
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Validates that this VAO is still valid and not disposed.
     */
    private void validateVAO() {
        if (isDisposed) {
            throw new IllegalStateException("Vertex Array has been disposed: " + debugName);
        }
        if (!isValid) {
            throw new IllegalStateException("Vertex Array is invalid: " + debugName);
        }
    }

    /**
     * Validates the VAO configuration and checks for common issues.
     * 
     * @return Validation result with any errors or warnings
     */
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        
        if (!isValid()) {
            result.addError("VAO is not valid or has been disposed");
            return result;
        }
        
        if (vertexBuffer == null) {
            result.addError("No vertex buffer assigned");
        } else if (!vertexBuffer.isValid()) {
            result.addError("Vertex buffer is invalid");
        }

        if (indexBuffer != null && !indexBuffer.isValid()) {
            result.addError("Index buffer is invalid");
        }

        return result;
    }
    
    /**
     * Disposes of this VAO and all associated buffers.
     * Exception-safe cleanup with comprehensive error handling.
     * This method is idempotent - calling it multiple times is safe.
     */
    @Override
    public void close() {
        if (!isDisposed && vaoId != 0) {
            // Dispose associated buffers with individual exception handling
            // Continue cleanup even if individual buffer disposal fails
            
            if (vertexBuffer != null) {
                try {
                    vertexBuffer.close();
                } catch (Exception e) {
                    System.err.println("WARNING: Failed to dispose vertex buffer in VAO " + debugName + ": " + e.getMessage());
                }
            }

            if (indexBuffer != null) {
                try {
                    indexBuffer.close();
                } catch (Exception e) {
                    System.err.println("WARNING: Failed to dispose index buffer in VAO " + debugName + ": " + e.getMessage());
                }
            }
            
            // Dispose additional buffers with individual error handling
            for (OpenGLBuffer buffer : additionalBuffers) {
                try {
                    buffer.close();
                } catch (Exception e) {
                    System.err.println("WARNING: Failed to dispose additional buffer in VAO " + debugName + ": " + e.getMessage());
                }
            }
            additionalBuffers.clear();
            
            // Clean up VAO itself
            try {
                GL30.glDeleteVertexArrays(vaoId);
                BufferManager.getInstance().unregisterVertexArray(this);
            } catch (Exception e) {
                System.err.println("WARNING: Failed to delete VAO or unregister from BufferManager for " + debugName + ": " + e.getMessage());
                // Continue with disposal marking even if OpenGL cleanup failed
            }
            
            // Always mark as disposed, even if some cleanup operations failed
            isDisposed = true;
            isValid = false;
            vaoId = 0;
        }
    }
    
    // Getters for monitoring and debugging
    public int getVaoId() { return vaoId; }
    public boolean isValid() { return isValid && !isDisposed; }
    public String getDebugName() { return debugName; }
    public long getLastAccessTime() { return lastAccessTime; }
    
    @Override
    public String toString() {
        return String.format("VertexArray{name='%s', id=%d, renders=%d, valid=%b}",
            debugName, vaoId, renderCount, isValid());
    }
    
    /**
     * Simple validation result class for VAO validation.
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        
        public void addError(String error) { errors.add(error); }

        public boolean isValid() { return errors.isEmpty(); }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("VAO Validation Result:\n");
            sb.append("  Errors: ").append(errors.size()).append("\n");
            sb.append("  Warnings: ").append(warnings.size()).append("\n");
            
            if (!errors.isEmpty()) {
                sb.append("  Error Details:\n");
                for (String error : errors) {
                    sb.append("    - ").append(error).append("\n");
                }
            }
            
            if (!warnings.isEmpty()) {
                sb.append("  Warning Details:\n");
                for (String warning : warnings) {
                    sb.append("    - ").append(warning).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}