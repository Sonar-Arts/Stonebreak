package com.openmason.main.bootstrap;

import com.openmason.main.omConfig;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.layout.CenterTabTracker;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.mainHub.ProjectHubScreen;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.menus.textureCreator.FaceEditorBridge;
import com.openmason.main.systems.menus.textureCreator.FaceTextureResizeDialog;
import com.openmason.main.systems.menus.textureCreator.IFaceTextureGPUService;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TexturePreviewPipeline;
import com.openmason.main.systems.menus.windows.TextureEditorWindow;
import com.openmason.main.systems.project.OMPFormat;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.scene.SceneViewerImGuiInterface;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.viewport.ViewportImGuiInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the tool's UI object graph (theme, hub, editor shell, viewports, texture/animation
 * editors, preview pipeline) and wires the callbacks between them. The application shell
 * supplies the few lifecycle hooks the graph needs through {@link Host}; the composed
 * components are exposed through getters and are populated progressively, so a partially
 * composed graph can still be disposed after a failure.
 */
public final class UiComposition {

    private static final Logger logger = LoggerFactory.getLogger(UiComposition.class);

    /** Lifecycle hooks the application shell provides to the composed UI graph. */
    public interface Host {
        /** Project Hub "create" transition (name + directory). */
        void createNewProjectFile(String name, String directory);

        /** Project Hub "open recent" transition. */
        void openRecentProject(RecentProject project);

        /** Re-open the scene recorded in a project and restore the centre tab. */
        void restoreSceneSession(OMPFormat.SceneReference ref);

        /** Leave any tool and return to the Project Hub. */
        void transitionToHomeScreen();

        /** Exit the application (after any unsaved-changes handling already ran). */
        void requestExit();

        /** Mark the texture editor visible and show its window. */
        void showTextureEditor();
    }

    private final omConfig omConfig;
    private final long window;
    private final CenterTabTracker centerTabTracker;
    private final Host host;

    private ThemeManager themeManager;
    private ProjectHubScreen projectHubScreen;
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    private SceneViewerImGuiInterface sceneViewerInterface;
    private TextureCreatorImGui textureCreatorInterface;
    private TextureEditorWindow textureEditorWindow;
    private AnimationEditorImGui animationEditor;
    private TexturePreviewPipeline texturePreviewPipeline;

    public UiComposition(omConfig omConfig, long window, CenterTabTracker centerTabTracker, Host host) {
        this.omConfig = omConfig;
        this.window = window;
        this.centerTabTracker = centerTabTracker;
        this.host = host;
    }

    public ThemeManager themeManager() { return themeManager; }
    public ProjectHubScreen projectHubScreen() { return projectHubScreen; }
    public MainImGuiInterface mainInterface() { return mainInterface; }
    public ViewportImGuiInterface viewportInterface() { return viewportInterface; }
    public SceneViewerImGuiInterface sceneViewerInterface() { return sceneViewerInterface; }
    public TextureCreatorImGui textureCreatorInterface() { return textureCreatorInterface; }
    public TextureEditorWindow textureEditorWindow() { return textureEditorWindow; }
    public AnimationEditorImGui animationEditor() { return animationEditor; }
    public TexturePreviewPipeline texturePreviewPipeline() { return texturePreviewPipeline; }

    /**
     * Initialize UI components and wire up callbacks.
     */
    public void compose() {
        try {
            themeManager = new ThemeManager();
            themeManager.initializeForImGui();

            projectHubScreen = new ProjectHubScreen(themeManager, omConfig);
            mainInterface = new MainImGuiInterface(themeManager);

            projectHubScreen.setTransitionCallbacks(host::createNewProjectFile, host::openRecentProject);
            projectHubScreen.setFolderPicker(onChosen ->
                    mainInterface.getFileDialogService().showPickFolderDialog(onChosen::accept));
            projectHubScreen.setOnPreferencesClicked(mainInterface.getShowPreferencesCallback());

            // Wire recent projects service from hub into main interface for project tracking
            mainInterface.setRecentProjectsService(projectHubScreen.getRecentProjectsService());

            // Initialize keybind system BEFORE creating viewport and texture editor
            initializeKeybindSystem();

            viewportInterface = new ViewportImGuiInterface(themeManager, new PreferencesManager());
            viewportInterface.setViewport3D(mainInterface.getViewport3D());

            // Scene Viewer: peer of the model editor's viewport, with its own ModelViewer.
            sceneViewerInterface = new com.openmason.main.systems.scene.SceneViewerImGuiInterface(
                    mainInterface.getUIVisibilityState());
            // A scene belongs to its project: drop it whenever the session changes.
            mainInterface.setOnProjectSessionReset(
                    () -> sceneViewerInterface.getSceneService().clearCurrentScene());

            // Camera sensitivities are user preferences: apply them to both surfaces.
            mainInterface.setSceneCameraPreferenceSink(
                    (orbit, pan) -> sceneViewerInterface.applyCameraPreferences(orbit, pan));

            sceneViewerInterface.setProjectRootSupplier(() -> {
                String dir = mainInterface.getProjectDirectorySupplier().get();
                return dir == null ? null : java.nio.file.Path.of(dir);
            });
            sceneViewerInterface.setOnEditModelRequested(omoPath -> {
                mainInterface.getModelOperations().loadOMOModel(omoPath);
                mainInterface.requestCenterTab(
                        com.openmason.main.systems.viewport.views.ViewportMainView.WINDOW_TITLE);
            });
            // Scene open/save, routed through the shell so the project root is applied.
            java.util.function.Supplier<java.nio.file.Path> sceneRoot = () -> {
                String dir = mainInterface.getProjectDirectorySupplier().get();
                return dir == null ? null : java.nio.file.Path.of(dir);
            };
            mainInterface.setOpenSceneCallback(path ->
                    sceneViewerInterface.getSceneService().openScene(path.toString(), sceneRoot.get()));
            mainInterface.setSceneActions(
                    () -> sceneViewerInterface.getSceneService().newScene("Untitled Scene"),
                    () -> mainInterface.getFileDialogService().showOpenOMSCDialog(path ->
                            sceneViewerInterface.getSceneService().openScene(path, sceneRoot.get())),
                    () -> {
                        var svc = sceneViewerInterface.getSceneService();
                        if (svc.hasCurrentScene()) {
                            svc.saveScene(sceneRoot.get());
                        } else {
                            mainInterface.getFileDialogService().showSaveOMSCDialog(path ->
                                    svc.saveSceneAs(path, sceneRoot.get()));
                        }
                    },
                    () -> mainInterface.getFileDialogService().showSaveOMSCDialog(path ->
                            sceneViewerInterface.getSceneService().saveSceneAs(path, sceneRoot.get())),
                    () -> sceneViewerInterface.getSceneService().hasUnsavedChanges());

            // Projects re-open the scene that was open when they were saved. Save side:
            // record the open .omsc (project-relative) + the front centre tab into the
            // .omp's scene node. Restore side: openProject hands the node back and the
            // scene and tab come back. A dirty, already-saved scene is also written out
            // alongside the project, the same way the active model is.
            mainInterface.setSceneSessionHooks(
                    () -> {
                        var svc = sceneViewerInterface.getSceneService();
                        java.nio.file.Path root = sceneRoot.get();
                        String stored = svc.hasCurrentScene()
                                ? com.openmason.main.systems.project.ProjectPaths
                                        .relativize(root, svc.getCurrentScenePath())
                                : null;
                        return new com.openmason.main.systems.project.OMPFormat.SceneReference(
                                stored, centerTabTracker.activeTab().name());
                    },
                    host::restoreSceneSession);
            mainInterface.setSaveOpenSceneAction(() -> {
                var svc = sceneViewerInterface.getSceneService();
                if (svc.hasCurrentScene() && svc.hasUnsavedChanges()) {
                    svc.saveScene(sceneRoot.get());
                }
            });

            sceneViewerInterface.setOnAddModelRequested(() ->
                    mainInterface.getFileDialogService().showOpenOMOInProjectDialog(path -> {
                        try {
                            var svc = sceneViewerInterface.getSceneService();
                            var ref = svc.addModelFromFile(java.nio.file.Path.of(path), sceneRoot.get());
                            String name = ref.sourceName() != null
                                    ? ref.sourceName().replaceFirst("(?i)\\.omo$", "")
                                    : "Instance";
                            sceneViewerInterface.getActions().place(ref, name, 0, 0, 0);
                        } catch (Exception e) {
                            logger.error("Could not add model {}: {}", path, e.getMessage());
                        }
                    }));

            // Wire slideouts: rigging pane ↔ viewport tool pane (Add Part, Part Transform)
            if (mainInterface.getRiggingPane() != null) {
                mainInterface.getRiggingPane().wireSlideouts(
                        viewportInterface.getViewportUIState(), mainInterface.getViewport3D());
            }

            textureCreatorInterface = TextureCreatorImGui.createDefault();
            textureEditorWindow = new TextureEditorWindow(textureCreatorInterface);

            // Point the texture editor's save/open dialogs at the open project's root
            // folder (same source the model-save dialogs use).
            textureCreatorInterface.getFileDialogService()
                    .setProjectDirectorySupplier(mainInterface.getProjectDirectorySupplier());
            animationEditor = new AnimationEditorImGui();
            animationEditor.setFileDialogService(mainInterface.getFileDialogService());
            mainInterface.setAnimationEditorInterface(animationEditor);

            // Load custom keybinds AFTER both viewport and texture editor are initialized
            loadCustomKeybinds();

            wireCallbacks();
            setWindowHandles();

            // Real-time texture preview: canvas edits → 3D viewport
            texturePreviewPipeline = new TexturePreviewPipeline(
                textureCreatorInterface.getController(),
                mainInterface.getViewport3D().getModelRenderer()
            );

            // Wire face editor bridge: property panel "Edit Texture" → texture editor
            FaceEditorBridge faceEditorBridge = new FaceEditorBridge(textureCreatorInterface.getController());
            mainInterface.getPropertyPanel().setFaceEditorBridge(faceEditorBridge);
            mainInterface.getPropertyPanel().setOnEditTextureRequested(host::showTextureEditor);

            // Wire per-face texture resize dialog into texture editor's Edit menu.
            // The dialog reads/writes GPU textures via the viewport connector and
            // uploads new textures via OMTTextureLoader. Both are accessed through
            // the property panel's existing viewport adapter.
            OMTTextureLoader resizeTextureLoader = new OMTTextureLoader();
            IFaceTextureGPUService gpuService = new IFaceTextureGPUService() {
                @Override
                public int[] getTextureDimensions(int gpuTextureId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    return c != null ? c.getTextureDimensions(gpuTextureId) : null;
                }
                @Override
                public byte[] readTexturePixels(int gpuTextureId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    return c != null ? c.readTexturePixels(gpuTextureId) : null;
                }
                @Override
                public void setFaceTexture(int faceId, int materialId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    if (c != null) c.setFaceTexture(faceId, materialId);
                }
                @Override
                public float[][] computeFacePolygon2D(int faceId) {
                    var c = mainInterface.getPropertyPanel().getViewportConnector();
                    return c != null ? c.computeFacePolygon2D(faceId) : null;
                }
                @Override
                public int uploadPixelCanvasToGPU(
                        com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas canvas) {
                    return resizeTextureLoader.uploadPixelCanvasToGPU(canvas);
                }
            };
            FaceTextureResizeDialog resizeDialog = new FaceTextureResizeDialog(
                    () -> {
                        var c = mainInterface.getPropertyPanel().getViewportConnector();
                        return c != null ? c.getFaceTextureManager() : null;
                    },
                    gpuService,
                    faceEditorBridge);
            textureCreatorInterface.setFaceTextureResizeDialog(resizeDialog);

        } catch (Exception e) {
            logger.error("Failed to initialize UI interfaces", e);
            throw new RuntimeException("UI initialization failed", e);
        }
    }

    /**
     * Initialize the keybind system.
     * Called before creating viewport and texture editor interfaces.
     */
    private void initializeKeybindSystem() {
        logger.info("Initializing keybind system...");

        // Get the keybind registry singleton
        com.openmason.main.systems.keybinds.KeybindRegistry registry =
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

        // Note: Viewport actions will be registered when ViewportImGuiInterface is created
        // Note: Texture editor actions will be registered when TextureCreatorImGui.createDefault() is called

        logger.info("Keybind registry initialized successfully");
    }

    /**
     * Load custom keybinds from preferences.
     * Called after both viewport and texture editor are created and have registered their actions.
     */
    private void loadCustomKeybinds() {
        logger.info("Loading custom keybinds from preferences...");

        com.openmason.main.systems.menus.preferences.PreferencesManager preferencesManager =
                new com.openmason.main.systems.menus.preferences.PreferencesManager();
        com.openmason.main.systems.keybinds.KeybindRegistry registry =
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

        preferencesManager.loadKeybindsIntoRegistry(registry);

        logger.info("Custom keybinds loaded successfully");
    }

    private void wireCallbacks() {
        mainInterface.setBackToHomeCallback(host::transitionToHomeScreen);
        mainInterface.setExitCallback(host::requestExit);
        mainInterface.setOpenTextureEditorCallback(() -> {
            // Standalone open: reset to a fresh blank canvas so previous
            // per-face edits don't leak into the standalone session
            textureCreatorInterface.getController().resetAll();
            host.showTextureEditor();
        });
        // Clicking a .OMT in the project browser opens it in the texture editor
        mainInterface.setOpenTextureInEditorCallback(path -> {
            if (path == null) return;
            boolean loaded = textureCreatorInterface.getController().loadProject(path.toString());
            if (!loaded) {
                logger.warn("Failed to open .OMT in texture editor: {}", path);
                return;
            }
            host.showTextureEditor();
        });
        mainInterface.setOpenAnimationEditorCallback(() -> {
            if (animationEditor == null) return;
            // Bind the animation editor to whatever model is currently loaded so
            // the timeline drives this viewport's parts.
            if (mainInterface.getViewport3D() != null) {
                animationEditor.bindViewport(mainInterface.getViewport3D().getPartManager());
            }
            animationEditor.show();
        });
        mainInterface.setTextureCreatorInterface(textureCreatorInterface);

        // Reset texture editor when a new/different model is loaded. Single
        // callback slot — the animation editor rebind must chain here, not
        // replace the texture editor reset.
        mainInterface.getModelOperations().setOnModelChangedCallback(() -> {
            textureCreatorInterface.getController().resetAll();
            if (animationEditor != null && animationEditor.isVisible()
                    && mainInterface.getViewport3D() != null) {
                animationEditor.getController()
                        .onModelChanged(mainInterface.getViewport3D().getPartManager());
            }
        });

        textureCreatorInterface.setBackToHomeCallback(host::transitionToHomeScreen);
        textureCreatorInterface.setPreferencesCallback(mainInterface.getShowPreferencesCallback());
    }

    private void setWindowHandles() {
        if (window == 0L) {
            throw new IllegalStateException("Window not created");
        }
        viewportInterface.setWindowHandle(window);
        textureCreatorInterface.setWindowHandle(window);
    }
}
