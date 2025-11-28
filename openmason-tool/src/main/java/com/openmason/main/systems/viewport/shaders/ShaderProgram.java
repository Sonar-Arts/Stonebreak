package com.openmason.main.systems.viewport.shaders;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20.*;

/**
 * Immutable wrapper for an OpenGL shader program and its uniform locations.
 * Follows value object pattern for clean separation of shader state.
 */
public class ShaderProgram {

    private final ShaderType type;
    private final int programId;
    private final int vertexShaderId;
    private final int fragmentShaderId;

    // Common uniform locations
    private final int mvpMatrixLocation;
    private final int colorLocation;

    // Matrix shader specific uniforms
    private final int modelMatrixLocation;
    private final int textureLocation;
    private final int useTextureLocation;

    /**
     * Constructor for BASIC and GIZMO shaders.
     */
    public ShaderProgram(ShaderType type, int programId, int vertexShaderId, int fragmentShaderId,
                        int mvpMatrixLocation, int colorLocation) {
        this(type, programId, vertexShaderId, fragmentShaderId, mvpMatrixLocation, colorLocation, -1, -1, -1);
    }

    /**
     * Constructor for MATRIX shader with all uniforms.
     */
    public ShaderProgram(ShaderType type, int programId, int vertexShaderId, int fragmentShaderId,
                        int mvpMatrixLocation, int colorLocation,
                        int modelMatrixLocation, int textureLocation, int useTextureLocation) {
        this.type = type;
        this.programId = programId;
        this.vertexShaderId = vertexShaderId;
        this.fragmentShaderId = fragmentShaderId;
        this.mvpMatrixLocation = mvpMatrixLocation;
        this.colorLocation = colorLocation;
        this.modelMatrixLocation = modelMatrixLocation;
        this.textureLocation = textureLocation;
        this.useTextureLocation = useTextureLocation;
    }

    /**
     * Clean up shader resources.
     */
    public void cleanup() {
        if (programId != -1) {
            glDeleteProgram(programId);
        }
        if (vertexShaderId != -1) {
            glDeleteShader(vertexShaderId);
        }
        if (fragmentShaderId != -1) {
            glDeleteShader(fragmentShaderId);
        }
    }

    // Getters
    public ShaderType getType() { return type; }
    public int getProgramId() { return programId; }
    public int getMvpMatrixLocation() { return mvpMatrixLocation; }
    public int getColorLocation() { return colorLocation; }
    public int getModelMatrixLocation() { return modelMatrixLocation; }
    public int getTextureLocation() { return textureLocation; }
    public int getUseTextureLocation() { return useTextureLocation; }

    /**
     * Check if shader program is valid.
     */
    public boolean isValid() {
        return programId != -1;
    }

    /**
     * Bind this shader program for use.
     */
    public void use() {
        glUseProgram(programId);
    }

    /**
     * Set a mat4 uniform in the shader.
     * @param name The uniform name
     * @param matrix The matrix to set
     */
    public void setMat4(String name, Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            int location = glGetUniformLocation(programId, name);
            if (location != -1) {
                glUniformMatrix4fv(location, false, buffer);
            }
        }
    }

    /**
     * Set an int uniform in the shader.
     * @param name The uniform name
     * @param value The integer value
     */
    public void setInt(String name, int value) {
        int location = glGetUniformLocation(programId, name);
        if (location != -1) {
            glUniform1i(location, value);
        }
    }

    /**
     * Set a boolean uniform in the shader.
     * @param name The uniform name
     * @param value The boolean value
     */
    public void setBool(String name, boolean value) {
        int location = glGetUniformLocation(programId, name);
        if (location != -1) {
            glUniform1i(location, value ? 1 : 0);
        }
    }

    /**
     * Set a float uniform in the shader.
     * @param name The uniform name
     * @param value The float value
     */
    public void setFloat(String name, float value) {
        int location = glGetUniformLocation(programId, name);
        if (location != -1) {
            glUniform1f(location, value);
        }
    }

    /**
     * Set a vec3 uniform in the shader.
     * @param name The uniform name
     * @param vector The vector value
     */
    public void setVec3(String name, Vector3f vector) {
        int location = glGetUniformLocation(programId, name);
        if (location != -1) {
            glUniform3f(location, vector.x, vector.y, vector.z);
        }
    }

    @Override
    public String toString() {
        return String.format("ShaderProgram{type=%s, programId=%d, mvpLoc=%d, colorLoc=%d}",
                           type, programId, mvpMatrixLocation, colorLocation);
    }
}
