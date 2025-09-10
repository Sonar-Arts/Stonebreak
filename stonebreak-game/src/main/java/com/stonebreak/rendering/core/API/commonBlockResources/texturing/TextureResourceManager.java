package com.stonebreak.rendering.core.API.commonBlockResources.texturing;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.textures.TextureAtlas;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * CBR Texture Resource Manager - bridges Block Definitions to existing TextureAtlas.
 * 
 * Follows SOLID principles:
 * - Single Responsibility: Manages texture resource lookup and caching for CBR
 * - Open/Closed: Extensible through BlockDefinition system
 * - Liskov Substitution: Can replace direct TextureAtlas calls in renderers
 * - Interface Segregation: Focused on texture coordinate resolution
 * - Dependency Inversion: Depends on abstractions (BlockDefinition, TextureAtlas)
 * 
 * Implements RAII pattern for automatic resource cleanup.
 */
public class TextureResourceManager implements AutoCloseable {
    
    private final TextureAtlas textureAtlas;
    private final BlockDefinitionRegistry registry;
    private final Map<String, TextureCoordinateCache> coordinateCache;
    private boolean disposed = false;
    
    /**
     * Creates a texture resource manager.
     * 
     * @param textureAtlas The existing texture atlas system
     * @param registry The block definition registry
     */
    public TextureResourceManager(TextureAtlas textureAtlas, BlockDefinitionRegistry registry) {
        this.textureAtlas = textureAtlas;
        this.registry = registry;
        this.coordinateCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Resolves texture coordinates for a block definition.
     * 
     * @param definition The block definition
     * @return Texture coordinates for the block
     */
    public TextureCoordinates resolveBlockTexture(BlockDefinition definition) {
        if (disposed) {
            throw new IllegalStateException("TextureResourceManager has been disposed");
        }
        
        String cacheKey = "block_" + definition.getResourceId();
        TextureCoordinateCache cached = coordinateCache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return cached.getCoordinates();
        }
        
        // Resolve using render type
        TextureCoordinates coords = resolveByRenderType(definition);
        
        // Cache the result
        coordinateCache.put(cacheKey, new TextureCoordinateCache(coords));
        
        return coords;
    }
    
    /**
     * Resolves texture coordinates for a specific block face.
     * 
     * @param definition The block definition
     * @param face The face to resolve (north, south, east, west, up, down)
     * @return Texture coordinates for the specific face
     */
    public TextureCoordinates resolveBlockFaceTexture(BlockDefinition definition, String face) {
        if (disposed) {
            throw new IllegalStateException("TextureResourceManager has been disposed");
        }
        
        String cacheKey = "block_face_" + definition.getResourceId() + "_" + face;
        TextureCoordinateCache cached = coordinateCache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return cached.getCoordinates();
        }
        
        TextureCoordinates coords;
        
        // Use existing TextureAtlas face resolution for BlockType compatibility
        if (definition.getRenderType() == BlockDefinition.RenderType.CUBE_DIRECTIONAL) {
            coords = resolveDirectionalBlockFace(definition, face);
        } else {
            // For uniform blocks, all faces use the same texture
            coords = resolveByRenderType(definition);
        }
        
        coordinateCache.put(cacheKey, new TextureCoordinateCache(coords));
        return coords;
    }
    
    /**
     * Resolves texture coordinates for legacy BlockType enum (backward compatibility).
     * 
     * @param blockType The legacy block type
     * @return Texture coordinates
     */
    public TextureCoordinates resolveBlockType(BlockType blockType) {
        if (disposed) {
            throw new IllegalStateException("TextureResourceManager has been disposed");
        }
        
        // Use existing TextureAtlas method directly for compatibility
        float[] coords = textureAtlas.getTextureCoordinatesForBlock(blockType);
        return new TextureCoordinates(coords[0], coords[1], coords[2], coords[3]);
    }
    
    /**
     * Resolves texture coordinates for legacy ItemType enum (backward compatibility).
     * 
     * @param itemType The item type
     * @return Texture coordinates
     */
    public TextureCoordinates resolveItemType(ItemType itemType) {
        if (disposed) {
            throw new IllegalStateException("TextureResourceManager has been disposed");
        }
        
        // Use existing TextureAtlas method directly for compatibility
        float[] coords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        return new TextureCoordinates(coords[0], coords[1], coords[2], coords[3]);
    }
    
    /**
     * Gets the underlying texture atlas for direct access when needed.
     * 
     * @return The texture atlas instance
     */
    public TextureAtlas getTextureAtlas() {
        if (disposed) {
            throw new IllegalStateException("TextureResourceManager has been disposed");
        }
        return textureAtlas;
    }
    
    /**
     * Clears the texture coordinate cache.
     */
    public void clearCache() {
        coordinateCache.clear();
    }
    
    /**
     * Gets cache statistics for monitoring.
     * 
     * @return Cache statistics
     */
    public CacheStatistics getCacheStatistics() {
        int totalEntries = coordinateCache.size();
        int validEntries = (int) coordinateCache.values().stream()
                .mapToLong(cache -> cache.isValid() ? 1 : 0)
                .sum();
        
        return new CacheStatistics(totalEntries, validEntries);
    }
    
    // === Private Implementation Methods ===
    
    /**
     * Resolves texture coordinates based on block definition render type.
     */
    private TextureCoordinates resolveByRenderType(BlockDefinition definition) {
        switch (definition.getRenderType()) {
            case CUBE_ALL:
                return resolveUniformBlock(definition);
            case CUBE_DIRECTIONAL:
                return resolveDirectionalBlock(definition);
            case CROSS:
                return resolveCrossBlock(definition);
            case SPRITE:
                return resolveSprite(definition);
            default:
                return getErrorTextureCoordinates();
        }
    }
    
    /**
     * Resolves uniform cube blocks (same texture on all faces).
     */
    private TextureCoordinates resolveUniformBlock(BlockDefinition definition) {
        // Map to legacy BlockType for existing atlas lookup
        BlockType legacyType = mapResourceIdToBlockType(definition.getResourceId());
        if (legacyType != null) {
            float[] coords = textureAtlas.getTextureCoordinatesForBlock(legacyType);
            return new TextureCoordinates(coords[0], coords[1], coords[2], coords[3]);
        }
        
        return getErrorTextureCoordinates();
    }
    
    /**
     * Resolves directional blocks (different textures per face).
     */
    private TextureCoordinates resolveDirectionalBlock(BlockDefinition definition) {
        // Use the "top" face as default for directional blocks
        return resolveDirectionalBlockFace(definition, "up");
    }
    
    /**
     * Resolves specific face of directional blocks.
     */
    private TextureCoordinates resolveDirectionalBlockFace(BlockDefinition definition, String face) {
        BlockType legacyType = mapResourceIdToBlockType(definition.getResourceId());
        if (legacyType != null) {
            BlockType.Face legacyFace = mapStringToFace(face);
            float[] coords = textureAtlas.getBlockFaceUVs(legacyType, legacyFace);
            return new TextureCoordinates(coords[0], coords[1], coords[2], coords[3]);
        }
        
        return getErrorTextureCoordinates();
    }
    
    /**
     * Resolves cross-shaped blocks (flowers, etc.).
     * Handles both regular cross textures and cube cross format (xoxx/oooo/xoxx).
     */
    private TextureCoordinates resolveCrossBlock(BlockDefinition definition) {
        // Check if this is a cube cross format texture
        if (isCubeCrossFormat(definition)) {
            // For cube cross format, use the same texture coordinates as uniform blocks
            // The mesh creation will handle mapping to the middle row of the texture
            return resolveUniformBlock(definition);
        } else {
            // Regular cross blocks use uniform texture mapping
            return resolveUniformBlock(definition);
        }
    }
    
    /**
     * Checks if a block definition uses the cube cross format (xoxx/oooo/xoxx).
     * This is determined by checking the atlas metadata for "block_cube_cross" type.
     */
    private boolean isCubeCrossFormat(BlockDefinition definition) {
        // Both rose and dandelion are cross section flowers that use full texture format
        // The cube cross format (xoxx/oooo/xoxx) is not used by either flower in this implementation
        // All cross blocks should use the full texture height
        return false; // No cross blocks use cube cross format currently
    }
    
    /**
     * Resolves 2D sprite items.
     */
    private TextureCoordinates resolveSprite(BlockDefinition definition) {
        // For items, try to map to ItemType
        ItemType itemType = mapResourceIdToItemType(definition.getResourceId());
        if (itemType != null) {
            float[] coords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
            return new TextureCoordinates(coords[0], coords[1], coords[2], coords[3]);
        }
        
        return getErrorTextureCoordinates();
    }
    
    /**
     * Maps resource ID to legacy BlockType for compatibility.
     */
    private BlockType mapResourceIdToBlockType(String resourceId) {
        String blockName = extractBlockName(resourceId);
        
        // Map common block names to BlockType enum
        switch (blockName.toLowerCase()) {
            // Basic terrain blocks
            case "grass": return BlockType.GRASS;
            case "dirt": return BlockType.DIRT;
            case "stone": return BlockType.STONE;
            case "cobblestone": return BlockType.COBBLESTONE;
            case "gravel": return BlockType.GRAVEL;
            case "bedrock": return BlockType.BEDROCK;
            case "sand": return BlockType.SAND;
            case "red_sand": return BlockType.RED_SAND;
            case "sandstone": return BlockType.SANDSTONE;
            case "red_sandstone": return BlockType.RED_SANDSTONE;
            case "snowy_dirt": return BlockType.SNOWY_DIRT;
            
            // Ores and minerals
            case "coal_ore": return BlockType.COAL_ORE;
            case "iron_ore": return BlockType.IRON_ORE;
            case "magma": return BlockType.MAGMA;
            case "crystal": return BlockType.CRYSTAL;
            
            // Wood and plant blocks
            case "wood": return BlockType.WOOD;
            case "pine": return BlockType.PINE;
            case "elm_wood_log": return BlockType.ELM_WOOD_LOG;
            case "wood_planks": return BlockType.WOOD_PLANKS;
            case "pine_wood_planks": return BlockType.PINE_WOOD_PLANKS;
            case "elm_wood_planks": return BlockType.ELM_WOOD_PLANKS;
            case "leaves": return BlockType.LEAVES;
            case "snowy_leaves": return BlockType.SNOWY_LEAVES;
            case "elm_leaves": return BlockType.ELM_LEAVES;
            
            // Flowers
            case "dandelion": return BlockType.DANDELION;
            case "rose": return BlockType.ROSE;
            
            // Utility blocks
            case "workbench": return BlockType.WORKBENCH;
            
            // Environmental blocks
            case "water": return BlockType.WATER;
            case "ice": return BlockType.ICE;
            case "snow": return BlockType.SNOW;
            
            // Add more mappings as needed
            default: return null;
        }
    }
    
    /**
     * Maps resource ID to legacy ItemType for compatibility.
     */
    private ItemType mapResourceIdToItemType(String resourceId) {
        String itemName = extractBlockName(resourceId);
        
        switch (itemName.toLowerCase()) {
            case "stick": return ItemType.STICK;
            case "wooden_pickaxe": return ItemType.WOODEN_PICKAXE;
            case "wooden_axe": return ItemType.WOODEN_AXE;
            default: return null;
        }
    }
    
    /**
     * Extracts block/item name from resource ID.
     */
    private String extractBlockName(String resourceId) {
        int colonIndex = resourceId.indexOf(':');
        return colonIndex > 0 ? resourceId.substring(colonIndex + 1) : resourceId;
    }
    
    /**
     * Maps string face name to legacy BlockType.Face enum.
     */
    private BlockType.Face mapStringToFace(String face) {
        switch (face.toLowerCase()) {
            case "up": case "top": return BlockType.Face.TOP;
            case "down": case "bottom": return BlockType.Face.BOTTOM;
            case "north": return BlockType.Face.SIDE_NORTH;
            case "south": return BlockType.Face.SIDE_SOUTH;
            case "east": return BlockType.Face.SIDE_EAST;
            case "west": return BlockType.Face.SIDE_WEST;
            default: return BlockType.Face.TOP;
        }
    }
    
    /**
     * Gets error texture coordinates for fallback.
     */
    private TextureCoordinates getErrorTextureCoordinates() {
        // Use atlas error texture if available
        float[] coords = new float[]{0.0f, 0.0f, 1.0f, 1.0f}; // fallback
        return new TextureCoordinates(coords[0], coords[1], coords[2], coords[3]);
    }
    
    @Override
    public void close() {
        if (!disposed) {
            coordinateCache.clear();
            disposed = true;
        }
    }
    
    // === Inner Classes ===
    
    /**
     * Immutable texture coordinates.
     */
    public static class TextureCoordinates {
        private final float u1, v1, u2, v2;
        
        public TextureCoordinates(float u1, float v1, float u2, float v2) {
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
        }
        
        public float getU1() { return u1; }
        public float getV1() { return v1; }
        public float getU2() { return u2; }
        public float getV2() { return v2; }
        
        public float[] toArray() {
            return new float[]{u1, v1, u2, v2};
        }
        
        @Override
        public String toString() {
            return String.format("TextureCoords[%.3f, %.3f, %.3f, %.3f]", u1, v1, u2, v2);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TextureCoordinates that = (TextureCoordinates) obj;
            return Float.compare(that.u1, u1) == 0 &&
                   Float.compare(that.v1, v1) == 0 &&
                   Float.compare(that.u2, u2) == 0 &&
                   Float.compare(that.v2, v2) == 0;
        }
        
        @Override
        public int hashCode() {
            return java.util.Objects.hash(u1, v1, u2, v2);
        }
    }
    
    /**
     * Cache entry with validation.
     */
    private static class TextureCoordinateCache {
        private final TextureCoordinates coordinates;
        private final long timestamp;
        private static final long CACHE_VALIDITY_MS = 300_000; // 5 minutes
        
        public TextureCoordinateCache(TextureCoordinates coordinates) {
            this.coordinates = coordinates;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_VALIDITY_MS;
        }
        
        public TextureCoordinates getCoordinates() {
            return coordinates;
        }
    }
    
    /**
     * Cache statistics for monitoring.
     */
    public static class CacheStatistics {
        private final int totalEntries;
        private final int validEntries;
        
        public CacheStatistics(int totalEntries, int validEntries) {
            this.totalEntries = totalEntries;
            this.validEntries = validEntries;
        }
        
        public int getTotalEntries() { return totalEntries; }
        public int getValidEntries() { return validEntries; }
        public int getExpiredEntries() { return totalEntries - validEntries; }
        public double getHitRatio() { 
            return totalEntries > 0 ? (double) validEntries / totalEntries : 0.0; 
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats[total=%d, valid=%d, expired=%d, hitRatio=%.2f]",
                               totalEntries, validEntries, getExpiredEntries(), getHitRatio());
        }
    }
}