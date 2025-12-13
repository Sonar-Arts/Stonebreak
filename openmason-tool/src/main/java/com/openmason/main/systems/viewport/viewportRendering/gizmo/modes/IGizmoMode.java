package com.openmason.main.systems.viewport.viewportRendering.gizmo.modes;

import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction.GizmoPart;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Interface for gizmo transform modes (Translate, Rotate, Scale).
 *
 * <p>This interface follows SOLID principles:
 * - Interface Segregation: Only essential methods for mode behavior
 * - Liskov Substitution: All implementations can be used interchangeably
 * - Open/Closed: New modes can be added without modifying existing code
 */
public interface IGizmoMode {
    /**
     * Initializes OpenGL resources for this mode (VAO, VBO, etc.).
     * Called once when the mode is first created.
     *
     * @throws IllegalStateException if initialization fails
     */
    void initialize();

    /**
     * Renders the gizmo in this mode.
     *
     * @param gizmoTransform The world transform matrix for gizmo position
     * @param viewProjection The combined view-projection matrix
     * @param gizmoState The current gizmo state for highlighting
     * @throws IllegalArgumentException if any parameter is null
     */
    void render(Matrix4f gizmoTransform, Matrix4f viewProjection, GizmoState gizmoState);

    /**
     * Gets all interactive parts of this gizmo mode for hit testing.
     *
     * @param gizmoPosition The world-space position of the gizmo center
     * @return List of gizmo parts (never null, may be empty)
     * @throws IllegalArgumentException if gizmoPosition is null
     */
    List<GizmoPart> getInteractiveParts(Vector3f gizmoPosition);

    /**
     * Handles a drag operation by computing the new transform values.
     *
     * @param currentMousePos Current mouse position in screen space
     * @param gizmoState The gizmo state containing drag start info
     * @param viewMatrix The camera view matrix
     * @param projectionMatrix The camera projection matrix
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return New transform value (position for translate, rotation for rotate, scale for scale)
     * @throws IllegalArgumentException if any parameter is null or invalid
     */
    Vector3f handleDrag(Vector2f currentMousePos, GizmoState gizmoState,
                        Matrix4f viewMatrix, Matrix4f projectionMatrix,
                        int viewportWidth, int viewportHeight);

    /**
     * Cleans up OpenGL resources for this mode.
     * Called when the mode is no longer needed.
     */
    void dispose();

    /**
     * Checks if this mode has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    boolean isInitialized();
}
