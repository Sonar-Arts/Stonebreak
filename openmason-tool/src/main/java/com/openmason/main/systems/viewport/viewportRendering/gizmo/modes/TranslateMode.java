package com.openmason.main.systems.viewport.viewportRendering.gizmo.modes;

import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.geometry.ArrowGeometry;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction.AxisConstraint;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction.GizmoPart;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Translation gizmo mode with 3 arrows (X, Y, Z) and 3 plane handles (XY, XZ, YZ).
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only handles translation gizmo
 * - Implements IGizmoMode: Interchangeable with other modes
 * - RAII: Proper OpenGL resource cleanup
 */
public class TranslateMode implements IGizmoMode {

    private static final float GIZMO_SIZE = 1.5f;  // Increased for better visibility
    private static final float PLANE_SIZE = 0.2f;  // Scaled proportionally
    private static final float PLANE_OFFSET = 0.01f; // Near origin for compact appearance (must be > 0)

    // OpenGL resources
    private boolean initialized = false;
    private int[] arrowVAOs = new int[3];  // X, Y, Z arrows
    private int[] arrowVBOs = new int[3];
    private int[] arrowVertexCounts = new int[3];

    private int[] planeVAOs = new int[3];  // XY, XZ, YZ planes
    private int[] planeVBOs = new int[3];
    private int[] planeVertexCounts = new int[3];

    private int shaderProgram = -1;  // Will be set externally

    /**
     * Creates a new TranslateMode.
     */
    public TranslateMode() {
        // Resources initialized in initialize()
    }

    @Override
    public void initialize() {
        if (initialized) {
            return; // Already initialized
        }

        try {
            initializeArrows();
            initializePlanes();
            initialized = true;
        } catch (Exception e) {
            dispose(); // Clean up partial initialization
            throw new IllegalStateException("Failed to initialize TranslateMode", e);
        }
    }

    /**
     * Initializes arrow geometry and OpenGL resources.
     */
    private void initializeArrows() {
        Vector3f origin = new Vector3f(0, 0, 0);

        // Generate arrow geometry
        float[][] arrowData = ArrowGeometry.createAxisArrows(origin, GIZMO_SIZE);

        // Create VAO/VBO for each arrow
        for (int i = 0; i < 3; i++) {
            arrowVAOs[i] = GL30.glGenVertexArrays();
            arrowVBOs[i] = GL30.glGenBuffers();

            GL30.glBindVertexArray(arrowVAOs[i]);
            GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, arrowVBOs[i]);

            // Upload vertex data
            FloatBuffer buffer = BufferUtils.createFloatBuffer(arrowData[i].length);
            buffer.put(arrowData[i]);
            buffer.flip();

            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_STATIC_DRAW);

            // Position attribute (location = 0)
            GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 0);
            GL30.glEnableVertexAttribArray(0);

            // Color attribute (location = 1)
            GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
            GL30.glEnableVertexAttribArray(1);

            // Store vertex count (6 floats per vertex = position + color)
            arrowVertexCounts[i] = arrowData[i].length / 6;

            GL30.glBindVertexArray(0);
        }
    }

    /**
     * Initializes plane handle geometry and OpenGL resources.
     */
    private void initializePlanes() {
        Vector3f origin = new Vector3f(0, 0, 0);

        // Generate plane geometry
        float[][] planeData = ArrowGeometry.createPlaneHandles(origin, PLANE_SIZE, PLANE_OFFSET);

        // Create VAO/VBO for each plane
        for (int i = 0; i < 3; i++) {
            planeVAOs[i] = GL30.glGenVertexArrays();
            planeVBOs[i] = GL30.glGenBuffers();

            GL30.glBindVertexArray(planeVAOs[i]);
            GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, planeVBOs[i]);

            // Upload vertex data
            FloatBuffer buffer = BufferUtils.createFloatBuffer(planeData[i].length);
            buffer.put(planeData[i]);
            buffer.flip();

            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_STATIC_DRAW);

            // Position attribute (location = 0)
            GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 0);
            GL30.glEnableVertexAttribArray(0);

            // Color attribute (location = 1)
            GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
            GL30.glEnableVertexAttribArray(1);

            planeVertexCounts[i] = planeData[i].length / 6;

            GL30.glBindVertexArray(0);
        }
    }

    @Override
    public void render(Matrix4f gizmoTransform, Matrix4f viewProjection, GizmoState gizmoState) {
        if (!initialized) {
            throw new IllegalStateException("TranslateMode not initialized");
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

        // Set view-projection matrix (same for all parts)
        FloatBuffer vpBuffer = BufferUtils.createFloatBuffer(16);
        viewProjection.get(vpBuffer);
        GL30.glUniformMatrix4fv(uViewProjLoc, false, vpBuffer);

        // Set model matrix (same for all parts)
        FloatBuffer modelBuffer = BufferUtils.createFloatBuffer(16);
        gizmoTransform.get(modelBuffer);
        GL30.glUniformMatrix4fv(uModelLoc, false, modelBuffer);

        // Enable blending for semi-transparent planes
        GL30.glEnable(GL30.GL_BLEND);
        GL30.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA);

        // Render arrows
        renderArrows(gizmoState, uIntensityLoc);

        // Render plane handles with transparency
        renderPlanes(gizmoState, uIntensityLoc);

        GL30.glDisable(GL30.GL_BLEND);
        GL30.glUseProgram(0);
    }

    /**
     * Renders the three axis arrows.
     */
    private void renderArrows(GizmoState gizmoState, int intensityUniformLoc) {
        AxisConstraint[] constraints = {AxisConstraint.X, AxisConstraint.Y, AxisConstraint.Z};

        for (int i = 0; i < 3; i++) {
            // Get intensity based on hover/active state
            float intensity = gizmoState.getIntensityForConstraint(constraints[i]);
            GL30.glUniform1f(intensityUniformLoc, intensity);

            // Render arrow
            GL30.glBindVertexArray(arrowVAOs[i]);

            // Arrows are mix of lines and triangles
            // First 2 vertices are the line, rest are cone triangles
            GL30.glDrawArrays(GL30.GL_LINES, 0, 2);
            GL30.glDrawArrays(GL30.GL_TRIANGLES, 2, arrowVertexCounts[i] - 2);

            GL30.glBindVertexArray(0);
        }
    }

    /**
     * Renders the three plane handles.
     */
    private void renderPlanes(GizmoState gizmoState, int intensityUniformLoc) {
        AxisConstraint[] constraints = {AxisConstraint.XY, AxisConstraint.XZ, AxisConstraint.YZ};

        for (int i = 0; i < 3; i++) {
            // Get intensity based on hover/active state
            float intensity = gizmoState.getIntensityForConstraint(constraints[i]);
            GL30.glUniform1f(intensityUniformLoc, intensity * 0.5f); // Make planes semi-transparent

            // Render plane (2 triangles = 6 vertices)
            GL30.glBindVertexArray(planeVAOs[i]);
            GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, planeVertexCounts[i]);
            GL30.glBindVertexArray(0);
        }
    }

    @Override
    public List<GizmoPart> getInteractiveParts(Vector3f gizmoPosition, float scaleFactor) {
        if (gizmoPosition == null) {
            throw new IllegalArgumentException("Gizmo position cannot be null");
        }

        List<GizmoPart> parts = new ArrayList<>();
        float scaledGizmoSize = GIZMO_SIZE * scaleFactor;
        float scaledPlaneSize = PLANE_SIZE * scaleFactor;
        float scaledPlaneOffset = PLANE_OFFSET * scaleFactor;

        // Add arrow parts
        Vector3f[] arrowDirections = {
            new Vector3f(1, 0, 0), // X
            new Vector3f(0, 1, 0), // Y
            new Vector3f(0, 0, 1)  // Z
        };

        AxisConstraint[] arrowConstraints = {AxisConstraint.X, AxisConstraint.Y, AxisConstraint.Z};

        Vector3f[] arrowColors = {
            new Vector3f(1, 0, 0), // Red
            new Vector3f(0, 1, 0), // Green
            new Vector3f(0, 0, 1)  // Blue
        };

        for (int i = 0; i < 3; i++) {
            Vector3f center = ArrowGeometry.getArrowCenter(
                gizmoPosition,
                arrowDirections[i],
                scaledGizmoSize
            );
            float radius = ArrowGeometry.getInteractionRadius(scaledGizmoSize);

            parts.add(new GizmoPart(
                arrowConstraints[i],
                arrowColors[i],
                GizmoPart.PartType.ARROW,
                center,
                radius
            ));
        }

        // Add plane parts
        Vector3f[][] planeAxes = {
            {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0)}, // XY
            {new Vector3f(1, 0, 0), new Vector3f(0, 0, 1)}, // XZ
            {new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}  // YZ
        };

        AxisConstraint[] planeConstraints = {AxisConstraint.XY, AxisConstraint.XZ, AxisConstraint.YZ};

        Vector3f[] planeColors = {
            new Vector3f(1, 1, 0), // Yellow
            new Vector3f(1, 0, 1), // Magenta
            new Vector3f(0, 1, 1)  // Cyan
        };

        for (int i = 0; i < 3; i++) {
            Vector3f center = ArrowGeometry.getPlaneHandleCenter(
                gizmoPosition,
                planeAxes[i][0],
                planeAxes[i][1],
                scaledPlaneSize,
                scaledPlaneOffset
            );

            parts.add(new GizmoPart(
                planeConstraints[i],
                planeColors[i],
                GizmoPart.PartType.PLANE,
                center,
                scaledPlaneSize * 0.7f // Interaction radius
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
        // For translation, we use the default implementation
        return new Vector3f(0, 0, 0);
    }

    @Override
    public void dispose() {
        if (!initialized) {
            return;
        }

        // Delete arrow VAOs/VBOs
        for (int i = 0; i < 3; i++) {
            if (arrowVAOs[i] != 0) {
                GL30.glDeleteVertexArrays(arrowVAOs[i]);
                arrowVAOs[i] = 0;
            }
            if (arrowVBOs[i] != 0) {
                GL30.glDeleteBuffers(arrowVBOs[i]);
                arrowVBOs[i] = 0;
            }
        }

        // Delete plane VAOs/VBOs
        for (int i = 0; i < 3; i++) {
            if (planeVAOs[i] != 0) {
                GL30.glDeleteVertexArrays(planeVAOs[i]);
                planeVAOs[i] = 0;
            }
            if (planeVBOs[i] != 0) {
                GL30.glDeleteBuffers(planeVBOs[i]);
                planeVBOs[i] = 0;
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
