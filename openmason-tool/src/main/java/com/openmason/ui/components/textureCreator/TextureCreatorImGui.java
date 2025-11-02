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
import com.openmason.ui.components.textureCreator.rendering.DialogProcessor;
import com.openmason.ui.components.textureCreator.rendering.MenuBarRenderer;
import com.openmason.ui.components.textureCreator.rendering.PanelRenderingCoordinator;
import com.openmason.ui.dialogs.ExportFormatDialog;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.services.StatusService;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Coordinators (Business Logic)
    private final PasteCoordinator pasteCoordinator;
    private final KeyboardShortcutManager shortcutManager;
    private final FileOperationsCoordinator fileOperations;
    private final FilterCoordinator filterCoordinator;
    private final ToolCoordinator toolCoordinator;

    // Renderers (UI)
    private final MenuBarRenderer menuBarRenderer;
    private final PanelRenderingCoordinator panelRenderer;
    private final DialogProcessor dialogProcessor;

    // Dialogs
    private final NewTextureDialog newTextureDialog;
    private final ImportPNGDialog importPNGDialog;
    private final OMTImportDialog omtImportDialog;
    private final ExportFormatDialog exportFormatDialog;

    // Panels (passed to renderer)
    private final ColorPanel colorPanel;

    // Window state
    private boolean showPreferencesWindow = false;
    private boolean showNoiseFilterWindow = false;
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
                              ToolbarPanel toolbarPanel,
                              ToolOptionsBar toolOptionsBar,
                              CanvasPanel canvasPanel,
                              LayerPanelRenderer layerPanel,
                              ColorPanel colorPanel,
                              PreferencesPanel preferencesPanel,
                              NewTextureDialog newTextureDialog,
                              ImportPNGDialog importPNGDialog,
                              OMTImportDialog omtImportDialog,
                              ExportFormatDialog exportFormatDialog,
                              FileOperationsCoordinator fileOperations,
                              FilterCoordinator filterCoordinator,
                              ToolCoordinator toolCoordinator,
                              PasteCoordinator pasteCoordinator,
                              KeyboardShortcutManager shortcutManager,
                              MenuBarRenderer menuBarRenderer,
                              PanelRenderingCoordinator panelRenderer,
                              DialogProcessor dialogProcessor,
                              DragDropHandler dragDropHandler) {
        this.state = state;
        this.controller = controller;
        this.preferences = preferences;
        this.newTextureDialog = newTextureDialog;
        this.importPNGDialog = importPNGDialog;
        this.omtImportDialog = omtImportDialog;
        this.exportFormatDialog = exportFormatDialog;
        this.fileOperations = fileOperations;
        this.filterCoordinator = filterCoordinator;
        this.toolCoordinator = toolCoordinator;
        this.pasteCoordinator = pasteCoordinator;
        this.shortcutManager = shortcutManager;
        this.menuBarRenderer = menuBarRenderer;
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

        // Create preferences
        PreferencesManager preferencesManager = new PreferencesManager();
        TextureCreatorPreferences preferences = new TextureCreatorPreferences(preferencesManager);

        // Create services
        StatusService statusService = new StatusService();
        FileDialogService fileDialogService = new FileDialogService(statusService);

        // Create panels
        ToolbarPanel toolbarPanel = new ToolbarPanel();
        ToolOptionsBar toolOptionsBar = new ToolOptionsBar();
        CanvasPanel canvasPanel = new CanvasPanel();
        LayerPanelRenderer layerPanel = new LayerPanelRenderer();
        ColorPanel colorPanel = new ColorPanel();
        NoiseFilterPanel noiseFilterPanel = new NoiseFilterPanel();
        PreferencesPanel preferencesPanel = new PreferencesPanel();

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

        // Create renderers
        MenuBarRenderer menuBarRenderer = new MenuBarRenderer(state, controller, fileOperations,
            newTextureDialog, importPNGDialog, exportFormatDialog);
        PanelRenderingCoordinator panelRenderer = new PanelRenderingCoordinator(state, controller, preferences,
            toolCoordinator, toolbarPanel, toolOptionsBar, canvasPanel, layerPanel, colorPanel, noiseFilterPanel, preferencesPanel);
        DialogProcessor dialogProcessor = new DialogProcessor(controller, fileOperations, dragDropHandler,
            newTextureDialog, importPNGDialog, omtImportDialog);

        return new TextureCreatorImGui(
            state, controller, preferences,
            toolbarPanel, toolOptionsBar, canvasPanel, layerPanel, colorPanel, preferencesPanel,
            newTextureDialog, importPNGDialog, omtImportDialog, exportFormatDialog,
            fileOperations, filterCoordinator, toolCoordinator, pasteCoordinator, shortcutManager,
            menuBarRenderer, panelRenderer, dialogProcessor, dragDropHandler
        );
    }

    /**
     * Wire up component dependencies.
     */
    private void wireUpComponents(ToolbarPanel toolbarPanel) {
        colorPanel.setColorHistory(preferences.getColorHistory());
        toolbarPanel.setSelectionManager(state.getSelectionManager());
        toolbarPanel.setPreferences(preferences);
        state.setCurrentTool(toolbarPanel.getCurrentTool());
        menuBarRenderer.setOnPreferencesToggle(() -> showPreferencesWindow = !showPreferencesWindow);
        menuBarRenderer.setOnNoiseFilterToggle(() -> showNoiseFilterWindow = !showNoiseFilterWindow);
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
            () -> showPreferencesWindow = !showPreferencesWindow);

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
        setupDragDropCallback();
    }

    /**
     * Set callback for returning to home screen.
     */
    public void setBackToHomeCallback(Runnable callback) {
        menuBarRenderer.setBackToHomeCallback(callback);
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
            List<String> errors = dragDropHandler.processDroppedPNGFiles(
                pngFiles.toArray(new String[0]), controller.getLayerManager());
            if (errors.isEmpty() || errors.size() < pngFiles.size()) {
                controller.notifyLayerModified();
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

        if (showPreferencesWindow) {
            panelRenderer.renderPreferencesWindow(showPreferencesWindow);
        }

        if (showNoiseFilterWindow) {
            panelRenderer.renderNoiseFilterWindow();
        }

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
    }

    public TextureCreatorState getState() {
        return state;
    }

    public TextureCreatorController getController() {
        return controller;
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
