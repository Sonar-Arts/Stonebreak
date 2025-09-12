package com.stonebreak.rendering.shaders.exceptions;

/**
 * Exception thrown when shader program linking fails.
 */
public class ShaderLinkException extends Exception {
    
    private final int programId;
    
    public ShaderLinkException(String message, int programId) {
        super(message);
        this.programId = programId;
    }
    
    public ShaderLinkException(String message, int programId, Throwable cause) {
        super(message, cause);
        this.programId = programId;
    }
    
    public int getProgramId() {
        return programId;
    }
    
    @Override
    public String toString() {
        return String.format("ShaderLinkException[program=%d]: %s", programId, getMessage());
    }
}