package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.main.systems.menus.textureCreator.coordinators.PasteCoordinator;
import com.openmason.main.systems.menus.textureCreator.coordinators.ToolCoordinator;
import com.openmason.main.systems.menus.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.main.systems.menus.textureCreator.dialogs.NewTextureDialog;
import com.openmason.main.systems.menus.textureCreator.dialogs.OMTImportDialog;
import com.openmason.main.systems.menus.textureCreator.icons.TextureToolIconManager;
import com.openmason.main.systems.menus.textureCreator.imports.ImportStrategyResolver;
import com.openmason.main.systems.menus.textureCreator.io.DragDropHandler;
import com.openmason.main.systems.menus.textureCreator.keyboard.KeyboardShortcutManager;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import com.openmason.main.systems.menus.textureCreator.panels.*;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.textureCreator.tools.EraserTool;
import com.openmason.main.systems.menus.textureCreator.tools.PencilTool;
import com.openmason.main.systems.menus.toolbars.TextureEditorToolbarRenderer;
import com.openmason.main.systems.menus.textureCreator.rendering.DialogProcessor;
import com.openmason.main.systems.menus.textureCreator.rendering.MenuBarRenderer;
import com.openmason.main.systems.menus.textureCreator.rendering.WindowedMenuBarRenderer;
import com.openmason.main.systems.menus.textureCreator.rendering.PanelRenderingCoordinator;
import com.openmason.main.systems.menus.dialogs.AboutDialog;
import com.openmason.main.systems.menus.dialogs.ExportFormatDialog;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.AboutMenuHandler;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.services.StatusService;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Main UI interface for the Texture Creator tool.
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

    // Drag-and-drop handler for processing dropped files
    private final DragDropHandler dragDropHandler;

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
                              NewTextureDialog newTextureDialog,
                              ImportPNGDialog importPNGDialog,
                              OMTImportDialog omtImportDialog,
                              ExportFormatDialog exportFormatDialog,
                              AboutDialog aboutDialog,
                              FileOperationsCoordinator fileOperations,
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
        KeyboardShortcutManager shortcutManager = new KeyboardShortcutManager();

        // Wire up dependencies to noise filter panel
        // Use lambda providers to avoid stale references when LayerManager is replaced (e.g., loading OMT files)
        noiseFilterPanel.setDependencies(controller::getLayerManager, controller::getCommandHistory);

        // Create about menu handler and about dialog
        AboutMenuHandler aboutMenuHandler = new AboutMenuHandler(windowState);
        AboutDialog aboutDialog = new AboutDialog(windowState, LogoManager.getInstance(), "Texture Creator");

        // Create renderers
        MenuBarRenderer menuBarRenderer = new MenuBarRenderer(state, controller, fileOperations,
            newTextureDialog, importPNGDialog, exportFormatDialog, null, aboutMenuHandler);
        WindowedMenuBarRenderer windowedMenuBarRenderer = new WindowedMenuBarRenderer(state, controller, fileOperations,
            newTextureDialog, importPNGDialog, exportFormatDialog, null, aboutMenuHandler);
        PanelRenderingCoordinator panelRenderer = new PanelRenderingCoordinator(state, controller, preferences,
            toolCoordinator, windowState, toolbarPanel, toolOptionsBar, canvasPanel, layerPanel, colorPanel, noiseFilterPanel, symmetryPanel);
        DialogProcessor dialogProcessor = new DialogProcessor(controller, fileOperations, dragDropHandler,
            newTextureDialog, importPNGDialog, omtImportDialog);

        return new TextureCreatorImGui(
            state, controller, preferences, windowState,
            toolbarPanel, toolOptionsBar, canvasPanel, layerPanel, colorPanel,
            newTextureDialog, importPNGDialog, omtImportDialog, exportFormatDialog, aboutDialog,
            fileOperations, toolCoordinator, pasteCoordinator, shortcutManager,
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
            if (tool instanceof PencilTool) {
                ((PencilTool) tool).setSymmetryState(state.getSymmetryState());
            } else if (tool instanceof EraserTool) {
                ((EraserTool) tool).setSymmetryState(state.getSymmetryState());
            }
            // Add more tools here as they gain symmetry support
        }
    }

    /**
     * Register keyboard shortcuts (declarative).
     * Now uses the central KeybindRegistry for customizable keybinds.
     */
    private void registerKeyboardShortcuts() {
        // Register all texture editor actions with the central keybind registry
        com.openmason.main.systems.keybinds.KeybindRegistry registry =
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

        TextureEditorKeybindActions.registerAll(
                registry,
                newTextureDialog,
                fileOperations,
                exportFormatDialog,
                windowState,
                controller,
                state,
                pasteCoordinator,
                toolCoordinator
        );

        logger.debug("Registered texture editor keyboard shortcuts with keybind registry");
    }

    /**
     * Set window handle.
     */
    public void setWindowHandle(long windowHandle) {
        // Window handle
        menuBarRenderer.setWindowHandle(windowHandle);

        // Set window handle for move tool (enables mouse capture for rotation/dragging)
        var moveTool = toolCoordinator.getMoveTool();
        if (moveTool != null) {
            moveTool.setWindowHandle(windowHandle);
        }

        // Note: Drop callbacks are now managed globally by ViewportDropCallbackManager
        // which registers callbacks on ALL ImGui platform windows (including floating windows)
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
     * Process dropped files (PNG and OMT).
     * Called from TextureEditorWindow when processing pending file drops.
     *
     * @param filePaths array of file paths to process
     */
    public void processDroppedFiles(String[] filePaths) {
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
        panelRenderer.renderNoiseFilterWindow();
        panelRenderer.renderSymmetryWindow();

        dialogProcessor.processAll();
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
     * Handle keyboard shortcuts only (no rendering).
     * Should be called by parent windows to process shortcuts when the window has focus.
     * This allows proper focus-based shortcut handling in windowed mode.
     */
    public void handleKeyboardShortcuts() {
        // Use the central keybind registry for customizable shortcuts
        com.openmason.main.systems.keybinds.KeybindRegistry registry =
                com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

        // Check all texture editor categories for matching keybinds
        String[] categories = {"File Operations", "Window", "Edit", "Clipboard",
                               "Selection", "View", "Tools"};

        for (String category : categories) {
            for (com.openmason.main.systems.keybinds.KeybindAction action :
                    registry.getActionsByCategory(category)) {
                com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey key =
                        registry.getKeybind(action.getId());
                if (key.isPressed()) {
                    action.execute();
                    return; // Only execute first matching shortcut
                }
            }
        }
    }

    /**
     * Cleanup.
     */
    public void dispose() {
        preferences.setColorHistory(colorPanel.getColorHistory());
        TextureToolIconManager.getInstance().dispose();
        logger.info("Texture Creator UI disposed");
    }
}
