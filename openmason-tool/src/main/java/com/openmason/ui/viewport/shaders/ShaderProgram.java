package com.openmason.ui.viewport.shaders;

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
    public int getVertexShaderId() { return vertexShaderId; }
    public int getFragmentShaderId() { return fragmentShaderId; }
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

    @Override
    public String toString() {
        return String.format("ShaderProgram{type=%s, programId=%d, mvpLoc=%d, colorLoc=%d}",
                           type, programId, mvpMatrixLocation, colorLocation);
    }
}
