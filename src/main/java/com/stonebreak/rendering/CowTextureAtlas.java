package com.stonebreak.rendering;

import com.stonebreak.textures.CowTextureLoader;
import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureGenerator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * JSON-driven texture atlas system for cows using individual variant files.
 * Each cow variant has its own JSON file with drawing instructions.
 */
public class CowTextureAtlas {
    
    private static boolean initialized = false;
    
    // OpenGL texture ID for the cow texture atlas
    private static int textureId = -1;
    
    // Available cow variants
    public static final String DEFAULT_VARIANT = "default";
    public static final String ANGUS_VARIANT = "angus";
    public static final String HIGHLAND_VARIANT = "highland";
    public static final String JERSEY_VARIANT = "jersey";
    
    /**
     * Initialize the cow texture atlas system.
     * Loads individual JSON variant files, generates the texture atlas, and creates OpenGL texture.
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            // Get available cow variants
            String[] variants = CowTextureLoader.getAvailableVariants();
            System.out.println("[CowTextureAtlas] Successfully found " + variants.length + " cow variants");
            
            // Test all variants to identify any issues
            System.out.println("[CowTextureAtlas] Testing variant integrity...");
            CowTextureLoader.testAllVariants();
            
            // Generate texture atlas from individual JSON files
            System.out.println("[CowTextureAtlas] Generating JSON-driven texture atlas...");
            ByteBuffer textureData = CowTextureGenerator.generateTextureAtlas();
            
            // Create OpenGL texture
            int glTextureId = createOpenGLTexture(textureData, 256, 256);
            setTextureId(glTextureId);
            
            System.out.println("[CowTextureAtlas] Successfully created texture atlas with ID: " + glTextureId);
            initialized = true;
            
        } catch (Exception e) {
            System.err.println("[CowTextureAtlas] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Set the OpenGL texture ID for the cow atlas.
     */
    public static void setTextureId(int textureId) {
        CowTextureAtlas.textureId = textureId;
    }
    
    /**
     * Get the OpenGL texture ID for the cow atlas.
     */
    public static int getTextureId() {
        return textureId;
    }
    
    /**
     * Bind the cow texture atlas for rendering.
     */
    public static void bind() {
        if (textureId != -1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        }
    }
    
    /**
     * Get UV coordinates for a specific cow variant and body part face.
     * Returns coordinates suitable for OpenGL quad rendering.
     * 
     * @param variant The cow variant (default, angus, highland, jersey)
     * @param faceName The face name (HEAD_FRONT, BODY_LEFT, etc.)
     * @return float array with 8 UV coordinates for quad vertices, or null if not found
     */
    public static float[] getUVCoordinates(String variant, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        int gridSize = 16; // Fixed grid size for cow textures
        float[] coords = CowTextureLoader.getQuadUVCoordinates(variant, faceName, gridSize);
        
        if (coords == null) {
            System.err.println("[CowTextureAtlas] No UV coordinates found for " + variant + ":" + faceName);
            return getDefaultUVCoordinates();
        }
        
        return coords;
    }
    
    /**
     * Get normalized UV coordinates (u1, v1, u2, v2) for a cow face.
     * 
     * @param variant The cow variant
     * @param faceName The face name
     * @return float array with normalized UV coordinates [u1, v1, u2, v2]
     */
    public static float[] getNormalizedUVCoordinates(String variant, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        int gridSize = 16; // Fixed grid size for cow textures
        float[] coords = CowTextureLoader.getNormalizedUVCoordinates(variant, faceName, gridSize);
        
        if (coords == null) {
            return new float[]{0.0f, 0.0f, 0.0625f, 0.0625f}; // Default tile in 16x16 grid
        }
        
        return coords;
    }
    
    /**
     * Get atlas coordinates for a cow face.
     * 
     * @param variant The cow variant
     * @param faceName The face name
     * @return AtlasCoordinate with x,y grid positions, or null if not found
     */
    public static CowTextureDefinition.AtlasCoordinate getAtlasCoordinate(String variant, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getAtlasCoordinate(variant, faceName);
    }
    
    /**
     * Check if a cow variant exists.
     */
    public static boolean isValidVariant(String variant) {
        return CowTextureLoader.isValidVariant(variant);
    }
    
    /**
     * Get the base color for a cow variant.
     * 
     * @param variant The cow variant
     * @param colorType The color type (primary, secondary, accent)
     * @return Hex color string
     */
    public static String getBaseColor(String variant, String colorType) {
        return CowTextureLoader.getBaseColor(variant, colorType);
    }
    
    /**
     * Convert hex color to integer.
     */
    public static int hexColorToInt(String hexColor) {
        return CowTextureLoader.hexColorToInt(hexColor);
    }
    
    /**
     * Get the texture atlas grid size.
     */
    public static int getGridSize() {
        return 16; // Fixed grid size for cow textures
    }
    
    /**
     * Get the texture atlas pixel dimensions.
     */
    public static int getAtlasWidth() {
        return 256; // Fixed width for cow textures
    }
    
    public static int getAtlasHeight() {
        return 256; // Fixed height for cow textures
    }
    
    /**
     * Get all available cow variants.
     */
    public static String[] getAvailableVariants() {
        return CowTextureLoader.getAvailableVariants();
    }
    
    /**
     * Get display name for a cow variant.
     */
    public static String getDisplayName(String variant) {
        if (!initialized) {
            initialize();
        }
        
        CowTextureDefinition.CowVariant cowVariant = CowTextureLoader.getCowVariant(variant);
        if (cowVariant == null) {
            return variant;
        }
        
        return cowVariant.getDisplayName();
    }
    
    /**
     * Create OpenGL texture from ByteBuffer data.
     */
    private static int createOpenGLTexture(ByteBuffer data, int width, int height) {
        int textureId = GL11.glGenTextures();
        
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        
        // Set texture parameters
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        
        // Upload texture data
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 
                         0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        
        // Generate mipmaps (OpenGL 3.0+)
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        
        // Unbind texture
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        
        return textureId;
    }
    
    /**
     * Cleanup resources and clear cache.
     */
    public static void cleanup() {
        CowTextureLoader.clearCache();
        initialized = false;
        
        if (textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
        }
    }
    
    /**
     * Default UV coordinates for fallback rendering.
     */
    private static float[] getDefaultUVCoordinates() {
        // Return coordinates for the first tile (0,0) in a 16x16 grid
        float tileSize = 1.0f / 16.0f;
        return new float[]{
            0.0f, 0.0f,         // bottom-left
            tileSize, 0.0f,     // bottom-right
            tileSize, tileSize, // top-right
            0.0f, tileSize      // top-left
        };
    }
}