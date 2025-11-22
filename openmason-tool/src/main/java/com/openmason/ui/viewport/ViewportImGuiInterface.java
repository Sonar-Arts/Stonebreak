package com.openmason.ui.viewport;

import com.openmason.ui.ViewportController;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.viewport.views.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the 3D viewport interface.
 * Follows MVC architecture - this is the Controller.
 * Follows SOLID principles:
 * - Single Responsibility: Coordinates between views, state, and actions
 * - Open/Closed: Can extend with new views without modifying controller
 * - Liskov Substitution: N/A (no inheritance)
 * - Interface Segregation: Uses focused interfaces for dependencies
 * - Dependency Inversion: Depends on abstractions via constructor injection
 */
public class ViewportImGuiInterface {

    private static final Logger logger = LoggerFactory.getLogger(ViewportImGuiInterface.class);

    // MVC Components
    private final ViewportState state;
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

    /**
     * Constructor with dependency injection.
     * @param preferencesManager The preferences manager for persistence
     */
    public ViewportImGuiInterface(PreferencesManager preferencesManager) {
        // Initialize state
        this.state = new ViewportState();
        this.preferencesManager = preferencesManager;

        // Actions will be initialized after viewport is set
        this.actions = null;
        this.keyboardShortcuts = null;

        // Views will be initialized after viewport is set
        this.mainView = null;
        this.cameraControlsView = null;
        this.renderingOptionsView = null;
        this.transformControlsView = null;
    }

    /**
     * Internal constructor used after viewport is injected.
     */
    private ViewportImGuiInterface(ViewportState state, ViewportController viewport, PreferencesManager preferencesManager) {
        this.state = state;
        this.viewport3D = viewport;
        this.preferencesManager = preferencesManager;

        // Initialize all components
        initializeComponents();
    }

    /**
     * Factory method to create fully initialized controller.
     */
    public static ViewportImGuiInterface create(ViewportController viewport, PreferencesManager preferencesManager) {
        ViewportState state = new ViewportState();
        return new ViewportImGuiInterface(state, viewport, preferencesManager);
    }

    public ViewportImGuiInterface() {
        // Backward compatibility constructor
        this.state = new ViewportState();
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
     * Initialize the viewport interface and set up 3D viewport.
     * @deprecated Use factory method create() instead
     */
    @Deprecated
    public void initialize() {
        if (viewport3D != null) {
            state.setViewportInitialized(true);
        }
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
     * @deprecated Use factory method create() instead
     */
    @Deprecated
    public void setViewport3D(ViewportController viewport) {
        this.viewport3D = viewport;
        logger.info("Shared 3D viewport injected into ViewportImGuiInterface: {}",
                   viewport != null ? System.identityHashCode(viewport) : "NULL");

        // Initialize components now that viewport is available (backward compatibility)
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

    /**
     * Toggle wireframe mode (public method for external access).
     */
    public void toggleWireframe() {
        if (actions != null) {
            actions.toggleWireframe();
        }
    }

    // ========== Getters for external access ==========

    public ViewportController getViewport3D() {
        return viewport3D;
    }

    public boolean isWireframeMode() {
        return state.getWireframeMode().get();
    }

    public boolean isGridVisible() {
        return state.getGridVisible().get();
    }

    public boolean isAxesVisible() {
        return state.getAxesVisible().get();
    }

    public boolean isViewportInitialized() {
        return state.isViewportInitialized();
    }

    public ViewportState getState() {
        return state;
    }

    // ========== Lifecycle methods ==========

    /**
     * Update method called every frame.
     */
    public void update(float deltaTime) {
        // Update any animated elements or periodic updates
        // Currently no frame-based updates needed
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

    /**
     * Reset viewport to defaults.
     */
    public void resetToDefaults() {
        state.resetToDefaults();
    }
}
