package com.openmason.main.systems.viewport.gizmo.modes;

import com.openmason.main.systems.viewport.gizmo.GizmoState;
import com.openmason.main.systems.viewport.gizmo.geometry.BoxGeometry;
import com.openmason.main.systems.viewport.gizmo.interaction.AxisConstraint;
import com.openmason.main.systems.viewport.gizmo.interaction.GizmoPart;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Scale gizmo mode with 3 axis handles (X, Y, Z) and a center uniform scale box.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only handles scale gizmo
 * - Implements IGizmoMode: Interchangeable with other modes
 * - RAII: Proper OpenGL resource cleanup
 */
public class ScaleMode implements IGizmoMode {

    private static final float GIZMO_SIZE = 1.5f;  // Match TranslateMode arrow length for consistency
    private static final float HANDLE_BOX_SIZE = 0.15f;  // Larger box handles for better visibility at scale
    private static final float CENTER_BOX_SIZE = 0.2f;   // Larger center box for uniform scaling

    // OpenGL resources
    private boolean initialized = false;
    private int[] handleVAOs = new int[3];  // X, Y, Z scale handles
    private int[] handleVBOs = new int[3];
    private int[] handleVertexCounts = new int[3];

    private int centerVAO = 0;  // Center uniform scale box
    private int centerVBO = 0;
    private int centerVertexCount = 0;

    private int shaderProgram = -1;  // Will be set externally

    /**
     * Creates a new ScaleMode.
     */
    public ScaleMode() {
        // Resources initialized in initialize()
    }

    @Override
    public void initialize() {
        if (initialized) {
            return; // Already initialized
        }

        try {
            initializeHandles();
            initializeCenterBox();
            initialized = true;
        } catch (Exception e) {
            dispose(); // Clean up partial initialization
            throw new IllegalStateException("Failed to initialize ScaleMode", e);
        }
    }

    /**
     * Initializes axis scale handle geometry and OpenGL resources.
     */
    private void initializeHandles() {
        Vector3f origin = new Vector3f(0, 0, 0);

        // Generate handle geometry
        float[][] handleData = BoxGeometry.createAxisScaleHandles(
            origin,
            GIZMO_SIZE,
            HANDLE_BOX_SIZE
        );

        // Create VAO/VBO for each handle
        for (int i = 0; i < 3; i++) {
            handleVAOs[i] = GL30.glGenVertexArrays();
            handleVBOs[i] = GL30.glGenBuffers();

            GL30.glBindVertexArray(handleVAOs[i]);
            GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, handleVBOs[i]);

            // Upload vertex data
            FloatBuffer buffer = BufferUtils.createFloatBuffer(handleData[i].length);
            buffer.put(handleData[i]);
            buffer.flip();

            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_STATIC_DRAW);

            // Position attribute (location = 0)
            GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 0);
            GL30.glEnableVertexAttribArray(0);

            // Color attribute (location = 1)
            GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
            GL30.glEnableVertexAttribArray(1);

            // Store vertex count
            handleVertexCounts[i] = handleData[i].length / 6;

            GL30.glBindVertexArray(0);
        }
    }

    /**
     * Initializes center uniform scale box geometry and OpenGL resources.
     */
    private void initializeCenterBox() {
        Vector3f origin = new Vector3f(0, 0, 0);

        // Generate center box geometry
        float[] centerData = BoxGeometry.createCenterScaleBox(origin, CENTER_BOX_SIZE);

        centerVAO = GL30.glGenVertexArrays();
        centerVBO = GL30.glGenBuffers();

        GL30.glBindVertexArray(centerVAO);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, centerVBO);

        // Upload vertex data
        FloatBuffer buffer = BufferUtils.createFloatBuffer(centerData.length);
        buffer.put(centerData);
        buffer.flip();

        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_STATIC_DRAW);

        // Position attribute (location = 0)
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL30.glEnableVertexAttribArray(0);

        // Color attribute (location = 1)
        GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
        GL30.glEnableVertexAttribArray(1);

        centerVertexCount = centerData.length / 6;

        GL30.glBindVertexArray(0);
    }

    @Override
    public void render(Matrix4f gizmoTransform, Matrix4f viewProjection, GizmoState gizmoState) {
        if (!initialized) {
            throw new IllegalStateException("ScaleMode not initialized");
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

        // Render handles
        renderHandles(gizmoState, uIntensityLoc);

        // Render center box
        renderCenterBox(gizmoState, uIntensityLoc);

        GL30.glUseProgram(0);
    }

    /**
     * Renders the three axis scale handles.
     */
    private void renderHandles(GizmoState gizmoState, int intensityUniformLoc) {
        AxisConstraint[] constraints = {AxisConstraint.X, AxisConstraint.Y, AxisConstraint.Z};

        for (int i = 0; i < 3; i++) {
            // Get intensity based on hover/active state
            float intensity = gizmoState.getIntensityForConstraint(constraints[i]);
            GL30.glUniform1f(intensityUniformLoc, intensity);

            // Render handle (lines + box triangles)
            GL30.glBindVertexArray(handleVAOs[i]);

            // First 2 vertices are line
            GL30.glDrawArrays(GL30.GL_LINES, 0, 2);

            // Rest are box triangles (12 triangles = 6 faces * 2 triangles = 36 vertices)
            GL30.glDrawArrays(GL30.GL_TRIANGLES, 2, handleVertexCounts[i] - 2);

            GL30.glBindVertexArray(0);
        }
    }

    /**
     * Renders the center uniform scale box.
     */
    private void renderCenterBox(GizmoState gizmoState, int intensityUniformLoc) {
        // Check if center box is hovered/active
        float intensity = gizmoState.getIntensityForConstraint(AxisConstraint.NONE);
        GL30.glUniform1f(intensityUniformLoc, intensity);

        // Render center box (36 vertices = 12 triangles)
        GL30.glBindVertexArray(centerVAO);
        GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, centerVertexCount);
        GL30.glBindVertexArray(0);
    }

    @Override
    public List<GizmoPart> getInteractiveParts(Vector3f gizmoPosition) {
        if (gizmoPosition == null) {
            throw new IllegalArgumentException("Gizmo position cannot be null");
        }

        List<GizmoPart> parts = new ArrayList<>();

        // Add axis handle parts
        Vector3f[] directions = {
            new Vector3f(1, 0, 0), // X
            new Vector3f(0, 1, 0), // Y
            new Vector3f(0, 0, 1)  // Z
        };

        AxisConstraint[] constraints = {AxisConstraint.X, AxisConstraint.Y, AxisConstraint.Z};

        Vector3f[] colors = {
            new Vector3f(1, 0, 0), // Red
            new Vector3f(0, 1, 0), // Green
            new Vector3f(0, 0, 1)  // Blue
        };

        for (int i = 0; i < 3; i++) {
            Vector3f center = BoxGeometry.getHandleCenter(
                gizmoPosition,
                directions[i],
                GIZMO_SIZE
            );
            float radius = BoxGeometry.getInteractionRadius(HANDLE_BOX_SIZE);

            parts.add(new GizmoPart(
                constraints[i],
                colors[i],
                GizmoPart.PartType.BOX,
                center,
                radius
            ));
        }

        // Add center box part (uniform scale)
        float centerRadius = BoxGeometry.getInteractionRadius(CENTER_BOX_SIZE);
        parts.add(new GizmoPart(
            AxisConstraint.NONE, // NONE constraint = uniform scale
            new Vector3f(0.9f, 0.9f, 0.9f),
            GizmoPart.PartType.CENTER,
            gizmoPosition,
            centerRadius
        ));

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

        // Delete handle VAOs/VBOs
        for (int i = 0; i < 3; i++) {
            if (handleVAOs[i] != 0) {
                GL30.glDeleteVertexArrays(handleVAOs[i]);
                handleVAOs[i] = 0;
            }
            if (handleVBOs[i] != 0) {
                GL30.glDeleteBuffers(handleVBOs[i]);
                handleVBOs[i] = 0;
            }
        }

        // Delete center box VAO/VBO
        if (centerVAO != 0) {
            GL30.glDeleteVertexArrays(centerVAO);
            centerVAO = 0;
        }
        if (centerVBO != 0) {
            GL30.glDeleteBuffers(centerVBO);
            centerVBO = 0;
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
