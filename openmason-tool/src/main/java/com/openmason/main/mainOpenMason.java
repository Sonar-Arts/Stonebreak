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
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TexturePreviewPipeline;
import com.openmason.main.systems.menus.windows.TextureEditorWindow;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaTestPanel;
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
    private TextureEditorWindow textureEditorWindow;
    private AnimationEditorImGui animationEditor;
    private TexturePreviewPipeline texturePreviewPipeline;
    private final McpServerBootstrap mcpServer = new McpServerBootstrap();
    private SkijaContext skijaContext;
    private SkijaTestPanel skijaTestPanel;

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

            omLifecycle.onApplicationStarted();
            mcpServer.start(mainInterface);
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
        try {
            skijaContext = SkijaContext.initialize();
            if (SkijaTestPanel.ENABLED) {
                skijaTestPanel = new SkijaTestPanel();
                logger.info("Skija test panel enabled (-Dopenmason.skija.test=true)");
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
        while (!shouldClose && !glfwWindowShouldClose(window)) {
            glfwPollEvents();
            MainThreadExecutor.drain();
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            imGuiBackend.beginFrame();

            renderUI();

            imGuiBackend.endFrame();

            imGuiBackend.handleMultiViewport();

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
                    public void createNewProjectFile(String name, String directory) {
                        mainOpenMason.this.createNewProjectFile(name, directory);
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
                        showTextureEditor = true;
                        textureEditorWindow.show();
                    }
                });
        try {
            composition.compose();
        } finally {
            themeManager = composition.themeManager();
            projectHubScreen = composition.projectHubScreen();
            mainInterface = composition.mainInterface();
            viewportInterface = composition.viewportInterface();
            sceneViewerInterface = composition.sceneViewerInterface();
            textureCreatorInterface = composition.textureCreatorInterface();
            textureEditorWindow = composition.textureEditorWindow();
            animationEditor = composition.animationEditor();
            texturePreviewPipeline = composition.texturePreviewPipeline();
        }
    }

    /**
     * Render UI components based on visibility flags.
     */
    private void renderUI() {
        float deltaTime = ImGui.getIO().getDeltaTime();

        if (showHomeScreen) {
            renderComponent(projectHubScreen, deltaTime, "Project Hub");
        }

        if (showModelEditor) {
            renderComponent(mainInterface, deltaTime, "Main Interface");
            renderComponent(viewportInterface, deltaTime, "Viewport");
            renderComponent(sceneViewerInterface, deltaTime, "Scene Viewer");

            // Both centre views have reported visibility/focus by now; remember which
            // tab is in front so a project save can record it.
            if (viewportInterface != null && sceneViewerInterface != null) {
                var sceneState = sceneViewerInterface.getUIState();
                var modelState = viewportInterface.getViewportUIState();
                centerTabTracker.noteFrame(
                        sceneState.isSceneViewVisible(), sceneState.isSceneViewFocused(),
                        modelState.isViewportWindowVisible(), modelState.isViewportFocused());
            }
        }

        if (showTextureEditor) {
            safeRender(() -> {
                textureEditorWindow.render();
                boolean stillVisible = textureEditorWindow.isVisible();
                if (!stillVisible) {
                    boolean wasFaceEdit = textureCreatorInterface.getController().isFaceRegionActive();

                    // Flush pending canvas edits to the face's GPU texture BEFORE
                    // closing the region — closeFaceRegion clears the material ID,
                    // so a later flush would target the wrong texture.
                    if (texturePreviewPipeline != null) {
                        texturePreviewPipeline.flush();
                    }
                    textureCreatorInterface.getController().closeFaceRegion();
                    mainInterface.getPropertyPanel().clearEditingFace();

                    // Auto-save the .OMO so per-face texture edits are persisted
                    if (wasFaceEdit && mainInterface.getModelOperations() != null) {
                        mainInterface.getModelOperations().saveModel();
                    }
                }
                showTextureEditor = stillVisible;
            }, "Texture Editor");
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

        if (mainInterface != null && mainInterface.getSBOTextureExportWindow() != null) {
            safeRender(() -> mainInterface.getSBOTextureExportWindow().render(), "SBO Texture Export Window");
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

        if (skijaTestPanel != null) {
            safeRender(() -> skijaTestPanel.render(), "Skija Test Panel");
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
            } else if (component instanceof ViewportImGuiInterface viewport) {
                viewport.render();
                viewport.update(deltaTime);
            } else if (component instanceof com.openmason.main.systems.scene.SceneViewerImGuiInterface scene) {
                scene.render();
                scene.update(deltaTime);
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
     * Create a new blank project at {@code directory}/{@code name}.omp, pre-save
     * it so the file exists immediately, record it in recent projects, then open
     * the editor on the fresh project.
     */
    private void createNewProjectFile(String name, String directory) {
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

        transitionToMainInterface();
        boolean saved = mainInterface.saveNewProject(safeName, path);
        if (saved && projectHubScreen != null) {
            projectHubScreen.getRecentProjectsService().addProject(safeName, path);
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
     * Transition back to home screen from any tool.
     */
    private void transitionToHomeScreen() {
        showHomeScreen = true;
        showModelEditor = false;
        showTextureEditor = false;
        if (textureEditorWindow != null) {
            textureEditorWindow.hide();
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
    private void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;

        try {
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
            if (com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.isInitialized()) {
                com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager.getInstance().close();
            }
        } catch (Exception e) {
            logger.error("Error cleaning up CBRResourceManager", e);
        }

        if (texturePreviewPipeline != null) {
            texturePreviewPipeline.dispose();
        }

        if (skijaTestPanel != null) {
            skijaTestPanel.close();
            skijaTestPanel = null;
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
