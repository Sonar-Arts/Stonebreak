package com.stonebreak.rendering.shaders.OpenGL;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.nio.IntBuffer;

import com.stonebreak.rendering.shaders.GeometryShaderSupport;
import com.stonebreak.rendering.shaders.managers.IShaderValidator;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * OpenGL implementation of shader validator.
 * Provides comprehensive validation to prevent visual corruption and runtime errors.
 */
public class OpenGLShaderValidator implements IShaderValidator {
    
    private static final Logger LOGGER = Logger.getLogger(OpenGLShaderValidator.class.getName());
    
    @Override
    public ValidationResult validateProgram(int programId) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean valid = true;
        
        if (programId <= 0) {
            issues.add("Invalid program ID: " + programId);
            return new ValidationResult(false, issues, warnings);
        }
        
        // Check if program exists
        if (!glIsProgram(programId)) {
            issues.add("Program ID " + programId + " is not a valid program object");
            return new ValidationResult(false, issues, warnings);
        }
        
        // Check link status
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            issues.add("Program is not linked: " + glGetProgramInfoLog(programId));
            valid = false;
        }
        
        // Check validation status
        glValidateProgram(programId);
        if (glGetProgrami(programId, GL_VALIDATE_STATUS) == 0) {
            warnings.add("Program validation warning: " + glGetProgramInfoLog(programId));
        }
        
        // Check attached shaders
        int attachedShaders = glGetProgrami(programId, GL_ATTACHED_SHADERS);
        if (attachedShaders == 0) {
            issues.add("No shaders attached to program");
            valid = false;
        } else if (attachedShaders < 2) {
            warnings.add("Only " + attachedShaders + " shader(s) attached - typically need vertex and fragment");
        }
        
        // Check active uniforms
        int activeUniforms = glGetProgrami(programId, GL_ACTIVE_UNIFORMS);
        if (activeUniforms > 0) {
            LOGGER.log(Level.FINE, "Program {0} has {1} active uniforms", 
                      new Object[]{programId, activeUniforms});
        }
        
        // Check active attributes
        int activeAttributes = glGetProgrami(programId, GL_ACTIVE_ATTRIBUTES);
        if (activeAttributes == 0) {
            warnings.add("No active vertex attributes found");
        }
        
        return new ValidationResult(valid, issues, warnings);
    }
    
    @Override
    public boolean validateState(int programId, String operation) {
        if (operation == null) {
            LOGGER.log(Level.WARNING, "Null operation provided for state validation");
            return false;
        }
        
        // Check current program state
        int currentProgram = glGetInteger(GL_CURRENT_PROGRAM);
        
        switch (operation.toLowerCase()) {
            case "bind":
            case "use":
                return validateProgram(programId).isValid();
                
            case "uniform":
                if (currentProgram != programId) {
                    LOGGER.log(Level.WARNING, "Setting uniform on program {0} but program {1} is currently active", 
                              new Object[]{programId, currentProgram});
                    return false;
                }
                return true;
                
            case "draw":
                if (currentProgram == 0) {
                    LOGGER.log(Level.WARNING, "Attempting to draw with no shader program active");
                    return false;
                }
                return true;
                
            default:
                LOGGER.log(Level.FINE, "Unknown operation for state validation: {0}", operation);
                return true;
        }
    }
    
    @Override
    public boolean validateUniform(int programId, String uniformName, UniformType expectedType) {
        if (uniformName == null || uniformName.trim().isEmpty()) {
            LOGGER.log(Level.WARNING, "Invalid uniform name for validation");
            return false;
        }
        
        if (!glIsProgram(programId)) {
            LOGGER.log(Level.WARNING, "Invalid program ID for uniform validation: {0}", programId);
            return false;
        }
        
        int location = glGetUniformLocation(programId, uniformName.trim());
        if (location < 0) {
            LOGGER.log(Level.WARNING, "Uniform '{0}' not found in program {1}", 
                      new Object[]{uniformName, programId});
            return false;
        }
        
        // Get uniform type from OpenGL
        IntBuffer sizeBuffer = BufferUtils.createIntBuffer(1);
        IntBuffer typeBuffer = BufferUtils.createIntBuffer(1);
        glGetActiveUniform(programId, location, sizeBuffer, typeBuffer);
        
        // Validate type compatibility
        boolean typeValid = isTypeCompatible(typeBuffer.get(0), expectedType);
        if (!typeValid) {
            LOGGER.log(Level.WARNING, "Uniform '{0}' type mismatch. Expected: {1}, Actual: {2}", 
                      new Object[]{uniformName, expectedType, getGLTypeName(typeBuffer.get(0))});
        }
        
        return typeValid;
    }
    
    @Override
    public ValidationResult validateTextureUnits(int[] textureUnits) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean valid = true;
        
        if (textureUnits == null) {
            issues.add("Null texture units array");
            return new ValidationResult(false, issues, warnings);
        }
        
        int maxTextureUnits = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        
        for (int i = 0; i < textureUnits.length; i++) {
            int unit = textureUnits[i];
            
            if (unit < 0) {
                issues.add("Invalid texture unit " + unit + " at index " + i);
                valid = false;
            } else if (unit >= maxTextureUnits) {
                issues.add("Texture unit " + unit + " exceeds maximum " + (maxTextureUnits - 1));
                valid = false;
            }
        }
        
        // Check for duplicates
        for (int i = 0; i < textureUnits.length - 1; i++) {
            for (int j = i + 1; j < textureUnits.length; j++) {
                if (textureUnits[i] == textureUnits[j]) {
                    warnings.add("Duplicate texture unit " + textureUnits[i] + " at indices " + i + " and " + j);
                }
            }
        }
        
        return new ValidationResult(valid, issues, warnings);
    }
    
    @Override
    public boolean validateUniformBuffer(int programId, String uniformBlockName, int expectedSize) {
        if (uniformBlockName == null || uniformBlockName.trim().isEmpty()) {
            LOGGER.log(Level.WARNING, "Invalid uniform block name");
            return false;
        }
        
        if (!glIsProgram(programId)) {
            LOGGER.log(Level.WARNING, "Invalid program ID for uniform buffer validation: {0}", programId);
            return false;
        }
        
        int blockIndex = glGetUniformBlockIndex(programId, uniformBlockName.trim());
        if (blockIndex == GL_INVALID_INDEX) {
            LOGGER.log(Level.WARNING, "Uniform block '{0}' not found in program {1}", 
                      new Object[]{uniformBlockName, programId});
            return false;
        }
        
        // Get block size
        int[] blockSize = new int[1];
        glGetActiveUniformBlockiv(programId, blockIndex, GL_UNIFORM_BLOCK_DATA_SIZE, blockSize);
        
        if (expectedSize > 0 && blockSize[0] != expectedSize) {
            LOGGER.log(Level.WARNING, "Uniform block '{0}' size mismatch. Expected: {1}, Actual: {2}", 
                      new Object[]{uniformBlockName, expectedSize, blockSize[0]});
            return false;
        }
        
        return true;
    }
    
    @Override
    public ValidationResult validateGeometryShaderConfig(int inputType, int outputType, int maxOutputVertices) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean valid = true;
        
        if (!GeometryShaderSupport.isGeometryShaderSupported()) {
            issues.add("Geometry shaders not supported on this OpenGL version");
            return new ValidationResult(false, issues, warnings);
        }
        
        // Validate input type
        if (!isValidGeometryInputType(inputType)) {
            issues.add("Invalid geometry shader input type: " + inputType);
            valid = false;
        }
        
        // Validate output type
        if (!isValidGeometryOutputType(outputType)) {
            issues.add("Invalid geometry shader output type: " + outputType);
            valid = false;
        }
        
        // Validate output vertices
        if (maxOutputVertices <= 0) {
            issues.add("Max output vertices must be positive: " + maxOutputVertices);
            valid = false;
        } else {
            int maxSupported = glGetInteger(GL_MAX_GEOMETRY_OUTPUT_VERTICES);
            if (maxOutputVertices > maxSupported) {
                issues.add("Max output vertices " + maxOutputVertices + " exceeds limit " + maxSupported);
                valid = false;
            } else if (maxOutputVertices > maxSupported * 0.8) {
                warnings.add("High output vertex count may impact performance: " + maxOutputVertices);
            }
        }
        
        return new ValidationResult(valid, issues, warnings);
    }
    
    @Override
    public ValidationResult validateSystem() {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean valid = true;
        
        // Check OpenGL context
        try {
            String version = glGetString(GL_VERSION);
            if (version == null || version.isEmpty()) {
                issues.add("No OpenGL context or invalid context");
                return new ValidationResult(false, issues, warnings);
            }
            
            // Parse version
            String[] parts = version.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1].split("\\s")[0]);
                
                if (major < 3 || (major == 3 && minor < 0)) {
                    warnings.add("OpenGL version " + version + " may not support all features");
                }
            }
            
        } catch (Exception e) {
            issues.add("Failed to query OpenGL version: " + e.getMessage());
            valid = false;
        }
        
        // Check resource limits
        try {
            int maxTextureUnits = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
            int maxUniforms = glGetInteger(GL_MAX_VERTEX_UNIFORM_COMPONENTS);
            int maxVaryings = glGetInteger(GL_MAX_VARYING_FLOATS);
            
            if (maxTextureUnits < 8) {
                warnings.add("Low texture unit count: " + maxTextureUnits);
            }
            
            if (maxUniforms < 1024) {
                warnings.add("Low uniform limit: " + maxUniforms);
            }
            
            LOGGER.log(Level.FINE, "System limits - Textures: {0}, Uniforms: {1}, Varyings: {2}", 
                      new Object[]{maxTextureUnits, maxUniforms, maxVaryings});
            
        } catch (Exception e) {
            issues.add("Failed to query OpenGL limits: " + e.getMessage());
            valid = false;
        }
        
        return new ValidationResult(valid, issues, warnings);
    }
    
    @Override
    public String getCapabilityInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== OpenGL Capabilities ===\n");
        
        try {
            info.append(String.format("Version: %s\n", glGetString(GL_VERSION)));
            info.append(String.format("Vendor: %s\n", glGetString(GL_VENDOR)));
            info.append(String.format("Renderer: %s\n", glGetString(GL_RENDERER)));
            info.append(String.format("GLSL Version: %s\n", glGetString(GL_SHADING_LANGUAGE_VERSION)));
            
            info.append("\n=== Resource Limits ===\n");
            info.append(String.format("Max Texture Units: %d\n", glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)));
            info.append(String.format("Max Vertex Uniforms: %d\n", glGetInteger(GL_MAX_VERTEX_UNIFORM_COMPONENTS)));
            info.append(String.format("Max Fragment Uniforms: %d\n", glGetInteger(GL_MAX_FRAGMENT_UNIFORM_COMPONENTS)));
            info.append(String.format("Max Varying Floats: %d\n", glGetInteger(GL_MAX_VARYING_FLOATS)));
            info.append(String.format("Max Vertex Attributes: %d\n", glGetInteger(GL_MAX_VERTEX_ATTRIBS)));
            
            if (GeometryShaderSupport.isGeometryShaderSupported()) {
                info.append(String.format("Max Geometry Output Vertices: %d\n", glGetInteger(GL_MAX_GEOMETRY_OUTPUT_VERTICES)));
                info.append(String.format("Max Geometry Shader Invocations: %d\n", GeometryShaderSupport.getMaxGeometryShaderInvocations()));
            }
            
            info.append(String.format("Max Uniform Buffer Size: %d\n", glGetInteger(GL_MAX_UNIFORM_BLOCK_SIZE)));
            info.append(String.format("Max Uniform Buffer Bindings: %d\n", glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS)));
            
        } catch (Exception e) {
            info.append("Error querying capabilities: ").append(e.getMessage()).append("\n");
        }
        
        info.append("=============================");
        return info.toString();
    }
    
    private boolean isTypeCompatible(int glType, UniformType expectedType) {
        switch (expectedType) {
            case INT:
                return glType == GL_INT || glType == GL_BOOL;
            case FLOAT:
                return glType == GL_FLOAT;
            case BOOL:
                return glType == GL_BOOL || glType == GL_INT;
            case VEC2:
                return glType == GL_FLOAT_VEC2;
            case VEC3:
                return glType == GL_FLOAT_VEC3;
            case VEC4:
                return glType == GL_FLOAT_VEC4;
            case MAT4:
                return glType == GL_FLOAT_MAT4;
            case SAMPLER_2D:
                return glType == GL_SAMPLER_2D;
            case SAMPLER_CUBE:
                return glType == GL_SAMPLER_CUBE;
            default:
                return false;
        }
    }
    
    private String getGLTypeName(int glType) {
        switch (glType) {
            case GL_INT: return "GL_INT";
            case GL_FLOAT: return "GL_FLOAT";
            case GL_BOOL: return "GL_BOOL";
            case GL_FLOAT_VEC2: return "GL_FLOAT_VEC2";
            case GL_FLOAT_VEC3: return "GL_FLOAT_VEC3";
            case GL_FLOAT_VEC4: return "GL_FLOAT_VEC4";
            case GL_FLOAT_MAT4: return "GL_FLOAT_MAT4";
            case GL_SAMPLER_2D: return "GL_SAMPLER_2D";
            case GL_SAMPLER_CUBE: return "GL_SAMPLER_CUBE";
            default: return "UNKNOWN(" + glType + ")";
        }
    }
    
    private boolean isValidGeometryInputType(int inputType) {
        switch (inputType) {
            case GL_POINTS:
            case GL_LINES:
            case GL_LINES_ADJACENCY:
            case GL_TRIANGLES:
            case GL_TRIANGLES_ADJACENCY:
                return true;
            default:
                return false;
        }
    }
    
    private boolean isValidGeometryOutputType(int outputType) {
        switch (outputType) {
            case GL_POINTS:
            case GL_LINE_STRIP:
            case GL_TRIANGLE_STRIP:
                return true;
            default:
                return false;
        }
    }
}