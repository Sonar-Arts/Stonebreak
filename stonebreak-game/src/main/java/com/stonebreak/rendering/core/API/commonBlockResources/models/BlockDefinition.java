package com.stonebreak.rendering.core.API.commonBlockResources.models;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable block definition following SOLID principles.
 * Represents a complete block definition with model, texture, and rendering information.
 * 
 * Follows Value Object pattern - immutable and equality based on content.
 */
public final class BlockDefinition {
    
    private final String resourceId;
    private final int numericId;
    private final RenderType renderType;
    private final String modelPath;
    private final Map<String, String> textureVariables;
    private final RenderLayer renderLayer;
    
    private BlockDefinition(Builder builder) {
        this.resourceId = Objects.requireNonNull(builder.resourceId, "Resource ID cannot be null");
        this.numericId = builder.numericId;
        this.renderType = Objects.requireNonNull(builder.renderType, "Render type cannot be null");
        this.modelPath = builder.modelPath;
        this.textureVariables = Map.copyOf(builder.textureVariables);
        this.renderLayer = Objects.requireNonNull(builder.renderLayer, "Render layer cannot be null");
    }
    
    public String getResourceId() { return resourceId; }
    public int getNumericId() { return numericId; }
    public RenderType getRenderType() { return renderType; }
    public Optional<String> getModelPath() { return Optional.ofNullable(modelPath); }
    public Map<String, String> getTextureVariables() { return textureVariables; }
    public RenderLayer getRenderLayer() { return renderLayer; }
    
    /**
     * Gets the namespace from the resource ID (e.g., "stonebreak" from "stonebreak:grass")
     */
    public String getNamespace() {
        int colonIndex = resourceId.indexOf(':');
        return colonIndex > 0 ? resourceId.substring(0, colonIndex) : "minecraft";
    }
    
    /**
     * Gets the block name from the resource ID (e.g., "grass" from "stonebreak:grass")
     */
    public String getBlockName() {
        int colonIndex = resourceId.indexOf(':');
        return colonIndex > 0 ? resourceId.substring(colonIndex + 1) : resourceId;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BlockDefinition)) return false;
        BlockDefinition that = (BlockDefinition) obj;
        return numericId == that.numericId && 
               Objects.equals(resourceId, that.resourceId) &&
               renderType == that.renderType &&
               Objects.equals(modelPath, that.modelPath) &&
               Objects.equals(textureVariables, that.textureVariables) &&
               renderLayer == that.renderLayer;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(resourceId, numericId, renderType, modelPath, textureVariables, renderLayer);
    }
    
    @Override
    public String toString() {
        return String.format("BlockDefinition{id='%s', numericId=%d, renderType=%s, renderLayer=%s}", 
                           resourceId, numericId, renderType, renderLayer);
    }
    
    /**
     * Render types that map to existing texture system
     */
    public enum RenderType {
        CUBE_ALL("cube_all"),           // Uses single texture for all faces
        CUBE_DIRECTIONAL("cube"),       // Uses different textures per face  
        CROSS("cross"),                 // Cross-shaped like flowers
        SPRITE("generated");            // 2D item sprite
        
        private final String modelTemplate;
        
        RenderType(String modelTemplate) {
            this.modelTemplate = modelTemplate;
        }
        
        public String getModelTemplate() { return modelTemplate; }
        
        /**
         * Maps legacy texture types to render types
         */
        public static RenderType fromLegacyTextureType(String textureType) {
            return switch (textureType.toLowerCase()) {
                case "uniform" -> CUBE_ALL;
                case "cube_net" -> CUBE_DIRECTIONAL;
                case "cross" -> CROSS;
                default -> CUBE_ALL;
            };
        }
    }
    
    /**
     * Render layers for transparency and culling
     */
    public enum RenderLayer {
        OPAQUE,      // Solid blocks - fastest rendering
        CUTOUT,      // Transparent pixels (like leaves) - alpha testing
        TRANSLUCENT  // Semi-transparent - slowest, needs sorting
    }
    
    /**
     * Builder pattern for creating BlockDefinitions
     */
    public static class Builder {
        private String resourceId;
        private int numericId = -1;
        private RenderType renderType = RenderType.CUBE_ALL;
        private String modelPath;
        private Map<String, String> textureVariables = Map.of();
        private RenderLayer renderLayer = RenderLayer.OPAQUE;
        
        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }
        
        public Builder numericId(int numericId) {
            this.numericId = numericId;
            return this;
        }
        
        public Builder renderType(RenderType renderType) {
            this.renderType = renderType;
            return this;
        }
        
        public Builder modelPath(String modelPath) {
            this.modelPath = modelPath;
            return this;
        }
        
        public Builder textureVariables(Map<String, String> textureVariables) {
            this.textureVariables = textureVariables;
            return this;
        }
        
        public Builder renderLayer(RenderLayer renderLayer) {
            this.renderLayer = renderLayer;
            return this;
        }
        
        public BlockDefinition build() {
            if (numericId < 0) {
                throw new IllegalStateException("Numeric ID must be set and non-negative");
            }
            return new BlockDefinition(this);
        }
    }
}