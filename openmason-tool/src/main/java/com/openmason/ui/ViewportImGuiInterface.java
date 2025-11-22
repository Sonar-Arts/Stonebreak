package com.openmason.ui;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.ui.viewport.gizmo.GizmoState;
import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dear ImGui viewport interface for 3D viewport rendering.
 * Handles 3D viewport rendering, camera controls, and view mode management using immediate mode GUI.
 */
public class ViewportImGuiInterface {

    private static final Logger logger = LoggerFactory.getLogger(ViewportImGuiInterface.class);

    // Viewport State - Using only ImGui types (no duplicate state)
    private final ImBoolean gridVisible = new ImBoolean(true);
    private final ImBoolean axesVisible = new ImBoolean(true);
    private final ImBoolean gridSnappingEnabled = new ImBoolean(false);
    private final ImBoolean wireframeMode = new ImBoolean(false);
    private final ImBoolean showVertices = new ImBoolean(false);
    private boolean viewportInitialized = false;

    // Control Window Visibility
    private final ImBoolean showCameraControls = new ImBoolean(false);
    private final ImBoolean showRenderingOptions = new ImBoolean(false);
    private final ImBoolean showTransformationControls = new ImBoolean(false);

    // View Mode State
    private final String[] viewModes = {"Perspective", "Orthographic", "Front", "Side", "Top", "Bottom", "Isometric"};
    private final ImInt currentViewModeIndex = new ImInt(0);

    // Render Mode State
    private final String[] renderModes = {"Solid", "Wireframe", "Points", "Textured"};
    private final ImInt currentRenderModeIndex = new ImInt(0);

    // Camera State
    private final ImFloat cameraDistance = new ImFloat(5.0f);
    private final ImFloat cameraPitch = new ImFloat(30.0f);
    private final ImFloat cameraYaw = new ImFloat(45.0f);
    private final ImFloat cameraFOV = new ImFloat(60.0f);

    // Core Components
    private OpenMason3DViewport viewport3D;

    // Viewport Dimensions
    private final ImVec2 viewportSize = new ImVec2();
    private final ImVec2 viewportPos = new ImVec2();

    public ViewportImGuiInterface() {
        initialize();
    }

    /**
     * Initialize the viewport interface and set up 3D viewport.
     */
    public void initialize() {
        setupViewport();
    }

    /**
     * Main render method for viewport and controls.
     */
    public void render() {
        try {
            // Keyboard shortcuts are now handled inside renderViewportWindow()
            // after ImGui.begin() so we can properly check window focus
            renderViewportWindow();

            if (showCameraControls.get()) {
                renderCameraControls();
            }

            if (showRenderingOptions.get()) {
                renderRenderingOptions();
            }

            if (showTransformationControls.get()) {
                renderTransformationControls();
            }

        } catch (Exception e) {
            logger.error("Critical error during viewport interface rendering", e);
            throw new RuntimeException("Viewport rendering failed", e);
        }
    }

    /**
     * Handle viewport-specific keyboard shortcuts.
     * Should only be called when the viewport window is focused and ImGui doesn't want keyboard capture.
     */
    private void handleKeyboardShortcuts() {
        if (viewport3D == null) {
            return;
        }

        ImGuiIO io = ImGui.getIO();
        boolean ctrlPressed = io.getKeyCtrl();

        // Ctrl+T: Toggle Transform Gizmo
        if (ctrlPressed && ImGui.isKeyPressed(GLFW.GLFW_KEY_T)) {
            boolean currentState = viewport3D.isGizmoEnabled();
            viewport3D.setGizmoEnabled(!currentState);
            logger.info("Transform gizmo toggled: {}", !currentState);
        }

        // Ctrl+G: Toggle Grid
        if (ctrlPressed && ImGui.isKeyPressed(GLFW.GLFW_KEY_G)) {
            gridVisible.set(!gridVisible.get());
            toggleGrid();
            logger.info("Grid toggled: {}", gridVisible.get());
        }

        // Ctrl+X: Toggle Axes
        if (ctrlPressed && ImGui.isKeyPressed(GLFW.GLFW_KEY_X)) {
            axesVisible.set(!axesVisible.get());
            toggleAxes();
            logger.info("Axes toggled: {}", axesVisible.get());
        }

        // Ctrl+W: Toggle Wireframe
        if (ctrlPressed && ImGui.isKeyPressed(GLFW.GLFW_KEY_W)) {
            toggleWireframe();
            logger.info("Wireframe toggled: {}", wireframeMode.get());
        }

        // Ctrl+R: Reset View
        if (ctrlPressed && ImGui.isKeyPressed(GLFW.GLFW_KEY_R)) {
            resetView();
            logger.info("View reset");
        }

        // Ctrl+F: Fit to View
        if (ctrlPressed && ImGui.isKeyPressed(GLFW.GLFW_KEY_F)) {
            fitToView();
            logger.info("Fit to view");
        }
    }

    /**
     * Render main viewport window with 3D content.
     */
    private void renderViewportWindow() {
        if (ImGui.begin("3D Viewport")) {
            // Handle keyboard shortcuts FIRST, before any widgets consume input
            // Only block shortcuts if actively typing in a text input field
            // Window focus check removed for consistency with TextureEditorWindow
            boolean activelyTyping = ImGui.isAnyItemActive() && ImGui.getIO().getWantTextInput();
            if (!activelyTyping) {
                handleKeyboardShortcuts();
            }

            // Viewport toolbar
            renderViewportToolbar();

            ImGui.separator();

            // Render 3D viewport content embedded in this window
            renderViewport3D();

        }
        ImGui.end();
    }

    /**
     * Render viewport toolbar with view and render mode controls.
     */
    private void renderViewportToolbar() {
        // View mode selection
        ImGui.text("View:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##viewmode", currentViewModeIndex, viewModes)) {
            updateViewMode();
        }

        ImGui.sameLine();

        // Render mode selection
        ImGui.text("Render:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##rendermode", currentRenderModeIndex, renderModes)) {
            updateRenderMode();
        }

        ImGui.sameLine();

        // View control buttons
        if (ImGui.button("Reset##viewport")) {
            resetView();
        }

        ImGui.sameLine();

        if (ImGui.button("Fit##viewport")) {
            fitToView();
        }

        ImGui.sameLine();

        // Toggle buttons
        if (ImGui.checkbox("Grid##viewport", gridVisible)) {
            toggleGrid();
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Axes##viewport", axesVisible)) {
            toggleAxes();
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Grid Snapping##viewport", gridSnappingEnabled)) {
            toggleGridSnapping();
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Mesh##viewport", showVertices)) {
            toggleShowVertices();
        }

        // Additional controls toggle
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();

        if (ImGui.button("Camera##viewport")) {
            showCameraControls.set(!showCameraControls.get());
        }

        ImGui.sameLine();

        if (ImGui.button("Rendering##viewport")) {
            showRenderingOptions.set(!showRenderingOptions.get());
        }

        ImGui.sameLine();

        if (ImGui.button("Transform##viewport")) {
            showTransformationControls.set(!showTransformationControls.get());
        }
    }

    /**
     * Render actual 3D viewport content.
     */
    private void renderViewport3D() {
        // Get available content region
        ImGui.getContentRegionAvail(viewportSize);
        ImGui.getCursorScreenPos(viewportPos);

        // Ensure minimum size
        if (viewportSize.x < 400) viewportSize.x = 400;
        if (viewportSize.y < 300) viewportSize.y = 300;

        // Resize viewport if needed
        viewport3D.resize((int) viewportSize.x, (int) viewportSize.y);

        // Render 3D content
        viewport3D.render();

        // Display the rendered texture with mouse capture functionality
        int colorTexture = viewport3D.getColorTexture();
        if (colorTexture == -1) {
            ImGui.text("Viewport texture not available");
            return;
        }

        // Get image position before drawing for manual bounds checking
        ImVec2 imagePos = ImGui.getCursorScreenPos();

        // Display the rendered texture directly without any widgets
        ImGui.image(colorTexture, viewportSize.x, viewportSize.y, 0, 1, 1, 0);

        // Check if the viewport window itself is being hovered
        // This prevents mouse capture when interacting with overlaying windows
        boolean viewportHovered = ImGui.isWindowHovered();

        // Handle input after image - no widgets interfering with raw input
        if (viewport3D.getInputHandler() != null) {
            viewport3D.getInputHandler().handleInput(imagePos, viewportSize.x, viewportSize.y, viewportHovered);
        }
    }

    /**
     * Render camera controls window.
     */
    private void renderCameraControls() {
        if (ImGui.begin("Camera Controls", showCameraControls)) {

            ImGui.text("Camera Position:");

            if (ImGui.sliderFloat("Distance", cameraDistance.getData(), 1.0f, 20.0f)) {
                updateCameraDistance();
            }

            if (ImGui.sliderFloat("Pitch", cameraPitch.getData(), -89.0f, 89.0f)) {
                updateCameraPitch();
            }

            if (ImGui.sliderFloat("Yaw", cameraYaw.getData(), -180.0f, 180.0f)) {
                updateCameraYaw();
            }

            ImGui.separator();

            ImGui.text("Camera Settings:");

            if (ImGui.sliderFloat("Field of View", cameraFOV.getData(), 30.0f, 120.0f)) {
                updateCameraFOV();
            }

            ImGui.separator();

            if (ImGui.button("Reset Camera")) {
                resetCamera();
            }

        }
        ImGui.end();
    }

    /**
     * Render rendering options window.
     */
    private void renderRenderingOptions() {
        if (ImGui.begin("Rendering Options", showRenderingOptions)) {

            ImGui.text("Render Mode:");
            if (ImGui.combo("##render_mode_detailed", currentRenderModeIndex, renderModes)) {
                updateRenderMode();
            }

            ImGui.separator();

            ImGui.text("Quality Settings:");
            ImGui.text("Anti-aliasing: 4x MSAA");
            ImGui.text("Shadow Quality: High");
            ImGui.text("Texture Filtering: Anisotropic 16x");

            ImGui.separator();

            if (ImGui.button("Apply Settings")) {
                applyRenderingSettings();
            }

        }
        ImGui.end();
    }

    /**
     * Render transformation controls window.
     */
    private void renderTransformationControls() {
        if (ImGui.begin("Transform Controls", showTransformationControls)) {

            // Gizmo visibility toggle
            boolean gizmoEnabled = viewport3D.isGizmoEnabled();
            if (ImGui.checkbox("Show Transform Gizmo (Ctrl+T)", new ImBoolean(gizmoEnabled))) {
                viewport3D.setGizmoEnabled(!gizmoEnabled);
                logger.info("Transform gizmo toggled: {}", !gizmoEnabled);
            }

            ImGui.separator();

            ImGui.text("Transform Mode:");
            ImGui.separator();

            // Get current gizmo mode
            GizmoState.Mode currentMode = viewport3D.getGizmoMode();

            // Translate mode radio button
            if (ImGui.radioButton("Translate Mode", currentMode == GizmoState.Mode.TRANSLATE)) {
                viewport3D.setGizmoMode(GizmoState.Mode.TRANSLATE);
                logger.info("Switched to Translate mode");
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Move the model using arrow gizmos (G key)");
            }

            ImGui.sameLine();

            // Rotate mode radio button
            if (ImGui.radioButton("Rotate Mode", currentMode == GizmoState.Mode.ROTATE)) {
                viewport3D.setGizmoMode(GizmoState.Mode.ROTATE);
                logger.info("Switched to Rotate mode");
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Rotate the model using circular grabbers (R key)");
            }

            ImGui.sameLine();

            // Scale mode radio button
            if (ImGui.radioButton("Scale Mode", currentMode == GizmoState.Mode.SCALE)) {
                viewport3D.setGizmoMode(GizmoState.Mode.SCALE);
                logger.info("Switched to Scale mode");
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Scale the model using box handles (S key)");
            }

            ImGui.separator();

            // Display mode-specific controls and information
            if (currentMode == GizmoState.Mode.SCALE) {
                // Uniform scaling toggle (only for Scale mode)
                boolean uniformScaling = viewport3D.getGizmoUniformScaling();
                if (ImGui.checkbox("Uniform Scaling", uniformScaling)) {
                    viewport3D.setGizmoUniformScaling(!uniformScaling);
                    logger.info("Uniform scaling toggled: {}", !uniformScaling);
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("When enabled, all axes scale together. When disabled, each axis scales independently.");
                }

                ImGui.separator();

                // Help text for scale mode
                if (uniformScaling) {
                    ImGui.textWrapped("Uniform scaling ON: Drag any handle to scale all axes together.");
                } else {
                    ImGui.textWrapped("Uniform scaling OFF: Drag colored box handles to scale individual axes - X (red), Y (green), or Z (blue). Drag the center white box to scale uniformly.");
                }
            } else if (currentMode == GizmoState.Mode.TRANSLATE) {
                ImGui.textWrapped("Drag the colored arrows to move along X (red), Y (green), or Z (blue) axes.");
            } else if (currentMode == GizmoState.Mode.ROTATE) {
                ImGui.textWrapped("Drag the circular grabbers to rotate around X (red), Y (green), or Z (blue) axes.");
            }

        }
        ImGui.end();
    }

    // Action Methods

    private void setupViewport() {
        // Viewport should be injected via setViewport3D()
        if (viewport3D == null) {
            logger.warn("No viewport3D instance provided - ViewportImGuiInterface requires external viewport injection");
        } else {
            viewportInitialized = true;
        }
    }

    /**
     * Set the shared 3D viewport instance.
     * This must be called before initialize() to prevent duplicate viewport creation.
     */
    public void setViewport3D(OpenMason3DViewport viewport) {
        this.viewport3D = viewport;
        logger.info("Shared 3D viewport injected into ViewportImGuiInterface: {}",
                   viewport != null ? System.identityHashCode(viewport) : "NULL");
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This must be called from the main application after initialization.
     */
    public void setWindowHandle(long windowHandle) {
        if (viewport3D != null) {
            viewport3D.setWindowHandle(windowHandle);
        } else {
            logger.warn("Cannot set window handle - viewport not initialized");
        }
    }

    private void updateViewMode() {
        String viewMode = viewModes[currentViewModeIndex.get()];

        var camera = viewport3D.getCamera();

        switch (viewMode.toLowerCase()) {
            case "front" -> {
                camera.setYaw(0.0f);
                camera.setPitch(0.0f);
            }
            case "side" -> {
                camera.setYaw(90.0f);
                camera.setPitch(0.0f);
            }
            case "top" -> {
                camera.setYaw(0.0f);
                camera.setPitch(-90.0f);
            }
            case "bottom" -> {
                camera.setYaw(0.0f);
                camera.setPitch(90.0f);
            }
            case "isometric" -> {
                camera.setYaw(45.0f);
                camera.setPitch(-30.0f);
            }
            default -> logger.warn("Unknown view mode: {}", viewMode);
        }

        updateCameraStateFromViewport();
    }

    private void updateRenderMode() {
        String renderMode = renderModes[currentRenderModeIndex.get()];

        switch (renderMode.toLowerCase()) {
            case "wireframe":
                wireframeMode.set(true);
                if (viewport3D != null) {
                    viewport3D.setWireframeMode(true);
                }
                break;
            default:
                wireframeMode.set(false);
                if (viewport3D != null) {
                    viewport3D.setWireframeMode(false);
                }
                break;
        }
    }

    private void resetView() {
        logger.info("Resetting viewport view");
        viewport3D.getCamera().reset();
        updateCameraStateFromViewport();

        // Reset to perspective view
        currentViewModeIndex.set(0);
    }

    private void fitToView() {
        logger.info("Fitting view to model");
        viewport3D.getCamera().setYaw(45.0f);
        viewport3D.getCamera().setPitch(-30.0f);
        updateCameraStateFromViewport();
    }

    private void toggleGrid() {
        logger.info("Grid visibility: {}", gridVisible.get());
        if (viewport3D != null) {
            viewport3D.setShowGrid(gridVisible.get());
        }
    }

    private void toggleAxes() {
        logger.info("Axes visibility: {}", axesVisible.get());
        if (viewport3D != null) {
            viewport3D.setAxesVisible(axesVisible.get());
        }
    }

    private void toggleGridSnapping() {
        logger.info("Grid snapping: {}", gridSnappingEnabled.get());
        if (viewport3D != null) {
            viewport3D.setGridSnappingEnabled(gridSnappingEnabled.get());

            // Save to preferences
            com.openmason.ui.preferences.PreferencesManager prefs =
                new com.openmason.ui.preferences.PreferencesManager();
            prefs.setGridSnappingEnabled(gridSnappingEnabled.get());
        }
    }

    private void toggleShowVertices() {
        if (viewport3D != null) {
            viewport3D.setShowVertices(showVertices.get());
        }
    }

    public void toggleWireframe() {
        wireframeMode.set(!wireframeMode.get());
        logger.info("Wireframe mode: {}", wireframeMode.get());

        // Update viewport wireframe mode
        if (viewport3D != null) {
            viewport3D.setWireframeMode(wireframeMode.get());
        }

        // Update render mode combo to match
        if (wireframeMode.get()) {
            currentRenderModeIndex.set(1); // Wireframe
        } else {
            currentRenderModeIndex.set(0); // Solid
        }
    }

    private void updateCameraDistance() {
        logger.debug("Camera distance updated: {}", cameraDistance.get());
        viewport3D.getCamera().setDistance(cameraDistance.get());
    }

    private void updateCameraPitch() {
        logger.debug("Camera pitch updated: {}", cameraPitch.get());
        viewport3D.getCamera().setPitch(cameraPitch.get());
    }

    private void updateCameraYaw() {
        logger.debug("Camera yaw updated: {}", cameraYaw.get());
        viewport3D.getCamera().setYaw(cameraYaw.get());
    }

    private void updateCameraFOV() {
        logger.debug("Camera FOV updated: {}", cameraFOV.get());
        viewport3D.getCamera().setFov(cameraFOV.get());
    }

    private void resetCamera() {
        logger.info("Resetting camera to defaults");
        cameraDistance.set(5.0f);
        cameraPitch.set(30.0f);
        cameraYaw.set(45.0f);
        cameraFOV.set(60.0f);

        viewport3D.getCamera().reset();
    }

    private void updateCameraStateFromViewport() {
        // Sync UI state with actual camera state
        var camera = viewport3D.getCamera();
        cameraDistance.set(camera.getDistance());
        cameraPitch.set(camera.getPitch());
        cameraYaw.set(camera.getYaw());
        cameraFOV.set(camera.getFov());
    }

    private void applyRenderingSettings() {
        logger.info("Applying rendering settings");
        // Implementation would apply rendering quality settings
    }

    // Getters for external access

    public OpenMason3DViewport getViewport3D() {
        return viewport3D;
    }

    public boolean isWireframeMode() {
        return wireframeMode.get();
    }

    public boolean isGridVisible() {
        return gridVisible.get();
    }

    public boolean isAxesVisible() {
        return axesVisible.get();
    }

    public boolean isViewportInitialized() {
        return viewportInitialized;
    }

    /**
     * Update method called every frame.
     */
    public void update(float deltaTime) {
        // Update any animated elements or periodic updates
    }

    /**
     * Cleanup method called when shutting down.
     */
    public void dispose() {
        logger.info("Disposing ViewportImGuiInterface");
        if (viewport3D != null) {
            viewport3D.cleanup();
            viewport3D = null;
        }
        viewportInitialized = false;
    }

    /**
     * Reset viewport to defaults.
     */
    public void resetToDefaults() {
        currentViewModeIndex.set(0);
        currentRenderModeIndex.set(0);
        cameraDistance.set(5.0f);
        gridVisible.set(true);
        axesVisible.set(true);
    }
}
