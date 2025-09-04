package com.stonebreak.rendering.shaders.exceptions;

/**
 * Exception thrown when uniform buffer operations fail.
 */
public class UniformBufferException extends Exception {
    
    private final String bufferName;
    
    public UniformBufferException(String message, String bufferName) {
        super(message);
        this.bufferName = bufferName;
    }
    
    public UniformBufferException(String message, String bufferName, Throwable cause) {
        super(message, cause);
        this.bufferName = bufferName;
    }
    
    public String getBufferName() {
        return bufferName;
    }
    
    @Override
    public String toString() {
        return String.format("UniformBufferException[buffer='%s']: %s", bufferName, getMessage());
    }
}