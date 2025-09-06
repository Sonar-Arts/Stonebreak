package com.stonebreak.rendering.core.API.commonBlockResources.resources;

/**
 * Core interface for block resources following Interface Segregation Principle.
 * Represents a single block resource that can be loaded, accessed, and disposed.
 * 
 * This interface follows RAII principles - resources are automatically managed
 * through their lifecycle.
 */
public interface BlockResource extends AutoCloseable {
    
    /**
     * Gets the unique identifier for this block resource.
     * @return The resource identifier (e.g., "minecraft:stone", "stonebreak:grass")
     */
    String getResourceId();
    
    /**
     * Checks if this resource is currently loaded and ready for use.
     * @return true if the resource is loaded and accessible
     */
    boolean isLoaded();
    
    /**
     * Gets the resource type for this block resource.
     * @return The type of this resource
     */
    ResourceType getResourceType();
    
    /**
     * Cleanup resources following RAII principles.
     * Called automatically when resource goes out of scope.
     */
    @Override
    void close();
    
    /**
     * Enumeration of resource types for type safety.
     */
    enum ResourceType {
        BLOCK_MODEL,
        ITEM_MODEL,
        TEXTURE_ATLAS,
        RENDER_LAYER
    }
}