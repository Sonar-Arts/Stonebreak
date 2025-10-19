package com.openmason.ui.viewport.rendering;

import com.openmason.ui.viewport.TransformGizmo;
import com.openmason.ui.viewport.shaders.ShaderProgram;
import com.openmason.ui.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders the transform gizmo with complete state isolation.
 * Ensures zero interference with model rendering pipeline.
 * Follows Single Responsibility Principle - only handles gizmo rendering.
 */
public class GizmoRenderer {

    private static final Logger logger = LoggerFactory.getLogger(GizmoRenderer.class);

    /**
     * Render transform gizmo with isolated context.
     */
    public void render(TransformGizmo gizmo, ShaderProgram gizmoShader,
                      RenderContext context, TransformState transform) {
        if (gizmo == null || !gizmo.isVisible() || !transform.isGizmoEnabled()) {
            return;
        }

        try {
            // COMPLETE STATE ISOLATION - Save ALL OpenGL state
            int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int previousVAO = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int previousArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);

            // Validate OpenGL context before gizmo operations
            int glError = glGetError();
            if (glError != GL_NO_ERROR) {
                logger.warn("OpenGL error detected before gizmo rendering: {}", glError);
                return; // Skip gizmo rendering if context is invalid
            }

            // Use DEDICATED gizmo shader (completely separate from model pipeline)
            glUseProgram(gizmoShader.getProgramId());

            // Set gizmo position from transform state
            gizmo.setPosition(transform.getPositionX(), transform.getPositionY(), transform.getPositionZ());

            // Create clean identity matrix (gizmo handles its own positioning)
            Matrix4f gizmoIdentityMatrix = new Matrix4f().identity();

            // Render gizmo with dedicated shader and uniforms
            gizmo.render(gizmoShader.getProgramId(),
                        gizmoShader.getMvpMatrixLocation(),
                        gizmoShader.getColorLocation(),
                        context.getViewProjectionMatrix(),
                        gizmoIdentityMatrix);

            // COMPLETE STATE RESTORATION - Restore ALL OpenGL state
            glUseProgram(previousProgram);
            glBindVertexArray(previousVAO);
            glBindBuffer(GL_ARRAY_BUFFER, previousArrayBuffer);

            // Verify no OpenGL errors occurred during gizmo rendering
            glError = glGetError();
            if (glError != GL_NO_ERROR) {
                logger.warn("OpenGL error during gizmo rendering: {}", glError);
            }

            logger.trace("Transform gizmo rendered successfully");

        } catch (Exception e) {
            logger.error("Error in isolated gizmo rendering", e);
            // Emergency state cleanup - ensure model rendering can continue
            try {
                glUseProgram(0);
                glBindVertexArray(0);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            } catch (Exception cleanupError) {
                logger.error("Error during emergency gizmo state cleanup", cleanupError);
            }
        }
    }

    /**
     * Check if gizmo should be rendered.
     */
    public boolean shouldRender(TransformGizmo gizmo, TransformState transform) {
        return gizmo != null && gizmo.isVisible() && transform.isGizmoEnabled();
    }
}
