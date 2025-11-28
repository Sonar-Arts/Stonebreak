package com.openmason.ui;

import com.openmason.ui.modelBrowser.ModelBrowserController;
import com.openmason.ui.modelBrowser.ModelBrowserImGui;
import com.openmason.ui.config.WindowConfig;
import com.openmason.ui.modelBrowser.events.BlockSelectedEvent;
import com.openmason.ui.modelBrowser.events.ItemSelectedEvent;
import com.openmason.ui.modelBrowser.events.ModelBrowserListener;
import com.openmason.ui.textureCreator.TextureCreatorImGui;
import com.openmason.ui.dialogs.AboutDialog;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.menus.*;
import com.openmason.ui.preferences.PreferencesWindow;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.properties.PropertyPanelImGui;
import com.openmason.ui.services.*;
import com.openmason.ui.state.*;
import com.openmason.ui.viewport.ViewportUIState;
import com.openmason.ui.themes.utils.ImGuiHelpers;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.toolbar.ModelViewerToolbarRenderer;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main ImGui interface
 */
public class MainImGuiInterface implements ModelBrowserListener {

    private static final Logger logger = LoggerFactory.getLogger(MainImGuiInterface.class);

    // State Objects
    private final ModelState modelState;
    private final UIVisibilityState uiVisibilityState;

    // Services
    private final StatusService statusService;
    private final ModelOperationService modelOperations;
    private final ViewportOperationService viewportOperations;
    private final FileDialogService fileDialogService;

    // UI Components
    private final ThemeManager themeManager;
    private final PreferencesManager preferencesManager;
    private PropertyPanelImGui propertyPanelImGui;
    private ModelBrowserImGui modelBrowserImGui;

    private PreferencesWindow preferencesWindow; // Initialized after components
    private final AboutDialog aboutDialog;

    // Menu System
    private final MenuBarCoordinator menuBarCoordinator;
    private final FileMenuHandler fileMenuHandler;
    private final ToolsMenuHandler toolsMenuHandler;

    // Toolbar
    private final ModelViewerToolbarRenderer toolbarRenderer;

    // Viewport
    private ViewportController viewport3D;

    // Window Configurations
    private final WindowConfig propertiesConfig = WindowConfig.forProperties();

    // Camera settings (shared with PreferencesDialog)
    private final ImFloat cameraMouseSensitivity = new ImFloat(3.0f);

    /**
     * Create MainImGuiInterface with dependency injection.
     */
    public MainImGuiInterface(ThemeManager themeManager) {
        if (themeManager == null) {
            throw new IllegalArgumentException("ThemeManager cannot be null");
        }

        this.themeManager = themeManager;

        // Initialize state objects
        this.modelState = new ModelState();
        ViewportUIState viewportState = new ViewportUIState();
        this.uiVisibilityState = new UIVisibilityState();

        // Initialize services
        this.statusService = new StatusService();

        // Initialize managers
        this.preferencesManager = new PreferencesManager();
        LogoManager logoManager = LogoManager.getInstance();

        // Initialize camera sensitivity from preferences
        float savedSensitivity = preferencesManager.getCameraMouseSensitivity();
        cameraMouseSensitivity.set(savedSensitivity);

        // Initialize file dialog service first (needed by model operations)
        this.fileDialogService = new FileDialogService(statusService);

        // Initialize operation services
        this.modelOperations = new ModelOperationService(modelState, statusService, fileDialogService);
        this.viewportOperations = new ViewportOperationService(viewportState, statusService);
        LayoutService layoutService = new LayoutService(uiVisibilityState, viewportState, statusService);

        // Initialize dialogs
        // Dialogs

        this.aboutDialog = new AboutDialog(uiVisibilityState, logoManager, "Model Viewer");

        // Note: UnifiedPreferencesWindow will be initialized after components (needs viewport and property panel)
        this.preferencesWindow = null; // Initialized in initializeComponents()

        // Initialize menu handlers
        this.fileMenuHandler = new FileMenuHandler(modelState, modelOperations,
                fileDialogService, statusService);
        EditMenuHandler editMenu = new EditMenuHandler(uiVisibilityState, statusService);
        ViewMenuHandler viewMenu = new ViewMenuHandler(uiVisibilityState, viewportState,
                viewportOperations, layoutService);
        this.toolsMenuHandler = new ToolsMenuHandler();
        AboutMenuHandler aboutMenu = new AboutMenuHandler(uiVisibilityState);

        this.menuBarCoordinator = new MenuBarCoordinator(uiVisibilityState, logoManager,
                fileMenuHandler, editMenu, viewMenu, toolsMenuHandler, aboutMenu);

        // Initialize toolbar
        this.toolbarRenderer = new ModelViewerToolbarRenderer(uiVisibilityState, modelState, modelOperations,
                viewportOperations, statusService);

        // Initialize components
        initializeComponents();

        // Wire up viewport references after initialization
        fileMenuHandler.setViewport(viewport3D);
        fileMenuHandler.setLogoManager(logoManager);
        fileMenuHandler.setThemeManager(themeManager);
        viewMenu.setViewport(viewport3D);
        toolbarRenderer.setViewport(viewport3D);
        modelOperations.setPropertiesPanel(propertyPanelImGui);

        // Create default blank model on startup
        createDefaultModel();
    }

    /**
     * Create a default blank model when Open Mason starts.
     * This provides an immediate working surface instead of showing a test cube.
     */
    private void createDefaultModel() {
        try {
            logger.info("Creating default blank model on startup");
            modelOperations.newModel();
        } catch (Exception e) {
            logger.error("Failed to create default blank model", e);
            statusService.updateStatus("Failed to create default model: " + e.getMessage());
        }
    }

    /**
     * Initialize UI components.
     */
    private void initializeComponents() {
        try {
            setupViewport();
            setupPropertiesPanel();
            setupModelBrowser();

            // Initialize unified preferences window after components are created
            // (needs viewport and property panel for real-time updates)
            // Note: TextureCreatorImGui will be set later from OpenMasonApp after it's created
            this.preferencesWindow = new PreferencesWindow(
                    uiVisibilityState.getShowPreferencesWindow(),
                    preferencesManager,
                    themeManager,
                    null, // TextureCreatorImGui set later via setTextureCreatorInterface()
                    viewport3D,
                    propertyPanelImGui
            );
            logger.debug("Unified preferences window initialized (TextureCreatorImGui will be set later)");
        } catch (Exception e) {
            logger.error("Failed to initialize components", e);
        }
    }

    /**
     * Setup 3D viewport.
     */
    private void setupViewport() {
        try {
            viewport3D = new ViewportController();

            if (viewport3D.getCamera() != null) {
                viewport3D.getCamera().setMouseSensitivity(cameraMouseSensitivity.get());
            }

            // Connect viewport to model operations for .OMO model support
            modelOperations.setViewport(viewport3D);
            logger.debug("Viewport connected to ModelOperationService");

        } catch (Exception e) {
            logger.error("Failed to setup 3D viewport", e);
        }
    }

    /**
     * Setup properties panel.
     */
    private void setupPropertiesPanel() {
        try {
            propertyPanelImGui = new PropertyPanelImGui(themeManager, fileDialogService, modelState);
        } catch (Exception e) {
            logger.error("Failed to setup properties panel", e);
        }
    }

    /**
     * Setup model browser component.
     */
    private void setupModelBrowser() {
        try {
            // Create controller with required dependencies
            ModelBrowserController controller = new ModelBrowserController(modelOperations, statusService);

            // Create UI component with controller, visibility state, and new model callback
            modelBrowserImGui = new ModelBrowserImGui(
                controller,
                uiVisibilityState.getShowModelBrowser(),
                this::createNewModel  // Callback for "New Model" button
            );

            // Register this as a listener to receive model browser events
            controller.addListener(this);

            logger.debug("Model Browser initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to setup model browser", e);
        }
    }

    /**
     * Main render method - called every frame.
     */
    public void render() {
        renderDockSpace();
        menuBarCoordinator.render();

        if (uiVisibilityState.getShowModelBrowser().get()) {
            renderModelBrowser();
        }

        if (uiVisibilityState.getShowPropertyPanel().get()) {
            renderPropertyPanel();
        }

        // Note: Unified preferences window is rendered at app level in OpenMasonApp
        // to be accessible from both the Project Hub and the main interface

        aboutDialog.render();

        // Render save warning dialog if open
        if (fileMenuHandler != null && fileMenuHandler.getSaveWarningDialog() != null) {
            fileMenuHandler.getSaveWarningDialog().render();
        }
    }

    /**
     * Render main docking space with integrated toolbar.
     */
    private void renderDockSpace() {
        int windowFlags = ImGuiWindowFlags.NoDocking;

        ImGuiViewport viewport = ImGui.getMainViewport();
        // Note: getWorkPosY() already accounts for the menu bar

        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY());
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY());
        ImGui.setNextWindowViewport(viewport.getID());

        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4.0f, 2.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0.0f, 0.0f);

        windowFlags |= ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove |
                ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;

        ImGui.begin("OpenMason Dockspace", windowFlags);
        ImGui.popStyleVar(4);

        // Render toolbar inline (pushes content down naturally)
        toolbarRenderer.render();

        // Add separator and spacing between toolbar and dockspace
        if (uiVisibilityState.getShowToolbar().get()) {
            ImGui.separator();
            ImGui.spacing();
        }

        // Reset padding for dockspace area
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        int dockspaceId = ImGui.getID("OpenMasonDockSpace");
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);

        ImGui.popStyleVar(1);

        ImGui.end();
    }

    /**
     * Render model browser panel.
     */
    private void renderModelBrowser() {
        if (modelBrowserImGui != null) {
            modelBrowserImGui.render();
        }
    }

    // ===========================
    // ModelBrowserListener Implementation (Observer Pattern)
    // ===========================

    /**
     * Handle block selection events from the Model Browser.
     * Forwards the event to the viewport for display.
     */
    @Override
    public void onBlockSelected(BlockSelectedEvent event) {
        try {
            if (viewport3D != null) {
                viewport3D.setSelectedBlock(event.getBlockType());
                logger.debug("Block selected via event: {}", event.getBlockType());
            }
        } catch (Exception e) {
            logger.error("Failed to handle block selection event", e);
        }
    }

    /**
     * Handle item selection events from the Model Browser.
     * Forwards the event to the viewport for display.
     */
    @Override
    public void onItemSelected(ItemSelectedEvent event) {
        try {
            if (viewport3D != null) {
                viewport3D.setSelectedItem(event.getItemType());
                logger.debug("Item selected via event: {}", event.getItemType());
            }
        } catch (Exception e) {
            logger.error("Failed to handle item selection event", e);
        }
    }

    /**
     * Render property panel.
     */
    private void renderPropertyPanel() {
        if (propertyPanelImGui != null) {
            // Only load texture variants for browser models, not .OMO files (which have embedded textures)
            if (modelState.isModelLoaded() &&
                !modelState.getCurrentModelPath().isEmpty() &&
                modelState.getModelSource() == ModelState.ModelSource.BROWSER) {
                String modelName = modelState.getCurrentModelPath().replace(".json", "");
                propertyPanelImGui.loadTextureVariants();
            }

            propertyPanelImGui.setViewport3D(viewport3D);
            propertyPanelImGui.render();
        } else {
            renderPropertyPanelFallback();
        }
    }

    /**
     * Render property panel fallback if not initialized.
     */
    private void renderPropertyPanelFallback() {
        ImGuiHelpers.configureWindowConstraints(propertiesConfig);

        if (ImGui.begin("Properties", uiVisibilityState.getShowPropertyPanel())) {
            ImGuiHelpers.configureWindowSize(propertiesConfig);
            ImGuiHelpers.configureWindowPosition(propertiesConfig);
            ImGui.textDisabled("Property panel not initialized");
            ImGui.text("Model: " + (modelState.isModelLoaded() ? modelState.getCurrentModelPath() : "No model loaded"));

            if (ImGui.button("Initialize Properties")) {
                setupPropertiesPanel();
            }
        }
        ImGui.end();
    }

    /**
     * Update method called every frame.
     */

    // Public API
    public ViewportController getViewport3D() {
        return viewport3D;
    }

    public ThemeManager getThemeManager() {
        return themeManager;
    }

    // Convenience methods for backward compatibility

    public void createNewModel() {
        modelOperations.newModel();
    }

    public void resetView() {
        viewportOperations.resetView(viewport3D);
    }

    /**
     * Update method called every frame.
     * Updates viewport camera and input handling.
     *
     * @param deltaTime time since last update in seconds
     */
    public void update(float deltaTime) {
        if (viewport3D != null) {
            viewport3D.update(deltaTime);
        }
    }

    /**
     * Set callback for returning to Home screen.
     * This callback will be invoked when the user selects "Home Screen" from the File menu.
     */
    public void setBackToHomeCallback(Runnable callback) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setBackToHomeCallback(callback);
        }
    }

    /**
     * Set callback for opening the texture editor.
     * This callback will be invoked when the user selects "Texture Editor" from the Tools menu.
     */
    public void setOpenTextureEditorCallback(Runnable callback) {
        if (toolsMenuHandler != null) {
            toolsMenuHandler.setOpenTextureEditorCallback(callback);
        }
    }

    /**
     * Gets a callback that shows the unified preferences window.
     */
    public Runnable getShowPreferencesCallback() {
        return () -> {
            if (preferencesWindow != null) {
                preferencesWindow.show();
            } else {
                logger.warn("Unified preferences window not yet initialized");
            }
        };
    }

    /**
     * Gets the unified preferences window for external rendering.
     */
    public PreferencesWindow getUnifiedPreferencesWindow() {
        return preferencesWindow;
    }

    /**
     * Sets the TextureCreatorImGui instance for unified preferences.
     */
    public void setTextureCreatorInterface(TextureCreatorImGui textureCreatorImGui) {
        if (preferencesWindow != null) {
            preferencesWindow.setTextureCreatorImGui(textureCreatorImGui);
            logger.debug("TextureCreatorImGui wired up to unified preferences window");
        } else {
            logger.warn("Cannot set TextureCreatorImGui - unified preferences window not initialized");
        }
    }
}
