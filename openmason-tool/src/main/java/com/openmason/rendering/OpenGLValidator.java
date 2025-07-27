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
     * Validates the current OpenGL context for a specific operation.
     * 
     * @param operation The operation being validated
     * @return List of validation issues, empty if no problems detected
     */
    public static List<String> validateContext(String operation) {
        return validateOpenGLContext();
    }
    
    /**
     * Validates the current OpenGL context and returns any issues found.
     * 
     * @return List of validation issues, empty if no problems detected
     */
    public static List<String> validateOpenGLContext() {
        List<String> issues = new ArrayList<>();
        
        // Check for OpenGL errors
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            issues.add("OpenGL error detected: " + getErrorString(error));
        }
        
        // Check OpenGL version
        String version = GL11.glGetString(GL11.GL_VERSION);
        if (version == null) {
            issues.add("Cannot retrieve OpenGL version - context may be invalid");
        } else {
            // Parse version and check minimum requirements
            if (!checkMinimumOpenGLVersion(version, 3, 3)) {
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
        
        if (vao.getTextureCoordinateBuffer() != null) {
            List<String> texCoordIssues = validateBuffer(vao.getTextureCoordinateBuffer());
            for (String issue : texCoordIssues) {
                issues.add("Texture coordinate buffer: " + issue);
            }
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
     * Performs a comprehensive validation of the buffer management system.
     * 
     * @param bufferManager The buffer manager to validate
     * @return Comprehensive validation report
     */
    public static ValidationReport validateBufferSystem(BufferManager bufferManager) {
        ValidationReport report = new ValidationReport();
        
        // Validate OpenGL context
        report.contextIssues.addAll(validateOpenGLContext());
        
        // Validate rendering state
        report.renderingStateIssues.addAll(validateRenderingState());
        
        // Get buffer manager statistics
        BufferManager.BufferManagerStatistics stats = bufferManager.getStatistics();
        report.bufferManagerStats = stats;
        
        // Check for resource leaks
        List<String> leakIssues = bufferManager.validateResources();
        report.resourceLeakIssues.addAll(leakIssues);
        
        // Check memory usage
        long memoryUsage = stats.currentMemoryUsage;
        long memoryThreshold = bufferManager.getMemoryWarningThreshold();
        
        if (memoryUsage > memoryThreshold) {
            report.memoryIssues.add("Memory usage exceeds threshold: " + 
                                  formatBytes(memoryUsage) + " > " + formatBytes(memoryThreshold));
        }
        
        if (stats.totalMemoryAllocated > 0) {
            double leakRatio = (double)(stats.totalMemoryAllocated - stats.totalMemoryDeallocated) / stats.totalMemoryAllocated;
            if (leakRatio > 0.1) { // More than 10% potential leaks
                report.memoryIssues.add("High potential memory leak ratio: " + 
                                      String.format("%.1f%%", leakRatio * 100));
            }
        }
        
        return report;
    }
    
    /**
     * Checks if the OpenGL version meets minimum requirements.
     * 
     * @param versionString The OpenGL version string
     * @param minMajor Minimum major version
     * @param minMinor Minimum minor version
     * @return True if version is sufficient
     */
    private static boolean checkMinimumOpenGLVersion(String versionString, int minMajor, int minMinor) {
        try {
            // Parse version string (format: "major.minor.patch ...")
            String[] parts = versionString.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1].split("\\s")[0]); // Remove any trailing text
                
                return (major > minMajor) || (major == minMajor && minor >= minMinor);
            }
        } catch (NumberFormatException e) {
            // Could not parse version
        }
        return false;
    }
    
    /**
     * Converts OpenGL error codes to human-readable strings.
     * 
     * @param error The OpenGL error code
     * @return Human-readable error description
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
    
    /**
     * Formats byte count as human-readable string.
     * 
     * @param bytes Byte count
     * @return Formatted string
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Comprehensive validation report for the buffer management system.
     */
    public static class ValidationReport {
        public final List<String> contextIssues = new ArrayList<>();
        public final List<String> renderingStateIssues = new ArrayList<>();
        public final List<String> memoryIssues = new ArrayList<>();
        public final List<String> resourceLeakIssues = new ArrayList<>();
        public BufferManager.BufferManagerStatistics bufferManagerStats;
        
        public boolean hasIssues() {
            return !contextIssues.isEmpty() || !renderingStateIssues.isEmpty() || 
                   !memoryIssues.isEmpty() || !resourceLeakIssues.isEmpty();
        }
        
        public int getTotalIssueCount() {
            return contextIssues.size() + renderingStateIssues.size() + 
                   memoryIssues.size() + resourceLeakIssues.size();
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== OpenGL Buffer System Validation Report ===\n");
            
            if (bufferManagerStats != null) {
                sb.append("Buffer Manager Statistics:\n");
                sb.append("  Active buffers: ").append(bufferManagerStats.activeBufferCount).append("\n");
                sb.append("  Active VAOs: ").append(bufferManagerStats.activeVertexArrayCount).append("\n");
                sb.append("  Current memory: ").append(formatBytes(bufferManagerStats.currentMemoryUsage)).append("\n");
                sb.append("\n");
            }
            
            if (!contextIssues.isEmpty()) {
                sb.append("OpenGL Context Issues (").append(contextIssues.size()).append("):\n");
                for (String issue : contextIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("\n");
            }
            
            if (!renderingStateIssues.isEmpty()) {
                sb.append("Rendering State Issues (").append(renderingStateIssues.size()).append("):\n");
                for (String issue : renderingStateIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("\n");
            }
            
            if (!memoryIssues.isEmpty()) {
                sb.append("Memory Issues (").append(memoryIssues.size()).append("):\n");
                for (String issue : memoryIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("\n");
            }
            
            if (!resourceLeakIssues.isEmpty()) {
                sb.append("Resource Leak Issues (").append(resourceLeakIssues.size()).append("):\n");
                for (String issue : resourceLeakIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("\n");
            }
            
            if (!hasIssues()) {
                sb.append("No issues detected - system is healthy!\n");
            }
            
            sb.append("===============================================");
            return sb.toString();
        }
    }
}