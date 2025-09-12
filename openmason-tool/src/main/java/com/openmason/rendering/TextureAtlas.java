package com.openmason.rendering;

import com.stonebreak.textures.mobs.CowTextureGenerator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;

/**
 * OpenGL texture atlas management for cow texture variants.
 * Handles texture atlas generation, loading, and binding for 3D model rendering.
 * 
 * This class bridges the gap between Stonebreak's texture generation system
 * and OpenMason's OpenGL rendering pipeline.
 */
public class TextureAtlas implements AutoCloseable {
    
    private int textureId = -1;
    private boolean initialized = false;
    private boolean disposed = false;
    private String debugName;
    
    // Atlas properties
    private static final int ATLAS_WIDTH = 256;
    private static final int ATLAS_HEIGHT = 256;
    
    // Texture unit management
    private static final int DEFAULT_TEXTURE_UNIT = 0;
    
    /**
     * Creates a new texture atlas.
     * 
     * @param debugName Debug name for tracking
     */
    public TextureAtlas(String debugName) {
        this.debugName = debugName != null ? debugName : "TextureAtlas";
    }
    
    /**
     * Initialize the texture atlas by generating and uploading cow textures.
     * This method calls the Stonebreak texture generation system and uploads
     * the resulting texture data to OpenGL.
     * 
     * @throws RuntimeException if texture generation or upload fails
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            // Generate texture atlas using Stonebreak's system
            System.out.println("[TextureAtlas] Generating cow texture atlas...");
            ByteBuffer textureData = CowTextureGenerator.generateTextureAtlas();
            
            if (textureData == null) {
                throw new RuntimeException("Failed to generate texture atlas - null data returned");
            }
            
            // Debug: Check if texture data contains non-zero values
            textureData.rewind();
            int totalPixels = ATLAS_WIDTH * ATLAS_HEIGHT;
            int nonZeroPixels = 0;
            for (int i = 0; i < totalPixels * 4; i += 4) {
                byte r = textureData.get(i);
                byte g = textureData.get(i + 1);
                byte b = textureData.get(i + 2);
                byte a = textureData.get(i + 3);
                if (r != 0 || g != 0 || b != 0 || a != 0) {
                    nonZeroPixels++;
                }
            }
            textureData.rewind();
            
            System.out.println("[TextureAtlas] Texture data validation:");
            System.out.println("  Total pixels: " + totalPixels);
            System.out.println("  Non-zero pixels: " + nonZeroPixels);
            System.out.println("  Percentage filled: " + String.format("%.1f%%", (nonZeroPixels * 100.0) / totalPixels));
            
            // Create OpenGL texture
            textureId = GL11.glGenTextures();
            if (textureId == 0) {
                throw new RuntimeException("Failed to generate OpenGL texture ID");
            }
            
            // Bind and configure texture
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            
            // Upload texture data
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,    // target
                0,                      // level
                GL11.GL_RGBA,          // internal format
                ATLAS_WIDTH,           // width
                ATLAS_HEIGHT,          // height
                0,                      // border
                GL11.GL_RGBA,          // format
                GL11.GL_UNSIGNED_BYTE, // type
                textureData            // data
            );
            
            // Configure texture parameters for pixel art style
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            
            // Unbind texture
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            
            initialized = true;
            System.out.println("[TextureAtlas] Successfully created texture atlas (ID: " + textureId + 
                              ", Size: " + ATLAS_WIDTH + "x" + ATLAS_HEIGHT + ")");
            
        } catch (Exception e) {
            // Clean up on failure
            cleanup();
            throw new RuntimeException("Failed to initialize texture atlas: " + debugName, e);
        }
    }
    
    /**
     * Bind this texture atlas for rendering.
     * Sets the texture as active on the specified texture unit.
     * 
     * @param textureUnit The texture unit to bind to (0-31)
     */
    public void bind(int textureUnit) {
        validateTexture();
        
        // Activate texture unit
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        
        // Bind texture
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }
    
    /**
     * Bind this texture atlas using the default texture unit (0).
     */
    public void bind() {
        bind(DEFAULT_TEXTURE_UNIT);
    }
    
    /**
     * Unbind any texture from the specified texture unit.
     * 
     * @param textureUnit The texture unit to unbind
     */
    public static void unbind(int textureUnit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
    
    /**
     * Unbind any texture from the default texture unit (0).
     */
    public static void unbind() {
        unbind(DEFAULT_TEXTURE_UNIT);
    }
    
    /**
     * Set the texture sampler uniform in a shader program.
     * 
     * @param shaderProgram The shader program ID
     * @param uniformName The name of the sampler uniform
     * @param textureUnit The texture unit this atlas is bound to
     */
    public void setTextureUniform(int shaderProgram, String uniformName, int textureUnit) {
        validateTexture();
        
        int location = GL20.glGetUniformLocation(shaderProgram, uniformName);
        if (location != -1) {
            GL20.glUniform1i(location, textureUnit);
        } else {
            System.err.println("[TextureAtlas] Warning: Texture uniform '" + uniformName + 
                             "' not found in shader program " + shaderProgram);
        }
    }
    
    /**
     * Set the texture sampler uniform using the default texture unit (0).
     * 
     * @param shaderProgram The shader program ID
     * @param uniformName The name of the sampler uniform
     */
    public void setTextureUniform(int shaderProgram, String uniformName) {
        setTextureUniform(shaderProgram, uniformName, DEFAULT_TEXTURE_UNIT);
    }
    
    /**
     * Validates that the texture is initialized and ready for use.
     */
    private void validateTexture() {
        if (disposed) {
            throw new IllegalStateException("Texture atlas has been disposed: " + debugName);
        }
        if (!initialized) {
            throw new IllegalStateException("Texture atlas not initialized: " + debugName);
        }
        if (textureId == -1) {
            throw new IllegalStateException("Invalid texture ID: " + debugName);
        }
    }
    
    /**
     * Check if the texture atlas is ready for use.
     * 
     * @return true if initialized and not disposed
     */
    public boolean isReady() {
        return initialized && !disposed && textureId != -1;
    }
    
    /**
     * Reload the texture atlas with fresh data.
     * Useful for development and texture variant updates.
     */
    public void reload() {
        if (initialized) {
            cleanup();
        }
        initialize();
    }
    
    /**
     * Clean up OpenGL resources.
     */
    private void cleanup() {
        if (textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
        }
        initialized = false;
    }
    
    /**
     * Get diagnostic information about the texture atlas.
     * 
     * @return String with texture atlas status
     */
    public String getDiagnosticInfo() {
        return String.format(
            "TextureAtlas{name='%s', id=%d, initialized=%b, disposed=%b, size=%dx%d}",
            debugName, textureId, initialized, disposed, ATLAS_WIDTH, ATLAS_HEIGHT
        );
    }
    
    // Getters
    public int getTextureId() { return textureId; }
    public boolean isInitialized() { return initialized; }
    public boolean isDisposed() { return disposed; }
    public String getDebugName() { return debugName; }
    public int getWidth() { return ATLAS_WIDTH; }
    public int getHeight() { return ATLAS_HEIGHT; }
    
    @Override
    public void close() {
        if (!disposed) {
            System.out.println("[TextureAtlas] Disposing texture atlas: " + debugName);
            cleanup();
            disposed = true;
        }
    }
    
    @Override
    public String toString() {
        return getDiagnosticInfo();
    }
}