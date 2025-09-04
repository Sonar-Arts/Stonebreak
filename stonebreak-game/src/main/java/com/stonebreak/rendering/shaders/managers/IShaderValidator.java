package com.stonebreak.rendering.shaders.managers;

import java.util.List;

/**
 * Interface for validating shader programs and operations.
 * Provides comprehensive validation to prevent visual corruption and runtime errors.
 */
public interface IShaderValidator {
    
    /**
     * Validates a shader program thoroughly.
     * @param programId The shader program ID to validate
     * @return ValidationResult containing validation status and issues
     */
    ValidationResult validateProgram(int programId);
    
    /**
     * Validates shader state before operations.
     * @param programId The shader program ID
     * @param operation The operation being performed
     * @return true if state is valid for the operation
     */
    boolean validateState(int programId, String operation);
    
    /**
     * Validates uniform compatibility with shader.
     * @param programId The shader program ID
     * @param uniformName The uniform name
     * @param expectedType The expected uniform type
     * @return true if uniform is compatible
     */
    boolean validateUniform(int programId, String uniformName, UniformType expectedType);
    
    /**
     * Validates texture unit bindings.
     * @param textureUnits Array of texture units to validate
     * @return ValidationResult containing validation status
     */
    ValidationResult validateTextureUnits(int[] textureUnits);
    
    /**
     * Validates uniform buffer compatibility.
     * @param programId The shader program ID
     * @param uniformBlockName The uniform block name
     * @param expectedSize The expected buffer size
     * @return true if uniform buffer is compatible
     */
    boolean validateUniformBuffer(int programId, String uniformBlockName, int expectedSize);
    
    /**
     * Validates geometry shader configuration.
     * @param inputType The input primitive type
     * @param outputType The output primitive type
     * @param maxOutputVertices Maximum output vertices
     * @return ValidationResult containing validation status
     */
    ValidationResult validateGeometryShaderConfig(int inputType, int outputType, int maxOutputVertices);
    
    /**
     * Performs a comprehensive system validation.
     * Checks OpenGL state, capabilities, and resource limits.
     * @return ValidationResult containing system validation status
     */
    ValidationResult validateSystem();
    
    /**
     * Gets detailed information about OpenGL capabilities.
     * @return String containing capability information
     */
    String getCapabilityInfo();
    
    /**
     * Enum for uniform types used in validation.
     */
    enum UniformType {
        INT, FLOAT, BOOL, VEC2, VEC3, VEC4, MAT4, SAMPLER_2D, SAMPLER_CUBE
    }
    
    /**
     * Result of validation operations.
     */
    class ValidationResult {
        private final boolean valid;
        private final List<String> issues;
        private final List<String> warnings;
        
        public ValidationResult(boolean valid, List<String> issues, List<String> warnings) {
            this.valid = valid;
            this.issues = issues;
            this.warnings = warnings;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public List<String> getIssues() {
            return issues;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }
        
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Valid: %s", valid));
            if (issues != null && !issues.isEmpty()) {
                sb.append(String.format(", Issues: %d", issues.size()));
            }
            if (warnings != null && !warnings.isEmpty()) {
                sb.append(String.format(", Warnings: %d", warnings.size()));
            }
            return sb.toString();
        }
    }
}