package com.stonebreak.rendering;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;

/**
 * Manages OpenGL shader programs.
 */
public class ShaderProgram {
    
    private final int programId;
    private int vertexShaderId;
    private int fragmentShaderId;
    
    // Cache for uniform locations
    private final Map<String, Integer> uniforms;
    
    /**
     * Creates a new shader program.
     */
    public ShaderProgram() {
        programId = glCreateProgram();
        if (programId == 0) {
            throw new RuntimeException("Could not create shader program");
        }
        
        uniforms = new HashMap<>();
    }
    
    /**
     * Creates a vertex shader from source code.
     */
    public void createVertexShader(String shaderCode) {
        vertexShaderId = createShader(shaderCode, GL_VERTEX_SHADER);
    }
    
    /**
     * Creates a fragment shader from source code.
     */
    public void createFragmentShader(String shaderCode) {
        fragmentShaderId = createShader(shaderCode, GL_FRAGMENT_SHADER);
    }
    
    /**
     * Creates a shader of the specified type from source code.
     */
    private int createShader(String shaderCode, int shaderType) {
        int shaderId = glCreateShader(shaderType);
        if (shaderId == 0) {
            throw new RuntimeException("Error creating shader. Type: " + shaderType);
        }
        
        // Debug: Print shader information
        String shaderTypeName = (shaderType == GL_VERTEX_SHADER) ? "Vertex" : "Fragment";
        System.out.println("---- Compiling Shader Type: " + shaderTypeName + " ----");
        System.out.println("Shader Code Length: " + (shaderCode != null ? shaderCode.length() : "null"));
        System.out.println("Shader Code:\n" + shaderCode);
        System.out.println("---- End Shader Code ----");

        glShaderSource(shaderId, shaderCode);
        glCompileShader(shaderId);
        
        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shaderId, 1024);
            System.err.println("Shader Compilation Failed for " + shaderTypeName + ". Log:\n" + log); // Also print log to stderr
            throw new RuntimeException("Error compiling shader code: " + log);
        }
        
        glAttachShader(programId, shaderId);
        
        return shaderId;
    }
    
    /**
     * Links the shader program.
     */
    public void link() {
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Error linking shader code: " + glGetProgramInfoLog(programId, 1024));
        }
        
        if (vertexShaderId != 0) {
            glDetachShader(programId, vertexShaderId);
        }
        
        if (fragmentShaderId != 0) {
            glDetachShader(programId, fragmentShaderId);
        }
        
        glValidateProgram(programId);
        if (glGetProgrami(programId, GL_VALIDATE_STATUS) == 0) {
            System.err.println("Warning validating shader code: " + glGetProgramInfoLog(programId, 1024));
        }
    }
    
    /**
     * Binds the shader program.
     */
    public void bind() {
        glUseProgram(programId);
    }
    
    /**
     * Unbinds the shader program.
     */
    public void unbind() {
        glUseProgram(0);
    }
    
    /**
     * Creates a uniform.
     */
    public void createUniform(String uniformName) {
        int uniformLocation = glGetUniformLocation(programId, uniformName);
        if (uniformLocation < 0) {
            System.err.println("Warning: Could not find uniform: " + uniformName);
        }
        uniforms.put(uniformName, uniformLocation);
    }
    
    /**
     * Sets an integer uniform.
     */
    public void setUniform(String uniformName, int value) {
        glUniform1i(uniforms.get(uniformName), value);
    }
    
    /**
     * Sets a float uniform.
     */
    public void setUniform(String uniformName, float value) {
        glUniform1f(uniforms.get(uniformName), value);
    }
    
    /**
     * Sets a Vector3f uniform.
     */
    public void setUniform(String uniformName, org.joml.Vector3f value) {
        glUniform3f(uniforms.get(uniformName), value.x, value.y, value.z);
    }

    /**
     * Sets a Vector2f uniform.
     */
    public void setUniform(String uniformName, org.joml.Vector2f value) {
        glUniform2f(uniforms.get(uniformName), value.x, value.y);
    }

    /**
     * Sets a Vector4f uniform.
     */
    public void setUniform(String uniformName, org.joml.Vector4f value) {
        glUniform4f(uniforms.get(uniformName), value.x, value.y, value.z, value.w);
    }

    /**
     * Sets a boolean uniform (as an integer).
     */
    public void setUniform(String uniformName, boolean value) {
        glUniform1i(uniforms.get(uniformName), value ? 1 : 0);
    }
    
    /**
     * Sets a Matrix4f uniform.
     */
    public void setUniform(String uniformName, Matrix4f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            value.get(fb);
            glUniformMatrix4fv(uniforms.get(uniformName), false, fb);
        }
    }

    /**
     * Gets the current value of a matrix4 uniform.
     * @param uniformName The name of the uniform
     * @param buffer The buffer to store the matrix values (must be size 16)
     */
    public void getUniformMatrix4fv(String uniformName, float[] buffer) {
        if (buffer.length != 16) {
            throw new IllegalArgumentException("Buffer must be of size 16");
        }
        
        Integer location = uniforms.get(uniformName);
        if (location != null) {
            glGetUniformfv(programId, location, buffer);
        }
    }
    
    /**
     * Cleans up resources used by the shader program.
     */
    public void cleanup() {
        unbind();
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }
}
