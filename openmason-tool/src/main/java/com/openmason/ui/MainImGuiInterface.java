package com.openmason.ui;

import com.openmason.model.ModelManager;
import com.openmason.ui.components.modelBrowser.ModelBrowserController;
import com.openmason.ui.components.modelBrowser.ModelBrowserImGui;
import com.openmason.ui.components.modelBrowser.events.*;
import com.openmason.ui.config.WindowConfig;
import com.openmason.ui.dialogs.AboutDialog;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.dialogs.PreferencesDialog;
import com.openmason.ui.menus.*;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.properties.PropertyPanelImGui;
import com.openmason.ui.services.*;
import com.openmason.ui.state.*;
import com.openmason.ui.themes.utils.ImGuiHelpers;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.toolbar.ToolbarRenderer;
import com.openmason.ui.viewport.OpenMason3DViewport;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Main ImGui interface - refactored to follow KISS, SOLID, YAGNI, and DRY principles.
 *
 * This class now acts as a lightweight coordinator, delegating responsibilities to specialized modules:
 * - State management → ui.state package
 * - Business logic → ui.services package
 * - Dialog windows → ui.dialogs package
 * - Menu operations → ui.menus package
 * - Toolbar rendering → ui.toolbar package
 * - Model Browser → ui.components.modelBrowser package
 *
 * Reduced from 1700+ lines to ~300 lines by following Single Responsibility Principle.
 * Implements ModelBrowserListener to handle model browser events using Observer pattern.
 */
public class MainImGuiInterface implements ModelBrowserListener {

    private static final Logger logger = LoggerFactory.getLogger(MainImGuiInterface.class);

    // State Objects
    private final ModelState modelState;
    private final ViewportState viewportState;
    private final UIVisibilityState uiVisibilityState;
    private final TransformState transformState;

    // Services
    private final StatusService statusService;
    private final PerformanceService performanceService;
    private final ModelOperationService modelOperations;
    private final ViewportOperationService viewportOperations;
    private final LayoutService layoutService;
    private final FileDialogService fileDialogService;

    // UI Components
    private final ThemeManager themeManager;
    private final PreferencesManager preferencesManager;
    private final LogoManager logoManager;
    private PropertyPanelImGui propertyPanelImGui;
    private ModelBrowserImGui modelBrowserImGui;

    // Dialogs
    private final PreferencesDialog preferencesDialog;
    private final AboutDialog aboutDialog;

    // Menu System
    private final MenuBarCoordinator menuBarCoordinator;
    private FileMenuHandler fileMenuHandler;

    // Toolbar
    private final ToolbarRenderer toolbarRenderer;

    // Viewport
    private OpenMason3DViewport viewport3D;
    private ModelManager modelManager;

    // Window Configurations
    private final WindowConfig viewportConfig = WindowConfig.forViewport();
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
        this.viewportState = new ViewportState();
        this.uiVisibilityState = new UIVisibilityState();
        this.transformState = new TransformState();

        // Initialize services
        this.statusService = new StatusService();
        this.performanceService = new PerformanceService();

        // Initialize managers
        this.preferencesManager = new PreferencesManager();
        this.logoManager = LogoManager.getInstance();
        this.modelManager = new ModelManager();

        // Initialize camera sensitivity from preferences
        float savedSensitivity = preferencesManager.getCameraMouseSensitivity();
        cameraMouseSensitivity.set(savedSensitivity);

        // Initialize operation services
        this.modelOperations = new ModelOperationService(modelState, statusService, modelManager);
        this.viewportOperations = new ViewportOperationService(viewportState, statusService);
        this.layoutService = new LayoutService(uiVisibilityState, viewportState, statusService);
        this.fileDialogService = new FileDialogService(statusService);

        // Initialize dialogs
        this.preferencesDialog = new PreferencesDialog(uiVisibilityState, themeManager,
                preferencesManager, statusService, cameraMouseSensitivity);
        this.aboutDialog = new AboutDialog(uiVisibilityState, logoManager);

        // Initialize menu handlers
        this.fileMenuHandler = new FileMenuHandler(modelState, modelOperations,
                fileDialogService, statusService);
        EditMenuHandler editMenu = new EditMenuHandler(uiVisibilityState, statusService);
        ViewMenuHandler viewMenu = new ViewMenuHandler(uiVisibilityState, viewportState,
                viewportOperations, layoutService);
        ToolsMenuHandler toolsMenu = new ToolsMenuHandler(modelState, transformState,
                modelOperations, viewportOperations);
        ThemeMenuHandler themeMenu = new ThemeMenuHandler(uiVisibilityState, themeManager);
        HelpMenuHandler helpMenu = new HelpMenuHandler(uiVisibilityState);

        this.menuBarCoordinator = new MenuBarCoordinator(uiVisibilityState, logoManager,
                fileMenuHandler, editMenu, viewMenu, toolsMenu, themeMenu, helpMenu);

        // Initialize toolbar
        this.toolbarRenderer = new ToolbarRenderer(uiVisibilityState, modelState, modelOperations,
                viewportOperations, performanceService, statusService, fileDialogService);

        // Initialize components
        initializeComponents();

        // Wire up viewport references after initialization
        fileMenuHandler.setViewport(viewport3D);
        fileMenuHandler.setLogoManager(logoManager);
        fileMenuHandler.setThemeManager(themeManager);
        viewMenu.setViewport(viewport3D);
        toolsMenu.setViewport(viewport3D);
        toolbarRenderer.setViewport(viewport3D);
        preferencesDialog.setViewport(viewport3D);
    }

    /**
     * Initialize UI components.
     */
    private void initializeComponents() {
        try {
            setupViewport();
            setupPropertiesPanel();
            setupModelBrowser();
            performanceService.updateAll(viewport3D);
        } catch (Exception e) {
            logger.error("Failed to initialize components", e);
        }
    }

    /**
     * Setup 3D viewport.
     */
    private void setupViewport() {
        try {
            viewport3D = new OpenMason3DViewport();

            if (viewport3D.getCamera() != null) {
                viewport3D.getCamera().setMouseSensitivity(cameraMouseSensitivity.get());
            }
        } catch (Exception e) {
            logger.error("Failed to setup 3D viewport", e);
        }
    }

    /**
     * Setup properties panel.
     */
    private void setupPropertiesPanel() {
        try {
            propertyPanelImGui = new PropertyPanelImGui(themeManager);

            // Initialize compact mode from preferences
            boolean compactMode = preferencesManager.getPropertiesCompactMode();
            propertyPanelImGui.setCompactMode(compactMode);

            // Wire up to preferences dialog
            if (preferencesDialog != null) {
                preferencesDialog.setPropertyPanel(propertyPanelImGui);
            }
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

            // Create UI component with controller and visibility state
            modelBrowserImGui = new ModelBrowserImGui(controller, uiVisibilityState.getShowModelBrowser());

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

        preferencesDialog.render();
        aboutDialog.render();
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
     * Handle model selection events from the Model Browser.
     * Forwards the event to the viewport for display.
     */
    @Override
    public void onModelSelected(ModelSelectedEvent event) {
        try {
            if (viewport3D != null) {
                viewport3D.loadModel(event.getModelName());
                logger.debug("Model selected via event: {}", event.getModelName());
            }
        } catch (Exception e) {
            logger.error("Failed to handle model selection event", e);
        }
    }

    /**
     * Render property panel.
     */
    private void renderPropertyPanel() {
        if (propertyPanelImGui != null) {
            if (modelState.isModelLoaded() && !modelState.getCurrentModelPath().isEmpty()) {
                String modelName = modelState.getCurrentModelPath().replace(".json", "");
                propertyPanelImGui.loadTextureVariants(modelName);
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
    public void update(float deltaTime) {
        performanceService.updateAll(viewport3D);
    }

    // Public API

    public PropertyPanelImGui getPropertyPanelImGui() {
        return propertyPanelImGui;
    }

    public OpenMason3DViewport getViewport3D() {
        return viewport3D;
    }

    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("modelLoaded", modelState.isModelLoaded());
        metrics.put("currentModelPath", modelState.getCurrentModelPath());
        metrics.put("unsavedChanges", modelState.hasUnsavedChanges());
        metrics.put("memoryUsage", performanceService.getMemoryUsage());
        metrics.put("frameRate", performanceService.getFrameRate());

        // Property panel metrics removed as part of YAGNI cleanup
        // The panel now focuses solely on its core responsibilities

        return metrics;
    }

    public void forceTextureVariantReload(String modelName) {
        if (propertyPanelImGui != null) {
            propertyPanelImGui.loadTextureVariants(modelName);
        }
    }

    // Convenience methods for backward compatibility

    public void createNewModel() {
        modelOperations.newModel();
    }

    public void openModel() {
        modelOperations.openModel();
    }

    public void saveModel() {
        modelOperations.saveModel();
    }

    public void resetView() {
        viewportOperations.resetView(viewport3D);
    }

    public void fitToView() {
        viewportOperations.fitToView();
    }

    public void toggleGrid() {
        viewportOperations.toggleGrid(viewport3D);
    }

    public void toggleWireframe() {
        viewportOperations.toggleWireframe(viewport3D);
    }

    public void switchToVariant(String variantName) {
        viewportOperations.switchTextureVariant(viewport3D, transformState, variantName);
    }

    /**
     * Set callback for returning to welcome screen.
     * This callback will be invoked when the user selects "Back to Welcome Menu" from the File menu.
     */
    public void setBackToWelcomeCallback(Runnable callback) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setBackToWelcomeCallback(callback);
        }
    }
}
