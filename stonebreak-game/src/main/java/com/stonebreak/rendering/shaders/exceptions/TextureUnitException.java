package com.stonebreak.rendering.shaders.exceptions;

/**
 * Exception thrown when texture unit operations fail.
 */
public class TextureUnitException extends Exception {
    
    private final String unitName;
    
    public TextureUnitException(String message, String unitName) {
        super(message);
        this.unitName = unitName;
    }
    
    public TextureUnitException(String message, String unitName, Throwable cause) {
        super(message, cause);
        this.unitName = unitName;
    }
    
    public String getUnitName() {
        return unitName;
    }
    
    @Override
    public String toString() {
        return String.format("TextureUnitException[unit='%s']: %s", unitName, getMessage());
    }
}