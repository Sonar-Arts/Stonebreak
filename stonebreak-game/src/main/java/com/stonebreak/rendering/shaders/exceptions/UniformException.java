package com.stonebreak.rendering.shaders.exceptions;

/**
 * Exception thrown when uniform operations fail.
 */
public class UniformException extends Exception {
    
    private final String uniformName;
    
    public UniformException(String message, String uniformName) {
        super(message);
        this.uniformName = uniformName;
    }
    
    public UniformException(String message, String uniformName, Throwable cause) {
        super(message, cause);
        this.uniformName = uniformName;
    }
    
    public String getUniformName() {
        return uniformName;
    }
    
    @Override
    public String toString() {
        return String.format("UniformException[uniform='%s']: %s", uniformName, getMessage());
    }
}