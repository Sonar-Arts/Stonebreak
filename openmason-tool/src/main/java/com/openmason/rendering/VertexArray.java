package com.openmason.rendering;

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
     * 
     * @param debugName Debug name for tracking and logging
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
        
        // Register with buffer manager for tracking
        BufferManager.getInstance().registerVertexArray(this);
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
    public void updateTextureVariant(com.openmason.texture.stonebreak.StonebreakTextureDefinition.CowVariant textureDefinition,
                                   String partName, String textureVariant) {
        if (textureCoordBuffer != null) {
            textureCoordBuffer.updateForTextureVariant(textureDefinition, partName, textureVariant);
        }
    }
    
    /**
     * Creates a complete VAO from model part data.
     * This is the primary factory method for creating VAOs from the existing model system.
     * 
     * @param vertices Vertex data from ModelPart.getVertices()
     * @param indices Index data from ModelPart.getIndices()
     * @param textureDefinition Texture definition for UV mapping
     * @param partName Model part name for texture mapping
     * @param debugName Debug name for the VAO
     * @return Fully configured VAO ready for rendering
     */
    public static VertexArray fromModelPart(float[] vertices, int[] indices,
                                          com.openmason.texture.stonebreak.StonebreakTextureDefinition textureDefinition,
                                          String partName, String debugName) {
        VertexArray vao = new VertexArray(debugName);
        
        // Create and configure vertex buffer
        VertexBuffer vertexBuf = VertexBuffer.fromModelVertices(vertices, debugName + "_vertices");
        vao.setVertexBuffer(vertexBuf);
        
        // Create and configure index buffer
        IndexBuffer indexBuf = IndexBuffer.fromModelIndices(indices, debugName + "_indices");
        vao.setIndexBuffer(indexBuf);
        
        // Create texture coordinate buffer (will be populated when variant is set)
        TextureCoordinateBuffer texCoordBuf = TextureCoordinateBuffer.createPlaceholder(
            vertices.length / 3, debugName + "_texcoords");
        vao.setTextureCoordinateBuffer(texCoordBuf);
        
        return vao;
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
     * Disposes of this VAO and all associated buffers.
     */
    @Override
    public void close() {
        if (!isDisposed && vaoId != 0) {
            // Dispose associated buffers
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            if (textureCoordBuffer != null) {
                textureCoordBuffer.close();
            }
            if (indexBuffer != null) {
                indexBuffer.close();
            }
            for (OpenGLBuffer buffer : additionalBuffers) {
                buffer.close();
            }
            additionalBuffers.clear();
            
            // Delete the VAO
            GL30.glDeleteVertexArrays(vaoId);
            BufferManager.getInstance().unregisterVertexArray(this);
            
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