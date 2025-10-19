package com.openmason.ui.viewport.rendering;

import com.openmason.ui.viewport.resources.GeometryBuffer;
import com.openmason.ui.viewport.shaders.ShaderProgram;
import com.openmason.ui.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Renders the test cube (fallback when no model is loaded).
 * Follows Single Responsibility Principle - only handles test cube rendering.
 */
public class TestCubeRenderer {

    private static final Logger logger = LoggerFactory.getLogger(TestCubeRenderer.class);

    // Cube colors
    private static final float CUBE_COLOR_R = 1.0f; // Orange cube
    private static final float CUBE_COLOR_G = 0.5f;
    private static final float CUBE_COLOR_B = 0.2f;
    private static final float WIREFRAME_COLOR_R = 1.0f; // White wireframe
    private static final float WIREFRAME_COLOR_G = 1.0f;
    private static final float WIREFRAME_COLOR_B = 1.0f;

    /**
     * Render test cube with transform applied.
     */
    public void render(GeometryBuffer cubeGeometry, ShaderProgram shader,
                      RenderContext context, TransformState transform) {
        if (!cubeGeometry.isInitialized()) {
            logger.warn("Cube geometry not initialized");
            return;
        }

        try {
            // Use shader program
            glUseProgram(shader.getProgramId());

            // Calculate MVP matrix with user transform applied
            Matrix4f mvpWithTransform = context.createMVPMatrix(transform.getTransformMatrix());

            // Upload transformed MVP matrix
            glUniformMatrix4fv(shader.getMvpMatrixLocation(), false, context.uploadMatrix(mvpWithTransform));

            // Set cube color based on wireframe mode
            if (context.isWireframeMode()) {
                glUniform3f(shader.getColorLocation(), WIREFRAME_COLOR_R, WIREFRAME_COLOR_G, WIREFRAME_COLOR_B);
            } else {
                glUniform3f(shader.getColorLocation(), CUBE_COLOR_R, CUBE_COLOR_G, CUBE_COLOR_B);
            }

            // Bind and render cube
            cubeGeometry.bind();
            glDrawArrays(GL_TRIANGLES, 0, cubeGeometry.getVertexCount());
            cubeGeometry.unbind();

            logger.trace("Test cube rendered successfully");

        } catch (Exception e) {
            logger.error("Error rendering test cube", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Render test cube with custom color.
     */
    public void renderWithColor(GeometryBuffer cubeGeometry, ShaderProgram shader,
                                RenderContext context, TransformState transform,
                                float r, float g, float b) {
        if (!cubeGeometry.isInitialized()) {
            logger.warn("Cube geometry not initialized");
            return;
        }

        try {
            glUseProgram(shader.getProgramId());

            Matrix4f mvpWithTransform = context.createMVPMatrix(transform.getTransformMatrix());
            glUniformMatrix4fv(shader.getMvpMatrixLocation(), false, context.uploadMatrix(mvpWithTransform));
            glUniform3f(shader.getColorLocation(), r, g, b);

            cubeGeometry.bind();
            glDrawArrays(GL_TRIANGLES, 0, cubeGeometry.getVertexCount());
            cubeGeometry.unbind();

            logger.trace("Test cube rendered with custom color: ({}, {}, {})", r, g, b);

        } catch (Exception e) {
            logger.error("Error rendering test cube with custom color", e);
        } finally {
            glUseProgram(0);
        }
    }
}
