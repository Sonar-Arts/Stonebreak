package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders the thin line between the player's fishing rod and the active bobber.
 */
public class FishingLineRenderer {

    private static final Vector4f LINE_COLOR = new Vector4f(0.15f, 0.1f, 0.05f, 1.0f);
    private static final float LINE_WIDTH = 1.5f;

    private final ShaderProgram shaderProgram;
    private final Matrix4f projectionMatrix;

    private int lineVao;
    private int lineVbo;
    private final FloatBuffer lineScratch = BufferUtils.createFloatBuffer(6); // 2 points × 3 floats

    public FishingLineRenderer(ShaderProgram shaderProgram, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.projectionMatrix = projectionMatrix;
        createGeometry();
    }

    private void createGeometry() {
        lineVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(lineVao);

        lineVbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, lineVbo);
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, (long) 6 * Float.BYTES, GL15.GL_DYNAMIC_DRAW);

        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    public void render(Vector3f from, Vector3f to, Matrix4f viewMatrix) {
        float savedLineWidth = glGetFloat(GL_LINE_WIDTH);
        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glLineWidth(LINE_WIDTH);

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", viewMatrix);
        shaderProgram.setUniform("modelMatrix", new Matrix4f());
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_useSolidColor", true);
        // Mark as a UI/overlay element so the vertex shader's water-wave
        // displacement is skipped — otherwise the line picks up the wave
        // animation and visibly ripples like water.
        shaderProgram.setUniform("u_isUIElement", true);
        shaderProgram.setUniform("u_color", LINE_COLOR);

        lineScratch.clear();
        lineScratch.put(from.x).put(from.y).put(from.z);
        lineScratch.put(to.x).put(to.y).put(to.z);
        lineScratch.flip();

        GL30.glBindVertexArray(lineVao);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, lineVbo);
        GL20.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, lineScratch);
        glDrawArrays(GL_LINES, 0, 2);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_isUIElement", false);
        shaderProgram.unbind();

        glLineWidth(savedLineWidth);
        if (!depthTestWasEnabled) {
            glDisable(GL_DEPTH_TEST);
        }
    }

    public void cleanup() {
        if (lineVbo != 0) GL20.glDeleteBuffers(lineVbo);
        if (lineVao != 0) GL30.glDeleteVertexArrays(lineVao);
    }
}
