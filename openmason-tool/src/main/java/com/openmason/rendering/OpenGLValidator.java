package com.openmason.rendering;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenGL validation and error handling utility for the buffer management system.
 * Provides comprehensive validation of OpenGL state, buffer configurations,
 * and context issues to help debug rendering problems.
 */
public class OpenGLValidator {
    
    /**
     * Checks if there is a valid OpenGL context available.
     * This method attempts to make a minimal OpenGL call to detect context availability.
     * 
     * @return True if OpenGL context is available, false otherwise
     */
    public static boolean hasValidOpenGLContext() {
        try {
            // Try to check if OpenGL context is current
            // This is safer than calling glGetError() directly
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            return !caps.OpenGL11;
        } catch (Exception | Error e) {
            // Any exception/error means no valid context
            return true;
        }
    }
    
    /**
     * Validates the current OpenGL context for a specific operation.
     * 
     * @return List of validation issues, empty if no problems detected
     */
    public static List<String> validateContext() {
        return validateOpenGLContext();
    }
    
    /**
     * Validates the current OpenGL context and returns any issues found.
     * 
     * @return List of validation issues, empty if no problems detected
     */
    public static List<String> validateOpenGLContext() {
        List<String> issues = new ArrayList<>();
        
        // First check if we have any OpenGL context at all
        if (hasValidOpenGLContext()) {
            issues.add("No valid OpenGL context available");
            return issues; // Early return - no point checking further
        }
        
        try {
            // Check for OpenGL errors
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                issues.add("OpenGL error detected: " + getErrorString(error));
            }
        } catch (Exception e) {
            issues.add("Failed to check OpenGL error state: " + e.getMessage());
            return issues; // Context is invalid, don't continue
        }
        
        try {
            // Check OpenGL version
            String version = GL11.glGetString(GL11.GL_VERSION);
            if (version == null) {
                issues.add("Cannot retrieve OpenGL version - context may be invalid");
            } else {
                // Parse version and check minimum requirements
                if (!checkMinimumOpenGLVersion(version)) {
                    issues.add("OpenGL version too low: " + version + " (minimum required: 3.3)");
                }
            }
            
            // Check renderer string
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (renderer == null) {
                issues.add("Cannot retrieve OpenGL renderer information");
            }
            
            // Check maximum texture size
            int maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (maxTextureSize < 1024) {
                issues.add("Maximum texture size very low: " + maxTextureSize + "x" + maxTextureSize);
            }
            
            // Check maximum vertex attributes
            int maxVertexAttribs = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
            if (maxVertexAttribs < 8) {
                issues.add("Very few vertex attributes supported: " + maxVertexAttribs);
            }
        } catch (Exception e) {
            issues.add("Failed to retrieve OpenGL context information: " + e.getMessage());
        }
        
        return issues;
    }
    
    /**
     * Validates a buffer object and its configuration.
     * 
     * @param buffer The buffer to validate
     * @return List of validation issues
     */
    public static List<String> validateBuffer(OpenGLBuffer buffer) {
        List<String> issues = new ArrayList<>();
        
        if (buffer == null) {
            issues.add("Buffer is null");
            return issues;
        }
        
        if (!buffer.isValid()) {
            issues.add("Buffer is not valid: " + buffer.getDebugName());
        }
        
        if (buffer.isDisposed()) {
            issues.add("Buffer has been disposed: " + buffer.getDebugName());
        }
        
        if (buffer.getBufferId() <= 0) {
            issues.add("Buffer has invalid ID: " + buffer.getBufferId());
        }
        
        if (buffer.getDataSize() <= 0) {
            issues.add("Buffer has no data: " + buffer.getDebugName());
        }
        
        // Check if buffer exists in OpenGL
        if (buffer.isValid() && !GL15.glIsBuffer(buffer.getBufferId())) {
            issues.add("Buffer ID not recognized by OpenGL: " + buffer.getBufferId());
        }
        
        return issues;
    }
    
    /**
     * Validates a vertex array object and its configuration.
     * 
     * @param vao The vertex array to validate
     * @return List of validation issues
     */
    public static List<String> validateVertexArray(VertexArray vao) {
        List<String> issues = new ArrayList<>();
        
        if (vao == null) {
            issues.add("Vertex array is null");
            return issues;
        }
        
        if (!vao.isValid()) {
            issues.add("Vertex array is not valid: " + vao.getDebugName());
        }
        
        if (vao.isDisposed()) {
            issues.add("Vertex array has been disposed: " + vao.getDebugName());
        }
        
        if (vao.getVaoId() <= 0) {
            issues.add("Vertex array has invalid ID: " + vao.getVaoId());
        }
        
        // Check if VAO exists in OpenGL
        if (vao.isValid() && !GL30.glIsVertexArray(vao.getVaoId())) {
            issues.add("Vertex array ID not recognized by OpenGL: " + vao.getVaoId());
        }
        
        // Validate associated buffers
        if (vao.getVertexBuffer() != null) {
            List<String> vertexBufferIssues = validateBuffer(vao.getVertexBuffer());
            for (String issue : vertexBufferIssues) {
                issues.add("Vertex buffer: " + issue);
            }
        } else {
            issues.add("No vertex buffer associated with VAO");
        }

        if (vao.getIndexBuffer() != null) {
            List<String> indexBufferIssues = validateBuffer(vao.getIndexBuffer());
            for (String issue : indexBufferIssues) {
                issues.add("Index buffer: " + issue);
            }
        }
        
        return issues;
    }
    
    /**
     * Validates the current OpenGL rendering state before drawing operations.
     * 
     * @return List of state validation issues
     */
    public static List<String> validateRenderingState() {
        List<String> issues = new ArrayList<>();
        
        // Check for OpenGL errors first
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            issues.add("OpenGL error in rendering state: " + getErrorString(error));
        }
        
        // Check if a shader program is bound
        int currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (currentProgram == 0) {
            issues.add("No shader program bound for rendering");
        } else if (!GL20.glIsProgram(currentProgram)) {
            issues.add("Bound shader program is invalid: " + currentProgram);
        }
        
        // Check if a VAO is bound
        int currentVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        if (currentVAO == 0) {
            issues.add("No vertex array bound for rendering");
        } else if (!GL30.glIsVertexArray(currentVAO)) {
            issues.add("Bound vertex array is invalid: " + currentVAO);
        }
        
        // Check depth testing configuration
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        if (!depthTestEnabled) {
            issues.add("Depth testing is disabled - 3D rendering may have issues");
        }
        
        // Check viewport settings
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        if (viewport[2] <= 0 || viewport[3] <= 0) {
            issues.add("Invalid viewport size: " + viewport[2] + "x" + viewport[3]);
        }
        
        return issues;
    }
    
    /**
     * Checks if the OpenGL version meets minimum requirements.
     */
    private static boolean checkMinimumOpenGLVersion(String versionString) {
        try {
            // Parse version string (format: "major.minor.patch ...")
            String[] parts = versionString.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1].split("\\s")[0]); // Remove any trailing text
                
                return (major > 3) || (major == 3 && minor >= 3);
            }
        } catch (NumberFormatException e) {
            // Could not parse version
        }
        return false;
    }
    
    /**
     * Converts OpenGL error codes to human-readable strings.
     */
    private static String getErrorString(int error) {
        return switch (error) {
            case GL11.GL_NO_ERROR -> "No error";
            case GL11.GL_INVALID_ENUM -> "Invalid enum";
            case GL11.GL_INVALID_VALUE -> "Invalid value";
            case GL11.GL_INVALID_OPERATION -> "Invalid operation";
            case GL11.GL_OUT_OF_MEMORY -> "Out of memory";
            case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "Invalid framebuffer operation";
            default -> "Unknown error (" + error + ")";
        };
    }
}