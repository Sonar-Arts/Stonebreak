package com.openmason.main.bootstrap;

import com.openmason.main.omConfig;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.layout.CenterTabTracker;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.mainHub.ProjectHubScreen;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.menus.textureCreator.FaceEditorBridge;
import com.openmason.main.systems.menus.textureCreator.FaceTextureResizeDialog;
import com.openmason.main.systems.menus.textureCreator.IFaceTextureGPUService;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TexturePreviewPipeline;
import com.openmason.main.systems.menus.scriptingWindow.ScriptingWindow;
import com.openmason.main.systems.menus.textureCreator.TextureEditorWorkspace;
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
        /** Project Hub "create" transition (name + directory + template, null when blank). */
        void createNewProjectFile(String name, String directory, ProjectTemplate template, String targetId);

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
    private final com.openmason.main.plugins.EditorRegistries registries;

    private ThemeManager themeManager;
    private ProjectHubScreen projectHubScreen;
    private MainImGuiInterface mainInterface;
    private ViewportImGuiInterface viewportInterface;
    private SceneViewerImGuiInterface sceneViewerInterface;
    private TextureCreatorImGui textureCreatorInterface;
    private TextureEditorWorkspace textureEditorWorkspace;
    private ScriptingWindow scriptingWindow;
    private AnimationEditorImGui animationEditor;
    private TexturePreviewPipeline texturePreviewPipeline;
    private final com.openmason.main.systems.layout.WorkspaceState workspaceState;
    private com.openmason.main.systems.layout.WorkspaceLayouts workspaceLayouts;
    private com.openmason.main.systems.uiEditor.view.UiEditorWorkspace uiEditor;

    public UiComposition(omConfig omConfig, long window, CenterTabTracker centerTabTracker, Host host,
                         com.openmason.main.plugins.EditorRegistries registries) {
        this.omConfig = omConfig;
        this.window = window;
        this.centerTabTracker = centerTabTracker;
        this.host = host;
        this.registries = registries;
        this.workspaceState = new com.openmason.main.systems.layout.WorkspaceState(registries.workspaces());
    }

    public ThemeManager themeManager() { return themeManager; }
    public ProjectHubScreen projectHubScreen() { return projectHubScreen; }
    public MainImGuiInterface mainInterface() { return mainInterface; }
    public ViewportImGuiInterface viewportInterface() { return viewportInterface; }
    public SceneViewerImGuiInterface sceneViewerInterface() { return sceneViewerInterface; }
    public TextureCreatorImGui textureCreatorInterface() { return textureCreatorInterface; }
    public TextureEditorWorkspace textureEditorWorkspace() { return textureEditorWorkspace; }
    public ScriptingWindow scriptingWindow() { return scriptingWindow; }
    public AnimationEditorImGui animationEditor() { return animationEditor; }
    public TexturePreviewPipeline texturePreviewPipeline() { return texturePreviewPipeline; }
    public com.openmason.main.systems.layout.WorkspaceState workspaceState() { return workspaceState; }
    public com.openmason.main.systems.layout.WorkspaceLayouts workspaceLayouts() { return workspaceLayouts; }
    public com.openmason.main.systems.uiEditor.view.UiEditorWorkspace uiEditor() { return uiEditor; }

    /**
     * Initialize UI components and wire up callbacks.
     */
    public void compose() {
        try {
            themeManager = new ThemeManager();
            themeManager.initializeForImGui();

            projectHubScreen = new ProjectHubScreen(themeManager, omConfig);
            // Templates and themes are plugin contributions (#379).
            projectHubScreen.getTemplateService().setSource(registries.templates()::all);
            // Game targets (#380): export dialogs, the asset catalog and the write sandbox read them here.
            com.openmason.main.systems.target.GameTargets.shared().setSource(registries.gameTargets()::all);
            registries.themes().addListener(new com.openmason.api.Contributions.Listener<>() {
                @Override
                public void added(com.openmason.main.systems.themes.core.ThemeDefinition theme) {
                    themeManager.registerPluginTheme(theme,
                            registries.themes().ownerOf(theme.getId()).orElse("?"));
                }

                @Override
                public void removed(com.openmason.main.systems.themes.core.ThemeDefinition theme) {
                    themeManager.unregisterPluginTheme(theme.getId());
                }
            });

            // Each workspace's default dock layout is versioned per workspace id.
            workspaceLayouts = new com.openmason.main.systems.layout.WorkspaceLayouts(
                    new com.openmason.main.systems.layout.ConfigLayoutVersionStore(omConfig,
                            PreferencesManager.getInstance()));
            mainInterface = new MainImGuiInterface(themeManager, registries, workspaceState, workspaceLayouts);
            registries.services().put(com.openmason.main.systems.layout.WorkspaceLayouts.class, workspaceLayouts);
            registries.services().put(ProjectHubScreen.class, projectHubScreen);

            projectHubScreen.setTransitionCallbacks(host::createNewProjectFile, host::openRecentProject);
            projectHubScreen.setFolderPicker(onChosen ->
                    mainInterface.getFileDialogService().showPickFolderDialog(onChosen::accept));
            projectHubScreen.setOnPreferencesClicked(mainInterface.getShowPreferencesCallback());

            // Wire recent projects service from hub into main interface for project tracking
            mainInterface.setRecentProjectsService(projectHubScreen.getRecentProjectsService());


            viewportInterface = new ViewportImGuiInterface(themeManager, new PreferencesManager());
            viewportInterface.setViewport3D(mainInterface.getViewport3D());
            mainInterface.registerModelFileKeybinds(
                    com.openmason.main.systems.keybinds.KeybindRegistry.getInstance());

            // Scene Viewer: peer of the model editor's viewport, with its own ModelViewer.
            sceneViewerInterface = new com.openmason.main.systems.scene.SceneViewerImGuiInterface(
                    mainInterface.getUIVisibilityState());
            // A scene belongs to its project: drop it whenever the session changes.
            mainInterface.setOnProjectSessionReset(() -> {
                sceneViewerInterface.getSceneService().clearCurrentScene();
                if (uiEditor != null) {
                    uiEditor.discardAll(); // UI documents belong to their project too
                }
            });

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
            // The edit round-trip: saving a model in the editor refreshes every scene
            // instance that places it.
            mainInterface.getModelOperations().setOnModelSaved(
                    path -> sceneViewerInterface.getSceneService().reloadModel(path));
            // Lets the MCP scene tools and the File menu's dirty checks reach the scene.
            mainInterface.setSceneViewer(sceneViewerInterface);
            // Scene open/save, routed through the shell so the project root is applied.
            java.util.function.Supplier<java.nio.file.Path> sceneRoot = () -> {
                String dir = mainInterface.getProjectDirectorySupplier().get();
                return dir == null ? null : java.nio.file.Path.of(dir);
            };
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
                if (svc.hasUnsavedChanges()) {
                    // An untitled scene lands in the project's Scenes/ folder under its
                    // name, so save-with-project / save-on-exit never drops it.
                    svc.saveIntoProject(sceneRoot.get());
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
            textureEditorWorkspace = new TextureEditorWorkspace(textureCreatorInterface);
            scriptingWindow = new ScriptingWindow(new com.openmason.main.systems.services.EditorServices(
                    registries.services()));
            registries.services().put(TextureEditorWorkspace.class, textureEditorWorkspace);
            registries.services().put(ScriptingWindow.class, scriptingWindow);
            registries.services().put(ViewportImGuiInterface.class, viewportInterface);

            // Point the texture editor's save/open dialogs at the open project's root
            // folder (same source the model-save dialogs use).
            textureCreatorInterface.getFileDialogService()
                    .setProjectDirectorySupplier(mainInterface.getProjectDirectorySupplier());
            animationEditor = new AnimationEditorImGui();
            animationEditor.setFileDialogService(mainInterface.getFileDialogService());
            mainInterface.setAnimationEditorInterface(animationEditor);
            wireAnimationEditor();

            wireUiEditor(sceneRoot);
            wireScenePanels();
            // The workspaces themselves (Scene, UI, Texture) are registered by the built-in plugins;
            // custom keybinds load once every plugin registered its commands (mainOpenMason).

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
            wireFaceDocuments();

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
     * Face documents and the model: the viewport outlines the face of the active face document,
     * and closing face documents (their tabs, or the whole Texture workspace) saves the model so
     * the per-face texture edits — already flushed to its textures — are persisted. Documents
     * dropped because another model was loaded are not saved.
     */
    private void wireFaceDocuments() {
        var panel = mainInterface.getPropertyPanel();
        textureCreatorInterface.getController().addDocumentListener(
                new com.openmason.main.systems.menus.textureCreator.TextureCreatorController.DocumentListener() {
                    @Override
                    public void activated(com.openmason.main.systems.menus.textureCreator.TextureDocument document) {
                        if (document.isFace() && document.face().faceId() >= 0) {
                            panel.setEditingFace(document.face().faceId());
                        } else {
                            panel.clearEditingFace();
                        }
                    }

                    @Override
                    public void closed(java.util.List<com.openmason.main.systems.menus.textureCreator.TextureDocument> documents,
                                       com.openmason.main.systems.menus.textureCreator.TextureCreatorController.CloseReason reason) {
                        boolean faces = documents.stream().anyMatch(
                                com.openmason.main.systems.menus.textureCreator.TextureDocument::isFace);
                        if (faces && reason != com.openmason.main.systems.menus.textureCreator.TextureCreatorController.CloseReason.MODEL_CHANGED
                                && mainInterface.getModelOperations() != null) {
                            mainInterface.getModelOperations().saveModel();
                        }
                    }
                });
    }

    /**
     * The UI workspace (#293): its own dockspace and panels behind the main window's
     * {@code Scene | UI} tabs (or its own window when popped out), its toolbar, file dialogs,
     * keybinds, the project-save hooks and the project file's uiEditor node (tab in front, popped
     * out or not, open UI documents, active one, canvas settings).
     */
    private void wireUiEditor(java.util.function.Supplier<java.nio.file.Path> projectRoot) {
        uiEditor = new com.openmason.main.systems.uiEditor.view.UiEditorWorkspace(projectRoot);
        uiEditor.setOnActivateRequest(() -> workspaceState.set(com.openmason.main.systems.layout.BuiltinWorkspaces.UI));
        mainInterface.setUiEditor(uiEditor); // MCP / scripting reach the workspace here (#324)
        var dialogs = mainInterface.getFileDialogService();
        uiEditor.setFileDialogs(new com.openmason.main.systems.uiEditor.view.UiEditorWorkspace.FileDialogs() {
            @Override
            public void openUiDocument(java.util.function.Consumer<String> chosen) {
                dialogs.showOpenUiDocumentDialog(chosen::accept);
            }

            @Override
            public void saveOmui(String defaultName, java.util.function.Consumer<String> chosen) {
                dialogs.showSaveOMUIDialog(defaultName, chosen::accept);
            }

            @Override
            public void saveSbui(String defaultName, java.util.function.Consumer<String> chosen) {
                dialogs.showSaveSBUIDialog(defaultName, chosen::accept);
            }

            @Override
            public void openSbui(java.util.function.Consumer<String> chosen) {
                dialogs.showOpenSBUIDialog(chosen::accept);
            }
        });
        uiEditor.registerKeybinds(com.openmason.main.systems.keybinds.KeybindRegistry.getInstance());
        // #294: "Edit Texture" opens the texture behind a UI image; saves refresh every document.
        uiEditor.setTextureEditor(omt -> {
            // Opens the texture as its own document tab (or focuses it); no other texture is touched.
            if (!textureCreatorInterface.getController().loadProject(omt.toString())) {
                return "The Texture Editor could not open " + omt.getFileName();
            }
            host.showTextureEditor();
            return null;
        });
        textureCreatorInterface.getController().addSaveListener(uiEditor::textureSaved);
        var editor = uiEditor;
        var workspaces = workspaceState;
        mainInterface.setUiMenuHooks(new com.openmason.main.systems.menus.FileMenuHandler.UiMenuHooks() {
            @Override
            public boolean anyDirty() {
                return editor.hasUnsavedChanges();
            }

            @Override
            public java.util.List<String> saveAllInPlace() {
                return editor.saveAllInPlace();
            }

            @Override
            public void discardAll() {
                editor.discardAll();
            }
        });
        var uiTab = com.openmason.main.systems.layout.BuiltinWorkspaces.UI;
        editor.setDockControls(() -> workspaces.isDetached(uiTab), () -> workspaces.canDetach(uiTab), () -> {
            if (workspaces.isDetached(uiTab)) {
                workspaces.attach(uiTab, true);
            } else {
                workspaces.detach(uiTab);
            }
        });
        editor.setOnResetLayout(() -> workspaceLayouts.requestReset(uiTab));
        mainInterface.setUiEditorSessionHooks(
                () -> new com.openmason.main.systems.project.OMPFormat.UiEditorReference(
                        workspaces.current(), editor.sessionDocuments(), editor.sessionActive(),
                        java.util.List.copyOf(workspaces.detached()), editor.sessionSettings()),
                ref -> {
                    if (ref == null) {
                        // A pre-1.3 project: Scene, as it always opened.
                        workspaces.restore(com.openmason.main.systems.layout.BuiltinWorkspaces.MODELING, java.util.List.of());
                        return;
                    }
                    editor.restoreSession(ref.documents(), ref.activeDocument());
                    editor.restoreSettings(ref.settings());
                    workspaces.restore(workspaces.resolve(ref.workspace()),
                            ref.detached().stream().map(workspaces::find)
                                    .filter(java.util.Objects::nonNull).toList());
                });
        // Opening, closing, activating or saving a UI document and switching workspace change what the
        // .omp records: the project turns dirty, so the session is not lost on exit.
        editor.service().addListener(mainInterface::notifyUiEditorSessionChanged);
        workspaceState.addListener(w -> mainInterface.notifyUiEditorSessionChanged());
    }

    /**
     * The Scene workspace's centre views: the model editor viewport and the Scene Viewer, drawn
     * while the workspace shows; afterwards the front centre tab is noted so a project save can
     * record it.
     */
    private void wireScenePanels() {
        mainInterface.getSceneWorkspaceDock().setPanels(deltaTime -> {
            renderSafely("Viewport", () -> {
                viewportInterface.render();
                viewportInterface.update(deltaTime);
            });
            renderSafely("Scene Viewer", () -> {
                sceneViewerInterface.render();
                sceneViewerInterface.update(deltaTime);
            });
            var sceneState = sceneViewerInterface.getUIState();
            var modelState = viewportInterface.getViewportUIState();
            centerTabTracker.noteFrame(
                    sceneState.isSceneViewVisible(), sceneState.isSceneViewFocused(),
                    modelState.isViewportWindowVisible(), modelState.isViewportFocused());
        });
    }

    private static void renderSafely(String name, Runnable render) {
        try {
            render.run();
        } catch (Exception e) {
            logger.error("Error rendering {}", name, e);
        }
    }

    private void wireCallbacks() {
        mainInterface.setBackToHomeCallback(host::transitionToHomeScreen);
        mainInterface.setExitCallback(host::requestExit);
        mainInterface.setTextureCreatorInterface(textureCreatorInterface);

        // A new/different model: the face documents point at the old model's textures, so they
        // close (no flush, no save); standalone textures stay open. Single callback slot — the
        // animation editor rebind must chain here, not replace the texture editor's.
        mainInterface.getModelOperations().setOnModelChangedCallback(() -> {
            textureCreatorInterface.getController().closeFaceDocuments(
                    com.openmason.main.systems.menus.textureCreator.TextureCreatorController.CloseReason.MODEL_CHANGED);
            if (animationEditor != null && animationEditor.isVisible()
                    && mainInterface.getViewport3D() != null) {
                animationEditor.getController()
                        .onModelChanged(mainInterface.getViewport3D().getPartManager());
            }
        });

        textureCreatorInterface.setBackToHomeCallback(host::transitionToHomeScreen);
        textureCreatorInterface.setPreferencesCallback(mainInterface.getShowPreferencesCallback());
    }

    /** Tools → Animation Editor (and the clip bridge): bound to the model the viewport holds. */
    private void openAnimationEditor() {
        if (animationEditor == null) return;
        animationEditor.openFor(mainInterface.getViewport3D() != null
                ? mainInterface.getViewport3D().getPartManager() : null);
    }

    /**
     * Cross-wiring the animation editor needs beyond its own window: the
     * model save puts the viewport at rest for the write (no preview pose in
     * the .omo), the clip records which model it targets, and the SBE/SBO
     * state editors can hand clips to/from the editor without a file.
     */
    private void wireAnimationEditor() {
        var controller = animationEditor.getController();
        mainInterface.getModelOperations().setAroundSaveHooks(
                () -> { if (animationEditor.isVisible()) controller.suspendPreview(); },
                () -> { if (animationEditor.isVisible()) controller.resumePreview(); });
        controller.setModelRefSupplier(() -> {
            var ms = mainInterface.getModelState();
            if (ms == null) return null;
            String omo = ms.getCurrentOMOFilePath();
            return omo != null ? omo : ms.getCurrentModelPath();
        });
        var bridge = new com.openmason.main.systems.menus.dialogs.AnimationClipBridge(
                controller::exportClipBytes,
                (bytes, label) -> {
                    openAnimationEditor();
                    animationEditor.confirmDiscardThen(() -> controller.importClipBytes(bytes, label));
                },
                () -> controller.state().clip().name());
        if (mainInterface.getSBEEditorWindow() != null) {
            mainInterface.getSBEEditorWindow().setAnimationBridge(bridge);
        }
        if (mainInterface.getSBOEditorWindow() != null) {
            mainInterface.getSBOEditorWindow().setAnimationBridge(bridge);
        }
    }

    private void setWindowHandles() {
        if (window == 0L) {
            throw new IllegalStateException("Window not created");
        }
        viewportInterface.setWindowHandle(window);
        textureCreatorInterface.setWindowHandle(window);
    }
}
