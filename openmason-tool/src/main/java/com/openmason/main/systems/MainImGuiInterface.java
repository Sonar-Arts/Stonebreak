package com.openmason.main.systems;

import com.openmason.main.systems.menus.*;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserController;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserImGui;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.AssetEditorDetacher;
import com.openmason.main.systems.menus.preferences.config.WindowConfig;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ProjectBrowserListener;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ModelSelectedEvent;
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
import com.openmason.main.systems.menus.dialogs.DeleteAssetDialog;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.SBEExportWindow;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOExportWindow;
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
import imgui.flag.ImGuiDir;
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

    /**
     * The one and only {@link ViewportUIState}. Owned here, handed to the menu
     * handlers and operation services below, and passed into {@link ViewportController}
     * so the render pipeline reads the same object the UI writes.
     */
    private final ViewportUIState viewportState;

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

    /** The Scene Viewer, once composed; null in headless/snapshot construction. */
    private com.openmason.main.systems.scene.SceneViewerImGuiInterface sceneViewer;
    private com.openmason.main.systems.uiEditor.view.UiEditorWorkspace uiEditor;

    private PreferencesWindow preferencesWindow; // Initialized after components
    private SBOExportWindow sboExportWindow; // Initialized after components
    private SBEExportWindow sbeExportWindow; // Initialized after components
    private SBTExportWindow sbtExportWindow; // Initialized after components
    private SBOEditorWindow sboEditorWindow; // Initialized after components
    private SBEEditorWindow sbeEditorWindow; // Initialized after components
    private final AboutDialog aboutDialog;

    // Menu System
    private final MenuBarCoordinator menuBarCoordinator;
    private final FileMenuHandler fileMenuHandler;
    private final EditMenuHandler editMenuHandler;
    private final ViewMenuHandler viewMenuHandler;
    private final AssetExportMenuActions assetExportActions;

    // Toolbar
    private final ModelEditorToolbarRenderer toolbarRenderer;

    // Project lifecycle (owns the ProjectService, hub new/open flows, session boundaries)
    private final ProjectLifecycle projectLifecycle;

    // Viewport
    private ViewportController viewport3D;

    // Texture editor (set after construction via setTextureCreatorInterface)
    private TextureCreatorImGui textureCreatorImGui;

    // Delete-asset confirmation dialog (shown from the Project Browser's Delete… menu)
    private final DeleteAssetDialog deleteAssetDialog = new DeleteAssetDialog();

    // Editor-detach rules for deleted backing files (stateless; headless-testable)
    private final AssetEditorDetacher assetEditorDetacher = new AssetEditorDetacher();

    // Animation editor (set after construction via setAnimationEditorInterface)
    private com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui animationEditorImGui;

    // Window Configurations
    private final WindowConfig propertiesConfig = WindowConfig.forProperties();

    // Dock layout (dockspace host window, default layout, centre-tab focus)
    private final MainDockLayout dockLayout;

    /** The Scene workspace's contents (registered as a workspace by the core plugin). */
    private final com.openmason.main.systems.layout.SceneWorkspaceDock sceneWorkspaceDock;

    /** Workspace tabs (registered by plugins) and the shared services this shell publishes. */
    private final com.openmason.main.systems.layout.WorkspaceState workspaceState;
    private final com.openmason.main.plugins.EditorRegistries registries;
    private final com.openmason.api.ServiceRegistry services;
    private final com.openmason.main.systems.io.FileTypeService fileTypes;

    // Camera settings loaded from preferences on startup
    private float initialCameraOrbitSpeed;
    private float initialCameraPanSpeed;

    /**
     * Create MainImGuiInterface with dependency injection.
     */
    public MainImGuiInterface(ThemeManager themeManager, com.openmason.main.plugins.EditorRegistries registries,
                              com.openmason.main.systems.layout.WorkspaceState workspaceState,
                              com.openmason.main.systems.layout.WorkspaceLayouts workspaceLayouts) {
        if (themeManager == null) {
            throw new IllegalArgumentException("ThemeManager cannot be null");
        }

        this.themeManager = themeManager;
        this.registries = java.util.Objects.requireNonNull(registries, "registries");
        this.services = registries.services();
        this.fileTypes = new com.openmason.main.systems.io.FileTypeService(registries.fileTypes(),
                registries.fileOpeners());
        this.workspaceState = java.util.Objects.requireNonNull(workspaceState, "workspaceState");

        // Initialize state objects
        this.modelState = new ModelState();
        this.viewportState = new ViewportUIState();
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

        // Initialize project lifecycle (owns the project service)
        this.projectLifecycle = new ProjectLifecycle(new ProjectService(), modelState,
                uiVisibilityState, modelOperations, statusService);
        projectLifecycle.setOnProjectPathChanged(this::refreshProjectBrowserRoot);
        com.openmason.main.systems.layout.CenterTabFocusRequest centerTabFocus =
                new com.openmason.main.systems.layout.CenterTabFocusRequest();
        this.dockLayout = new MainDockLayout(workspaceState, workspaceLayouts, centerTabFocus);
        layoutService.setResetDockLayout(dockLayout::resetLayout);

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
        this.editMenuHandler = editMenu;
        this.viewMenuHandler = viewMenu;
        this.assetExportActions = new AssetExportMenuActions();

        // The menus themselves are contributions (core plugin: File/Edit/View/About; each
        // editor's plugin: its Tools entries).
        this.menuBarCoordinator = new MenuBarCoordinator(uiVisibilityState, registries.menus(),
                registries.menuEntries());
        menuBarCoordinator.setHomeAction(fileMenuHandler::requestHomeScreen);

        // Initialize toolbar
        this.toolbarRenderer = new ModelEditorToolbarRenderer(uiVisibilityState, modelState, modelOperations,
                viewportOperations, statusService);
        this.sceneWorkspaceDock = new com.openmason.main.systems.layout.SceneWorkspaceDock(
                centerTabFocus, toolbarRenderer::render);

        // Initialize components
        initializeComponents();

        // Wire up viewport references after initialization
        fileMenuHandler.setViewport(viewport3D);
        fileMenuHandler.setLogoManager(logoManager);
        fileMenuHandler.setThemeManager(themeManager);
        projectLifecycle.setViewport(viewport3D);
        // Project service, session-boundary and path-changed hooks for File > Open/Save As
        projectLifecycle.bindFileMenu(fileMenuHandler);
        fileMenuHandler.setUIVisibilityState(uiVisibilityState);
        editMenu.setViewport(viewport3D);
        viewMenu.setViewport(viewport3D);
        toolbarRenderer.setViewport(viewport3D);
        modelOperations.setPropertiesPanel(propertyPanelImGui);

        publishServices();

        // Create default blank model on startup
        createDefaultModel();
    }

    /**
     * Publishes this shell's services for plugins and the MCP services (#379); late-wired ones
     * are published by their setters.
     */
    private void publishServices() {
        services.put(com.openmason.main.systems.layout.WorkspaceState.class, workspaceState);
        services.put(com.openmason.main.systems.io.FileTypeService.class, fileTypes);
        services.put(ThemeManager.class, themeManager);
        services.put(PreferencesManager.class, preferencesManager);
        services.put(ModelState.class, modelState);
        services.put(UIVisibilityState.class, uiVisibilityState);
        services.put(ViewportUIState.class, viewportState);
        services.put(StatusService.class, statusService);
        services.put(ModelOperationService.class, modelOperations);
        services.put(ViewportOperationService.class, viewportOperations);
        services.put(FileDialogService.class, fileDialogService);
        services.put(com.openmason.main.systems.services.ProjectDirectory.class, getProjectDirectorySupplier()::get);
        services.put(ProjectService.class, projectLifecycle.getProjectService());
        services.put(FileMenuHandler.class, fileMenuHandler);
        services.put(EditMenuHandler.class, editMenuHandler);
        services.put(ViewMenuHandler.class, viewMenuHandler);
        services.put(AssetExportMenuActions.class, assetExportActions);
        services.put(com.openmason.main.systems.layout.SceneWorkspaceDock.class, sceneWorkspaceDock);
        services.put(com.openmason.main.systems.keybinds.KeybindRegistry.class,
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance());
        services.put(ViewportController.class, viewport3D);
        services.put(PropertyPanelImGui.class, propertyPanelImGui);
        services.put(RiggingPaneImGui.class, riggingPaneImGui);
        services.put(PreferencesWindow.class, preferencesWindow);
        services.put(SBOExportWindow.class, sboExportWindow);
        services.put(SBEExportWindow.class, sbeExportWindow);
        services.put(SBTExportWindow.class, sbtExportWindow);
        services.put(SBOEditorWindow.class, sboEditorWindow);
        services.put(SBEEditorWindow.class, sbeEditorWindow);
        services.put(MainImGuiInterface.class, this);
    }

    /** The registries and shared services the plugins see. */
    public com.openmason.api.ServiceRegistry getServices() {
        return services;
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
                    registries.preferencePages(),
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
            assetExportActions.setSBOExportWindow(sboExportWindow);

            // Initialize SBE export window
            this.sbeExportWindow = new SBEExportWindow(
                    uiVisibilityState.getShowSBEExportWindow(),
                    themeManager,
                    modelState,
                    statusService,
                    fileDialogService
            );
            assetExportActions.setSBEExportWindow(sbeExportWindow);

            // Initialize SBT export window — OMT path supplier is wired later
            // when the TextureCreatorImGui becomes available.
            this.sbtExportWindow = new SBTExportWindow(
                    uiVisibilityState.getShowSBTExportWindow(),
                    themeManager,
                    statusService,
                    fileDialogService
            );

            // SBO editor — for opening and editing existing .sbo files
            this.sboEditorWindow = new SBOEditorWindow(fileDialogService, statusService);
            assetExportActions.setSBOEditorWindow(sboEditorWindow);
            sboEditorWindow.setOpenModelHandler((omoBytes, name) -> {
                if (modelOperations != null) {
                    modelOperations.openExtractedAssetModel(omoBytes, name, name);
                }
            });

            // SBE editor — for opening and editing existing .sbe files
            this.sbeEditorWindow = new SBEEditorWindow(fileDialogService, statusService);
            assetExportActions.setSBEEditorWindow(sbeEditorWindow);
            sbeEditorWindow.setOpenModelHandler((omoBytes, name) -> {
                if (modelOperations != null) {
                    modelOperations.openExtractedAssetModel(omoBytes, name, name);
                }
            });

            // Export windows act as "start screens": a successful export opens
            // the new file straight in the matching editor.
            sboExportWindow.setOnExported(sboEditorWindow::openFile);
            sbeExportWindow.setOnExported(sbeEditorWindow::openFile);

            assetExportActions.setModelState(modelState);
            assetExportActions.setStatusService(statusService);
            assetExportActions.setModelOperations(modelOperations);
            logger.debug("SBO, SBE, SBT export windows and SBO/SBE editors initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize components", e);
        }
    }

    /**
     * Setup 3D viewport.
     */
    private void setupViewport() {
        try {
            // The viewport shares this shell's ViewportUIState — the same object the
            // View menu and the operation services already hold. Giving the viewport
            // its own copy is what used to make menu toggles (e.g. Show Bones) write
            // to a state the render pipeline never reads.
            viewport3D = new ViewportController(viewportState);

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
                    new ProjectBrowserController(projectLifecycle.getProjectService(), modelOperations, statusService);

            // Create UI component with controller, visibility state, and new model callback
            projectBrowserImGui = new ProjectBrowserImGui(
                controller,
                uiVisibilityState.getShowModelBrowser(),
                this::createNewModel  // Callback for "New Model" button
            );

            // Listed types and click-to-open come from the registered file types (#379)
            controller.setFileTypes(fileTypes);

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
        dockLayout.render();
        dockLayout.renderDetached();
        menuBarCoordinator.render();

        if (!isWorkspaceShown(com.openmason.main.systems.layout.BuiltinWorkspaces.MODELING)) {
            // Scene is neither the main window's front tab nor a visible window of its own: its
            // panels stay docked but hidden (the UI and Texture workspaces draw their own).
            aboutDialog.render();
            dockLayout.tickCenterTabFocus();
            if (fileMenuHandler != null && fileMenuHandler.getHomeScreenDialog() != null) {
                fileMenuHandler.getHomeScreenDialog().render();
            }
            com.openmason.main.systems.keybinds.KeybindRegistry.getInstance().dispatchGlobal();
            return;
        }

        if (uiVisibilityState.getShowModelBrowser().get()) {
            renderProjectBrowser();
        }

        if (uiVisibilityState.getShowPropertyPanel().get()) {
            renderPropertyPanel();
        }

        if (uiVisibilityState.getShowRiggingPane().get()) {
            renderRiggingPane();
        }

        if (assistantPane != null) {
            assistantPane.render(uiVisibilityState.getShowAssistantPane());
        }

        // Note: Unified preferences window is rendered at app level in OpenMasonApp
        // to be accessible from both the Project Hub and the main interface

        aboutDialog.render();

        // Runs after every window has been submitted, which is what makes focusing a
        // freshly docked tab actually take effect.
        dockLayout.tickCenterTabFocus();

        // Render home screen dialog if open
        if (fileMenuHandler != null && fileMenuHandler.getHomeScreenDialog() != null) {
            fileMenuHandler.getHomeScreenDialog().render();
        }
        // Plugin commands in the global context (#379) fire wherever focus is.
        com.openmason.main.systems.keybinds.KeybindRegistry.getInstance().dispatchGlobal();
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

    /** Delete was chosen in the browser: show the confirmation dialog first. */
    @Override
    public void onAssetDeleteRequested(AssetEntry entry) {
        deleteAssetDialog.show(entry, this::onAssetDeleteConfirmed);
    }

    /**
     * User confirmed the delete: remove the file, then detach the editor from
     * the deleted backing file so the next Save behaves like Save As.
     */
    public void onAssetDeleteConfirmed(AssetEntry entry) {
        if (projectBrowserImGui == null) {
            logger.warn("Project Browser not initialized; cannot delete {}", entry.name());
            return;
        }
        boolean deleted = projectBrowserImGui.getController().deleteAsset(entry);
        assetEditorDetacher.afterDelete(entry, deleted, getModelState(),
                textureCreatorImGui != null ? textureCreatorImGui.getController() : null);
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

        if (ImGui.begin(PropertyPanelImGui.WINDOW_TITLE, uiVisibilityState.getShowPropertyPanel())) {
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
        return projectLifecycle.getProjectDirectorySupplier();
    }

    /** The shared UI visibility flags, needed by the Scene Viewer's windows. */
    /** Forwards the File menu's Scene group to the app-supplied handlers. */
    public void setSceneActions(Runnable newScene, Runnable openScene,
                                Runnable saveScene, Runnable saveSceneAs,
                                java.util.function.BooleanSupplier sceneDirty) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setSceneActions(newScene, openScene, saveScene, saveSceneAs, sceneDirty);
        }
    }

    /**
     * Wire the project file's scene node to the scene layer: {@code saveSupplier} is read
     * on every project save (which scene is open + which centre tab is in front), and
     * {@code restoreHook} runs after a project opens (null reference = pre-1.2 file).
     */
    public void setSceneSessionHooks(
            java.util.function.Supplier<com.openmason.main.systems.project.OMPFormat.SceneReference> saveSupplier,
            java.util.function.Consumer<com.openmason.main.systems.project.OMPFormat.SceneReference> restoreHook) {
        projectLifecycle.setSceneSessionHooks(saveSupplier, restoreHook);
    }

    /** Forwards "save the open scene alongside the project" to the app-supplied handler. */
    public void setSaveOpenSceneAction(Runnable action) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setOnSaveOpenScene(action);
        }
    }

    /** Forwards live camera-preference changes to the Scene Viewer. */
    public void setSceneCameraPreferenceSink(java.util.function.BiConsumer<Float, Float> sink) {
        if (preferencesWindow != null) {
            preferencesWindow.setSceneCameraSink(sink);
        }
    }

    public UIVisibilityState getUIVisibilityState() {
        return uiVisibilityState;
    }

    /**
     * Bring a centre-dock tab to the front (Scene Viewer or 3D Viewport).
     * Deferred by a few frames, because focusing a window ImGui has not submitted yet
     * silently does nothing.
     */
    public void requestCenterTab(String windowTitle) {
        dockLayout.requestCenterTab(windowTitle);
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

    /** Approval gate for agent-initiated working-set changes (null until UI wiring). */
    private com.openmason.main.systems.mcp.approval.ApprovalGate approvalGate;

    public void setApprovalGate(com.openmason.main.systems.mcp.approval.ApprovalGate gate) {
        this.approvalGate = gate;
        services.put(com.openmason.main.systems.mcp.approval.ApprovalGate.class, gate);
    }

    public com.openmason.main.systems.mcp.approval.ApprovalGate getApprovalGate() {
        return approvalGate;
    }

    /** Save Sheet gate for agent-initiated file writes (null until UI wiring). */
    private com.openmason.main.systems.mcp.approval.SaveSheetGate saveSheetGate;

    public void setSaveSheetGate(com.openmason.main.systems.mcp.approval.SaveSheetGate gate) {
        this.saveSheetGate = gate;
        services.put(com.openmason.main.systems.mcp.approval.SaveSheetGate.class, gate);
    }

    public com.openmason.main.systems.mcp.approval.SaveSheetGate getSaveSheetGate() {
        return saveSheetGate;
    }

    /** Model/session state (loaded flag, dirty flag, source). */
    public com.openmason.main.systems.stateHandling.ModelState getModelState() {
        return modelState;
    }

    /** The Project Browser's delete-asset confirmation dialog. */
    public DeleteAssetDialog getDeleteAssetDialog() {
        return deleteAssetDialog;
    }

    public ModelOperationService getModelOperations() {
        return modelOperations;
    }

    /**
     * Registers the model editor's Ctrl+O / Ctrl+S shortcuts. Call once, after
     * the viewport actions are registered and before custom keybinds load.
     * Save mirrors File &gt; Save Model: a no-op when nothing needs saving.
     */
    public void registerModelFileKeybinds(com.openmason.main.systems.keybinds.KeybindRegistry registry) {
        com.openmason.main.systems.viewport.ViewportKeybindActions.registerFileActions(
                registry,
                modelOperations::openModel,
                () -> {
                    if (modelState.canSaveModel() && modelState.hasUnsavedChanges()) {
                        modelOperations.saveModel();
                    }
                });
    }

    public void setSceneViewer(com.openmason.main.systems.scene.SceneViewerImGuiInterface sceneViewer) {
        this.sceneViewer = sceneViewer;
        services.put(com.openmason.main.systems.scene.SceneViewerImGuiInterface.class, sceneViewer);
    }

    /** The Scene Viewer (scene service, document, actions), or null before composition. */
    public com.openmason.main.systems.scene.SceneViewerImGuiInterface getSceneViewer() {
        return sceneViewer;
    }

    public void setUiEditor(com.openmason.main.systems.uiEditor.view.UiEditorWorkspace uiEditor) {
        this.uiEditor = uiEditor;
        services.put(com.openmason.main.systems.uiEditor.view.UiEditorWorkspace.class, uiEditor);
    }

    /** The UI Editor workspace (#293; open UI documents, their runtimes), or null before composition. */
    public com.openmason.main.systems.uiEditor.view.UiEditorWorkspace getUiEditor() {
        return uiEditor;
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
    public SBTExportWindow getSBTExportWindow() {
        return sbtExportWindow;
    }

    /**
     * Get the texture creator interface, or null if not yet wired.
     */
    public TextureCreatorImGui getTextureCreator() {
        return textureCreatorImGui;
    }

    /** Assistant chat pane (installed by the app shell once the harness exists). */
    private com.openmason.main.systems.assistant.ui.AssistantPaneImGui assistantPane;

    public void setAssistantPane(com.openmason.main.systems.assistant.ui.AssistantPaneImGui pane) {
        this.assistantPane = pane;
    }

    private com.openmason.main.systems.services.ScriptingPresenter scriptingPresenter;
    private com.openmason.main.systems.services.TextureEditorPresenter textureEditorPresenter;

    public void setScriptingPresenter(com.openmason.main.systems.services.ScriptingPresenter presenter) {
        this.scriptingPresenter = presenter;
        services.put(com.openmason.main.systems.services.ScriptingPresenter.class, presenter);
    }

    public com.openmason.main.systems.services.ScriptingPresenter getScriptingPresenter() {
        return scriptingPresenter;
    }

    /** Install the shell's texture editor window control. */
    public void setTextureEditorPresenter(com.openmason.main.systems.services.TextureEditorPresenter presenter) {
        this.textureEditorPresenter = presenter;
        services.put(com.openmason.main.systems.services.TextureEditorPresenter.class, presenter);
    }

    /** @return the texture editor window control, or null before the shell wires it */
    public com.openmason.main.systems.services.TextureEditorPresenter getTextureEditorPresenter() {
        return textureEditorPresenter;
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
        services.put(com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui.class, animationEditorImGui);
    }

    /**
     * Sets the TextureCreatorImGui instance for unified preferences.
     */
    public void setTextureCreatorInterface(TextureCreatorImGui textureCreatorImGui) {
        this.textureCreatorImGui = textureCreatorImGui;
        services.put(TextureCreatorImGui.class, textureCreatorImGui);
        if (preferencesWindow != null) {
            preferencesWindow.setTextureCreatorImGui(textureCreatorImGui);
            logger.debug("TextureCreatorImGui wired up to unified preferences window");
        } else {
            logger.warn("Cannot set TextureCreatorImGui - unified preferences window not initialized");
        }

        // Wire the SBT/SBO export OMT-path source — only project (.OMT) files are
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
        if (sboExportWindow != null) {
            sboExportWindow.setOMTPathSupplier(omtPathSupplier);
        }
        if (textureCreatorImGui != null && sbtExportWindow != null) {
            textureCreatorImGui.setSBTExportTrigger(sbtExportWindow::show, omtPathSupplier);
        }
        if (textureCreatorImGui != null && sboExportWindow != null) {
            // Same export UI as the model viewer, in item/texture context.
            textureCreatorImGui.setSBOExportTrigger(sboExportWindow::showForTexture);
        }
        if (textureCreatorImGui != null && sboEditorWindow != null) {
            textureCreatorImGui.setSBOEditorTrigger(sboEditorWindow::openWithDialog);
        }
    }

    /**
     * Set the recent projects service for tracking project open/save in the hub.
     */
    public void setRecentProjectsService(RecentProjectsService recentProjectsService) {
        projectLifecycle.setRecentProjectsService(recentProjectsService);
    }

    /**
     * Set callback for application exit. Wires the unsaved changes dialog.
     *
     * @param exitCallback called to perform the actual application exit
     */
    public void setExitCallback(Runnable exitCallback) {
        projectLifecycle.setExitCallback(exitCallback);
    }

    /**
     * Request application exit through the file menu handler.
     * Shows the unsaved changes dialog if there are unsaved changes.
     */
    public void requestExit() {
        projectLifecycle.requestExit();
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
    /** The UI workspace's save hooks: Save Project / exit prompts cover open UI documents too. */
    public void setUiMenuHooks(com.openmason.main.systems.menus.FileMenuHandler.UiMenuHooks hooks) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setUiHooks(hooks);
        }
    }

    /**
     * Whether workspace {@code ws}'s panels should draw this frame: it is the main window's front tab
     * or a popped-out window showing its contents. Valid once this frame's {@link #render()} ran.
     */
    public boolean isWorkspaceShown(String ws) {
        return dockLayout.isShown(ws);
    }

    /** The Scene workspace's dock (registered by the core plugin). */
    public com.openmason.main.systems.layout.SceneWorkspaceDock getSceneWorkspaceDock() {
        return sceneWorkspaceDock;
    }

    public com.openmason.main.systems.layout.WorkspaceState getWorkspaceState() {
        return workspaceState;
    }

    /** The project service's UI Editor session seams (v1.3 node). */
    public void setUiEditorSessionHooks(
            java.util.function.Supplier<com.openmason.main.systems.project.OMPFormat.UiEditorReference> supplier,
            java.util.function.Consumer<com.openmason.main.systems.project.OMPFormat.UiEditorReference> hook) {
        projectLifecycle.setUiEditorSessionHooks(supplier, hook);
    }

    /** The UI Editor's open documents or workspace changed: the project may need saving. */
    public void notifyUiEditorSessionChanged() {
        if (projectLifecycle.getProjectService() != null) {
            projectLifecycle.getProjectService().uiSessionChanged();
        }
    }

    public void setOnProjectSessionReset(Runnable callback) {
        projectLifecycle.setOnProjectSessionReset(callback);
    }

    public void resetEditorState() {
        if (viewport3D != null) {
            viewport3D.resetCamera();
            viewport3D.resetModelTransform();
        }

        uiVisibilityState.resetToDefault();
        createDefaultModel();

        projectLifecycle.clearForNewSession();

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
    public boolean saveNewProject(String projectName, String ompFilePath, String targetId) {
        return projectLifecycle.saveNewProject(projectName, ompFilePath, targetId);
    }

    public void openProjectFromHub(String ompFilePath) {
        projectLifecycle.openProjectFromHub(ompFilePath);
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
        if (assistantPane != null) {
            assistantPane.dispose(); // Skija regions must close before SkijaContext
        }
        if (projectBrowserImGui != null) {
            projectBrowserImGui.cleanup();
        }
        if (propertyPanelImGui != null) {
            propertyPanelImGui.dispose();
        }
        if (sboEditorWindow != null) {
            sboEditorWindow.close();
        }
        if (sbeEditorWindow != null) {
            sbeEditorWindow.close();
        }
        if (sboExportWindow != null) {
            sboExportWindow.close();
        }
        if (sbeExportWindow != null) {
            sbeExportWindow.close();
        }
        if (sbtExportWindow != null) {
            sbtExportWindow.close();
        }
        if (preferencesWindow != null) {
            preferencesWindow.close();
        }
        // Audio audition engine (OpenAL, lazily started by the Sounds tabs).
        com.openmason.main.systems.menus.dialogs.SoundPreviewService.shutdown();
    }
}
