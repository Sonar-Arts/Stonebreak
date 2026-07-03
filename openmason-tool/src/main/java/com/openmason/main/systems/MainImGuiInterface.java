package com.openmason.main.systems;

import com.openmason.main.systems.menus.*;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserController;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserImGui;
import com.openmason.main.systems.menus.preferences.config.WindowConfig;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ProjectBrowserListener;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ModelSelectedEvent;
import com.openmason.main.systems.menus.panes.projectBrowser.events.TextureSelectedEvent;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.LayoutService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.services.ViewportOperationService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.dialogs.AboutDialog;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.SBEExportWindow;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOExportWindow;
import com.openmason.main.systems.menus.dialogs.SBOTextureExportWindow;
import com.openmason.main.systems.menus.dialogs.SBTExportWindow;
import com.openmason.main.systems.menus.preferences.PreferencesWindow;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.menus.panes.riggingPane.RiggingPaneImGui;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.themes.utils.ImGuiHelpers;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.menus.toolbars.ModelEditorToolbarRenderer;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main ImGui interface
 */
public class MainImGuiInterface implements ProjectBrowserListener {

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
    private RiggingPaneImGui riggingPaneImGui;
    private ProjectBrowserImGui projectBrowserImGui;

    /** Invoked with a .OMT file path to open it in the texture editor (wired in mainOpenMason). */
    private java.util.function.Consumer<java.nio.file.Path> openTextureInEditorCallback;

    private PreferencesWindow preferencesWindow; // Initialized after components
    private SBOExportWindow sboExportWindow; // Initialized after components
    private SBEExportWindow sbeExportWindow; // Initialized after components
    private SBTExportWindow sbtExportWindow; // Initialized after components
    private SBOTextureExportWindow sboTextureExportWindow; // Initialized after components
    private SBOEditorWindow sboEditorWindow; // Initialized after components
    private SBEEditorWindow sbeEditorWindow; // Initialized after components
    private final AboutDialog aboutDialog;

    // Menu System
    private final MenuBarCoordinator menuBarCoordinator;
    private final FileMenuHandler fileMenuHandler;
    private final ToolsMenuHandler toolsMenuHandler;

    // Toolbar
    private final ModelEditorToolbarRenderer toolbarRenderer;

    // Project
    private ProjectService projectService;

    // Viewport
    private ViewportController viewport3D;

    // Texture editor (set after construction via setTextureCreatorInterface)
    private TextureCreatorImGui textureCreatorImGui;

    // Animation editor (set after construction via setAnimationEditorInterface)
    private com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui animationEditorImGui;

    // Window Configurations
    private final WindowConfig propertiesConfig = WindowConfig.forProperties();

    // Layout initialization tracking
    private boolean defaultLayoutBuilt = false;

    // Camera settings loaded from preferences on startup
    private float initialCameraOrbitSpeed;
    private float initialCameraPanSpeed;

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

        // Load camera settings from preferences for initial viewport setup
        initialCameraOrbitSpeed = preferencesManager.getCameraMouseSensitivity();
        initialCameraPanSpeed = preferencesManager.getCameraPanSensitivity();

        // Initialize file dialog service first (needed by model operations)
        this.fileDialogService = new FileDialogService(statusService);

        // Initialize operation services
        this.modelOperations = new ModelOperationService(modelState, statusService, fileDialogService);
        this.viewportOperations = new ViewportOperationService(viewportState, statusService);
        LayoutService layoutService = new LayoutService(uiVisibilityState, viewportState, statusService);

        // Initialize project service
        this.projectService = new ProjectService();

        // Model-save dialogs start in the open project's folder (.OMP location)
        fileDialogService.setProjectDirectorySupplier(getProjectDirectorySupplier());

        // Initialize dialogs
        this.aboutDialog = new AboutDialog(uiVisibilityState, logoManager, "Model Editor");

        // Note: UnifiedPreferencesWindow will be initialized after components (needs viewport and property panel)
        this.preferencesWindow = null; // Initialized in initializeComponents()

        // Initialize menu handlers
        this.fileMenuHandler = new FileMenuHandler(modelState, modelOperations,
                fileDialogService, statusService);
        EditMenuHandler editMenu = new EditMenuHandler(uiVisibilityState);
        ViewMenuHandler viewMenu = new ViewMenuHandler(uiVisibilityState, viewportState,
                viewportOperations, layoutService);
        this.toolsMenuHandler = new ToolsMenuHandler();
        AboutMenuHandler aboutMenu = new AboutMenuHandler(uiVisibilityState);

        this.menuBarCoordinator = new MenuBarCoordinator(uiVisibilityState, logoManager,
                fileMenuHandler, editMenu, viewMenu, toolsMenuHandler, aboutMenu);

        // Initialize toolbar
        this.toolbarRenderer = new ModelEditorToolbarRenderer(uiVisibilityState, modelState, modelOperations,
                viewportOperations, statusService);

        // Initialize components
        initializeComponents();

        // Wire up viewport references after initialization
        fileMenuHandler.setViewport(viewport3D);
        fileMenuHandler.setLogoManager(logoManager);
        fileMenuHandler.setThemeManager(themeManager);
        fileMenuHandler.setProjectService(projectService);
        fileMenuHandler.setUIVisibilityState(uiVisibilityState);
        fileMenuHandler.setOnProjectPathChanged(this::refreshProjectBrowserRoot);
        editMenu.setViewport(viewport3D);
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
            setupRiggingPane();
            setupProjectBrowser();

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

            // Initialize SBO export window
            this.sboExportWindow = new SBOExportWindow(
                    uiVisibilityState.getShowSBOExportWindow(),
                    themeManager,
                    modelState,
                    statusService,
                    fileDialogService
            );
            toolsMenuHandler.setSBOExportWindow(sboExportWindow);

            // Initialize SBE export window
            this.sbeExportWindow = new SBEExportWindow(
                    uiVisibilityState.getShowSBEExportWindow(),
                    themeManager,
                    modelState,
                    statusService,
                    fileDialogService
            );
            toolsMenuHandler.setSBEExportWindow(sbeExportWindow);

            // Initialize SBT export window — OMT path supplier is wired later
            // when the TextureCreatorImGui becomes available.
            this.sbtExportWindow = new SBTExportWindow(
                    uiVisibilityState.getShowSBTExportWindow(),
                    themeManager,
                    statusService,
                    fileDialogService
            );

            // Texture-only SBO export (sprite items). Same OMT-path lifecycle
            // as the SBT window; wired in setTextureCreatorImGui().
            this.sboTextureExportWindow = new SBOTextureExportWindow(
                    uiVisibilityState.getShowSBOTextureExportWindow(),
                    themeManager,
                    statusService,
                    fileDialogService
            );

            // SBO editor — for opening and editing existing .sbo files
            this.sboEditorWindow = new SBOEditorWindow(fileDialogService, statusService);
            toolsMenuHandler.setSBOEditorWindow(sboEditorWindow);

            // SBE editor — for opening and editing existing .sbe files
            this.sbeEditorWindow = new SBEEditorWindow(fileDialogService, statusService);
            toolsMenuHandler.setSBEEditorWindow(sbeEditorWindow);

            toolsMenuHandler.setModelState(modelState);
            toolsMenuHandler.setStatusService(statusService);
            toolsMenuHandler.setModelOperations(modelOperations);
            logger.debug("SBO, SBE, SBT, SBO-texture export windows and SBO/SBE editors initialized");
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
                viewport3D.getCamera().setMouseSensitivity(initialCameraOrbitSpeed);
                viewport3D.getCamera().setPanSensitivity(initialCameraPanSpeed);
            }

            // Connect viewport to model operations for .OMO model support
            modelOperations.setViewport(viewport3D);
            logger.debug("Viewport connected to ModelOperationService");

            // Mark model as having unsaved changes whenever the command history changes
            viewport3D.getCommandHistory().setOnHistoryChange(() -> modelState.setUnsavedChanges(true));

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
     * Setup rigging pane (parts + bones tabs).
     */
    private void setupRiggingPane() {
        try {
            riggingPaneImGui = new RiggingPaneImGui();
            riggingPaneImGui.setFileDialogService(fileDialogService);
            riggingPaneImGui.setViewport(viewport3D);
        } catch (Exception e) {
            logger.error("Failed to setup rigging pane", e);
        }
    }

    /**
     * Setup project browser component (browses the open project's folder).
     */
    private void setupProjectBrowser() {
        try {
            // Create controller with required dependencies
            ProjectBrowserController controller =
                    new ProjectBrowserController(projectService, modelOperations, statusService);

            // Create UI component with controller, visibility state, and new model callback
            projectBrowserImGui = new ProjectBrowserImGui(
                controller,
                uiVisibilityState.getShowModelBrowser(),
                this::createNewModel  // Callback for "New Model" button
            );

            // Register this as a listener to receive project browser events
            controller.addListener(this);

            logger.debug("Project Browser initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to setup project browser", e);
        }
    }

    /**
     * Main render method - called every frame.
     */
    public void render() {
        renderDockSpace();
        menuBarCoordinator.render();

        if (uiVisibilityState.getShowModelBrowser().get()) {
            renderProjectBrowser();
        }

        if (uiVisibilityState.getShowPropertyPanel().get()) {
            renderPropertyPanel();
        }

        if (uiVisibilityState.getShowRiggingPane().get()) {
            renderRiggingPane();
        }

        // Note: Unified preferences window is rendered at app level in OpenMasonApp
        // to be accessible from both the Project Hub and the main interface

        aboutDialog.render();

        // Render home screen dialog if open
        if (fileMenuHandler != null && fileMenuHandler.getHomeScreenDialog() != null) {
            fileMenuHandler.getHomeScreenDialog().render();
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
        // Bottom border is drawn by the toolbar itself
        toolbarRenderer.render();

        // Reset padding for dockspace area
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        int dockspaceId = ImGui.getID("OpenMasonDockSpace");
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);

        // Build default layout on first frame if no saved layout exists
        if (!defaultLayoutBuilt) {
            defaultLayoutBuilt = true;
            buildDefaultLayout(dockspaceId);
        }

        ImGui.popStyleVar(1);

        ImGui.end();
    }

    /**
     * Build the default docking layout programmatically using DockBuilder.
     * This creates the standard layout: Properties (left), 3D Viewport (center), Model Browser (bottom).
     * Only applies when no saved imgui.ini layout exists for this dockspace.
     */
    private void buildDefaultLayout(int dockspaceId) {
        var node = imgui.internal.ImGui.dockBuilderGetNode(dockspaceId);
        boolean hasSavedLayout = node != null && node.isSplitNode();

        com.openmason.main.omConfig config = new com.openmason.main.omConfig();
        if (hasSavedLayout && config.isProjectBrowserLayoutMigrated()) {
            // Dockspace already has a saved layout from imgui.ini — don't override
            return;
        }

        if (hasSavedLayout) {
            // Saved layout predates the Model Browser → Project Browser rename,
            // so it has no dock slot for the new window. Rebuild the default
            // layout once so the browser lands in its bottom grid position.
            logger.info("Migrating saved layout: rebuilding default so Project Browser docks at the bottom");
        } else {
            logger.info("No saved layout found — building default docking layout");
        }
        config.setProjectBrowserLayoutMigrated(true);
        config.saveConfiguration();

        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, ImGuiDockNodeFlags.PassthruCentralNode);

        ImGuiViewport viewport = ImGui.getMainViewport();
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, viewport.getWorkSizeX(), viewport.getWorkSizeY());

        // Split bottom for Model Browser (~37% of height)
        ImInt dockTop = new ImInt();
        ImInt dockBottom = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Down, 0.37f, dockBottom, dockTop);

        // Split left for Model Properties panel (~20% of remaining width)
        ImInt dockLeft = new ImInt();
        ImInt dockCenter = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockTop.get(), ImGuiDir.Left, 0.20f, dockLeft, dockCenter);

        // Dock windows into their respective nodes
        imgui.internal.ImGui.dockBuilderDockWindow("Model Properties", dockLeft.get());
        imgui.internal.ImGui.dockBuilderDockWindow(RiggingPaneImGui.WINDOW_TITLE, dockLeft.get());
        imgui.internal.ImGui.dockBuilderDockWindow("3D Viewport", dockCenter.get());
        imgui.internal.ImGui.dockBuilderDockWindow(ProjectBrowserImGui.WINDOW_TITLE, dockBottom.get());

        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);

        logger.info("Default docking layout built successfully");
    }

    /**
     * Render project browser panel.
     */
    private void renderProjectBrowser() {
        if (projectBrowserImGui != null) {
            projectBrowserImGui.render();
        }
    }

    // ===========================
    // ProjectBrowserListener Implementation (Observer Pattern)
    // ===========================

    /** ModelOperationService.loadOMOModel handles the actual viewport load; this hook is for logging/UI sync. */
    @Override
    public void onModelSelected(ModelSelectedEvent event) {
        logger.debug(".OMO selected: {}", event.entry().name());
    }

    /** Clicking a .OMT opens it in the texture editor for editing. */
    @Override
    public void onTextureSelected(TextureSelectedEvent event) {
        try {
            if (openTextureInEditorCallback != null) {
                openTextureInEditorCallback.accept(event.entry().path());
            } else {
                logger.warn("No texture-editor open callback wired; cannot open {}", event.entry().name());
            }
        } catch (Exception e) {
            logger.error("Failed to handle OMT selection event", e);
        }
    }

    /**
     * Render property panel.
     */
    private void renderPropertyPanel() {
        if (propertyPanelImGui != null) {
            propertyPanelImGui.setViewport3D(viewport3D);
            propertyPanelImGui.render();
        } else {
            renderPropertyPanelFallback();
        }
    }

    /**
     * Render the rigging pane (parts + bones).
     */
    private void renderRiggingPane() {
        if (riggingPaneImGui != null) {
            riggingPaneImGui.render();
        }
    }

    /**
     * Render property panel fallback if not initialized.
     */
    private void renderPropertyPanelFallback() {
        ImGuiHelpers.configureWindowConstraints(propertiesConfig);

        if (ImGui.begin("Model Properties", uiVisibilityState.getShowPropertyPanel())) {
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

    public FileDialogService getFileDialogService() {
        return fileDialogService;
    }

    /**
     * Supplier of the open project's root folder (the directory the .OMP lives in),
     * or null when no project is loaded. Shared with other save dialogs (e.g. the
     * texture editor) so their file dialogs start in the project root too.
     */
    public java.util.function.Supplier<String> getProjectDirectorySupplier() {
        return () -> {
            if (projectService == null || !projectService.hasCurrentProject()) {
                return null;
            }
            String ompPath = projectService.getCurrentProjectPath();
            if (ompPath == null || ompPath.isBlank()) {
                return null;
            }
            java.nio.file.Path parent = java.nio.file.Path.of(ompPath).getParent();
            return parent != null ? parent.toString() : null;
        };
    }

    public PropertyPanelImGui getPropertyPanel() {
        return propertyPanelImGui;
    }

    public RiggingPaneImGui getRiggingPane() {
        return riggingPaneImGui;
    }

    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public ModelOperationService getModelOperations() {
        return modelOperations;
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
     * Set the callback that opens a specific .OMT file in the texture editor.
     * Invoked when a .OMT is clicked in the project browser.
     */
    public void setOpenTextureInEditorCallback(java.util.function.Consumer<java.nio.file.Path> callback) {
        this.openTextureInEditorCallback = callback;
    }

    /**
     * Set callback for opening the animation editor.
     */
    public void setOpenAnimationEditorCallback(Runnable callback) {
        if (toolsMenuHandler != null) {
            toolsMenuHandler.setOpenAnimationEditorCallback(callback);
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
     * Gets the SBO export window for external rendering.
     */
    public SBOExportWindow getSBOExportWindow() {
        return sboExportWindow;
    }

    /**
     * Gets the SBE export window for external rendering.
     */
    public SBEExportWindow getSBEExportWindow() {
        return sbeExportWindow;
    }

    /**
     * Gets the SBO editor window for external rendering.
     */
    public SBOEditorWindow getSBOEditorWindow() {
        return sboEditorWindow;
    }

    /**
     * Gets the SBE editor window for external rendering.
     */
    public SBEEditorWindow getSBEEditorWindow() {
        return sbeEditorWindow;
    }

    /**
     * Gets the SBT export window for external rendering.
     */
    public SBOTextureExportWindow getSBOTextureExportWindow() {
        return sboTextureExportWindow;
    }

    public SBTExportWindow getSBTExportWindow() {
        return sbtExportWindow;
    }

    /**
     * Get the texture creator interface, or null if not yet wired.
     */
    public TextureCreatorImGui getTextureCreator() {
        return textureCreatorImGui;
    }

    /**
     * Get the animation editor interface, or null if not yet wired.
     */
    public com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui getAnimationEditor() {
        return animationEditorImGui;
    }

    /**
     * Sets the AnimationEditorImGui instance so external systems (e.g. the MCP
     * server) can query and drive the animation editor.
     */
    public void setAnimationEditorInterface(
            com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui animationEditorImGui) {
        this.animationEditorImGui = animationEditorImGui;
    }

    /**
     * Sets the TextureCreatorImGui instance for unified preferences.
     */
    public void setTextureCreatorInterface(TextureCreatorImGui textureCreatorImGui) {
        this.textureCreatorImGui = textureCreatorImGui;
        if (preferencesWindow != null) {
            preferencesWindow.setTextureCreatorImGui(textureCreatorImGui);
            logger.debug("TextureCreatorImGui wired up to unified preferences window");
        } else {
            logger.warn("Cannot set TextureCreatorImGui - unified preferences window not initialized");
        }

        // Wire the SBT export OMT-path source — only project (.OMT) files are
        // valid sources, never arbitrary PNG-backed sessions.
        java.util.function.Supplier<String> omtPathSupplier = () -> {
            if (textureCreatorImGui == null) return null;
            var state = textureCreatorImGui.getState();
            if (state == null || !state.isProjectFile() || !state.hasFilePath()) {
                return null;
            }
            return state.getCurrentFilePath();
        };
        if (sbtExportWindow != null) {
            sbtExportWindow.setOMTPathSupplier(omtPathSupplier);
        }
        if (sboTextureExportWindow != null) {
            sboTextureExportWindow.setOMTPathSupplier(omtPathSupplier);
        }
        if (textureCreatorImGui != null && sbtExportWindow != null) {
            textureCreatorImGui.setSBTExportTrigger(sbtExportWindow::show, omtPathSupplier);
        }
        if (textureCreatorImGui != null && sboTextureExportWindow != null) {
            textureCreatorImGui.setSBOExportTrigger(sboTextureExportWindow::show);
        }
        if (textureCreatorImGui != null && sboEditorWindow != null) {
            textureCreatorImGui.setSBOEditorTrigger(sboEditorWindow::openWithDialog);
        }
    }

    /**
     * Set the recent projects service for tracking project open/save in the hub.
     */
    public void setRecentProjectsService(RecentProjectsService recentProjectsService) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setRecentProjectsService(recentProjectsService);
        }
    }

    /**
     * Set callback for application exit. Wires the unsaved changes dialog.
     *
     * @param exitCallback called to perform the actual application exit
     */
    public void setExitCallback(Runnable exitCallback) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setExitCallback(exitCallback);
        }
    }

    /**
     * Request application exit through the file menu handler.
     * Shows the unsaved changes dialog if there are unsaved changes.
     */
    public void requestExit() {
        if (fileMenuHandler != null) {
            fileMenuHandler.requestExit();
        }
    }

    /**
     * Get the file menu handler for dialog rendering access.
     */
    public FileMenuHandler getFileMenuHandler() {
        return fileMenuHandler;
    }

    /**
     * Reset all editor state to defaults for a fresh session.
     * Called when creating a new blank project from the hub.
     */
    public void resetEditorState() {
        if (viewport3D != null) {
            viewport3D.resetCamera();
            viewport3D.resetModelTransform();
        }

        uiVisibilityState.resetToDefault();
        createDefaultModel();

        if (projectService != null) {
            projectService.clearCurrentProject();
        }
        refreshProjectBrowserRoot();

        logger.info("Editor state reset to defaults for new project");
    }

    /**
     * Open a project from the Project Hub by loading an .OMP file.
     * Called when the user selects a recent project from the hub.
     *
     * @param ompFilePath the path to the .OMP project file
     */
    /**
     * Create a blank project and pre-save it to {@code ompFilePath} so the
     * file exists on disk the moment the editor opens. Assumes the editor was
     * just reset to a fresh blank session.
     *
     * @return true if the project file was written
     */
    public boolean saveNewProject(String projectName, String ompFilePath) {
        if (projectService == null || viewport3D == null) {
            logger.warn("Cannot create project: service or viewport not initialized");
            return false;
        }
        boolean success = projectService.saveProjectAs(ompFilePath, viewport3D, modelState,
                uiVisibilityState, projectName);
        if (success) {
            statusService.updateStatus("Project created: " + projectName);
            logger.info("New project pre-saved: {}", ompFilePath);
        } else {
            statusService.updateStatus("Failed to create project: " + ompFilePath);
        }
        refreshProjectBrowserRoot();
        return success;
    }

    public void openProjectFromHub(String ompFilePath) {
        if (projectService == null || viewport3D == null) {
            logger.warn("Cannot open project: service or viewport not initialized");
            return;
        }

        boolean success = projectService.openProject(ompFilePath, viewport3D, modelState,
                uiVisibilityState, modelOperations);
        if (success) {
            statusService.updateStatus("Project opened: " + projectService.getCurrentProjectName());
            logger.info("Project loaded from hub: {}", ompFilePath);
        } else {
            statusService.updateStatus("Failed to open project: " + ompFilePath);
        }
        refreshProjectBrowserRoot();
    }

    /** Re-root the project browser after the current project path changed. */
    private void refreshProjectBrowserRoot() {
        if (projectBrowserImGui != null) {
            projectBrowserImGui.getController().refreshRoot();
        }
    }

    /**
     * Release GPU-backed resources (project browser thumbnails, property panel
     * Skija regions). Must run with a current GL context, before the
     * SkijaContext is closed.
     */
    public void dispose() {
        if (projectBrowserImGui != null) {
            projectBrowserImGui.cleanup();
        }
        if (propertyPanelImGui != null) {
            propertyPanelImGui.dispose();
        }
    }
}
