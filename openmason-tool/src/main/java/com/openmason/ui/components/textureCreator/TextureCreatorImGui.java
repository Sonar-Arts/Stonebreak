package com.openmason.ui.components.textureCreator;

import com.openmason.ui.components.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.ui.components.textureCreator.coordinators.FilterCoordinator;
import com.openmason.ui.components.textureCreator.coordinators.PasteCoordinator;
import com.openmason.ui.components.textureCreator.coordinators.ToolCoordinator;
import com.openmason.ui.components.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.components.textureCreator.dialogs.NewTextureDialog;
import com.openmason.ui.components.textureCreator.dialogs.OMTImportDialog;
import com.openmason.ui.components.textureCreator.icons.TextureToolIconManager;
import com.openmason.ui.components.textureCreator.imports.ImportStrategyResolver;
import com.openmason.ui.components.textureCreator.io.DragDropHandler;
import com.openmason.ui.components.textureCreator.keyboard.KeyboardShortcutManager;
import com.openmason.ui.components.textureCreator.keyboard.ShortcutKey;
import com.openmason.ui.components.textureCreator.panels.*;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;
import com.openmason.ui.LogoManager;
import com.openmason.ui.toolbars.TextureEditorToolbarRenderer;
import com.openmason.ui.components.textureCreator.rendering.DialogProcessor;
import com.openmason.ui.components.textureCreator.rendering.MenuBarRenderer;
import com.openmason.ui.components.textureCreator.rendering.WindowedMenuBarRenderer;
import com.openmason.ui.components.textureCreator.rendering.PanelRenderingCoordinator;
import com.openmason.ui.dialogs.AboutDialog;
import com.openmason.ui.dialogs.ExportFormatDialog;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.menus.AboutMenuHandler;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.services.StatusService;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Main UI interface for the Texture Creator tool.
 *
 * SOLID Architecture (Post-Refactoring):
 * This class follows Single Responsibility Principle - it ONLY wires components and delegates.
 *
 * All complex logic extracted into specialized coordinators and renderers:
 *
 * Coordinators (Business Logic):
 * - PasteCoordinator: Paste workflow management
 * - FileOperationsCoordinator: File operation handling
 * - ToolCoordinator: Tool state management + Enter/ESC handlers
 * - KeyboardShortcutManager: Keyboard shortcut routing
 *
 * Renderers (UI):
 * - MenuBarRenderer: Menu bar rendering
 * - PanelRenderingCoordinator: All panel rendering
 * - DialogProcessor: Dialog result processing
 *
 * This Class Only Does:
 * 1. Component wiring (constructor)
 * 2. Shortcut registration (declarative)
 * 3. Delegation to coordinators
 * 4. Drag-drop setup
 * 5. Lifecycle (dispose)
 *
 * Benefits:
 * - 990 lines → ~350 lines (65% reduction!)
 * - True Single Responsibility
 * - Every concern properly isolated
 * - Fully testable components
 * - Easy to extend
 *
 * @author Open Mason Team
 */
public class TextureCreatorImGui {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorImGui.class);
    private static final float ZOOM_FACTOR = 1.2f;

    // Core dependencies
    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final TextureCreatorPreferences preferences;
    private final TextureCreatorWindowState windowState;

    // Coordinators (Business Logic)
    private final PasteCoordinator pasteCoordinator;
    private final KeyboardShortcutManager shortcutManager;
    private final FileOperationsCoordinator fileOperations;
    private final FilterCoordinator filterCoordinator;
    private final ToolCoordinator toolCoordinator;

    // Renderers (UI)
    private final MenuBarRenderer menuBarRenderer;
    private final WindowedMenuBarRenderer windowedMenuBarRenderer;
    private final PanelRenderingCoordinator panelRenderer;
    private final DialogProcessor dialogProcessor;

    // Dialogs
    private final NewTextureDialog newTextureDialog;
    private final ImportPNGDialog importPNGDialog;
    private final OMTImportDialog omtImportDialog;
    private final ExportFormatDialog exportFormatDialog;
    private final AboutDialog aboutDialog;

    // Panels (passed to renderer)
    private final ColorPanel colorPanel;

    // Window handle
    private long windowHandle = 0;

    // Drag-and-drop
    private final DragDropHandler dragDropHandler;
    private GLFWDropCallback dropCallback;

    /**
     * Create texture creator UI with dependency injection.
     * Follows SOLID: Dependency Inversion Principle.
     */
    public TextureCreatorImGui(TextureCreatorState state,
                              TextureCreatorController controller,
                              TextureCreatorPreferences preferences,
                              TextureCreatorWindowState windowState,
                              TextureEditorToolbarRenderer toolbarPanel,
                              ToolOptionsBar toolOptionsBar,
                              CanvasPanel canvasPanel,
                              LayerPanelRenderer layerPanel,
                              ColorPanel colorPanel,
                              PreferencesPanel preferencesPanel,
                              NewTextureDialog newTextureDialog,
                              ImportPNGDialog importPNGDialog,
                              OMTImportDialog omtImportDialog,
                              ExportFormatDialog exportFormatDialog,
                              AboutDialog aboutDialog,
                              FileOperationsCoordinator fileOperations,
                              FilterCoordinator filterCoordinator,
                              ToolCoordinator toolCoordinator,
                              PasteCoordinator pasteCoordinator,
                              KeyboardShortcutManager shortcutManager,
                              MenuBarRenderer menuBarRenderer,
                              WindowedMenuBarRenderer windowedMenuBarRenderer,
                              PanelRenderingCoordinator panelRenderer,
                              DialogProcessor dialogProcessor,
                              DragDropHandler dragDropHandler) {
        this.state = state;
        this.controller = controller;
        this.preferences = preferences;
        this.windowState = windowState;
        this.newTextureDialog = newTextureDialog;
        this.importPNGDialog = importPNGDialog;
        this.omtImportDialog = omtImportDialog;
        this.exportFormatDialog = exportFormatDialog;
        this.aboutDialog = aboutDialog;
        this.fileOperations = fileOperations;
        this.filterCoordinator = filterCoordinator;
        this.toolCoordinator = toolCoordinator;
        this.pasteCoordinator = pasteCoordinator;
        this.shortcutManager = shortcutManager;
        this.menuBarRenderer = menuBarRenderer;
        this.windowedMenuBarRenderer = windowedMenuBarRenderer;
        this.panelRenderer = panelRenderer;
        this.dialogProcessor = dialogProcessor;
        this.dragDropHandler = dragDropHandler;
        this.colorPanel = colorPanel;

        // Wire up dependencies
        toolCoordinator.setDependencies(controller, pasteCoordinator);
        wireUpComponents(toolbarPanel);
        registerKeyboardShortcuts();

        logger.info("Texture Creator UI initialized with coordinator architecture");
    }

    /**
     * Factory method for creating TextureCreatorImGui with default dependencies.
     */
    public static TextureCreatorImGui createDefault() {
        // Create state and controller
        TextureCreatorState state = new TextureCreatorState();
        TextureCreatorController controller = new TextureCreatorController(state);

        // Create preferences and window state
        PreferencesManager preferencesManager = new PreferencesManager();
        TextureCreatorPreferences preferences = new TextureCreatorPreferences(preferencesManager);
        TextureCreatorWindowState windowState = new TextureCreatorWindowState();

        // Create services
        StatusService statusService = new StatusService();
        FileDialogService fileDialogService = new FileDialogService(statusService);

        // Create panels
        TextureEditorToolbarRenderer toolbarPanel = new TextureEditorToolbarRenderer();
        ToolOptionsBar toolOptionsBar = new ToolOptionsBar(preferences);
        CanvasPanel canvasPanel = new CanvasPanel();
        LayerPanelRenderer layerPanel = new LayerPanelRenderer();
        ColorPanel colorPanel = new ColorPanel();
        NoiseFilterPanel noiseFilterPanel = new NoiseFilterPanel();
        PreferencesPanel preferencesPanel = new PreferencesPanel();
        SymmetryPanel symmetryPanel = new SymmetryPanel();

        // Create dialogs
        NewTextureDialog newTextureDialog = new NewTextureDialog();
        ImportPNGDialog importPNGDialog = new ImportPNGDialog();
        OMTImportDialog omtImportDialog = new OMTImportDialog();
        ExportFormatDialog exportFormatDialog = new ExportFormatDialog();

        // Create drag-drop handler
        DragDropHandler dragDropHandler = new DragDropHandler();

        // Create coordinators
        ImportStrategyResolver importResolver = new ImportStrategyResolver();
        ToolCoordinator toolCoordinator = new ToolCoordinator(state, toolbarPanel);
        PasteCoordinator pasteCoordinator = new PasteCoordinator(state, controller, toolCoordinator, preferences);
        FileOperationsCoordinator fileOperations = new FileOperationsCoordinator(
            fileDialogService, controller, state, importResolver);
        FilterCoordinator filterCoordinator = new FilterCoordinator(
            controller, state, controller.getLayerManager(), controller.getCommandHistory());
        KeyboardShortcutManager shortcutManager = new KeyboardShortcutManager();

        // Wire up dependencies to noise filter panel
        noiseFilterPanel.setDependencies(controller.getLayerManager(), controller.getCommandHistory());

        // Create about menu handler and about dialog
        AboutMenuHandler aboutMenuHandler = new AboutMenuHandler(windowState);
        AboutDialog aboutDialog = new AboutDialog(windowState, LogoManager.getInstance(), "Texture Creator");

        // Create renderers
        MenuBarRenderer menuBarRenderer = new MenuBarRenderer(state, controller, fileOperations,
            newTextureDialog, importPNGDialog, exportFormatDialog, null, aboutMenuHandler);
        WindowedMenuBarRenderer windowedMenuBarRenderer = new WindowedMenuBarRenderer(state, controller, fileOperations,
            newTextureDialog, importPNGDialog, exportFormatDialog, null, aboutMenuHandler);
        PanelRenderingCoordinator panelRenderer = new PanelRenderingCoordinator(state, controller, preferences,
            toolCoordinator, windowState, toolbarPanel, toolOptionsBar, canvasPanel, layerPanel, colorPanel, noiseFilterPanel, preferencesPanel, symmetryPanel);
        DialogProcessor dialogProcessor = new DialogProcessor(controller, fileOperations, dragDropHandler,
            newTextureDialog, importPNGDialog, omtImportDialog);

        return new TextureCreatorImGui(
            state, controller, preferences, windowState,
            toolbarPanel, toolOptionsBar, canvasPanel, layerPanel, colorPanel, preferencesPanel,
            newTextureDialog, importPNGDialog, omtImportDialog, exportFormatDialog, aboutDialog,
            fileOperations, filterCoordinator, toolCoordinator, pasteCoordinator, shortcutManager,
            menuBarRenderer, windowedMenuBarRenderer, panelRenderer, dialogProcessor, dragDropHandler
        );
    }

    /**
     * Wire up component dependencies.
     */
    private void wireUpComponents(TextureEditorToolbarRenderer toolbarPanel) {
        colorPanel.setColorHistory(preferences.getColorHistory());
        toolbarPanel.setSelectionManager(state.getSelectionManager());
        toolbarPanel.setPreferences(preferences);
        state.setCurrentTool(toolbarPanel.getCurrentTool());

        // Wire up callbacks for both menu bar renderers
        menuBarRenderer.setOnPreferencesToggle(windowState::togglePreferencesWindow);
        menuBarRenderer.setOnNoiseFilterToggle(windowState::toggleNoiseFilterWindow);
        menuBarRenderer.setOnSymmetryToggle(windowState::toggleSymmetryWindow);
        menuBarRenderer.setOnLayersPanelToggle(windowState::toggleLayersPanel, windowState.getShowLayersPanel());
        menuBarRenderer.setOnColorPanelToggle(windowState::toggleColorPanel, windowState.getShowColorPanel());

        windowedMenuBarRenderer.setOnPreferencesToggle(windowState::togglePreferencesWindow);
        windowedMenuBarRenderer.setOnNoiseFilterToggle(windowState::toggleNoiseFilterWindow);
        windowedMenuBarRenderer.setOnSymmetryToggle(windowState::toggleSymmetryWindow);
        windowedMenuBarRenderer.setOnLayersPanelToggle(windowState::toggleLayersPanel, windowState.getShowLayersPanel());
        windowedMenuBarRenderer.setOnColorPanelToggle(windowState::toggleColorPanel, windowState.getShowColorPanel());

        // Inject SymmetryState into tools that support it
        injectSymmetryStateIntoTools(toolbarPanel);
    }

    /**
     * Inject SymmetryState into all tools that support symmetry.
     */
    private void injectSymmetryStateIntoTools(TextureEditorToolbarRenderer toolbarPanel) {
        // Get all tools from toolbar
        var tools = toolbarPanel.getTools();

        // Inject into tools that have setSymmetryState method (PencilTool, EraserTool, etc.)
        for (var tool : tools) {
            if (tool instanceof com.openmason.ui.components.textureCreator.tools.PencilTool) {
                ((com.openmason.ui.components.textureCreator.tools.PencilTool) tool).setSymmetryState(state.getSymmetryState());
            } else if (tool instanceof com.openmason.ui.components.textureCreator.tools.EraserTool) {
                ((com.openmason.ui.components.textureCreator.tools.EraserTool) tool).setSymmetryState(state.getSymmetryState());
            }
            // Add more tools here as they gain symmetry support
        }
    }

    /**
     * Register keyboard shortcuts (declarative).
     */
    private void registerKeyboardShortcuts() {
        // File operations
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_N), newTextureDialog::show);
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_O), fileOperations::openProject);
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_S), fileOperations::saveProject);
        shortcutManager.register(ShortcutKey.ctrlShift(GLFW.GLFW_KEY_S), fileOperations::saveProjectAs);
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_E), () ->
            exportFormatDialog.show(format -> {
                if (format == ExportFormatDialog.ExportFormat.PNG) fileOperations.exportPNG();
                else if (format == ExportFormatDialog.ExportFormat.OMT) fileOperations.exportOMT();
            }));

        // Window
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_COMMA),
            windowState::togglePreferencesWindow);

        // Edit
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_Z), controller::undo);
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_Y), controller::redo);

        // Clipboard
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_C), () -> {
            if (state.hasSelection()) controller.copySelection();
        });
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_X), () -> {
            if (state.hasSelection()) controller.cutSelection();
        });
        shortcutManager.register(ShortcutKey.ctrl(GLFW.GLFW_KEY_V), () -> {
            if (pasteCoordinator.canPaste()) pasteCoordinator.initiatePaste();
        });

        // Selection
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_DELETE), controller::deleteSelection);
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_BACKSPACE), controller::deleteSelection);

        // View
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_G),
            () -> state.getShowGrid().set(!state.getShowGrid().get()));
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_EQUAL),
            () -> controller.getCanvasState().zoomIn(ZOOM_FACTOR));
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_KP_ADD),
            () -> controller.getCanvasState().zoomIn(ZOOM_FACTOR));
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_MINUS),
            () -> controller.getCanvasState().zoomOut(ZOOM_FACTOR));
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_KP_SUBTRACT),
            () -> controller.getCanvasState().zoomOut(ZOOM_FACTOR));
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_0),
            () -> controller.getCanvasState().resetView());
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_KP_0),
            () -> controller.getCanvasState().resetView());

        // Tool operations (delegated to ToolCoordinator)
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_ENTER), toolCoordinator::handleEnterKey);
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_KP_ENTER), toolCoordinator::handleEnterKey);
        shortcutManager.register(ShortcutKey.simple(GLFW.GLFW_KEY_ESCAPE), toolCoordinator::handleEscapeKey);

        logger.debug("Registered {} keyboard shortcuts", shortcutManager.getShortcutCount());
    }

    /**
     * Set window handle.
     */
    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        menuBarRenderer.setWindowHandle(windowHandle);

        // Set window handle for move tool (enables mouse capture for rotation/dragging)
        var moveTool = toolCoordinator.getMoveTool();
        if (moveTool != null) {
            moveTool.setWindowHandle(windowHandle);
        }

        setupDragDropCallback();
    }

    /**
     * Set callback for returning to home screen.
     */
    public void setBackToHomeCallback(Runnable callback) {
        menuBarRenderer.setBackToHomeCallback(callback);
        windowedMenuBarRenderer.setBackToHomeCallback(callback);
    }

    /**
     * Set callback for opening external preferences window.
     * <p>
     * When set, this callback replaces the internal preferences panel toggle.
     * This allows integration with the unified preferences system.
     * </p>
     *
     * @param callback the callback to invoke when preferences is requested
     */
    public void setPreferencesCallback(Runnable callback) {
        if (callback != null) {
            menuBarRenderer.setOnPreferencesToggle(callback);
            windowedMenuBarRenderer.setOnPreferencesToggle(callback);
            logger.debug("External preferences callback set");
        }
    }

    /**
     * Set up drag-and-drop.
     */
    private void setupDragDropCallback() {
        if (windowHandle == 0) return;

        dropCallback = new GLFWDropCallback() {
            @Override
            public void invoke(long window, int count, long names) {
                String[] filePaths = new String[count];
                for (int i = 0; i < count; i++) {
                    filePaths[i] = GLFWDropCallback.getName(names, i);
                }
                handleDroppedFiles(filePaths);
            }
        };

        GLFW.glfwSetDropCallback(windowHandle, dropCallback);
    }

    /**
     * Handle dropped files.
     */
    private void handleDroppedFiles(String[] filePaths) {
        if (filePaths == null || filePaths.length == 0) return;

        List<String> pngFiles = new ArrayList<>();
        List<String> omtFiles = new ArrayList<>();

        for (String filePath : filePaths) {
            String ext = getFileExtension(filePath).toLowerCase();
            if (ext.equals("png")) pngFiles.add(filePath);
            else if (ext.equals("omt")) omtFiles.add(filePath);
        }

        if (!pngFiles.isEmpty()) {
            int successCount = dragDropHandler.processDroppedPNGFiles(
                pngFiles.toArray(new String[0]), controller.getLayerManager());
            if (successCount > 0) {
                controller.notifyLayerModified();

                // Get the bounds of the imported image and create a selection
                Rectangle importedBounds = dragDropHandler.getLastImportedBounds();
                if (importedBounds != null) {
                    // Create a rectangular selection matching the imported image bounds
                    RectangularSelection selection = new RectangularSelection(
                        importedBounds.x,
                        importedBounds.y,
                        importedBounds.x + importedBounds.width - 1,
                        importedBounds.y + importedBounds.height - 1
                    );
                    controller.getState().setCurrentSelection(selection);

                    // Automatically activate move tool to allow repositioning the imported image
                    toolCoordinator.switchToMoveTool();

                    // Setup move session (not paste) so transform handles work and hole is created
                    // Use setupMoveSession() instead of setupPasteSession() because imported images
                    // should leave a hole at the original location when moved (like regular move operations)
                    var moveTool = toolCoordinator.getMoveTool();
                    if (moveTool != null) {
                        moveTool.setupMoveSession(controller.getActiveLayerCanvas(), selection);
                    }
                }
            }
        }

        if (!omtFiles.isEmpty()) {
            omtImportDialog.show(omtFiles.get(0));
        }
    }

    private String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        return (lastDot == -1 || lastDot == filePath.length() - 1) ? "" : filePath.substring(lastDot + 1);
    }

    /**
     * Render (delegates to coordinators and renderers).
     */
    public void render() {
        shortcutManager.handleInput();
        menuBarRenderer.render();
        panelRenderer.renderToolOptionsBar();
        panelRenderer.renderDockSpace();
        panelRenderer.renderAllPanels();
        renderDialogs();

        // Render closeable windows (visibility managed by windowState)
        panelRenderer.renderPreferencesWindow();
        panelRenderer.renderNoiseFilterWindow();
        panelRenderer.renderSymmetryWindow();

        dialogProcessor.processAll();
    }

    /**
     * Render in windowed mode (for use inside TextureEditorWindow).
     * Skips fullscreen dockspace and main menu bar, uses windowed equivalents.
     */
    public void renderWindowed() {
        shortcutManager.handleInput();
        renderWindowedMenuBar();
        renderWindowedToolbar();
        // Note: Parent window creates dockspace between toolbar and panels
        renderWindowedPanels();
    }

    /**
     * Render windowed menu bar (for use at top of TextureEditorWindow).
     */
    public void renderWindowedMenuBar() {
        windowedMenuBarRenderer.render();
    }

    /**
     * Render windowed toolbar (for use below menu bar in TextureEditorWindow).
     */
    public void renderWindowedToolbar() {
        panelRenderer.renderToolOptionsBar();
    }

    /**
     * Render windowed panels and dialogs (for use below dockspace in TextureEditorWindow).
     */
    public void renderWindowedPanels() {
        panelRenderer.renderAllPanels();
        renderDialogs();

        // Render closeable windows
        panelRenderer.renderPreferencesWindow();
        panelRenderer.renderNoiseFilterWindow();
        panelRenderer.renderSymmetryWindow();

        dialogProcessor.processAll();
    }

    /**
     * Render dialogs.
     */
    private void renderDialogs() {
        newTextureDialog.render();
        importPNGDialog.render();
        omtImportDialog.render();
        exportFormatDialog.render();
        aboutDialog.render();
    }

    public TextureCreatorState getState() {
        return state;
    }

    public TextureCreatorController getController() {
        return controller;
    }

    /**
     * Gets the texture creator preferences instance.
     * <p>
     * This allows external systems (like UnifiedPreferencesWindow) to access
     * the same preferences instance for real-time updates.
     * </p>
     *
     * @return the texture creator preferences
     */
    public TextureCreatorPreferences getPreferences() {
        return preferences;
    }

    /**
     * Set windowed mode flag for panel renderer.
     * When true, the panel renderer will skip creating its own fullscreen dockspace.
     *
     * @param windowedMode true to enable windowed mode, false for fullscreen mode
     */
    public void setWindowedMode(boolean windowedMode) {
        if (panelRenderer != null) {
            panelRenderer.setWindowedMode(windowedMode);
        }
    }

    /**
     * Cleanup.
     */
    public void dispose() {
        preferences.setColorHistory(colorPanel.getColorHistory());
        if (dropCallback != null) {
            dropCallback.free();
        }
        TextureToolIconManager.getInstance().dispose();
        logger.info("Texture Creator UI disposed");
    }
}
