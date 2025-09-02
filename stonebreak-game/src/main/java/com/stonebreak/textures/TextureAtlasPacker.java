package com.stonebreak.textures;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Efficiently packs textures into atlas with optimal layout.
 * Uses bin packing algorithm to minimize wasted space.
 */
public class TextureAtlasPacker {
    
    /**
     * Represents a texture to be packed into the atlas.
     */
    public static class TextureEntry {
        public final String name;
        public final BufferedImage image;
        public final int width;
        public final int height;
        public final TextureResourceLoader.TextureType type;
        
        // Coordinates in the atlas (set during packing)
        public int atlasX;
        public int atlasY;
        
        public TextureEntry(String name, BufferedImage image, TextureResourceLoader.TextureType type) {
            this.name = name;
            this.image = image;
            this.width = image.getWidth();
            this.height = image.getHeight();
            this.type = type;
            this.atlasX = -1;
            this.atlasY = -1;
        }
    }
    
    
    /**
     * Result of packing operation.
     */
    public static class PackResult {
        public final List<TextureEntry> packedTextures;
        public final int atlasWidth;
        public final int atlasHeight;
        public final double utilizationPercent;
        public final BufferedImage atlasImage;
        
        public PackResult(List<TextureEntry> packedTextures, int atlasWidth, int atlasHeight, 
                         double utilizationPercent, BufferedImage atlasImage) {
            this.packedTextures = packedTextures;
            this.atlasWidth = atlasWidth;
            this.atlasHeight = atlasHeight;
            this.utilizationPercent = utilizationPercent;
            this.atlasImage = atlasImage;
        }
    }
    
    private final AtlasSizeOptimizer sizeOptimizer;
    
    public TextureAtlasPacker() {
        this.sizeOptimizer = new AtlasSizeOptimizer();
    }
    
    /**
     * Pack textures into an optimal atlas layout.
     * @param textures List of textures to pack
     * @return PackResult containing packed textures and atlas image
     */
    public PackResult packTextures(List<TextureEntry> textures) {
        if (textures.isEmpty()) {
            throw new IllegalArgumentException("Cannot pack empty texture list");
        }
        
        // Sort textures by size (largest first) for better packing efficiency
        List<TextureEntry> sortedTextures = new ArrayList<>(textures);
        sortedTextures.sort((a, b) -> Integer.compare(b.width * b.height, a.width * a.height));
        
        // Count texture types for optimal sizing
        int uniformBlocks = (int) sortedTextures.stream()
            .filter(t -> t.type == TextureResourceLoader.TextureType.BLOCK_UNIFORM)
            .count();
        int cubeCrossBlockFaces = (int) sortedTextures.stream()
            .filter(t -> t.type == TextureResourceLoader.TextureType.BLOCK_CUBE_CROSS)
            .count();
        int items = (int) sortedTextures.stream()
            .filter(t -> t.type == TextureResourceLoader.TextureType.ITEM)
            .count();
        int errors = (int) sortedTextures.stream()
            .filter(t -> t.type == TextureResourceLoader.TextureType.ERROR)
            .count();
        
        // Since cube cross faces are already extracted, we pass them as individual uniforms
        // The AtlasSizeOptimizer will calculate: uniformBlocks + items + errors = total tiles
        AtlasSizeOptimizer.TextureRequirements requirements = 
            new AtlasSizeOptimizer.TextureRequirements(uniformBlocks + cubeCrossBlockFaces, 0, items, errors);
        
        AtlasSizeOptimizer.AtlasSize sizeResult = AtlasSizeOptimizer.calculateOptimalSize(requirements);
        
        int atlasWidth = sizeResult.width;
        int atlasHeight = sizeResult.height;
        
        // Try packing with calculated dimensions
        List<TextureEntry> packedTextures = attemptPacking(sortedTextures, atlasWidth, atlasHeight);
        
        // If packing failed, try larger sizes
        while (packedTextures.isEmpty() && atlasWidth < 4096) {
            atlasWidth *= 2;
            atlasHeight *= 2;
            packedTextures = attemptPacking(sortedTextures, atlasWidth, atlasHeight);
        }
        
        if (packedTextures.isEmpty()) {
            throw new RuntimeException("Failed to pack textures even with maximum atlas size (4096x4096)");
        }
        
        // Generate the actual atlas image
        BufferedImage atlasImage = generateAtlasImage(packedTextures, atlasWidth, atlasHeight);
        
        // Calculate utilization
        int usedPixels = packedTextures.stream().mapToInt(t -> t.width * t.height).sum();
        double utilizationPercent = (double) usedPixels / (atlasWidth * atlasHeight) * 100.0;
        
        return new PackResult(packedTextures, atlasWidth, atlasHeight, utilizationPercent, atlasImage);
    }
    
    /**
     * Attempt to pack textures into given dimensions using grid-based packing.
     * Since all textures are 16x16, we can use a simple grid layout for optimal space utilization.
     * @param textures Sorted list of textures to pack
     * @param atlasWidth Target atlas width
     * @param atlasHeight Target atlas height
     * @return List of successfully packed textures with coordinates set, empty if packing failed
     */
    private List<TextureEntry> attemptPacking(List<TextureEntry> textures, int atlasWidth, int atlasHeight) {
        final int TILE_SIZE = 16; // All textures are 16x16
        int tilesPerRow = atlasWidth / TILE_SIZE;
        int tilesPerColumn = atlasHeight / TILE_SIZE;
        int totalTileSlots = tilesPerRow * tilesPerColumn;
        
        // Check if we have enough grid slots
        if (textures.size() > totalTileSlots) {
            return Collections.emptyList(); // Not enough space
        }
        
        List<TextureEntry> packedTextures = new ArrayList<>();
        
        // Pack textures in grid positions
        for (int i = 0; i < textures.size(); i++) {
            TextureEntry texture = textures.get(i);
            
            // Calculate grid position (row-major order)
            int tileX = i % tilesPerRow;
            int tileY = i / tilesPerRow;
            
            // Convert to pixel coordinates
            texture.atlasX = tileX * TILE_SIZE;
            texture.atlasY = tileY * TILE_SIZE;
            
            packedTextures.add(texture);
        }
        
        return packedTextures;
    }
    
    
    
    /**
     * Generate the final atlas image by drawing all packed textures.
     */
    private BufferedImage generateAtlasImage(List<TextureEntry> packedTextures, int atlasWidth, int atlasHeight) {
        BufferedImage atlasImage = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        var graphics = atlasImage.createGraphics();
        
        try {
            // Clear background to transparent
            graphics.setComposite(java.awt.AlphaComposite.Clear);
            graphics.fillRect(0, 0, atlasWidth, atlasHeight);
            graphics.setComposite(java.awt.AlphaComposite.SrcOver);
            
            // Draw each texture at its packed position
            for (TextureEntry texture : packedTextures) {
                graphics.drawImage(texture.image, texture.atlasX, texture.atlasY, null);
            }
        } finally {
            graphics.dispose();
        }
        
        return atlasImage;
    }
    
    /**
     * Get texture coordinate data for atlas metadata.
     * @param packedTextures List of packed textures with coordinates
     * @return Map of texture name to coordinate data
     */
    public Map<String, Map<String, Object>> getTextureCoordinates(List<TextureEntry> packedTextures) {
        Map<String, Map<String, Object>> coordinates = new HashMap<>();
        
        for (TextureEntry texture : packedTextures) {
            Map<String, Object> coords = new HashMap<>();
            coords.put("x", texture.atlasX);
            coords.put("y", texture.atlasY);
            coords.put("width", texture.width);
            coords.put("height", texture.height);
            coords.put("type", getTypeString(texture.type));
            
            coordinates.put(texture.name, coords);
        }
        
        return coordinates;
    }
    
    /**
     * Convert texture type enum to string for JSON metadata.
     */
    private String getTypeString(TextureResourceLoader.TextureType type) {
        switch (type) {
            case BLOCK_UNIFORM: return "block_uniform";
            case BLOCK_CUBE_CROSS: return "block_cube_cross";
            case ITEM: return "item";
            case ERROR: return "error";
            default: return "unknown";
        }
    }
}