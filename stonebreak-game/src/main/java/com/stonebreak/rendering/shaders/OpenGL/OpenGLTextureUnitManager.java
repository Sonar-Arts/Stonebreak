package com.stonebreak.rendering.shaders.OpenGL;

import com.stonebreak.rendering.shaders.managers.ITextureUnitManager;
import com.stonebreak.rendering.shaders.exceptions.TextureUnitException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.BitSet;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * OpenGL implementation of texture unit manager.
 * Manages allocation and binding of texture units for multi-texture rendering.
 */
public class OpenGLTextureUnitManager implements ITextureUnitManager {
    
    private static final Logger LOGGER = Logger.getLogger(OpenGLTextureUnitManager.class.getName());
    
    private final ConcurrentMap<String, Integer> allocatedUnits;
    private final BitSet unitUsage; // Thread-safe for our use case (single GL context)
    private final AtomicInteger activeTextureUnit;
    private final int maxTextureUnits;
    
    public OpenGLTextureUnitManager() {
        // Query OpenGL for maximum texture units
        this.maxTextureUnits = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        this.allocatedUnits = new ConcurrentHashMap<>();
        this.unitUsage = new BitSet(maxTextureUnits);
        this.activeTextureUnit = new AtomicInteger(0);
        
        LOGGER.log(Level.INFO, "Initialized TextureUnitManager with {0} available texture units", maxTextureUnits);
    }
    
    @Override
    public synchronized int allocateTextureUnit(String name) throws TextureUnitException {
        if (name == null || name.trim().isEmpty()) {
            throw new TextureUnitException("Texture unit name cannot be null or empty", name);
        }
        
        String trimmedName = name.trim();
        
        // Check if already allocated
        Integer existingUnit = allocatedUnits.get(trimmedName);
        if (existingUnit != null) {
            LOGGER.log(Level.FINE, "Texture unit '{0}' already allocated to unit {1}", 
                      new Object[]{trimmedName, existingUnit});
            return GL_TEXTURE0 + existingUnit;
        }
        
        // Find next available unit
        int unit = unitUsage.nextClearBit(0);
        if (unit >= maxTextureUnits) {
            throw new TextureUnitException(
                "No available texture units (max: " + maxTextureUnits + ")", 
                trimmedName
            );
        }
        
        // Allocate the unit
        unitUsage.set(unit);
        allocatedUnits.put(trimmedName, unit);
        
        LOGGER.log(Level.FINE, "Allocated texture unit {0} for '{1}'", 
                  new Object[]{unit, trimmedName});
        
        return GL_TEXTURE0 + unit;
    }
    
    @Override
    public int getTextureUnit(String name) {
        if (name == null) {
            return -1;
        }
        
        Integer unit = allocatedUnits.get(name.trim());
        return unit != null ? GL_TEXTURE0 + unit : -1;
    }
    
    @Override
    public synchronized boolean releaseTextureUnit(String name) {
        if (name == null) {
            return false;
        }
        
        String trimmedName = name.trim();
        Integer unit = allocatedUnits.remove(trimmedName);
        
        if (unit != null) {
            unitUsage.clear(unit);
            LOGGER.log(Level.FINE, "Released texture unit {0} ('{1}')", 
                      new Object[]{unit, trimmedName});
            return true;
        }
        
        return false;
    }
    
    @Override
    public void activateTextureUnit(String name) throws TextureUnitException {
        if (name == null || name.trim().isEmpty()) {
            throw new TextureUnitException("Texture unit name cannot be null or empty", name);
        }
        
        String trimmedName = name.trim();
        Integer unit = allocatedUnits.get(trimmedName);
        
        if (unit == null) {
            throw new TextureUnitException("Texture unit '" + trimmedName + "' not allocated", trimmedName);
        }
        
        activateTextureUnit(unit);
    }
    
    @Override
    public void activateTextureUnit(int unit) {
        if (unit < 0 || unit >= maxTextureUnits) {
            LOGGER.log(Level.WARNING, "Invalid texture unit: {0} (max: {1})", 
                      new Object[]{unit, maxTextureUnits - 1});
            return;
        }
        
        glActiveTexture(GL_TEXTURE0 + unit);
        activeTextureUnit.set(unit);
        
        LOGGER.log(Level.FINEST, "Activated texture unit {0}", unit);
    }
    
    @Override
    public void bindTexture(int textureId, int target) {
        glBindTexture(target, textureId);
        
        LOGGER.log(Level.FINEST, "Bound texture {0} (target: {1}) to active unit {2}", 
                  new Object[]{textureId, target, activeTextureUnit.get()});
    }
    
    @Override
    public void bindTextureToUnit(String name, int textureId, int target) throws TextureUnitException {
        if (name == null || name.trim().isEmpty()) {
            throw new TextureUnitException("Texture unit name cannot be null or empty", name);
        }
        
        String trimmedName = name.trim();
        Integer unit = allocatedUnits.get(trimmedName);
        
        if (unit == null) {
            throw new TextureUnitException("Texture unit '" + trimmedName + "' not allocated", trimmedName);
        }
        
        // Save current active texture unit
        int previousUnit = activeTextureUnit.get();
        
        // Activate target unit and bind texture
        activateTextureUnit(unit);
        bindTexture(textureId, target);
        
        // Restore previous active unit
        if (previousUnit != unit) {
            activateTextureUnit(previousUnit);
        }
        
        LOGGER.log(Level.FINE, "Bound texture {0} (target: {1}) to unit '{2}' ({3})", 
                  new Object[]{textureId, target, trimmedName, unit});
    }
    
    @Override
    public int getMaxTextureUnits() {
        return maxTextureUnits;
    }
    
    @Override
    public int getAllocatedUnitCount() {
        return allocatedUnits.size();
    }
    
    @Override
    public int getAvailableUnitCount() {
        return maxTextureUnits - allocatedUnits.size();
    }
    
    @Override
    public boolean isUnitAllocated(String name) {
        if (name == null) {
            return false;
        }
        return allocatedUnits.containsKey(name.trim());
    }
    
    @Override
    public synchronized void releaseAllUnits() {
        int releasedCount = allocatedUnits.size();
        allocatedUnits.clear();
        unitUsage.clear();
        
        if (releasedCount > 0) {
            LOGGER.log(Level.INFO, "Released all {0} texture units", releasedCount);
        }
    }
    
    @Override
    public String getUsageSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Texture Unit Usage Summary ===\n");
        summary.append(String.format("Max Texture Units: %d\n", maxTextureUnits));
        summary.append(String.format("Allocated Units: %d\n", allocatedUnits.size()));
        summary.append(String.format("Available Units: %d\n", getAvailableUnitCount()));
        summary.append(String.format("Active Unit: %d\n", activeTextureUnit.get()));
        
        if (!allocatedUnits.isEmpty()) {
            summary.append("\nAllocated Units:\n");
            allocatedUnits.forEach((name, unit) -> {
                summary.append(String.format("  '%s' -> Unit %d (GL_TEXTURE%d)\n", name, unit, unit));
            });
        }
        
        summary.append("===================================");
        return summary.toString();
    }
}