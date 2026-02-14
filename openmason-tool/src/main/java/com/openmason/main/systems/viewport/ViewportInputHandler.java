package com.openmason.main.systems.viewport;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.KnifePreviewRenderer;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.input.*;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.operations.EdgeInputController;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.operations.FaceInputController;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main controller for viewport input handling.
 * Orchestrates specialized sub-controllers using priority-based routing.
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
    private final KnifeToolController knifeController;

    // Translation coordinator for mutual exclusion
    private TranslationCoordinator translationCoordinator;

    // Last known viewport-relative mouse position (for G key grab)
    private float lastViewportMouseX = 0;
    private float lastViewportMouseY = 0;

    public ViewportInputHandler(ViewportCamera viewportCamera) {
        this.viewportCamera = viewportCamera;

        // Initialize sub-controllers
        this.mouseCaptureManager = new MouseCaptureManager();
        this.cameraController = new CameraInputController(viewportCamera, mouseCaptureManager);
        this.gizmoController = new GizmoInputController();
        this.vertexController = new VertexInputController();
        this.edgeController = new EdgeInputController();
        this.faceController = new FaceInputController();
        this.knifeController = new KnifeToolController();
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
        gizmoController.setVertexRenderer(vertexRenderer); // Gizmo controller needs vertex renderer for priority
    }

    /**
     * Set the vertex selection state for vertex manipulation.
     */
    public void setVertexSelectionState(VertexSelectionState vertexSelectionState) {
        vertexController.setVertexSelectionState(vertexSelectionState);
    }

    /**
     * Set the translation coordinator to manage all translation handlers.
     * The coordinator is passed to each input controller to ensure mutual exclusion.
     * Also registers with EditModeManager to cancel drags on mode switch.
     */
    public void setTranslationCoordinator(TranslationCoordinator translationCoordinator) {
        this.translationCoordinator = translationCoordinator;

        // Pass coordinator to individual controllers (they will use it for mutual exclusion)
        vertexController.setTranslationCoordinator(translationCoordinator);
        edgeController.setTranslationCoordinator(translationCoordinator);
        faceController.setTranslationCoordinator(translationCoordinator);

        // Register with EditModeManager to cancel drags on mode switch
        EditModeManager.getInstance().setTranslationCoordinator(translationCoordinator);

        logger.debug("Translation coordinator set and distributed to input controllers");
    }

    /**
     * Set the edge renderer for edge hover detection.
     */
    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        edgeController.setEdgeRenderer(edgeRenderer);
        faceController.setEdgeRenderer(edgeRenderer); // Face controller needs edge renderer for priority
        gizmoController.setEdgeRenderer(edgeRenderer); // Gizmo controller needs edge renderer for priority
        knifeController.setEdgeRenderer(edgeRenderer); // Knife tool needs edge renderer for hover detection
    }

    /**
     * Set the edge selection state for edge manipulation.
     */
    public void setEdgeSelectionState(EdgeSelectionState edgeSelectionState) {
        edgeController.setEdgeSelectionState(edgeSelectionState);
    }

    /**
     * Set the face renderer for face hover detection.
     */
    public void setFaceRenderer(FaceRenderer faceRenderer) {
        faceController.setFaceRenderer(faceRenderer);
        gizmoController.setFaceRenderer(faceRenderer); // Gizmo controller needs face renderer for priority
    }

    /**
     * Set the face selection state for face manipulation.
     */
    public void setFaceSelectionState(FaceSelectionState faceSelectionState) {
        faceController.setFaceSelectionState(faceSelectionState);
    }

    /**
     * Set the transform state for model matrix access.
     */
    public void setTransformState(TransformState transformState) {
        vertexController.setTransformState(transformState);
        edgeController.setTransformState(transformState);
        faceController.setTransformState(transformState);
        knifeController.setTransformState(transformState);
    }

    /**
     * Set the generic model renderer for mesh operations (J key edge insert, F key face create, X key face delete, K knife tool).
     */
    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        vertexController.setModelRenderer(modelRenderer);
        faceController.setModelRenderer(modelRenderer);
        knifeController.setModelRenderer(modelRenderer);
    }

    /**
     * Set the knife tool preview renderer for visual feedback.
     */
    public void setKnifePreviewRenderer(KnifePreviewRenderer previewRenderer) {
        knifeController.setPreviewRenderer(previewRenderer);
    }

    /**
     * Toggle the knife tool on/off.
     * Delegates to KnifeToolController.
     */
    public void toggleKnifeTool() {
        knifeController.toggle();
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

        // Track last viewport mouse position for G key grab
        this.lastViewportMouseX = viewportMouseX;
        this.lastViewportMouseY = viewportMouseY;

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

        // Update translation coordinator camera matrices if available
        if (translationCoordinator != null) {
            translationCoordinator.updateCamera(
                    viewportCamera.getViewMatrix(),
                    viewportCamera.getProjectionMatrix(),
                    (int) imageWidth,
                    (int) imageHeight
            );
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
                viewportCamera.getProjectionMatrix(),
                ImGui.getIO().getKeyShift(),
                ImGui.getIO().getKeyCtrl(),
                ImGui.getIO().getKeyAlt()
        );

        // ========== Priority-Based Input Routing ==========

        // CRITICAL: If camera is already dragging, it maintains priority until drag ends
        // This prevents other controllers from interrupting an active camera drag
        if (cameraController.isDragging()) {
            cameraController.handleInput(context);
            return; // Camera drag in progress, block all other controllers
        }

        // Priority 0: Knife tool (highest when active — modal tool consumes all input)
        if (knifeController.handleInput(context)) {
            return; // Knife tool handled input, block all lower-priority controllers
        }

        // Priority 1: Vertex
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
        // Face selection and manipulation (surface editing, lower than edge)
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

    /**
     * Get the translation coordinator for direct access to grab operations.
     * Used by keybind actions for G key grab mode.
     *
     * @return The translation coordinator, or null if not set
     */
    public TranslationCoordinator getTranslationCoordinator() {
        return translationCoordinator;
    }

    /**
     * Start or confirm grab mode from keybind (G key).
     * Uses the last known viewport mouse position as the drag reference point.
     * Blender-style: Press G to grab, press G again to confirm.
     *
     * @return true if grab started or confirmed, false otherwise
     */
    public boolean startGrabMode() {
        if (translationCoordinator == null) {
            logger.debug("Cannot start grab: translation coordinator not set");
            return false;
        }

        // If already dragging, G confirms the drag
        if (translationCoordinator.isDragging()) {
            // Final position update before committing
            translationCoordinator.handleMouseMove(lastViewportMouseX, lastViewportMouseY);
            // Commit the final position
            translationCoordinator.handleMouseRelease(lastViewportMouseX, lastViewportMouseY);
            logger.info("Confirmed grab mode from keybind (G pressed again)");
            return true;
        }

        boolean started = translationCoordinator.handleMousePress(lastViewportMouseX, lastViewportMouseY);
        if (started) {
            logger.info("Started grab mode from keybind at ({}, {})", lastViewportMouseX, lastViewportMouseY);
        } else {
            logger.debug("Grab mode not started: no selection or wrong edit mode");
        }

        return started;
    }

}
