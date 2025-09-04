package com.stonebreak.rendering.shaders.managers;

import com.stonebreak.rendering.shaders.exceptions.TextureUnitException;

/**
 * Interface for managing OpenGL texture units.
 * Essential for multi-texture rendering like skyboxes, terrain, etc.
 */
public interface ITextureUnitManager {
    
    /**
     * Allocates a texture unit for the given name.
     * @param name The name/identifier for the texture unit
     * @return The allocated texture unit number (GL_TEXTURE0 + unit)
     * @throws TextureUnitException if no units available
     */
    int allocateTextureUnit(String name) throws TextureUnitException;
    
    /**
     * Gets the texture unit for the given name.
     * @param name The name of the texture unit
     * @return The texture unit number, or -1 if not allocated
     */
    int getTextureUnit(String name);
    
    /**
     * Releases a texture unit by name.
     * @param name The name of the texture unit to release
     * @return true if unit was found and released
     */
    boolean releaseTextureUnit(String name);
    
    /**
     * Activates a texture unit by name.
     * @param name The name of the texture unit
     * @throws TextureUnitException if unit not found
     */
    void activateTextureUnit(String name) throws TextureUnitException;
    
    /**
     * Activates a texture unit by unit number.
     * @param unit The texture unit number (0-based, not GL_TEXTURE0+unit)
     */
    void activateTextureUnit(int unit);
    
    /**
     * Binds a texture to the currently active texture unit.
     * @param textureId The texture ID to bind
     * @param target The texture target (GL_TEXTURE_2D, GL_TEXTURE_CUBE_MAP, etc.)
     */
    void bindTexture(int textureId, int target);
    
    /**
     * Binds a texture to a specific texture unit by name.
     * @param name The name of the texture unit
     * @param textureId The texture ID to bind
     * @param target The texture target
     * @throws TextureUnitException if unit not found
     */
    void bindTextureToUnit(String name, int textureId, int target) throws TextureUnitException;
    
    /**
     * Gets the maximum number of texture units available.
     * @return The maximum texture units supported
     */
    int getMaxTextureUnits();
    
    /**
     * Gets the number of allocated texture units.
     * @return The count of allocated units
     */
    int getAllocatedUnitCount();
    
    /**
     * Gets the number of available texture units.
     * @return The count of available units
     */
    int getAvailableUnitCount();
    
    /**
     * Checks if a texture unit is allocated.
     * @param name The name of the texture unit
     * @return true if allocated
     */
    boolean isUnitAllocated(String name);
    
    /**
     * Releases all allocated texture units.
     */
    void releaseAllUnits();
    
    /**
     * Gets a summary of texture unit usage.
     * @return String containing usage summary
     */
    String getUsageSummary();
}