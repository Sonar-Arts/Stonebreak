package com.openmason.ui.viewport;

import com.openmason.ui.ViewportController;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.viewport.views.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the 3D viewport (MVC).
 * Adheres to SOLID: Single Responsibility, Open/Closed, Interface Segregation, Dependency Inversion.
 */
public class ViewportImGuiInterface {

    private static final Logger logger = LoggerFactory.getLogger(ViewportImGuiInterface.class);

    // MVC Components
    private final ViewportUIState state;
    private ViewportActions actions;
    private ViewportKeyboardShortcuts keyboardShortcuts;

    // View Components
    private ViewportMainView mainView;
    private CameraControlsView cameraControlsView;
    private RenderingOptionsView renderingOptionsView;
    private TransformControlsView transformControlsView;

    // 3D Viewport reference
    private ViewportController viewport3D;

    // Preferences manager for backward compatibility
    private PreferencesManager preferencesManager;

    public ViewportImGuiInterface() {
        // Backward compatibility constructor
        this.state = new ViewportUIState();
        this.preferencesManager = new PreferencesManager(); // Create default
        this.actions = null;
        this.keyboardShortcuts = null;
        this.mainView = null;
        this.cameraControlsView = null;
        this.renderingOptionsView = null;
        this.transformControlsView = null;
    }

    /**
     * Initialize all MVC components after viewport is set.
     */
    private void initializeComponents() {
        if (viewport3D == null) {
            logger.warn("Cannot initialize components - viewport3D is null");
            return;
        }

        // Initialize actions with dependencies
        this.actions = new ViewportActions(viewport3D, state, preferencesManager);

        // Initialize keyboard shortcuts
        this.keyboardShortcuts = new ViewportKeyboardShortcuts(actions, state);

        // Initialize view components
        this.mainView = new ViewportMainView(state, actions, viewport3D);
        this.cameraControlsView = new CameraControlsView(state, actions);
        this.renderingOptionsView = new RenderingOptionsView(state, actions);
        this.transformControlsView = new TransformControlsView(state, actions, viewport3D);

        state.setViewportInitialized(true);
        logger.info("ViewportImGuiInterface components initialized");
    }

    /**
     * Main render method - coordinates all view rendering.
     * This is the controller's main responsibility.
     */
    public void render() {
        try {
            // Check if fully initialized
            if (!isFullyInitialized()) {
                logger.warn("ViewportImGuiInterface not fully initialized - skipping render");
                return;
            }

            // Handle keyboard shortcuts before rendering (global shortcuts)
            if (ViewportKeyboardShortcuts.shouldProcessShortcuts()) {
                keyboardShortcuts.handleKeyboardShortcuts();
            }

            // Delegate rendering to view components
            mainView.render();

            if (state.getShowCameraControls().get()) {
                cameraControlsView.render();
            }

            if (state.getShowRenderingOptions().get()) {
                renderingOptionsView.render();
            }

            if (state.getShowTransformationControls().get()) {
                transformControlsView.render();
            }

        } catch (Exception e) {
            logger.error("Critical error during viewport interface rendering", e);
            throw new RuntimeException("Viewport rendering failed", e);
        }
    }

    /**
     * Check if the controller is fully initialized.
     */
    private boolean isFullyInitialized() {
        return viewport3D != null &&
               actions != null &&
               keyboardShortcuts != null &&
               mainView != null;
    }

    /**
     * Set the shared 3D viewport instance.
     */
    public void setViewport3D(ViewportController viewport) {
        this.viewport3D = viewport;
        logger.info("Shared 3D viewport injected into ViewportImGuiInterface: {}",
                   viewport != null ? System.identityHashCode(viewport) : "NULL");

        // Initialize components now that viewport is available
        initializeComponents();
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     */
    public void setWindowHandle(long windowHandle) {
        if (viewport3D != null) {
            viewport3D.setWindowHandle(windowHandle);
        } else {
            logger.warn("Cannot set window handle - viewport not initialized");
        }
    }

    // ========== Lifecycle methods ==========

    /**
     * Update method called every frame.
     * Currently no frame-based updates needed - keyboard shortcuts and rendering
     * are handled in the render() method.
     */
    public void update(float deltaTime) {
        // Reserved for future animated elements or periodic updates
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
        state.setViewportInitialized(false);
    }
}
