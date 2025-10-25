package com.openmason.ui.viewport.rendering;

import com.openmason.ui.viewport.resources.GeometryBuffer;
import com.openmason.ui.viewport.shaders.ShaderProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Renders the grid in the viewport.
 * Follows Single Responsibility Principle - only handles grid rendering.
 */
public class GridRenderer {

    private static final Logger logger = LoggerFactory.getLogger(GridRenderer.class);

    // Grid color (gray)
    private static final float GRID_COLOR_R = 0.5f;
    private static final float GRID_COLOR_G = 0.5f;
    private static final float GRID_COLOR_B = 0.5f;

    /**
     * Render grid using provided geometry and shader.
     */
    public void render(GeometryBuffer gridGeometry, ShaderProgram shader, RenderContext context) {
        if (!gridGeometry.isInitialized()) {
            logger.warn("Grid geometry not initialized");
            return;
        }

        try {
            // Use shader program
            glUseProgram(shader.getProgramId());

            // Upload view-projection matrix
            glUniformMatrix4fv(shader.getMvpMatrixLocation(), false, context.uploadMatrix(context.getViewProjectionMatrix()));

            // Set grid color
            glUniform3f(shader.getColorLocation(), GRID_COLOR_R, GRID_COLOR_G, GRID_COLOR_B);

            // Bind and render grid
            gridGeometry.bind();
            glDrawArrays(GL_LINES, 0, gridGeometry.getVertexCount());
            gridGeometry.unbind();

            logger.trace("Grid rendered successfully");

        } catch (Exception e) {
            logger.error("Error rendering grid", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Set custom grid color.
     */
    public void renderWithColor(GeometryBuffer gridGeometry, ShaderProgram shader, RenderContext context,
                                float r, float g, float b) {
        if (!gridGeometry.isInitialized()) {
            logger.warn("Grid geometry not initialized");
            return;
        }

        try {
            glUseProgram(shader.getProgramId());
            glUniformMatrix4fv(shader.getMvpMatrixLocation(), false, context.uploadMatrix(context.getViewProjectionMatrix()));
            glUniform3f(shader.getColorLocation(), r, g, b);

            gridGeometry.bind();
            glDrawArrays(GL_LINES, 0, gridGeometry.getVertexCount());
            gridGeometry.unbind();

            logger.trace("Grid rendered with custom color: ({}, {}, {})", r, g, b);

        } catch (Exception e) {
            logger.error("Error rendering grid with custom color", e);
        } finally {
            glUseProgram(0);
        }
    }
}
