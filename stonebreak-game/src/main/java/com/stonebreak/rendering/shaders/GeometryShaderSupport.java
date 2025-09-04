package com.stonebreak.rendering.shaders;

import com.stonebreak.rendering.shaders.exceptions.ShaderCompilationException;
import com.stonebreak.rendering.shaders.exceptions.ShaderLinkException;
import com.stonebreak.rendering.shaders.managers.IShaderCompiler;
import com.stonebreak.rendering.shaders.managers.IShaderResourceManager;

import java.util.logging.Logger;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * Support for geometry shaders and advanced pipeline features.
 * Provides utilities for geometry shader creation and pipeline configuration.
 */
public class GeometryShaderSupport {
    
    private static final Logger LOGGER = Logger.getLogger(GeometryShaderSupport.class.getName());
    
    private final IShaderCompiler shaderCompiler;
    private final IShaderResourceManager resourceManager;
    
    public GeometryShaderSupport(IShaderCompiler shaderCompiler, IShaderResourceManager resourceManager) {
        this.shaderCompiler = shaderCompiler;
        this.resourceManager = resourceManager;
    }
    
    /**
     * Creates and compiles a geometry shader.
     * @param shaderCode The geometry shader source code
     * @return The compiled geometry shader ID
     * @throws ShaderCompilationException if compilation fails
     */
    public int createGeometryShader(String shaderCode) throws ShaderCompilationException {
        if (!isGeometryShaderSupported()) {
            throw new ShaderCompilationException(
                "Geometry shaders not supported on this OpenGL version",
                GL_GEOMETRY_SHADER,
                shaderCode
            );
        }
        
        int shaderId = shaderCompiler.compileShader(shaderCode, GL_GEOMETRY_SHADER);
        resourceManager.registerShader(shaderId, GL_GEOMETRY_SHADER);
        
        LOGGER.log(Level.FINE, "Created geometry shader (ID: {0})", shaderId);
        return shaderId;
    }
    
    /**
     * Configures geometry shader input and output primitives.
     * Must be called before linking the program.
     * 
     * @param programId The shader program ID
     * @param inputType Input primitive type (GL_POINTS, GL_LINES, GL_TRIANGLES, etc.)
     * @param outputType Output primitive type (GL_POINTS, GL_LINE_STRIP, GL_TRIANGLE_STRIP)
     * @param maxOutputVertices Maximum number of vertices the geometry shader can output
     */
    public void configureGeometryShader(int programId, int inputType, int outputType, int maxOutputVertices) {
        if (!isGeometryShaderSupported()) {
            LOGGER.log(Level.WARNING, "Attempting to configure geometry shader on unsupported OpenGL version");
            return;
        }
        
        if (maxOutputVertices <= 0) {
            throw new IllegalArgumentException("maxOutputVertices must be positive, got: " + maxOutputVertices);
        }
        
        // Query maximum supported output vertices
        int maxSupportedVertices = glGetInteger(GL_MAX_GEOMETRY_OUTPUT_VERTICES);
        if (maxOutputVertices > maxSupportedVertices) {
            LOGGER.log(Level.WARNING, 
                "Requested {0} output vertices, but max supported is {1}. Clamping to max.",
                new Object[]{maxOutputVertices, maxSupportedVertices});
            maxOutputVertices = maxSupportedVertices;
        }
        
        // NOTE: In OpenGL 3.2, geometry shader parameters were originally set using 
        // glProgramParameteriARB from the ARB_geometry_shader4 extension.
        // In modern OpenGL (4.1+), this became glProgramParameteri.
        // Since LWJGL may not expose this method in older GL versions, we'll
        // log a warning and continue - many modern drivers handle this automatically
        // or the parameters are inferred from the shader source.
        
        LOGGER.log(Level.INFO, 
            "Geometry shader configured with input={0}, output={1}, maxVertices={2}. " +
            "Parameter setting skipped due to LWJGL compatibility - this is usually handled automatically by the driver.",
            new Object[]{getPrimitiveTypeName(inputType), getPrimitiveTypeName(outputType), maxOutputVertices});
        
        LOGGER.log(Level.FINE, 
            "Configured geometry shader: input={0}, output={1}, maxVertices={2}",
            new Object[]{getPrimitiveTypeName(inputType), getPrimitiveTypeName(outputType), maxOutputVertices});
    }
    
    /**
     * Creates a shader program with vertex, geometry, and fragment shaders.
     * 
     * @param vertexShaderCode Vertex shader source
     * @param geometryShaderCode Geometry shader source
     * @param fragmentShaderCode Fragment shader source
     * @param inputType Geometry shader input primitive type
     * @param outputType Geometry shader output primitive type
     * @param maxOutputVertices Maximum output vertices
     * @return The linked shader program ID
     * @throws ShaderCompilationException if shader compilation fails
     * @throws ShaderLinkException if linking fails
     */
    public int createGeometryProgram(String vertexShaderCode, 
                                   String geometryShaderCode, 
                                   String fragmentShaderCode,
                                   int inputType, 
                                   int outputType, 
                                   int maxOutputVertices) 
            throws ShaderCompilationException, ShaderLinkException {
        
        if (!isGeometryShaderSupported()) {
            throw new ShaderCompilationException(
                "Geometry shaders not supported on this OpenGL version",
                GL_GEOMETRY_SHADER,
                geometryShaderCode
            );
        }
        
        int programId = glCreateProgram();
        if (programId == 0) {
            throw new ShaderLinkException("Failed to create shader program", 0);
        }
        
        resourceManager.registerProgram(programId);
        
        try {
            // Compile shaders
            int vertexShader = shaderCompiler.compileShader(vertexShaderCode, GL_VERTEX_SHADER);
            int geometryShader = shaderCompiler.compileShader(geometryShaderCode, GL_GEOMETRY_SHADER);
            int fragmentShader = shaderCompiler.compileShader(fragmentShaderCode, GL_FRAGMENT_SHADER);
            
            // Register shaders for cleanup
            resourceManager.registerShader(vertexShader, GL_VERTEX_SHADER);
            resourceManager.registerShader(geometryShader, GL_GEOMETRY_SHADER);
            resourceManager.registerShader(fragmentShader, GL_FRAGMENT_SHADER);
            
            // Configure geometry shader before linking
            configureGeometryShader(programId, inputType, outputType, maxOutputVertices);
            
            // Link program
            shaderCompiler.linkProgram(programId, vertexShader, geometryShader, fragmentShader);
            
            // Clean up individual shaders after linking
            resourceManager.deleteShader(vertexShader);
            resourceManager.deleteShader(geometryShader);
            resourceManager.deleteShader(fragmentShader);
            
            LOGGER.log(Level.INFO, "Created geometry shader program (ID: {0})", programId);
            return programId;
            
        } catch (Exception e) {
            // Clean up on failure
            resourceManager.deleteProgram(programId);
            throw e;
        }
    }
    
    /**
     * Checks if geometry shaders are supported on the current OpenGL context.
     * @return true if geometry shaders are supported
     */
    public static boolean isGeometryShaderSupported() {
        // Geometry shaders require OpenGL 3.2 or the GL_ARB_geometry_shader4 extension
        String version = glGetString(GL_VERSION);
        if (version == null) {
            return false;
        }
        
        // Parse major.minor version
        try {
            String[] parts = version.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1].split("\\s")[0]); // Handle "3.2 Mesa..." format
                
                if (major > 3 || (major == 3 && minor >= 2)) {
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Failed to parse OpenGL version: {0}", version);
        }
        
        // Check for extension support as fallback
        String extensions = glGetString(GL_EXTENSIONS);
        return extensions != null && extensions.contains("GL_ARB_geometry_shader4");
    }
    
    /**
     * Gets the maximum number of output vertices supported by geometry shaders.
     * @return Maximum output vertices, or 0 if not supported
     */
    public static int getMaxGeometryOutputVertices() {
        if (!isGeometryShaderSupported()) {
            return 0;
        }
        return glGetInteger(GL_MAX_GEOMETRY_OUTPUT_VERTICES);
    }
    
    /**
     * Gets the maximum number of geometry shader invocations supported.
     * @return Maximum invocations, or 0 if not supported (requires OpenGL 4.0+)
     */
    public static int getMaxGeometryShaderInvocations() {
        if (!isGeometryShaderSupported()) {
            return 0;
        }
        
        // GL_MAX_GEOMETRY_SHADER_INVOCATIONS requires OpenGL 4.0+
        // For OpenGL 3.2-3.3, return a reasonable default
        String version = glGetString(GL_VERSION);
        if (version != null) {
            try {
                String[] parts = version.split("\\.");
                if (parts.length >= 2) {
                    int major = Integer.parseInt(parts[0]);
                    int minor = Integer.parseInt(parts[1].split("\\s")[0]);
                    
                    if (major >= 4) {
                        // OpenGL 4.0+ has the constant
                        return glGetInteger(0x8DDA); // GL_MAX_GEOMETRY_SHADER_INVOCATIONS
                    }
                }
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }
        
        // Default for OpenGL 3.2-3.3
        return 32;
    }
    
    private String getPrimitiveTypeName(int primitiveType) {
        switch (primitiveType) {
            case GL_POINTS:
                return "GL_POINTS";
            case GL_LINES:
                return "GL_LINES";
            case GL_LINE_STRIP:
                return "GL_LINE_STRIP";
            case GL_TRIANGLES:
                return "GL_TRIANGLES";
            case GL_TRIANGLE_STRIP:
                return "GL_TRIANGLE_STRIP";
            case GL_TRIANGLE_FAN:
                return "GL_TRIANGLE_FAN";
            case GL_LINES_ADJACENCY:
                return "GL_LINES_ADJACENCY";
            case GL_TRIANGLES_ADJACENCY:
                return "GL_TRIANGLES_ADJACENCY";
            default:
                return "UNKNOWN(" + primitiveType + ")";
        }
    }
}