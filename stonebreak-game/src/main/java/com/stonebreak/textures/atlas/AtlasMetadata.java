package com.stonebreak.textures.atlas;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.HashMap;

/**
 * Data structure for parsed texture atlas metadata from JSON.
 * Contains all information needed for runtime texture coordinate lookups.
 */
public class AtlasMetadata {
    
    @JsonProperty("atlasVersion")
    private String atlasVersion;
    
    @JsonProperty("schemaVersion")
    private String schemaVersion;
    
    @JsonProperty("generatedAt")
    private String generatedAt;
    
    @JsonProperty("textureSize")
    private int textureSize;
    
    @JsonProperty("atlasSize")
    private AtlasSize atlasSize;
    
    @JsonProperty("textures")
    private Map<String, TextureEntry> textures;
    
    // Computed lookup maps for performance
    private transient Map<String, TextureEntry> blockTextureMap;
    private transient Map<String, TextureEntry> itemTextureMap;
    
    /**
     * Atlas size information.
     */
    public static class AtlasSize {
        @JsonProperty("width")
        private int width;
        
        @JsonProperty("height") 
        private int height;
        
        @JsonProperty("calculatedOptimal")
        private boolean calculatedOptimal;
        
        @JsonProperty("utilizationPercent")
        private double utilizationPercent;
        
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public boolean isCalculatedOptimal() { return calculatedOptimal; }
        public double getUtilizationPercent() { return utilizationPercent; }
    }
    
    /**
     * Individual texture entry in the atlas.
     */
    public static class TextureEntry {
        @JsonProperty("x")
        private int x;
        
        @JsonProperty("y")
        private int y;
        
        @JsonProperty("width")
        private int width;
        
        @JsonProperty("height")
        private int height;
        
        @JsonProperty("type")
        private String type;
        
        public int getX() { return x; }
        public int getY() { return y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getType() { return type; }
        
        /**
         * Convert pixel coordinates to UV coordinates.
         * @param atlasWidth Total atlas width in pixels
         * @param atlasHeight Total atlas height in pixels
         * @return Array of UV coordinates [u1, v1, u2, v2]
         */
        public float[] getUVCoordinates(int atlasWidth, int atlasHeight) {
            float u1 = (float) x / atlasWidth;
            float v1 = (float) y / atlasHeight;
            float u2 = (float) (x + width) / atlasWidth;
            float v2 = (float) (y + height) / atlasHeight;
            
            return new float[]{u1, v1, u2, v2};
        }
    }
    
    // Getters
    public String getAtlasVersion() { return atlasVersion; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getGeneratedAt() { return generatedAt; }
    public int getTextureSize() { return textureSize; }
    public AtlasSize getAtlasSize() { return atlasSize; }
    public Map<String, TextureEntry> getTextures() { return textures; }
    
    /**
     * Initialize computed lookup maps after deserialization.
     * Should be called after loading from JSON.
     */
    public void initializeLookupMaps() {
        blockTextureMap = new HashMap<>();
        itemTextureMap = new HashMap<>();
        
        if (textures != null) {
            for (Map.Entry<String, TextureEntry> entry : textures.entrySet()) {
                String key = entry.getKey();
                TextureEntry texture = entry.getValue();
                
                if (texture.getType().startsWith("block")) {
                    blockTextureMap.put(key, texture);
                } else if (texture.getType().equals("item")) {
                    itemTextureMap.put(key, texture);
                }
            }
        }
    }
    
    /**
     * Find texture by name (direct lookup).
     * @param textureName The texture name
     * @return TextureEntry if found, null otherwise
     */
    public TextureEntry findTexture(String textureName) {
        return textures != null ? textures.get(textureName) : null;
    }
    
    /**
     * Find block texture by name and face.
     * @param blockName Block name (e.g., "grass_block")
     * @param face Face name (e.g., "top", "bottom", "north")
     * @return TextureEntry if found, null otherwise
     */
    public TextureEntry findBlockTexture(String blockName, String face) {
        if (blockTextureMap == null) {
            initializeLookupMaps();
        }
        
        // Try specific face first
        String faceTextureName = blockName + "_" + face;
        TextureEntry texture = blockTextureMap.get(faceTextureName);
        
        if (texture == null) {
            // Try without face for uniform textures
            texture = blockTextureMap.get(blockName);
        }
        
        return texture;
    }
    
    /**
     * Find item texture by name.
     * @param itemName Item name
     * @return TextureEntry if found, null otherwise
     */
    public TextureEntry findItemTexture(String itemName) {
        if (itemTextureMap == null) {
            initializeLookupMaps();
        }
        
        return itemTextureMap.get(itemName);
    }
    
    /**
     * Get error texture entry (Errockson.gif fallback).
     * @return TextureEntry for error texture, null if not found
     */
    public TextureEntry getErrorTexture() {
        // Look for various error texture names
        TextureEntry errorTexture = findTexture("errockson");
        if (errorTexture == null) {
            errorTexture = findTexture("error");
        }
        if (errorTexture == null) {
            errorTexture = findTexture("missing");
        }
        
        return errorTexture;
    }
    
    /**
     * Get atlas dimensions for UV coordinate calculations.
     * @return Array [width, height] in pixels
     */
    public int[] getAtlasDimensions() {
        if (atlasSize != null) {
            return new int[]{atlasSize.getWidth(), atlasSize.getHeight()};
        }
        return new int[]{256, 256}; // Default fallback
    }
    
    @Override
    public String toString() {
        return String.format("AtlasMetadata{version=%s, size=%dx%d, textures=%d}", 
                           atlasVersion, 
                           atlasSize != null ? atlasSize.getWidth() : 0,
                           atlasSize != null ? atlasSize.getHeight() : 0,
                           textures != null ? textures.size() : 0);
    }
}