package com.openmason.ui;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.model.StonebreakModel;
import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import org.joml.Vector3f;

/**
 * Dear ImGui viewport interface replacing JavaFX ViewportController.
 * Handles 3D viewport rendering, camera controls, and view mode management using immediate mode GUI.
 */
public class ViewportImGuiInterface {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportImGuiInterface.class);
    
    // Viewport State
    private boolean gridVisible = true;
    private boolean axesVisible = true;
    private boolean wireframeMode = false;
    private boolean viewportInitialized = false;
    private boolean showViewportControls = true;
    private boolean showCameraControls = false;
    private boolean showRenderingOptions = false;
    private boolean showTransformationControls = false;
    
    // View Mode State
    private final String[] viewModes = {"Perspective", "Orthographic", "Front", "Side", "Top", "Bottom", "Isometric"};
    private final ImInt currentViewModeIndex = new ImInt(0);
    
    // Render Mode State
    private final String[] renderModes = {"Solid", "Wireframe", "Points", "Textured"};
    private final ImInt currentRenderModeIndex = new ImInt(0);
    
    // UI State Management
    private final ImBoolean showViewportControlsRef = new ImBoolean(true);
    private final ImBoolean showCameraControlsRef = new ImBoolean(false);
    private final ImBoolean showRenderingOptionsRef = new ImBoolean(false);
    private final ImBoolean showTransformationControlsRef = new ImBoolean(false);
    private final ImBoolean gridVisibleRef = new ImBoolean(true);
    private final ImBoolean axesVisibleRef = new ImBoolean(true);
    private final ImBoolean wireframeModeRef = new ImBoolean(false);
    
    // Camera State
    private final ImFloat cameraDistance = new ImFloat(5.0f);
    private final ImFloat cameraPitch = new ImFloat(30.0f);
    private final ImFloat cameraYaw = new ImFloat(45.0f);
    private final ImFloat cameraFOV = new ImFloat(60.0f);
    
    // Matrix Transformation State
    private StonebreakModel currentModel = null;
    
    
    // Core Components
    private OpenMason3DViewport viewport3D;
    
    // Viewport Dimensions
    private final ImVec2 viewportSize = new ImVec2();
    private final ImVec2 viewportPos = new ImVec2();
    
    public ViewportImGuiInterface() {
        // logger.info("Initializing ViewportImGuiInterface...");
        
        // Initialize UI state synchronization
        syncBooleanStates();
        
        initialize();
        // logger.info("ViewportImGuiInterface initialized successfully");
    }
    
    /**
     * Initialize the viewport interface and set up 3D viewport.
     */
    public void initialize() {
        setupViewport();
        // logger.info("ViewportImGuiInterface initialized successfully");
    }
    
    /**
     * Main render method for viewport and controls.
     */
    /**
     * Synchronize boolean states between primitive and ImGui reference types
     */
    private void syncBooleanStates() {
        try {
            showViewportControlsRef.set(showViewportControls);
            showCameraControlsRef.set(showCameraControls);
            showRenderingOptionsRef.set(showRenderingOptions);
            showTransformationControlsRef.set(showTransformationControls);
            gridVisibleRef.set(gridVisible);
            axesVisibleRef.set(axesVisible);
            wireframeModeRef.set(wireframeMode);
            // Matrix transformations are always enabled
        } catch (Exception e) {
            logger.warn("Error synchronizing boolean states", e);
        }
    }
    
    /**
     * Update primitive boolean states from ImGui reference types
     */
    private void updateBooleanStates() {
        try {
            showViewportControls = showViewportControlsRef.get();
            showCameraControls = showCameraControlsRef.get();
            showTransformationControls = showTransformationControlsRef.get();
            
            // Matrix transformation mode is always enabled
            showRenderingOptions = showRenderingOptionsRef.get();
            gridVisible = gridVisibleRef.get();
            axesVisible = axesVisibleRef.get();
            wireframeMode = wireframeModeRef.get();
        } catch (Exception e) {
            logger.warn("Error updating boolean states", e);
        }
    }
    
    public void render() {
        try {
            // Synchronize states before rendering
            syncBooleanStates();
            
            // Render main components with error handling
            renderViewportWindow();
            
            // Render optional windows based on state
            if (showViewportControls) {
                renderViewportControls();
            }
            
            if (showCameraControls) {
                renderCameraControls();
            }
            
            if (showRenderingOptions) {
                renderRenderingOptions();
            }
            
            if (showTransformationControls) {
                renderTransformationControls();
            }
            
            // Model controls are handled by Model Browser
            
            // Update states after rendering
            updateBooleanStates();
            
            
        } catch (Exception e) {
            logger.error("Critical error during viewport interface rendering", e);
            throw new RuntimeException("Viewport rendering failed", e);
        }
    }
    
    /**
     * Render main viewport window with 3D content.
     */
    private void renderViewportWindow() {
        if (ImGui.begin("3D Viewport")) {
            
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
        
        if (ImGui.checkbox("Wireframe##viewport", wireframeModeRef)) {
            wireframeMode = wireframeModeRef.get();
            
            // Update viewport wireframe mode
            if (viewport3D != null) {
                viewport3D.setWireframeMode(wireframeMode);
            }
            
            // Update render mode combo to match
            if (wireframeMode) {
                currentRenderModeIndex.set(1); // Wireframe
            } else {
                currentRenderModeIndex.set(0); // Solid
            }
        }
        
        // Additional controls toggle
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();
        
        if (ImGui.button("Controls##viewport")) {
            showViewportControls = !showViewportControls;
        }
        
        ImGui.sameLine();
        
        if (ImGui.button("Camera##viewport")) {
            showCameraControls = !showCameraControls;
        }
        
        ImGui.sameLine();
        
        if (ImGui.button("Rendering##viewport")) {
            showRenderingOptions = !showRenderingOptions;
        }
        
        ImGui.sameLine();
        
        if (ImGui.button("Transform##viewport")) {
            showTransformationControls = !showTransformationControls;
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
        
        // Handle input after image - no widgets interfering with raw input
        if (viewport3D.getInputHandler() != null) {
            viewport3D.getInputHandler().handleInput(imagePos, viewportSize.x, viewportSize.y);
        }
    }
    
    
    /**
     * Render grid overlay on viewport.
     */
    private void renderGrid(ImDrawList drawList) {
        int gridColor = ImColor.rgba(80, 80, 80, 255);
        int spacing = 50;
        
        // Vertical grid lines
        for (int x = (int)viewportPos.x; x < viewportPos.x + viewportSize.x; x += spacing) {
            drawList.addLine(x, viewportPos.y, x, viewportPos.y + viewportSize.y, gridColor);
        }
        
        // Horizontal grid lines
        for (int y = (int)viewportPos.y; y < viewportPos.y + viewportSize.y; y += spacing) {
            drawList.addLine(viewportPos.x, y, viewportPos.x + viewportSize.x, y, gridColor);
        }
    }
    
    /**
     * Render coordinate axes overlay.
     */
    private void renderAxes(ImDrawList drawList) {
        float centerX = viewportPos.x + 50;
        float centerY = viewportPos.y + viewportSize.y - 50;
        float axisLength = 30;
        
        // X axis (red)
        drawList.addLine(centerX, centerY, centerX + axisLength, centerY, 
                        ImColor.rgba(255, 100, 100, 255), 2.0f);
        drawList.addText(centerX + axisLength + 5, centerY - 5, 
                        ImColor.rgba(255, 100, 100, 255), "X");
        
        // Y axis (green)  
        drawList.addLine(centerX, centerY, centerX, centerY - axisLength,
                        ImColor.rgba(100, 255, 100, 255), 2.0f);
        drawList.addText(centerX - 5, centerY - axisLength - 15,
                        ImColor.rgba(100, 255, 100, 255), "Y");
        
        // Z axis (blue) - diagonal to simulate depth
        drawList.addLine(centerX, centerY, centerX - axisLength * 0.7f, centerY - axisLength * 0.7f,
                        ImColor.rgba(100, 100, 255, 255), 2.0f);
        drawList.addText(centerX - axisLength * 0.7f - 10, centerY - axisLength * 0.7f - 10,
                        ImColor.rgba(100, 100, 255, 255), "Z");
    }
    
    
    /**
     * Render viewport controls window.
     */
    private void renderViewportControls() {
        if (ImGui.begin("Viewport Controls", showViewportControlsRef)) {
            
            ImGui.text("Display Options:");
            
            if (ImGui.checkbox("Show Grid", gridVisible)) {
                toggleGrid();
            }
            
            if (ImGui.checkbox("Show Axes", axesVisible)) {
                toggleAxes();
            }
            
            if (ImGui.checkbox("Wireframe Mode", wireframeModeRef)) {
                wireframeMode = wireframeModeRef.get();
                
                // Update viewport wireframe mode
                if (viewport3D != null) {
                    viewport3D.setWireframeMode(wireframeMode);
                }
                
                // Update render mode combo to match
                if (wireframeMode) {
                    currentRenderModeIndex.set(1); // Wireframe
                } else {
                    currentRenderModeIndex.set(0); // Solid
                }
            }
            
            ImGui.separator();
            
            ImGui.text("View Presets:");
            
            if (ImGui.button("Front View")) {
                setViewPreset("Front");
            }
            ImGui.sameLine();
            
            if (ImGui.button("Side View")) {
                setViewPreset("Side");
            }
            ImGui.sameLine();
            
            if (ImGui.button("Top View")) {
                setViewPreset("Top");
            }
            
            if (ImGui.button("Isometric")) {
                setViewPreset("Isometric");
            }
            ImGui.sameLine();
            
            if (ImGui.button("Reset View")) {
                resetView();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Fit to View")) {
                fitToView();
            }
            
        }
        ImGui.end();
    }
    
    /**
     * Render camera controls window.
     */
    private void renderCameraControls() {
        if (ImGui.begin("Camera Controls", showCameraControlsRef)) {
            
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
        if (ImGui.begin("Rendering Options", showRenderingOptionsRef)) {
            
            ImGui.text("Render Mode:");
            if (ImGui.combo("##render_mode_detailed", currentRenderModeIndex, renderModes)) {
                updateRenderMode();
            }
            
            ImGui.separator();
            
            ImGui.text("Quality Settings:");
            // Placeholder for additional rendering options
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
    
    // Action Methods
    
    private void setupViewport() {
        // logger.info("Setting up 3D viewport...");
        // Don't create a new viewport - it should be injected via setViewport3D()
        if (viewport3D == null) {
            // logger.warn("No viewport3D instance provided - ViewportImGuiInterface requires external viewport injection");
        } else {
            viewportInitialized = true;
            // logger.info("3D viewport initialized successfully using injected instance: {}", System.identityHashCode(viewport3D));
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
            // logger.info("Window handle set on viewport for mouse capture");
        } else {
            logger.warn("Cannot set window handle - viewport not initialized");
        }
    }
    
    
    private void updateViewMode() {
        String viewMode = viewModes[currentViewModeIndex.get()];
        // logger.info("Updating view mode to: {}", viewMode);
        
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
        // logger.info("Updating render mode to: {}", renderMode);
        
        switch (renderMode.toLowerCase()) {
            case "wireframe":
                wireframeMode = true;
                wireframeModeRef.set(true); // Keep ImBoolean in sync
                if (viewport3D != null) {
                    viewport3D.setWireframeMode(true);
                }
                break;
            default:
                wireframeMode = false;
                wireframeModeRef.set(false); // Keep ImBoolean in sync
                if (viewport3D != null) {
                    viewport3D.setWireframeMode(false);
                }
                break;
        }
    }
    
    private void setViewPreset(String preset) {
        logger.info("Setting view preset: {}", preset);
        
        // Update combo box to match
        for (int i = 0; i < viewModes.length; i++) {
            if (viewModes[i].equalsIgnoreCase(preset)) {
                currentViewModeIndex.set(i);
                updateViewMode();
                break;
            }
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
    
    private void zoomIn() {
        viewport3D.getCamera().zoom(1.0f);
        updateCameraStateFromViewport();
    }
    
    private void zoomOut() {
        viewport3D.getCamera().zoom(-1.0f);
        updateCameraStateFromViewport();
    }
    
    private void toggleGrid() {
        gridVisible = !gridVisible;
        logger.info("Grid visibility: {}", gridVisible);
        viewport3D.setShowGrid(gridVisible);
    }
    
    private void toggleAxes() {
        axesVisible = !axesVisible;
        logger.info("Axes visibility: {}", axesVisible);
        // TODO: Add axes visibility to viewport
    }
    
    private void toggleWireframe() {
        wireframeMode = !wireframeMode;
        wireframeModeRef.set(wireframeMode); // Keep ImBoolean in sync
        logger.info("Wireframe mode: {}", wireframeMode);
        
        // Update viewport wireframe mode
        if (viewport3D != null) {
            viewport3D.setWireframeMode(wireframeMode);
        }
        
        // Update render mode combo to match
        if (wireframeMode) {
            currentRenderModeIndex.set(1); // Wireframe
        } else {
            currentRenderModeIndex.set(0); // Solid
        }
    }
    
    private void handleCameraRotation(float deltaX, float deltaY) {
        var camera = viewport3D.getCamera();
        camera.rotate(deltaX * 0.01f, deltaY * 0.01f);
        updateCameraStateFromViewport();
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
    
    /**
     * Render model loading and control interface from the viewport.
     */
    private void renderModelControls() {
        if (viewport3D != null) {
            // Create ImGui window for model controls
            if (ImGui.begin("Model Controls")) {
                // Get current model state
                String currentModelName = viewport3D.getCurrentModelName();
                String currentTextureVariant = viewport3D.getCurrentTextureVariant();
                
                // Model loading section
                ImGui.text("Model Loading:");
                ImGui.separator();
                
                if (currentModelName != null) {
                    ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Model: " + currentModelName);
                    if (currentTextureVariant != null) {
                        ImGui.text("Variant: " + currentTextureVariant);
                    }
                    
                    // Texture variant controls
                    ImGui.separator();
                    ImGui.text("Texture Variants:");
                    
                    String[] variants = {"default", "angus", "highland", "jersey"};
                    for (String variant : variants) {
                        if (ImGui.radioButton(variant, variant.equals(currentTextureVariant))) {
                            viewport3D.setCurrentTextureVariant(variant);
                        }
                    }
                    
                    if (ImGui.button("Unload Model")) {
                        viewport3D.setCurrentModelName(null);
                        viewport3D.setCurrentTextureVariant("default");
                    }
                } else {
                    ImGui.text("No model loaded");
                    
                    ImGui.separator();
                    ImGui.text("Available Models:");
                    
                    // Model loading buttons
                    if (ImGui.button("Load Cow Model")) {
                        viewport3D.loadModel("standard_cow");
                    }
                }
            }
            ImGui.end();
        }
    }
    
    // Getters for external access
    
    public OpenMason3DViewport getViewport3D() {
        return viewport3D;
    }
    
    public boolean isWireframeMode() {
        return wireframeMode;
    }
    
    public boolean isGridVisible() {
        return gridVisible;
    }
    
    public boolean isAxesVisible() {
        return axesVisible;
    }
    
    public boolean isViewportInitialized() {
        return viewportInitialized;
    }
    
    
    /**
     * Update method called every frame.
     */
    public void update(float deltaTime) {
        // Update any animated elements or periodic updates
        // The viewport renders when displayInImGui() is called
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
     * Render transformation controls for individual model part positioning.
     */
    private void renderTransformationControls() {
        if (ImGui.begin("Matrix Transformations", showTransformationControlsRef)) {
            
            // Matrix transformation mode (always enabled)
            ImGui.text("Matrix Transform Mode: ALWAYS ENABLED");
            
            ImGui.separator();
            
            // Model selection
            if (currentModel != null) {
                ImGui.text("Model: " + currentModel.getVariantName());
                ImGui.text("Matrix Mode: ALWAYS ENABLED");
                
                ImGui.separator();
                
                // Model parts info
                ImGui.text("Model Parts:");
                for (StonebreakModel.BodyPart bodyPart : currentModel.getBodyParts()) {
                    ImGui.text("  - " + bodyPart.getName());
                }
                
            } else {
                ImGui.text("No model loaded");
                ImGui.text("Load a cow model to view parts information");
            }
                
        }
        ImGui.end();
    }
    
    
    
    
    /**
     * Set the current model for display.
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        
        if (model != null) {
            logger.info("Set current model for display: " + model.getVariantName());
        }
    }
    
    /**
     * Reset viewport to defaults.
     */
    public void resetToDefaults() {
        currentViewModeIndex.set(0);
        currentRenderModeIndex.set(0);
        cameraDistance.set(5.0f);
        gridVisible = true;
        axesVisible = true;
        syncBooleanStates();
    }
}
