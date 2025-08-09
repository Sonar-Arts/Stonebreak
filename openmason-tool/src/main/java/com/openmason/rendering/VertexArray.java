package com.openmason.rendering;

import com.stonebreak.textures.CowTextureDefinition;
import org.lwjgl.opengl.GL11;
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
    private String debugName;
    
    // Associated buffers for lifecycle management
    private VertexBuffer vertexBuffer;
    private TextureCoordinateBuffer textureCoordBuffer;
    private IndexBuffer indexBuffer;
    private final List<OpenGLBuffer> additionalBuffers;
    
    // Rendering statistics
    private long lastAccessTime;
    private int renderCount;
    
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
     * Sets the vertex buffer for this VAO and configures the vertex attribute.
     * 
     * @param vertexBuffer The vertex buffer containing position data
     */
    public void setVertexBuffer(VertexBuffer vertexBuffer) {
        validateVAO();
        bind();
        
        this.vertexBuffer = vertexBuffer;
        vertexBuffer.enableVertexAttribute();
        
        unbind();
    }
    
    /**
     * Sets the texture coordinate buffer for this VAO and configures the texture attribute.
     * 
     * @param textureCoordBuffer The texture coordinate buffer containing UV data
     */
    public void setTextureCoordinateBuffer(TextureCoordinateBuffer textureCoordBuffer) {
        validateVAO();
        bind();
        
        this.textureCoordBuffer = textureCoordBuffer;
        textureCoordBuffer.enableVertexAttribute();
        
        unbind();
    }
    
    /**
     * Sets the index buffer for this VAO.
     * 
     * @param indexBuffer The index buffer containing triangle indices
     */
    public void setIndexBuffer(IndexBuffer indexBuffer) {
        validateVAO();
        bind();
        
        this.indexBuffer = indexBuffer;
        indexBuffer.bind(); // Binding an EBO while a VAO is bound associates it with the VAO
        
        unbind();
    }
    
    /**
     * Adds an additional buffer to this VAO for lifecycle management.
     * 
     * @param buffer Additional buffer to track
     */
    public void addBuffer(OpenGLBuffer buffer) {
        additionalBuffers.add(buffer);
    }
    
    /**
     * Renders this VAO using triangles with the associated index buffer.
     * The VAO must have an index buffer set for this method to work.
     */
    public void renderTriangles() {
        validateVAO();
        if (indexBuffer == null) {
            throw new IllegalStateException("Cannot render triangles without an index buffer: " + debugName);
        }
        
        bind();
        indexBuffer.drawTriangles();
        unbind();
        
        renderCount++;
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Renders this VAO using the specified primitive type and vertex count.
     * Used when rendering without an index buffer (vertex arrays).
     * 
     * @param primitiveType The OpenGL primitive type (GL_TRIANGLES, GL_QUADS, etc.)
     * @param vertexCount Number of vertices to render
     */
    public void renderVertices(int primitiveType, int vertexCount) {
        validateVAO();
        
        bind();
        GL11.glDrawArrays(primitiveType, 0, vertexCount);
        unbind();
        
        renderCount++;
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Updates the texture coordinates for a new texture variant.
     * This enables real-time texture variant switching without recreating the VAO.
     * 
     * @param textureDefinition The texture definition containing face mappings
     * @param partName The model part name
     * @param textureVariant The new texture variant name
     */
    public void updateTextureVariant(CowTextureDefinition.CowVariant textureDefinition,
                                   String partName, String textureVariant) {
        if (textureCoordBuffer != null) {
            textureCoordBuffer.updateForTextureVariant(textureDefinition, partName, textureVariant);
        }
    }
    
    /**
     * Creates a complete VAO from model part data.
     * This is the primary factory method for creating VAOs from the existing model system.
     * 
     * Exception-safe creation with guaranteed cleanup of partial resources on failure.
     * Utilizes existing BufferManager tracking and AutoCloseable pattern.
     * 
     * @param vertices Vertex data from ModelPart.getVertices()
     * @param indices Index data from ModelPart.getIndices()
     * @param textureDefinition Texture definition for UV mapping
     * @param partName Model part name for texture mapping
     * @param debugName Debug name for the VAO
     * @return Fully configured VAO ready for rendering
     * @throws RuntimeException if VAO or buffer creation fails, with guaranteed cleanup
     */
    public static VertexArray fromModelPart(float[] vertices, int[] indices,
                                          CowTextureDefinition.CowVariant textureDefinition,
                                          String partName, String debugName) {
        VertexArray vao = null;
        VertexBuffer vertexBuf = null;
        IndexBuffer indexBuf = null;
        TextureCoordinateBuffer texCoordBuf = null;
        
        try {
            // Create VAO first
            vao = new VertexArray(debugName);
            
            // Create and configure vertex buffer
            vertexBuf = VertexBuffer.fromModelVertices(vertices, debugName + "_vertices");
            vao.setVertexBuffer(vertexBuf);
            
            // Create and configure index buffer
            indexBuf = IndexBuffer.fromModelIndices(indices, debugName + "_indices");
            vao.setIndexBuffer(indexBuf);
            
            // Create texture coordinate buffer (will be populated when variant is set)
            texCoordBuf = TextureCoordinateBuffer.createPlaceholder(
                vertices.length / 3, debugName + "_texcoords");
            vao.setTextureCoordinateBuffer(texCoordBuf);
            
            // All resources created successfully
            return vao;
            
        } catch (Exception e) {
            // Exception-safe cleanup: dispose resources in reverse creation order
            // Each resource's close() method is idempotent and handles null checks
            
            try {
                if (texCoordBuf != null) {
                    texCoordBuf.close();
                }
            } catch (Exception cleanupEx) {
                System.err.println("WARNING: Failed to cleanup texture coordinate buffer during exception handling: " + cleanupEx.getMessage());
            }
            
            try {
                if (indexBuf != null) {
                    indexBuf.close();
                }
            } catch (Exception cleanupEx) {
                System.err.println("WARNING: Failed to cleanup index buffer during exception handling: " + cleanupEx.getMessage());
            }
            
            try {
                if (vertexBuf != null) {
                    vertexBuf.close();
                }
            } catch (Exception cleanupEx) {
                System.err.println("WARNING: Failed to cleanup vertex buffer during exception handling: " + cleanupEx.getMessage());
            }
            
            try {
                if (vao != null) {
                    // VAO close() will attempt to dispose associated buffers, but they're already disposed above
                    // This ensures the VAO itself is properly cleaned up
                    vao.close();
                }
            } catch (Exception cleanupEx) {
                System.err.println("WARNING: Failed to cleanup VAO during exception handling: " + cleanupEx.getMessage());
            }
            
            // Re-throw the original exception with context
            throw new RuntimeException("Failed to create VertexArray from model part '" + debugName + 
                "': " + e.getMessage() + ". All partial resources have been cleaned up.", e);
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
        
        if (textureCoordBuffer != null && !textureCoordBuffer.isValid()) {
            result.addWarning("Texture coordinate buffer is invalid");
        }
        
        if (indexBuffer != null && !indexBuffer.isValid()) {
            result.addError("Index buffer is invalid");
        }
        
        // Check vertex count consistency
        if (vertexBuffer != null && textureCoordBuffer != null) {
            if (vertexBuffer.getVertexCount() != textureCoordBuffer.getVertexCount()) {
                result.addWarning(String.format(
                    "Vertex count mismatch: vertices=%d, texcoords=%d",
                    vertexBuffer.getVertexCount(), textureCoordBuffer.getVertexCount()));
            }
        }
        
        return result;
    }
    
    /**
     * Validates that the cleanup process works correctly with the BufferManager.
     * This method verifies that all resources are properly tracked and can be cleaned up.
     * 
     * @return Validation result specifically focused on cleanup and lifecycle management
     */
    public ValidationResult validateCleanupCapability() {
        ValidationResult result = new ValidationResult();
        
        if (isDisposed) {
            result.addError("Cannot validate cleanup capability: VAO is already disposed");
            return result;
        }
        
        // Verify BufferManager tracking and statistics
        try {
            BufferManager bufferManager = BufferManager.getInstance();
            BufferManager.BufferManagerStatistics stats = bufferManager.getStatistics();
            
            // Validate that the manager is functional
            if (stats.activeVertexArrayCount < 0 || stats.activeBufferCount < 0) {
                result.addError("BufferManager statistics are invalid");
            }
            
            // Check for resource validation issues
            List<String> issues = bufferManager.validateResources();
            for (String issue : issues) {
                // Only report issues related to this VAO
                if (issue.contains(this.debugName)) {
                    result.addWarning("BufferManager validation issue: " + issue);
                }
                // Report issues with our associated buffers
                if (vertexBuffer != null && issue.contains(vertexBuffer.getDebugName())) {
                    result.addWarning("Vertex buffer validation issue: " + issue);
                }
                if (textureCoordBuffer != null && issue.contains(textureCoordBuffer.getDebugName())) {
                    result.addWarning("Texture coordinate buffer validation issue: " + issue);
                }
                if (indexBuffer != null && issue.contains(indexBuffer.getDebugName())) {
                    result.addWarning("Index buffer validation issue: " + issue);
                }
            }
            
        } catch (Exception e) {
            result.addError("Failed to validate BufferManager integration: " + e.getMessage());
        }
        
        // Verify AutoCloseable pattern compliance
        if (!(this instanceof AutoCloseable)) {
            result.addError("VAO does not implement AutoCloseable correctly");
        }
        
        // Check that all buffers implement AutoCloseable
        if (vertexBuffer != null && !(vertexBuffer instanceof AutoCloseable)) {
            result.addError("Vertex buffer does not implement AutoCloseable");
        }
        
        if (textureCoordBuffer != null && !(textureCoordBuffer instanceof AutoCloseable)) {
            result.addError("Texture coordinate buffer does not implement AutoCloseable");
        }
        
        if (indexBuffer != null && !(indexBuffer instanceof AutoCloseable)) {
            result.addError("Index buffer does not implement AutoCloseable");
        }
        
        // Validate that buffers are in valid state for cleanup
        if (vertexBuffer != null && vertexBuffer.isDisposed()) {
            result.addWarning("Vertex buffer is already disposed but still referenced");
        }
        
        if (textureCoordBuffer != null && textureCoordBuffer.isDisposed()) {
            result.addWarning("Texture coordinate buffer is already disposed but still referenced");
        }
        
        if (indexBuffer != null && indexBuffer.isDisposed()) {
            result.addWarning("Index buffer is already disposed but still referenced");
        }
        
        // Check for potential double-cleanup issues
        for (OpenGLBuffer buffer : additionalBuffers) {
            if (buffer.isDisposed()) {
                result.addWarning("Additional buffer is already disposed but still in list: " + buffer.getDebugName());
            }
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
            
            if (textureCoordBuffer != null) {
                try {
                    textureCoordBuffer.close();
                } catch (Exception e) {
                    System.err.println("WARNING: Failed to dispose texture coordinate buffer in VAO " + debugName + ": " + e.getMessage());
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
    public boolean isDisposed() { return isDisposed; }
    public String getDebugName() { return debugName; }
    public long getLastAccessTime() { return lastAccessTime; }
    public int getRenderCount() { return renderCount; }
    public VertexBuffer getVertexBuffer() { return vertexBuffer; }
    public TextureCoordinateBuffer getTextureCoordinateBuffer() { return textureCoordBuffer; }
    public IndexBuffer getIndexBuffer() { return indexBuffer; }
    
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
        public void addWarning(String warning) { warnings.add(warning); }
        
        public boolean isValid() { return errors.isEmpty(); }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        
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