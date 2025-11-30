package com.openmason.main.systems.viewport;

import com.openmason.main.systems.viewport.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.input.*;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.viewportRendering.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexTranslationHandler;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main controller for viewport input handling.
 * Orchestrates specialized sub-controllers using priority-based routing.
 *
 * Priority System: camera (if dragging) > vertex > edge > face > gizmo > camera (fallback)
 * - Active camera drag maintains priority (prevents interruption)
 * - Vertex editing has highest priority for new input (most precise)
 * - Edge editing has second priority (precise editing)
 * - Face editing has third priority (surface editing)
 * - Gizmo has fourth priority (general transformation)
 * - Camera has lowest priority as fallback (navigation)
 */
public class ViewportInputHandler {

    private static final Logger logger = LoggerFactory.getLogger(ViewportInputHandler.class);

    // Camera reference (legacy for compatibility)
    private final ViewportCamera viewportCamera;

    // Sub-controllers
    private final MouseCaptureManager mouseCaptureManager;
    private final CameraInputController cameraController;
    private final GizmoInputController gizmoController;
    private final VertexInputController vertexController;
    private final EdgeInputController edgeController;
    private final FaceInputController faceController;

    public ViewportInputHandler(ViewportCamera viewportCamera) {
        this.viewportCamera = viewportCamera;

        // Initialize sub-controllers
        this.mouseCaptureManager = new MouseCaptureManager();
        this.cameraController = new CameraInputController(viewportCamera, mouseCaptureManager);
        this.gizmoController = new GizmoInputController();
        this.vertexController = new VertexInputController();
        this.edgeController = new EdgeInputController();
        this.faceController = new FaceInputController();
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     */
    public void setWindowHandle(long windowHandle) {
        mouseCaptureManager.setWindowHandle(windowHandle);
    }

    /**
     * Set the gizmo renderer for transform interaction.
     */
    public void setGizmoRenderer(GizmoRenderer gizmoRenderer) {
        gizmoController.setGizmoRenderer(gizmoRenderer);
    }

    /**
     * Set the vertex renderer for vertex hover detection.
     */
    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        vertexController.setVertexRenderer(vertexRenderer);
        edgeController.setVertexRenderer(vertexRenderer); // Edge controller needs vertex renderer for priority
        faceController.setVertexRenderer(vertexRenderer); // Face controller needs vertex renderer for priority
    }

    /**
     * Set the vertex selection state for vertex manipulation.
     */
    public void setVertexSelectionState(VertexSelectionState vertexSelectionState) {
        vertexController.setVertexSelectionState(vertexSelectionState);
    }

    /**
     * Set the vertex translation handler for drag operations.
     */
    public void setVertexTranslationHandler(VertexTranslationHandler vertexTranslationHandler) {
        vertexController.setVertexTranslationHandler(vertexTranslationHandler);
    }

    /**
     * Set the edge renderer for edge hover detection.
     */
    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        edgeController.setEdgeRenderer(edgeRenderer);
        faceController.setEdgeRenderer(edgeRenderer); // Face controller needs edge renderer for priority
    }

    /**
     * Set the edge selection state for edge manipulation.
     */
    public void setEdgeSelectionState(EdgeSelectionState edgeSelectionState) {
        edgeController.setEdgeSelectionState(edgeSelectionState);
    }

    /**
     * Set the edge translation handler for drag operations.
     */
    public void setEdgeTranslationHandler(EdgeTranslationHandler edgeTranslationHandler) {
        edgeController.setEdgeTranslationHandler(edgeTranslationHandler);
    }

    /**
     * Set the transform state for model matrix access.
     */
    public void setTransformState(TransformState transformState) {
        vertexController.setTransformState(transformState);
        edgeController.setTransformState(transformState);
        faceController.setTransformState(transformState);
    }

    /**
     * Set the face renderer for face hover detection.
     */
    public void setFaceRenderer(FaceRenderer faceRenderer) {
        faceController.setFaceRenderer(faceRenderer);
    }

    /**
     * Set the face selection state for face manipulation.
     */
    public void setFaceSelectionState(FaceSelectionState faceSelectionState) {
        faceController.setFaceSelectionState(faceSelectionState);
    }

    /**
     * Set the face translation handler for drag operations.
     */
    public void setFaceTranslationHandler(FaceTranslationHandler faceTranslationHandler) {
        faceController.setFaceTranslationHandler(faceTranslationHandler);
    }

    /**
     * Handle input for camera controls with priority-based routing.
     *
     * Priority System: vertex > edge > face > gizmo > camera
     * - Vertex editing (highest): Most precise manipulation
     * - Edge editing: Precise edge manipulation
     * - Face editing: Surface manipulation
     * - Gizmo: General transformation tool
     * - Camera (lowest): Fallback for navigation
     */
    public void handleInput(ImVec2 imagePos, float imageWidth, float imageHeight, boolean viewportHovered) {
        if (viewportCamera == null) {
            logger.warn("Camera is null - cannot handle input");
            return;
        }

        // Check if ImGui wants to capture mouse
        boolean wantCapture = ImGui.getIO().getWantCaptureMouse();

        // Get mouse state
        ImVec2 mousePos = ImGui.getIO().getMousePos();

        // Calculate viewport-relative mouse coordinates
        float viewportMouseX = mousePos.x - imagePos.x;
        float viewportMouseY = mousePos.y - imagePos.y;

        // Check if mouse is within viewport bounds
        boolean mouseInBounds = !mouseCaptureManager.isMouseCaptured() && (mousePos.x >= imagePos.x &&
                mousePos.x < imagePos.x + imageWidth &&
                mousePos.y >= imagePos.y &&
                mousePos.y < imagePos.y + imageHeight);

        // Only respect WantCaptureMouse if mouse is NOT in viewport bounds or we're not actively dragging
        if (wantCapture && !mouseInBounds && !cameraController.isDragging()) {
            if (cameraController.isDragging()) {
                cameraController.stopDragging();
            }
            return;
        }

        // Build input context for sub-controllers
        InputContext context = new InputContext(
                viewportMouseX,
                viewportMouseY,
                mouseInBounds,
                viewportHovered,
                ImGui.isMouseClicked(0),
                ImGui.isMouseDown(0),
                ImGui.isMouseReleased(0),
                ImGui.getIO().getMouseWheel(),
                ImGui.getIO().getMouseDelta(),
                (int) imageWidth,
                (int) imageHeight,
                viewportCamera.getViewMatrix(),
                viewportCamera.getProjectionMatrix()
        );

        // ========== Priority-Based Input Routing ==========

        // CRITICAL: If camera is already dragging, it maintains priority until drag ends
        // This prevents other controllers from interrupting an active camera drag
        if (cameraController.isDragging()) {
            cameraController.handleInput(context);
            return; // Camera drag in progress, block all other controllers
        }

        // Priority 1: Vertex (highest)
        // Vertex selection and manipulation gets highest priority (most precise editing)
        if (vertexController.handleInput(context)) {
            return; // Vertex handled input, block all lower-priority controllers
        }

        // Priority 2: Edge
        // Edge selection and manipulation (precise editing, but lower than vertex)
        if (edgeController.handleInput(context)) {
            return; // Edge handled input, block face, gizmo and camera
        }

        // Priority 3: Face
        // Face selection and manipulation (surface editing, lower than vertex/edge)
        if (faceController.handleInput(context)) {
            return; // Face handled input, block gizmo and camera
        }

        // Priority 4: Gizmo
        // Gizmo transformation (general tool, lower priority than precise editing)
        if (gizmoController.handleInput(context)) {
            return; // Gizmo handled input, block camera
        }

        // Priority 5: Camera (lowest, fallthrough)
        // Camera is always available as fallback if no other controller handled input
        cameraController.handleInput(context);
    }

    /**
     * Handle keyboard input for camera controls.
     * Delegates to camera controller for first-person movement.
     */
    public void handleKeyboardInput(float deltaTime) {
        cameraController.handleKeyboardInput(deltaTime);
    }

    /**
     * Force release mouse capture. Useful for escape key handling.
     */
    public void forceReleaseMouse() {
        cameraController.stopDragging();
    }

    /**
     * Reset the input handler state.
     */
    public void reset() {
        cameraController.reset();
    }

    /**
     * Cleanup resources when the input handler is no longer needed.
     */
    public void cleanup() {
        reset();
    }

    // Getters for compatibility with existing code

    public boolean isDragging() {
        return cameraController.isDragging();
    }

    public boolean isMouseCaptured() {
        return mouseCaptureManager.isMouseCaptured();
    }

    public boolean isRawMouseMotionSupported() {
        return mouseCaptureManager.isRawMouseMotionSupported();
    }

    public double getSavedCursorX() {
        return mouseCaptureManager.getSavedCursorX();
    }

    public double getSavedCursorY() {
        return mouseCaptureManager.getSavedCursorY();
    }

    public long getWindowHandle() {
        return mouseCaptureManager.getWindowHandle();
    }
}
