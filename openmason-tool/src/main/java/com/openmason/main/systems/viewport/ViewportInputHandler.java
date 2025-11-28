package com.openmason.main.systems.viewport;

import com.openmason.main.systems.viewport.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.viewportRendering.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.VertexRenderer;
import imgui.ImGui;
import imgui.ImVec2;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.DoubleBuffer;

/**
 * Handles all input processing for the OpenMason 3D viewport.
 * Separates input concerns from viewport rendering for better architecture.
 *
 * Features:
 * - Mouse capture for endless dragging
 * - Camera rotation and zooming
 * - Gizmo interaction (transform manipulation)
 * - Vertex selection and manipulation
 * - Professional mouse sensitivity settings
 * - Raw mouse motion support
 * - Clean separation of input and rendering concerns
 */
public class ViewportInputHandler {

    private static final Logger logger = LoggerFactory.getLogger(ViewportInputHandler.class);

    // Camera reference for input handling
    private final ViewportCamera viewportCamera;

    // Gizmo renderer for transform interaction
    private GizmoRenderer gizmoRenderer = null;

    // Vertex renderer for vertex hover detection
    private VertexRenderer vertexRenderer = null;

    // Vertex selection state for vertex manipulation
    private VertexSelectionState vertexSelectionState = null;

    // Edge renderer for edge hover detection
    private EdgeRenderer edgeRenderer = null;

    // Mouse interaction state
    private boolean isDragging = false;
    
    // Mouse capture state for endless dragging
    private boolean isMouseCaptured = false;
    private double savedCursorX = 0.0;
    private double savedCursorY = 0.0;
    private long windowHandle = 0L; // GLFW window handle
    private boolean rawMouseMotionSupported = false;
    
    public ViewportInputHandler(ViewportCamera viewportCamera) {
        this.viewportCamera = viewportCamera;
        // logger.info("ViewportInputHandler initialized");
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This should be called from the main application.
     */
    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        if (windowHandle != 0L) {
            // Check if raw mouse motion is supported
            this.rawMouseMotionSupported = GLFW.glfwRawMouseMotionSupported();
            // logger.info("Window handle set. Raw mouse motion supported: {}", rawMouseMotionSupported);
        }
    }

    /**
     * Set the gizmo renderer for transform interaction.
     * This should be called after viewport initialization.
     */
    public void setGizmoRenderer(GizmoRenderer gizmoRenderer) {
        this.gizmoRenderer = gizmoRenderer;
        logger.debug("Gizmo renderer set in ViewportInputHandler");
    }

    /**
     * Set the vertex renderer for vertex hover detection.
     * This should be called after viewport initialization.
     */
    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        this.vertexRenderer = vertexRenderer;
        logger.debug("Vertex renderer set in ViewportInputHandler");
    }

    /**
     * Set the vertex selection state for vertex manipulation.
     * This should be called after viewport initialization.
     */
    public void setVertexSelectionState(VertexSelectionState vertexSelectionState) {
        this.vertexSelectionState = vertexSelectionState;
        logger.debug("Vertex selection state set in ViewportInputHandler");
    }

    /**
     * Set the edge renderer for edge hover detection.
     * This should be called after viewport initialization.
     */
    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        this.edgeRenderer = edgeRenderer;
        logger.debug("Edge renderer set in ViewportInputHandler");
    }
    
    /**
     * Capture the mouse cursor for endless dragging.
     * Hides cursor and enables unlimited mouse movement.
     */
    private void captureMouse() {
        if (windowHandle == 0L || isMouseCaptured) {
            return;
        }
        
        try {
            // Save current cursor position
            DoubleBuffer xPos = BufferUtils.createDoubleBuffer(1);
            DoubleBuffer yPos = BufferUtils.createDoubleBuffer(1);
            GLFW.glfwGetCursorPos(windowHandle, xPos, yPos);
            savedCursorX = xPos.get(0);
            savedCursorY = yPos.get(0);
            
            // Disable cursor (hides it and enables infinite movement)
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            
            // Enable raw mouse motion if supported for better camera control
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
            }
            
            isMouseCaptured = true;
            // logger.info("Mouse captured for endless dragging at position: ({}, {})", savedCursorX, savedCursorY);
            
        } catch (Exception e) {
            logger.error("Failed to capture mouse", e);
        }
    }
    
    /**
     * Release mouse capture and restore cursor visibility and position.
     */
    private void releaseMouse() {
        if (windowHandle == 0L || !isMouseCaptured) {
            return;
        }
        
        try {
            // Restore normal cursor mode
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            
            // Disable raw mouse motion
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            }
            
            // Restore cursor position
            GLFW.glfwSetCursorPos(windowHandle, savedCursorX, savedCursorY);
            
            isMouseCaptured = false;
            // logger.info("Mouse released and cursor restored to position: ({}, {})", savedCursorX, savedCursorY);
            
        } catch (Exception e) {
            logger.error("Failed to release mouse", e);
        }
    }
    
    /**
     * Handle input for camera controls with endless dragging and mouse capture.
     * This is the main entry point for input processing.
     *
     * @param imagePos The screen position of the viewport image
     * @param imageWidth The width of the viewport image
     * @param imageHeight The height of the viewport image
     * @param viewportHovered Whether the viewport window is being hovered (not overlaying windows)
     */
    public void handleInput(ImVec2 imagePos, float imageWidth, float imageHeight, boolean viewportHovered) {
        if (viewportCamera == null) {
            logger.warn("Camera is null - cannot handle input");
            return;
        }

        // Get mouse state
        ImVec2 mousePos = ImGui.getIO().getMousePos();
        boolean wantCapture = ImGui.getIO().getWantCaptureMouse();

        // Calculate viewport-relative mouse coordinates
        float viewportMouseX = mousePos.x - imagePos.x;
        float viewportMouseY = mousePos.y - imagePos.y;

        // Check if mouse is within viewport bounds
        boolean mouseInBounds = !isMouseCaptured && (mousePos.x >= imagePos.x &&
                               mousePos.x < imagePos.x + imageWidth &&
                               mousePos.y >= imagePos.y &&
                               mousePos.y < imagePos.y + imageHeight);

        // Only respect WantCaptureMouse if mouse is NOT in viewport bounds or we're not actively dragging
        if (wantCapture && !mouseInBounds && !isDragging) {
            if (isDragging) {
                stopDragging();
            }
            return;
        }

        // ========== Gizmo Input Handling (Priority System) ==========
        // Gizmo gets priority over camera controls to prevent mouse capture conflicts.
        // The gizmo needs screen-space mouse positions and visible cursor for raycasting,
        // while camera rotation uses mouse capture (hidden cursor, endless dragging).
        // These are incompatible, so we give gizmo full priority when active.

        boolean gizmoHandledInput = false;
        boolean gizmoIsActive = false; // Tracks if gizmo is hovered or dragging

        if (gizmoRenderer != null && gizmoRenderer.isInitialized() &&
            gizmoRenderer.getGizmoState().isEnabled()) {

            // Update gizmo with camera matrices for raycasting
            gizmoRenderer.handleMouseMove(
                viewportMouseX,
                viewportMouseY,
                viewportCamera.getViewMatrix(),
                viewportCamera.getProjectionMatrix(),
                (int) imageWidth,
                (int) imageHeight
            );

            // Check if gizmo has a hovered part (prevents camera from capturing mouse)
            // This is critical: we need to check hover BEFORE camera can start dragging
            boolean gizmoIsHovered = gizmoRenderer.getGizmoState().getHoveredPart() != null;

            // Handle mouse press on gizmo
            if (mouseInBounds && ImGui.isMouseClicked(0)) {
                gizmoHandledInput = gizmoRenderer.handleMousePress(viewportMouseX, viewportMouseY);
                if (gizmoHandledInput) {
                    logger.debug("Gizmo captured mouse press");
                }
            }

            // Handle mouse release
            if (ImGui.isMouseReleased(0)) {
                gizmoRenderer.handleMouseRelease(viewportMouseX, viewportMouseY);
            }

            // Priority 1: Active gizmo dragging
            // If gizmo is dragging, release any camera mouse capture immediately
            // This ensures gizmo gets clean screen-space mouse tracking
            if (gizmoRenderer.isDragging()) {
                gizmoHandledInput = true;
                gizmoIsActive = true;

                // Release camera mouse capture if it was active
                // Gizmo needs visible cursor and screen-space coordinates
                if (isDragging || isMouseCaptured) {
                    logger.debug("Releasing camera mouse capture - gizmo is dragging");
                    stopDragging();
                }
            }

            // Priority 2: Gizmo hover state
            // Prevent camera from starting drag when hovering over gizmo parts
            // This prevents the camera from capturing the mouse on the next click
            if (gizmoIsHovered) {
                gizmoIsActive = true;
                gizmoHandledInput = true; // Treat hover as "handled" to block camera input
            }
        }

        // ========== Vertex Selection Handling (Priority 2) ==========
        // Handle vertex selection - gets priority after gizmo but before camera.
        // This allows clicking on vertices to select them without camera interference.
        boolean vertexHandledInput = false;

        if (vertexRenderer != null && vertexRenderer.isInitialized() && vertexRenderer.isEnabled() &&
            vertexSelectionState != null && !gizmoHandledInput) {

            // Handle ESC key to deselect vertex
            if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                if (vertexSelectionState.hasSelection()) {
                    vertexSelectionState.clearSelection();
                    vertexRenderer.clearSelection();
                    logger.debug("Vertex selection cleared (ESC key pressed)");
                    vertexHandledInput = true;
                }
            }

            // Handle mouse click for vertex selection
            if (mouseInBounds && ImGui.isMouseClicked(0)) {
                int hoveredVertex = vertexRenderer.getHoveredVertexIndex();

                if (hoveredVertex >= 0) {
                    // Clicking on a hovered vertex - select it
                    Vector3f vertexPosition = getVertexPosition(hoveredVertex);
                    if (vertexPosition != null) {
                        vertexSelectionState.selectVertex(hoveredVertex, vertexPosition);
                        vertexRenderer.setSelectedVertex(hoveredVertex);
                        logger.debug("Vertex {} selected at position ({}, {}, {})",
                                hoveredVertex,
                                String.format("%.2f", vertexPosition.x),
                                String.format("%.2f", vertexPosition.y),
                                String.format("%.2f", vertexPosition.z));
                        vertexHandledInput = true;
                    }
                } else {
                    // Clicking on empty space - deselect if something was selected
                    if (vertexSelectionState.hasSelection()) {
                        vertexSelectionState.clearSelection();
                        vertexRenderer.clearSelection();
                        logger.debug("Vertex selection cleared (clicked on empty space)");
                        vertexHandledInput = true;
                    }
                }
            }
        }

        // ========== Vertex Hover Detection ==========
        // Handle vertex hover using the same pattern as gizmo
        if (vertexRenderer != null && vertexRenderer.isInitialized() && vertexRenderer.isEnabled()) {
            // Update vertex hover with camera matrices for raycasting
            vertexRenderer.handleMouseMove(
                viewportMouseX,
                viewportMouseY,
                viewportCamera.getViewMatrix(),
                viewportCamera.getProjectionMatrix(),
                (int) imageWidth,
                (int) imageHeight
            );
        }

        // ========== Edge Hover Detection ==========
        // Handle edge hover using the same pattern as vertex hover
        if (edgeRenderer != null && edgeRenderer.isInitialized() && edgeRenderer.isEnabled()) {
            // Update edge hover with camera matrices for raycasting
            edgeRenderer.handleMouseMove(
                viewportMouseX,
                viewportMouseY,
                viewportCamera.getViewMatrix(),
                viewportCamera.getProjectionMatrix(),
                (int) imageWidth,
                (int) imageHeight
            );
        }

        // ========== Camera Input Handling (Fallthrough) ==========
        // Only process camera input if gizmo or vertex didn't capture the input.
        // This ensures gizmo and vertex selection have priority over camera controls.
        // Camera will only activate when:
        // - Gizmo is disabled OR not active, AND
        // - Vertex selection didn't handle the input
        if (!gizmoHandledInput && !vertexHandledInput) {
            boolean mouseClicked = ImGui.isMouseClicked(0);

            // Start camera dragging when left mouse button is pressed within viewport bounds
            // AND the viewport window itself is being hovered (not overlaying windows)
            // This won't be reached if gizmo is hovered or active
            if (mouseInBounds && mouseClicked && viewportHovered) {
                startDragging();
            }

            // Continue dragging with unlimited mouse movement (mouse is now captured)
            if (isDragging && ImGui.isMouseDown(0)) {
                processDragging();
            }

            // Stop dragging when mouse button is released
            if (ImGui.isMouseReleased(0) && isDragging) {
                stopDragging();
            }

            // Handle mouse wheel for zooming (only when hovering over viewport or actively dragging)
            // Check viewportHovered to prevent scroll bleed-through from overlaying windows (e.g., texture editor)
            if ((mouseInBounds && viewportHovered) || isDragging) {
                processZooming();
            }
        }
    }
    
    /**
     * Start a drag operation.
     */
    private void startDragging() {
        isDragging = true;
        captureMouse(); // Enable endless dragging with mouse capture
        // logger.info("Started endless dragging in 3D viewport - mouse captured");
    }
    
    /**
     * Process mouse dragging for camera rotation.
     */
    private void processDragging() {
        ImVec2 mouseDelta = ImGui.getIO().getMouseDelta();
        
        // Process any mouse movement - even tiny amounts for smooth rotation
        if (Math.abs(mouseDelta.x) > 0.0001f || Math.abs(mouseDelta.y) > 0.0001f) {
            logger.trace("Processing mouse delta: ({}, {})", mouseDelta.x, mouseDelta.y);
            
            // Apply rotation with appropriate sensitivity for captured mouse
            float rotationSpeed = isMouseCaptured ? 0.003f : 0.1f; // Lower sensitivity for captured mouse
            viewportCamera.rotate(-mouseDelta.x * rotationSpeed, -mouseDelta.y * rotationSpeed);
            logger.trace("Camera rotated by delta: ({}, {}) with speed: {}", 
                -mouseDelta.x * rotationSpeed, -mouseDelta.y * rotationSpeed, rotationSpeed);
        }
    }
    
    /**
     * Stop the current drag operation.
     */
    private void stopDragging() {
        // logger.info("Stopped endless dragging in 3D viewport - mouse released");
        isDragging = false;
        releaseMouse(); // Restore cursor visibility and position
    }
    
    /**
     * Process mouse wheel for zooming.
     */
    private void processZooming() {
        float wheel = ImGui.getIO().getMouseWheel();
        if (wheel != 0) {
            viewportCamera.zoom(wheel * 0.5f);
            // logger.debug("Camera zoomed by: {}", wheel * 0.5f);
        }
    }
    
    /**
     * Force release mouse capture. Useful for escape key handling.
     */
    public void forceReleaseMouse() {
        if (isDragging) {
            isDragging = false;
            // logger.info("Forced release of mouse capture");
        }
        releaseMouse();
    }
    
    /**
     * Handle keyboard input for camera controls.
     * This can be extended for first-person camera movement.
     */
    public void handleKeyboardInput(float deltaTime) {
        if (viewportCamera == null || viewportCamera.getCameraMode() != ViewportCamera.CameraMode.FIRST_PERSON) {
            return;
        }
        
        // First-person movement controls
        boolean moved = false;
        
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_W)) {
            viewportCamera.moveForward(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_S)) {
            viewportCamera.moveForward(-deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_A)) {
            viewportCamera.moveRight(-deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_D)) {
            viewportCamera.moveRight(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_SPACE)) {
            viewportCamera.moveUp(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || ImGui.isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            viewportCamera.moveUp(-deltaTime);
            moved = true;
        }
        
        if (moved) {
            logger.trace("First-person camera movement processed");
        }
    }
    
    /**
     * Reset the input handler state.
     */
    public void reset() {
        if (isDragging) {
            stopDragging();
        }
        // logger.debug("ViewportInputHandler reset");
    }
    
    /**
     * Cleanup resources when the input handler is no longer needed.
     */
    public void cleanup() {
        reset();
        // logger.info("ViewportInputHandler cleaned up");
    }

    /**
     * Get the world-space position of a vertex by its index.
     * This is a helper method for vertex selection.
     *
     * @param vertexIndex The index of the vertex
     * @return The vertex position, or null if invalid
     */
    private Vector3f getVertexPosition(int vertexIndex) {
        if (vertexRenderer == null || !vertexRenderer.isInitialized()) {
            return null;
        }

        return vertexRenderer.getVertexPosition(vertexIndex);
    }

    // Getters for state information
    public boolean isDragging() {
        return isDragging;
    }

    public boolean isMouseCaptured() {
        return isMouseCaptured;
    }

    public boolean isRawMouseMotionSupported() {
        return rawMouseMotionSupported;
    }

    public double getSavedCursorX() {
        return savedCursorX;
    }

    public double getSavedCursorY() {
        return savedCursorY;
    }

    public long getWindowHandle() {
        return windowHandle;
    }
}