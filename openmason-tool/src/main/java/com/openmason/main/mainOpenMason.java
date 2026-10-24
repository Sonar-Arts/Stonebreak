package com.openmason.main;

import com.openmason.main.bootstrap.UiComposition;
import com.openmason.main.platform.AppWindow;
import com.openmason.main.platform.GlfwPlatform;
import com.openmason.main.platform.ImGuiBackend;
import com.openmason.main.platform.WindowsTaskbarIdentity;
import com.openmason.main.systems.mcp.McpServerBootstrap;
import com.openmason.main.systems.threading.MainThreadExecutor;
import imgui.ImGui;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.viewport.ViewportImGuiInterface;
import com.openmason.main.systems.menus.mainHub.ProjectHubScreen;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.project.TemplateScaffolder;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TexturePreviewPipeline;
import com.openmason.main.systems.menus.textureCreator.TextureEditorWorkspace;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaTestPanel;
import com.openmason.main.systems.uiPreview.MasonryPreviewPanel;
import com.openmason.main.systems.uiPreview.UiDocumentPreviewPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main ImGui application for OpenMason tool.
 * Thin lifecycle orchestrator: init → main loop → shutdown sequencing. Windowing and
 * platform policy live in {@code com.openmason.main.platform}; the UI object graph is
 * built by {@link UiComposition}.
 */
public class mainOpenMason {

    private static final Logger logger = LoggerFactory.getLogger(mainOpenMason.class);

    // Core components
    private final GlfwPlatform glfwPlatform = new GlfwPlatform();
    private AppWindow appWindow;
    private final ImGuiBackend imGuiBackend = new ImGuiBackend();
    private omConfig omConfig;
    private omLifecycle omLifecycle;

    // UI components
    private ThemeManager themeManager;
    private ProjectHubScreen projectHubScreen;
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    /** Second 3D surface: shares the centre dock node with the model editor's viewport. */
    private com.openmason.main.systems.scene.SceneViewerImGuiInterface sceneViewerInterface;

    /** Which centre tab is in front, recorded into the project file on save. */
    private final com.openmason.main.systems.layout.CenterTabTracker centerTabTracker =
            new com.openmason.main.systems.layout.CenterTabTracker();
    private TextureCreatorImGui textureCreatorInterface;
    private TextureEditorWorkspace textureEditorWorkspace;
    private com.openmason.main.systems.menus.scriptingWindow.ScriptingWindow scriptingWindow;
    private com.openmason.main.systems.assistant.AssistantController assistantController;
    private AnimationEditorImGui animationEditor;
    private TexturePreviewPipeline texturePreviewPipeline;
    private final McpServerBootstrap mcpServer = new McpServerBootstrap();
    /** Every contribution list + the shared services; filled by the plugins (#379). */
    private final com.openmason.main.plugins.EditorRegistries registries =
            new com.openmason.main.plugins.EditorRegistries();
    private final com.openmason.main.plugins.PluginManager pluginManager =
            new com.openmason.main.plugins.PluginManager(registries);
    private final com.openmason.main.systems.mcp.approval.McpApprovalGate approvalGate =
            new com.openmason.main.systems.mcp.approval.McpApprovalGate();
    private com.openmason.main.systems.menus.dialogs.ApprovalDialog approvalDialog;
    private final com.openmason.main.systems.mcp.approval.SaveSheetGate saveSheetGate =
            new com.openmason.main.systems.mcp.approval.SaveSheetGate();
    private com.openmason.main.systems.menus.dialogs.SaveSheetDialog saveSheetDialog;
    private SkijaContext skijaContext;
    private SkijaTestPanel skijaTestPanel;
    private MasonryPreviewPanel masonryPreviewPanel;
    private UiDocumentPreviewPanel uiDocumentPreviewPanel;
    /** The UI Editor workspace (#293) and which workspace is in front. */
    private com.openmason.main.systems.uiEditor.view.UiEditorWorkspace uiEditor;
    private com.openmason.main.systems.layout.WorkspaceState workspaceState;
    private com.openmason.engine.ui.script.UiNativeHealth.Status uiNativeStatus;

    // State flags
    private boolean showHomeScreen = true;
    private boolean showModelEditor = false;
    private boolean showTextureEditor = false;
    private boolean shouldClose = false;
    private boolean cleanedUp = false;

    /**
     * Initialize and run the application.
     */
    public void run() {
        try {
            MainThreadExecutor.bindToCurrentThread();
            WindowsTaskbarIdentity.apply();
            omConfig = new omConfig();
            omLifecycle = new omLifecycle();

            glfwPlatform.initialize();
            appWindow = new AppWindow(omConfig, this::requestApplicationExit);
            appWindow.create();
            imGuiBackend.initialize(window());
            initializeSkija();
            initializeUI();

            omLifecycle.setListeners(registries.lifecycle()::all);
            omLifecycle.onApplicationStarted();
            com.openmason.main.systems.mcp.ToolCapabilities toolCapabilities = buildToolCapabilities();
            // The MCP surface is what the active plugins contributed.
            mcpServer.start(new com.openmason.main.systems.services.EditorServices(registries.services()),
                    toolCapabilities, new com.openmason.main.systems.mcp.McpSurface(registries.mcpTools().all(),
                            registries.guideTopics().all(), registries.knowledgePacks().all()));
            pluginManager.started();
            if (assistantController != null) {
                assistantController.setLibalexAvailable(toolCapabilities.libalexAvailable());
            }
            runMainLoop();

        } catch (Exception e) {
            logger.error("Failed to start OpenMason application", e);
            System.exit(1);
        } finally {
            cleanup();
        }
    }

    /** Native window handle, or {@code NULL} before creation / after destruction. */
    private long window() {
        return appWindow != null ? appWindow.handle() : NULL;
    }

    /**
     * Initialize the shared Skija DirectContext for high-quality 2D widget
     * rendering. Non-fatal on failure — Skija-backed widgets fall back to
     * ImGui draw-list rendering when no context is available.
     */
    private void initializeSkija() {
        // UI scripting and layout have no fallback (#292): hand-shake at launch, errors logged.
        uiNativeStatus = com.openmason.engine.ui.script.UiNativeHealth.check();
        try {
            skijaContext = SkijaContext.initialize();
            if (SkijaTestPanel.ENABLED) {
                skijaTestPanel = new SkijaTestPanel();
                logger.info("Skija test panel enabled (-Dopenmason.skija.test=true)");
            }
            if (MasonryPreviewPanel.ENABLED) {
                masonryPreviewPanel = new MasonryPreviewPanel();
                logger.info("Masonry preview panel enabled (-Dopenmason.masonry.preview=true)");
            }
            if (UiDocumentPreviewPanel.ENABLED) {
                uiDocumentPreviewPanel = new UiDocumentPreviewPanel();
                logger.info("UI document preview enabled (-D{})", UiDocumentPreviewPanel.PROPERTY);
            }
        } catch (Throwable t) {
            logger.error("Skija initialization failed — Skija widgets will fall back to ImGui", t);
            skijaContext = null;
        }
    }

    /**
     * Main render loop.
     */
    private void runMainLoop() {
        long window = window();
        AutoScreenshot shot = AutoScreenshot.fromProperty();
        int frame = 0;
        while (!shouldClose && !glfwWindowShouldClose(window)) {
            glfwPollEvents();
            MainThreadExecutor.drain();
            if (++frame == 3) {
                devOpenUiEditor();
                devOpenTextureEditor();
            }
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            imGuiBackend.beginFrame();

            renderUI();

            imGuiBackend.endFrame();

            imGuiBackend.handleMultiViewport();

            if (shot != null && shot.beforeSwap(window, imGuiBackend::captureSecondaryViewports)) {
                shouldClose = true;
            }
            glfwSwapBuffers(window);
        }
    }

    /**
     * Build the UI object graph and adopt its components. Components are adopted even
     * when composition fails part-way so {@link #cleanup()} can dispose what was created.
     */
    private void initializeUI() {
        UiComposition composition = new UiComposition(omConfig, window(), centerTabTracker,
                new UiComposition.Host() {
                    @Override
                    public void createNewProjectFile(String name, String directory, ProjectTemplate template,
                                                     String targetId) {
                        mainOpenMason.this.createNewProjectFile(name, directory, template, targetId);
                    }

                    @Override
                    public void openRecentProject(RecentProject project) {
                        mainOpenMason.this.openRecentProject(project);
                    }

                    @Override
                    public void restoreSceneSession(com.openmason.main.systems.project.OMPFormat.SceneReference ref) {
                        mainOpenMason.this.restoreSceneSession(ref);
                    }

                    @Override
                    public void transitionToHomeScreen() {
                        mainOpenMason.this.transitionToHomeScreen();
                    }

                    @Override
                    public void requestExit() {
                        shouldClose = true;
                    }

                    @Override
                    public void showTextureEditor() {
                        openTextureEditor();
                    }
                }, registries);
        try {
            composition.compose();
        } finally {
            themeManager = composition.themeManager();
            projectHubScreen = composition.projectHubScreen();
            mainInterface = composition.mainInterface();
            viewportInterface = composition.viewportInterface();
            sceneViewerInterface = composition.sceneViewerInterface();
            textureCreatorInterface = composition.textureCreatorInterface();
            textureEditorWorkspace = composition.textureEditorWorkspace();
            scriptingWindow = composition.scriptingWindow();
            animationEditor = composition.animationEditor();
            texturePreviewPipeline = composition.texturePreviewPipeline();
            uiEditor = composition.uiEditor();
            workspaceState = composition.workspaceState();
        }
        if (mainInterface != null) {
            com.fasterxml.jackson.databind.ObjectMapper assistantMapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            assistantController = new com.openmason.main.systems.assistant.AssistantController(
                    () -> mcpServer.registry(),
                    com.openmason.main.systems.menus.preferences.AssistantPreferences::read,
                    () -> mainInterface.getModelState() != null
                            ? mainInterface.getModelState().getCurrentModelPath() : null,
                    assistantMapper);
            mainInterface.setAssistantPane(
                    new com.openmason.main.systems.assistant.ui.AssistantPaneImGui(assistantController));
        }
        if (mainInterface != null) {
            mainInterface.setApprovalGate(approvalGate);
            approvalDialog = new com.openmason.main.systems.menus.dialogs.ApprovalDialog(
                    approvalGate,
                    () -> {
                        var ops = mainInterface.getModelOperations();
                        if (ops != null) {
                            ops.saveModel();
                        }
                    },
                    () -> mainInterface.getModelState() != null
                            && mainInterface.getModelState().hasUnsavedChanges());
            // Agent-initiated file writes pick their target here, never in an OS dialog.
            mainInterface.setSaveSheetGate(saveSheetGate);
            saveSheetDialog = new com.openmason.main.systems.menus.dialogs.SaveSheetDialog(
                    saveSheetGate,
                    new com.openmason.main.systems.io.WriteSandbox(
                            com.openmason.main.systems.io.WriteRoots.forInterface(
                            new com.openmason.main.systems.services.EditorServices(registries.services()))));
            // Any registered kind (a plugin's too) gets the native save dialog for its file type.
            saveSheetDialog.setNativePicker((kind, suggestedName, preferredDir, onPicked) -> {
                var dialogs = mainInterface.getFileDialogService();
                if (dialogs != null) {
                    dialogs.showSaveDialog(kind.fileType(), suggestedName, preferredDir, onPicked::accept);
                }
            });
        }
        if (mainInterface != null && scriptingWindow != null) {
            mainInterface.setScriptingPresenter(new com.openmason.main.systems.services.ScriptingPresenter() {
                @Override
                public void show() {
                    scriptingWindow.show();
                }

                @Override
                public void showWithScript(String scriptName) {
                    scriptingWindow.showWithScript(scriptName);
                }

                @Override
                public void close() {
                    scriptingWindow.hide();
                }

                @Override
                public boolean isVisible() {
                    return scriptingWindow.isVisible();
                }
            });
        }
        if (mainInterface != null && textureEditorWorkspace != null) {
            mainInterface.setTextureEditorPresenter(new com.openmason.main.systems.services.TextureEditorPresenter() {
                @Override
                public void show() {
                    openTextureEditor();
                }

                @Override
                public boolean isVisible() {
                    return showTextureEditor && workspaceState != null
                            && workspaceState.isOpen(com.openmason.main.systems.layout.BuiltinWorkspaces.TEXTURE);
                }

                @Override
                public void flush() {
                    if (texturePreviewPipeline != null) {
                        texturePreviewPipeline.flush();
                    }
                }
            });
        }
        registerPlugins();
    }

    /**
     * Registers the editor's core features, then the built-in plugins and every plugin found on the
     * module path (#379), then loads
     * the user's custom keybinds (after every plugin registered its commands, so none is dropped).
     */
    private void registerPlugins() {
        if (mainInterface == null) {
            logger.error("UI composition failed; plugins not registered");
            return;
        }
        registries.services().put(com.openmason.main.plugins.PluginManager.class, pluginManager);
        registries.services().put(com.openmason.main.plugins.EditorRegistries.class, registries);
        var keybinds = com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();
        registries.forwardCommandsTo(keybinds);
        if (mainInterface.getViewport3D() != null) {
            mainInterface.getViewport3D().setContentProviders(registries.viewportContent()::all);
        }
        // The editor's own features first, through the same registrars; a failure here stops start-up.
        com.openmason.main.bootstrap.CoreFeatures.register(registries);
        pluginManager.registerAll(com.openmason.main.plugins.builtin.BuiltinPlugins.all(),
                pluginManager.discover());
        new com.openmason.main.systems.menus.preferences.PreferencesManager().loadKeybindsIntoRegistry(keybinds);
    }

    /**
     * Render UI components based on visibility flags.
     */
    private void renderUI() {
        float deltaTime = ImGui.getIO().getDeltaTime();

        // Agent approval modal — independent of which screen is showing.
        if (approvalDialog != null) {
            approvalDialog.render();
        }
        if (saveSheetDialog != null) {
            saveSheetDialog.render();
        }

        // Scripting window — standalone like the texture editor.
        if (scriptingWindow != null) {
            scriptingWindow.render();
        }

        if (showHomeScreen) {
            renderComponent(projectHubScreen, deltaTime, "Project Hub");
        }

        if (showModelEditor) {
            renderComponent(mainInterface, deltaTime, "Main Interface");
        }
        // Each workspace's panels draw while it is the main window's front tab or a visible
        // popped-out window (the main interface has just submitted the dockspaces they dock into);
        // a dock may also tick while hidden.
        if (workspaceState != null) {
            for (var ws : workspaceState.workspaces()) {
                boolean shown = showModelEditor && mainInterface != null && mainInterface.isWorkspaceShown(ws.id());
                safeRender(() -> ws.dock().renderPanels(deltaTime, shown), ws.label());
            }
            // Closing the Texture tab runs the editor's tear-down.
            if (showTextureEditor && !workspaceState.isOpen(com.openmason.main.systems.layout.BuiltinWorkspaces.TEXTURE)) {
                onTextureEditorClosed();
            }
        }

        if (animationEditor != null && animationEditor.isVisible()) {
            safeRender(() -> animationEditor.render(deltaTime), "Animation Editor");
        }

        if (mainInterface != null && mainInterface.getUnifiedPreferencesWindow() != null) {
            safeRender(() -> mainInterface.getUnifiedPreferencesWindow().render(), "Preferences Window");
        }

        if (mainInterface != null && mainInterface.getSBOExportWindow() != null) {
            safeRender(() -> mainInterface.getSBOExportWindow().render(), "SBO Export Window");
        }

        if (mainInterface != null && mainInterface.getSBEExportWindow() != null) {
            safeRender(() -> mainInterface.getSBEExportWindow().render(), "SBE Export Window");
        }

        if (mainInterface != null && mainInterface.getSBTExportWindow() != null) {
            safeRender(() -> mainInterface.getSBTExportWindow().render(), "SBT Export Window");
        }

        if (mainInterface != null && mainInterface.getSBOEditorWindow() != null) {
            safeRender(() -> mainInterface.getSBOEditorWindow().render(), "SBO Editor Window");
        }

        if (mainInterface != null && mainInterface.getSBEEditorWindow() != null) {
            safeRender(() -> mainInterface.getSBEEditorWindow().render(), "SBE Editor Window");
        }

        // Render unsaved changes dialog (must be rendered outside other windows for modal to work)
        if (mainInterface != null && mainInterface.getFileMenuHandler() != null) {
            safeRender(() -> mainInterface.getFileMenuHandler().getUnsavedChangesDialog().render(),
                    "Unsaved Changes Dialog");
        }

        // Render delete-asset confirmation dialog (must be rendered outside other windows for modal to work)
        if (mainInterface != null && mainInterface.getDeleteAssetDialog() != null) {
            safeRender(() -> mainInterface.getDeleteAssetDialog().render(), "Delete Asset Dialog");
        }

        if (skijaTestPanel != null) {
            safeRender(() -> skijaTestPanel.render(), "Skija Test Panel");
        }
        if (masonryPreviewPanel != null) {
            safeRender(() -> masonryPreviewPanel.render(), "Masonry Preview Panel");
        }
        if (uiDocumentPreviewPanel != null) {
            safeRender(() -> uiDocumentPreviewPanel.render(), "UI Document Preview Panel");
        }

        // Flush pending texture preview updates to the 3D viewport
        if (texturePreviewPipeline != null) {
            texturePreviewPipeline.flush();
        }
    }

    private void renderComponent(Object component, float deltaTime, String name) {
        if (component == null) return;

        safeRender(() -> {
            if (component instanceof ProjectHubScreen hub) {
                hub.render();
                hub.update(deltaTime);
            } else if (component instanceof MainImGuiInterface main) {
                main.render();
                main.update(deltaTime);
            }
        }, name);
    }

    private void safeRender(Runnable renderAction, String componentName) {
        try {
            renderAction.run();
        } catch (Exception e) {
            logger.error("Error rendering {}", componentName, e);
        }
    }

    /**
     * Request application exit. Shows the unsaved changes dialog if the model editor
     * is active and has unsaved changes, otherwise exits immediately.
     */
    private void requestApplicationExit() {
        if (showModelEditor && mainInterface != null) {
            mainInterface.requestExit();
        } else {
            shouldClose = true;
        }
    }

    /**
     * Transition to main interface from home screen.
     */
    /**
     * Dev hook for live runs: {@code -Dopenmason.texeditor=<project.omp>[,<texture.omt>]} opens the
     * project (when given) and the Texture Editor tab, with the texture loaded; add
     * {@code -Dopenmason.texeditor.detached=true} to pop the tab out into its own window.
     */
    private void devOpenTextureEditor() {
        String spec = System.getProperty("openmason.texeditor");
        if (spec == null || mainInterface == null || textureCreatorInterface == null) {
            return;
        }
        String[] parts = spec.split(",", 2);
        transitionToMainInterface();
        if (!parts[0].isBlank()) {
            mainInterface.openProjectFromHub(parts[0].trim());
        }
        if (parts.length > 1 && !parts[1].isBlank()
                && !textureCreatorInterface.getController().loadProject(parts[1].trim())) {
            logger.warn("openmason.texeditor: could not open {}", parts[1].trim());
        }
        openTextureEditor();
        if (Boolean.getBoolean("openmason.texeditor.detached")) {
            workspaceState.detach(com.openmason.main.systems.layout.BuiltinWorkspaces.TEXTURE);
        }
    }

    /**
     * Dev hook for live runs: {@code -Dopenmason.uieditor=<project.omp>[,<document.omui|.sbui>]}
     * opens the project, brings the UI Editor workspace to the front and opens the document.
     * Add {@code -Dopenmason.uieditor.select=<key>} to select an element and
     * {@code -Dopenmason.uieditor.preview=true} to start in Preview, and
     * {@code -Dopenmason.uieditor.sprites=<sheet id>} to open that sprite sheet in the Sprites panel, and
     * {@code -Dopenmason.uieditor.timeline=<clip>[@seconds]} to show a clip in the Timeline at a time, and
     * {@code -Dopenmason.uieditor.detached=true|scene} to start with the UI (or the Scene) tab popped out
     * into its own window.
     */
    private void devOpenUiEditor() {
        String spec = System.getProperty("openmason.uieditor");
        if (spec == null || spec.isBlank() || uiEditor == null || mainInterface == null) {
            return;
        }
        String[] parts = spec.split(",", 2);
        transitionToMainInterface();
        if (!parts[0].isBlank()) {
            mainInterface.openProjectFromHub(parts[0].trim());
        }
        workspaceState.set(com.openmason.main.systems.layout.BuiltinWorkspaces.UI);
        String detach = System.getProperty("openmason.uieditor.detached");
        if (detach != null && !detach.isBlank() && !"false".equalsIgnoreCase(detach)) {
            // start with a tab popped out into its own window: true/ui = UI, scene = Scene
            workspaceState.detach("scene".equalsIgnoreCase(detach.trim())
                    ? com.openmason.main.systems.layout.BuiltinWorkspaces.MODELING
                    : com.openmason.main.systems.layout.BuiltinWorkspaces.UI);
        }
        if (parts.length > 1 && !parts[1].isBlank()) {
            uiEditor.openFile(java.nio.file.Path.of(parts[1].trim()));
        }
        String select = System.getProperty("openmason.uieditor.select");
        var doc = uiEditor.context().doc();
        if (select != null && doc != null) {
            doc.select(java.util.List.of(select.split(",")));
        }
        String timeline = System.getProperty("openmason.uieditor.timeline");
        if (timeline != null && !timeline.isBlank()) {
            uiEditor.context().timelineRequest = timeline.trim(); // #295: Timeline on a clip at a time
        }
        String sprites = System.getProperty("openmason.uieditor.sprites");
        if (sprites != null && !sprites.isBlank()) {
            uiEditor.context().editSheetRequest = sprites.trim(); // #294: open the Sprites panel on a sheet
        }
        if (Boolean.getBoolean("openmason.uieditor.preview") && uiEditor.context().runtime() != null) {
            uiEditor.context().runtime().setMode(
                    com.openmason.main.systems.uiEditor.view.DesignerRuntime.Mode.PREVIEW);
        }
        logger.info("[uieditor] dev hook opened {}", spec);
    }

    private void transitionToMainInterface() {
        showHomeScreen = false;
        showModelEditor = true;

        // Reset all editor state for a clean session.
        // For blank template: this gives a fresh workspace.
        // For openRecentProject(): the subsequent openProjectFromHub() call
        // overwrites this reset with the saved project state.
        if (mainInterface != null) {
            mainInterface.resetEditorState();
        }
    }

    /**
     * Create a new project at {@code directory}/{@code name}.omp, pre-save it so
     * the file exists immediately, record it in recent projects, then open the
     * editor on the fresh project. A template's starter models are written beside
     * the .omp and its open-on-create model is loaded into the editor.
     */
    private void createNewProjectFile(String name, String directory, ProjectTemplate template, String targetId) {
        String safeName = (name == null || name.isBlank()) ? "Untitled" : name.trim();
        if (directory == null || directory.isBlank()) {
            // No directory chosen — just open a fresh (unsaved) editor session.
            transitionToMainInterface();
            return;
        }

        String fileName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".omp";
        String path = java.nio.file.Path.of(directory, fileName).toString();

        // First use of the base folder (or a per-project subfolder) — make sure
        // the directory chain exists before the pre-save writes the .omp.
        AppPaths.ensureDir(java.nio.file.Path.of(directory));
        // Scenes live in their own subfolder; create it up front so the Scene Viewer's
        // save dialog has somewhere sensible to default to.
        com.openmason.main.systems.project.ProjectLayout.ensureScaffold(java.nio.file.Path.of(directory));
        // Template models go down before the pre-save, whose browser refresh then lists them.
        com.openmason.api.target.GameTarget target =
                com.openmason.main.systems.target.GameTargets.shared().find(targetId).orElse(null);
        java.nio.file.Path openModel = TemplateScaffolder.scaffold(template, java.nio.file.Path.of(directory), target);

        transitionToMainInterface();
        boolean saved = mainInterface.saveNewProject(safeName, path, targetId);
        if (saved && projectHubScreen != null) {
            projectHubScreen.getRecentProjectsService().addProject(safeName, path);
        }
        if (openModel != null) {
            mainInterface.getModelOperations().loadOMOModel(openModel.toString());
        }
        logger.info("Created new project '{}' at {}", safeName, path);
    }

    /**
     * Open a recent project from the Project Hub.
     * Transitions to the main interface and loads the .OMP project file.
     */
    private void openRecentProject(RecentProject project) {
        transitionToMainInterface();

        if (project != null && project.getPath() != null && !project.getPath().isBlank()) {
            // Delegate to MainImGuiInterface to load the project via ProjectService
            mainInterface.openProjectFromHub(project.getPath());
            logger.info("Opening project from hub: {}", project.getPath());
        }
    }

    /**
     * Re-open the scene a project recorded as open, and put the centre tab back where the
     * user left it. Runs from {@code ProjectService.openProject} after the document is
     * restored; the outgoing project's scene was already dropped at the session boundary.
     *
     * <p>A null reference is a pre-1.2 project: the (already cleared) scene stays empty
     * and the tabs are not moved, so upgrading users see no change.
     */
    private void restoreSceneSession(com.openmason.main.systems.project.OMPFormat.SceneReference ref) {
        if (ref == null) {
            return;
        }
        if (ref.sceneFilePath() != null && !ref.sceneFilePath().isBlank()) {
            String dir = mainInterface.getProjectDirectorySupplier().get();
            java.nio.file.Path root = dir == null ? null : java.nio.file.Path.of(dir);
            String scenePath = com.openmason.main.systems.project.ProjectPaths
                    .resolve(root, ref.sceneFilePath());
            if (scenePath != null && java.nio.file.Files.exists(java.nio.file.Path.of(scenePath))) {
                if (!sceneViewerInterface.getSceneService().openScene(scenePath, root)) {
                    logger.warn("Could not re-open the project's scene: {}", scenePath);
                }
            } else {
                logger.warn("Project references a scene that no longer exists: {} (resolved: {})",
                        ref.sceneFilePath(), scenePath);
            }
        }
        mainInterface.requestCenterTab(
                com.openmason.main.systems.layout.CenterTab.resolve(
                        ref.activeCenterTab(),
                        com.openmason.main.systems.layout.CenterTab.MODEL_EDITOR).windowTitle());
    }

    /**
     * Tear-down that runs once the texture editor's tab has gone away (the user closed it; unsaved
     * textures were saved or discarded first, see {@code TextureEditorWorkspace.confirmClose}):
     * every document closes. The controller's listeners flush face documents to their textures,
     * clear the viewport highlight and save the .OMO when face edits were open.
     */
    private void onTextureEditorClosed() {
        showTextureEditor = false;
        textureCreatorInterface.getController().closeAll(
                com.openmason.main.systems.menus.textureCreator.TextureCreatorController.CloseReason.WORKSPACE_CLOSED);
    }

    /**
     * Opens the Texture Editor's workspace tab and brings it forward (its own window again when it
     * was closed from one).
     */
    private void openTextureEditor() {
        if (workspaceState == null) {
            return;
        }
        showTextureEditor = true;
        workspaceState.open(com.openmason.main.systems.layout.BuiltinWorkspaces.TEXTURE);
    }

    /**
     * Transition back to home screen from any tool.
     */
    private void transitionToHomeScreen() {
        showHomeScreen = true;
        showModelEditor = false;
        showTextureEditor = false;
        if (workspaceState != null) {
            workspaceState.close(com.openmason.main.systems.layout.BuiltinWorkspaces.TEXTURE);
        }
        if (animationEditor != null) {
            animationEditor.hide();
        }
        if (projectHubScreen != null) {
            projectHubScreen.onShown();
        }
    }

    /**
     * Cleanup all application resources (idempotent).
     */
    private com.openmason.main.systems.assistant.libalex.LibalexClient libalexClient;

    /**
     * Optional-capability detection: libalex is enabled when a launch config
     * exists on this machine AND the user hasn't disabled it in Preferences.
     * Absence of either simply leaves knowledge_search unregistered.
     */
    private com.openmason.main.systems.mcp.ToolCapabilities buildToolCapabilities() {
        try {
            if (!com.openmason.main.systems.menus.preferences.AssistantPreferences.libalexEnabled()) {
                logger.info("libalex disabled in preferences — knowledge_search not registered");
                return com.openmason.main.systems.mcp.ToolCapabilities.none();
            }
            var config = com.openmason.main.systems.assistant.libalex.LibalexDetector.detect();
            if (config == null) {
                logger.info("libalex not detected — knowledge_search not registered");
                return com.openmason.main.systems.mcp.ToolCapabilities.none();
            }
            libalexClient = new com.openmason.main.systems.assistant.libalex.LibalexClient(
                    config, new com.fasterxml.jackson.databind.ObjectMapper());
            logger.info("libalex detected — knowledge_search registered");
            return new com.openmason.main.systems.mcp.ToolCapabilities(
                    (query, collection, limit) -> libalexClient.recall(query, collection, limit));
        } catch (RuntimeException e) {
            logger.warn("libalex detection failed — feature disabled: {}", e.toString());
            return com.openmason.main.systems.mcp.ToolCapabilities.none();
        }
    }

    private void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;

        try {
            // Plugins and lifecycle listeners hear about shutdown while the UI and GL still exist.
            if (omLifecycle != null) {
                omLifecycle.notifyShuttingDown();
            }
            pluginManager.stopping();
            approvalGate.shutdown();
            saveSheetGate.shutdown();
            if (libalexClient != null) {
                libalexClient.close();
            }
            if (assistantController != null) {
                assistantController.shutdown();
            }
            if (scriptingWindow != null) {
                scriptingWindow.shutdown();
            }
            mcpServer.stop();

            long window = window();
            if (window != NULL) {
                glfwMakeContextCurrent(window);
                cleanupOpenGLResources();
                imGuiBackend.shutdown();
            }

            if (omLifecycle != null) {
                omLifecycle.onApplicationShutdown();
            }

            cleanupGLFW();

        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
    }

    private void cleanupOpenGLResources() {
        try {
            com.openmason.main.systems.rendering.model.block.BlockManager.shutdown();
        } catch (Exception e) {
            logger.error("Error cleaning up block preview meshes", e);
        }

        if (texturePreviewPipeline != null) {
            texturePreviewPipeline.dispose();
        }

        if (skijaTestPanel != null) {
            skijaTestPanel.close();
            skijaTestPanel = null;
        }
        if (masonryPreviewPanel != null) {
            masonryPreviewPanel.close();
            masonryPreviewPanel = null;
        }
        if (uiDocumentPreviewPanel != null) {
            uiDocumentPreviewPanel.close();
            uiDocumentPreviewPanel = null;
        }
        if (uiEditor != null) {
            try {
                uiEditor.close(); // GL/Skia resources; unsaved work stays in recovery
            } catch (Exception e) {
                logger.error("Error closing the UI editor", e);
            }
            uiEditor = null;
        }
        // Hub owns Skija regions (FBOs/textures) — release them before the
        // SkijaContext that backs them is closed.
        if (projectHubScreen != null) {
            try {
                projectHubScreen.dispose();
            } catch (Exception e) {
                logger.error("Error disposing Project Hub", e);
            }
        }
        // Editor GPU resources: browser thumbnails + property panel Skija regions.
        if (mainInterface != null) {
            try {
                mainInterface.dispose();
            } catch (Exception e) {
                logger.error("Error disposing main interface resources", e);
            }
        }
        // Animation editor Mortar regions (FBOs) — must close before SkijaContext.
        if (animationEditor != null) {
            try {
                animationEditor.dispose();
            } catch (Exception e) {
                logger.error("Error disposing Animation Editor", e);
            }
        }
        if (skijaContext != null) {
            skijaContext.close();
            skijaContext = null;
        }

        safeDispose(viewportInterface);
        safeDispose(textureCreatorInterface);
        safeDispose(themeManager);

        try {
            com.openmason.main.systems.menus.icons.MenuBarIconManager.getInstance().dispose();
        } catch (Exception e) {
            logger.error("Error cleaning up MenuBarIconManager", e);
        }

        try {
            com.openmason.main.systems.menus.dialogs.icons.PartShapeIconManager.getInstance().dispose();
        } catch (Exception e) {
            logger.error("Error cleaning up PartShapeIconManager", e);
        }
    }

    private void cleanupGLFW() {
        if (appWindow != null) {
            appWindow.destroy();
        }

        glfwPlatform.terminate();
    }

    private void safeDispose(Object resource) {
        if (resource == null) return;
        try {
            if (resource instanceof ViewportImGuiInterface v) v.dispose();
            else if (resource instanceof com.openmason.main.systems.scene.SceneViewerImGuiInterface s) s.dispose();
            else if (resource instanceof TextureCreatorImGui t) t.dispose();
            else if (resource instanceof ThemeManager tm) tm.dispose();
        } catch (Exception e) {
            logger.error("Error disposing resource: {}", resource.getClass().getSimpleName(), e);
        }
    }

    /**
     * Application entry point.
     */
    public static void main(String[] args) {
        try {
            new mainOpenMason().run();
        } catch (Exception e) {
            logger.error("Failed to launch OpenMason application", e);
            System.exit(1);
        }
    }
}
