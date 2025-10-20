package com.openmason.ui.viewport.gizmo.modes;

import com.openmason.ui.viewport.gizmo.GizmoState;
import com.openmason.ui.viewport.gizmo.geometry.CircleGeometry;
import com.openmason.ui.viewport.gizmo.interaction.AxisConstraint;
import com.openmason.ui.viewport.gizmo.interaction.GizmoPart;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Rotation gizmo mode with 3 rotation circles (X, Y, Z axes).
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only handles rotation gizmo
 * - Implements IGizmoMode: Interchangeable with other modes
 * - RAII: Proper OpenGL resource cleanup
 */
public class RotateMode implements IGizmoMode {

    private static final float GIZMO_RADIUS = 1.0f;

    // OpenGL resources
    private boolean initialized = false;
    private int[] circleVAOs = new int[3];  // X, Y, Z rotation circles
    private int[] circleVBOs = new int[3];
    private int[] circleVertexCounts = new int[3];

    private int shaderProgram = -1;  // Will be set externally

    /**
     * Creates a new RotateMode.
     */
    public RotateMode() {
        // Resources initialized in initialize()
    }

    @Override
    public void initialize() {
        if (initialized) {
            return; // Already initialized
        }

        try {
            initializeCircles();
            initialized = true;
        } catch (Exception e) {
            dispose(); // Clean up partial initialization
            throw new IllegalStateException("Failed to initialize RotateMode", e);
        }
    }

    /**
     * Initializes rotation circle geometry and OpenGL resources.
     * Uses thick triangles for proper visible thickness.
     */
    private void initializeCircles() {
        Vector3f center = new Vector3f(0, 0, 0);

        // Generate THICK circle geometry for each axis (rendered as triangles)
        float[][] circleData = CircleGeometry.createThickAxisRotationCircles(center, GIZMO_RADIUS);

        // Create VAO/VBO for each circle
        for (int i = 0; i < 3; i++) {
            circleVAOs[i] = GL30.glGenVertexArrays();
            circleVBOs[i] = GL30.glGenBuffers();

            GL30.glBindVertexArray(circleVAOs[i]);
            GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, circleVBOs[i]);

            // Upload vertex data
            FloatBuffer buffer = BufferUtils.createFloatBuffer(circleData[i].length);
            buffer.put(circleData[i]);
            buffer.flip();

            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_STATIC_DRAW);

            // Position attribute (location = 0)
            GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 0);
            GL30.glEnableVertexAttribArray(0);

            // Color attribute (location = 1)
            GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
            GL30.glEnableVertexAttribArray(1);

            // Store vertex count
            circleVertexCounts[i] = circleData[i].length / 6;

            GL30.glBindVertexArray(0);
        }
    }

    @Override
    public void render(Matrix4f gizmoTransform, Matrix4f viewProjection, GizmoState gizmoState) {
        if (!initialized) {
            throw new IllegalStateException("RotateMode not initialized");
        }
        if (gizmoTransform == null || viewProjection == null || gizmoState == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (shaderProgram < 0) {
            throw new IllegalStateException("Shader program not set");
        }

        // Use shader program
        GL30.glUseProgram(shaderProgram);

        // Get uniform locations
        int uModelLoc = GL30.glGetUniformLocation(shaderProgram, "uModelMatrix");
        int uViewProjLoc = GL30.glGetUniformLocation(shaderProgram, "uViewProjection");
        int uIntensityLoc = GL30.glGetUniformLocation(shaderProgram, "uIntensity");

        // Set view-projection matrix
        FloatBuffer vpBuffer = BufferUtils.createFloatBuffer(16);
        viewProjection.get(vpBuffer);
        GL30.glUniformMatrix4fv(uViewProjLoc, false, vpBuffer);

        // Set model matrix
        FloatBuffer modelBuffer = BufferUtils.createFloatBuffer(16);
        gizmoTransform.get(modelBuffer);
        GL30.glUniformMatrix4fv(uModelLoc, false, modelBuffer);

        // Render circles (no glLineWidth needed - we're using thick triangles)
        renderCircles(gizmoState, uIntensityLoc);

        GL30.glUseProgram(0);
    }

    /**
     * Renders the three rotation circles as thick triangles.
     */
    private void renderCircles(GizmoState gizmoState, int intensityUniformLoc) {
        AxisConstraint[] constraints = {AxisConstraint.X, AxisConstraint.Y, AxisConstraint.Z};

        for (int i = 0; i < 3; i++) {
            // Get intensity based on hover/active state
            float intensity = gizmoState.getIntensityForConstraint(constraints[i]);
            GL30.glUniform1f(intensityUniformLoc, intensity);

            // Render circle as triangle strip (thick geometry)
            GL30.glBindVertexArray(circleVAOs[i]);
            GL30.glDrawArrays(GL30.GL_TRIANGLE_STRIP, 0, circleVertexCounts[i]);
            GL30.glBindVertexArray(0);
        }
    }

    @Override
    public List<GizmoPart> getInteractiveParts(Vector3f gizmoPosition) {
        if (gizmoPosition == null) {
            throw new IllegalArgumentException("Gizmo position cannot be null");
        }

        List<GizmoPart> parts = new ArrayList<>();

        // Add rotation circle parts
        AxisConstraint[] constraints = {AxisConstraint.X, AxisConstraint.Y, AxisConstraint.Z};

        Vector3f[] colors = {
            new Vector3f(1, 0, 0), // Red for X
            new Vector3f(0, 1, 0), // Green for Y
            new Vector3f(0, 0, 1)  // Blue for Z
        };

        for (int i = 0; i < 3; i++) {
            parts.add(new GizmoPart(
                constraints[i],
                colors[i],
                GizmoPart.PartType.CIRCLE,
                gizmoPosition,
                GIZMO_RADIUS // Circle radius for intersection testing
            ));
        }

        return parts;
    }

    @Override
    public Vector3f handleDrag(Vector2f currentMousePos, GizmoState gizmoState,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               int viewportWidth, int viewportHeight) {
        // Drag handling is done in GizmoInteractionHandler
        // This method is for modes that need custom drag behavior
        return new Vector3f(0, 0, 0);
    }

    @Override
    public void dispose() {
        if (!initialized) {
            return;
        }

        // Delete circle VAOs/VBOs
        for (int i = 0; i < 3; i++) {
            if (circleVAOs[i] != 0) {
                GL30.glDeleteVertexArrays(circleVAOs[i]);
                circleVAOs[i] = 0;
            }
            if (circleVBOs[i] != 0) {
                GL30.glDeleteBuffers(circleVBOs[i]);
                circleVBOs[i] = 0;
            }
        }

        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Sets the shader program for rendering.
     * Must be called before rendering.
     *
     * @param program OpenGL shader program ID
     */
    public void setShaderProgram(int program) {
        this.shaderProgram = program;
    }
}
