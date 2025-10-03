package com.stonebreak.rendering.emitters;

import java.nio.FloatBuffer;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

import com.stonebreak.audio.emitters.SoundEmitter;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.shaders.ShaderProgram;

/**
 * Specialized renderer for sound emitters, displaying them as yellow triangle wireframes
 * that are only visible in debug mode.
 */
public class SoundEmitterRenderer {
    private static final Vector3f EMITTER_COLOR = new Vector3f(1.0f, 1.0f, 0.0f); // Yellow
    private static final float TRIANGLE_SIZE = 0.5f; // Size of the triangle

    private ShaderProgram shaderProgram;
    private Matrix4f projectionMatrix;
    private int triangleVao;

    public SoundEmitterRenderer(ShaderProgram shaderProgram, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.projectionMatrix = projectionMatrix;
        createTriangleWireframe();
    }

    /**
     * Creates the wireframe VAO for a triangle representing sound emitters.
     * The triangle points upward and is created as wireframe lines.
     */
    private void createTriangleWireframe() {
        // Create vertices for an upward-pointing triangle wireframe
        // Triangle has 3 vertices, wireframe needs 6 vertices (3 lines * 2 vertices each)
        float size = TRIANGLE_SIZE;
        float[] vertices = {
            // Line 1: Bottom-left to bottom-right
            -size, 0.0f, 0.0f,   size, 0.0f, 0.0f,
            // Line 2: Bottom-right to top
            size, 0.0f, 0.0f,    0.0f, size * 1.732f, 0.0f, // Height = size * sqrt(3) for equilateral triangle
            // Line 3: Top to bottom-left
            0.0f, size * 1.732f, 0.0f,   -size, 0.0f, 0.0f
        };

        // Create VAO
        triangleVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(triangleVao);

        // Create VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, buffer, GL20.GL_STATIC_DRAW);

        // Define vertex attributes
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        // Unbind
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders all debug-visible sound emitters as yellow triangle wireframes.
     * This method should only be called when debug mode is enabled.
     *
     * @param emitters List of sound emitters to potentially render
     * @param debugMode Whether debug mode is currently enabled
     */
    public void renderSoundEmitters(List<SoundEmitter> emitters, boolean debugMode) {
        if (!debugMode || emitters.isEmpty()) {
            return;
        }

        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);

        // Set up OpenGL state for wireframe rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);

        // Use shader program
        shaderProgram.bind();

        // Set view and projection matrices
        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
            shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        }

        // Set shader uniforms for solid color rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(EMITTER_COLOR.x, EMITTER_COLOR.y, EMITTER_COLOR.z, 1.0f));

        // Bind triangle VAO
        GL30.glBindVertexArray(triangleVao);

        // Render each sound emitter
        for (SoundEmitter emitter : emitters) {
            if (emitter.isDebugVisible()) {
                renderSingleEmitter(emitter);
            }
        }

        // Unbind VAO
        GL30.glBindVertexArray(0);

        // Restore OpenGL state
        if (!depthTestEnabled) {
            glDisable(GL_DEPTH_TEST);
        }

        // Unbind shader
        shaderProgram.unbind();
    }

    /**
     * Renders a single sound emitter as a triangle wireframe.
     *
     * @param emitter The sound emitter to render
     */
    private void renderSingleEmitter(SoundEmitter emitter) {
        Vector3f position = emitter.getPosition();

        // Calculate model matrix for the emitter
        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.translation(position.x, position.y, position.z);

        // Make the triangle always face the player (billboard effect)
        Player player = Game.getPlayer();
        if (player != null) {
            Vector3f playerPos = player.getPosition();
            Vector3f direction = new Vector3f(playerPos).sub(position).normalize();

            // Calculate rotation to face player (simple Y-axis rotation)
            float yaw = (float) Math.atan2(direction.x, direction.z);
            modelMatrix.rotateY(yaw);
        }

        // Apply the model matrix to the shader
        shaderProgram.setUniform("modelMatrix", modelMatrix);

        // Render the triangle wireframe (6 vertices forming 3 lines)
        glDrawArrays(GL_LINES, 0, 6);
    }

    /**
     * Renders a single sound emitter with optional color override.
     * Useful for highlighting specific emitters or showing different states.
     *
     * @param emitter The sound emitter to render
     * @param color Custom color for this emitter (null to use default yellow)
     * @param debugMode Whether debug mode is enabled
     */
    public void renderSingleEmitter(SoundEmitter emitter, Vector3f color, boolean debugMode) {
        if (!debugMode || !emitter.isDebugVisible()) {
            return;
        }

        Vector3f renderColor = (color != null) ? color : EMITTER_COLOR;

        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);

        // Set up OpenGL state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);

        // Use shader program
        shaderProgram.bind();

        // Set matrices
        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
            shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        }

        // Set color
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(renderColor.x, renderColor.y, renderColor.z, 1.0f));

        // Render
        GL30.glBindVertexArray(triangleVao);
        renderSingleEmitter(emitter);
        GL30.glBindVertexArray(0);

        // Restore state
        if (!depthTestEnabled) {
            glDisable(GL_DEPTH_TEST);
        }

        shaderProgram.unbind();
    }

    /**
     * Gets the default color used for sound emitter wireframes.
     *
     * @return The default yellow color vector
     */
    public static Vector3f getDefaultColor() {
        return new Vector3f(EMITTER_COLOR);
    }

    /**
     * Gets the size of the triangle used to represent sound emitters.
     *
     * @return The triangle size
     */
    public static float getTriangleSize() {
        return TRIANGLE_SIZE;
    }

    /**
     * Cleanup method to free OpenGL resources.
     * Should be called when the renderer is no longer needed.
     */
    public void cleanup() {
        if (triangleVao != 0) {
            GL30.glDeleteVertexArrays(triangleVao);
            triangleVao = 0;
        }
    }
}