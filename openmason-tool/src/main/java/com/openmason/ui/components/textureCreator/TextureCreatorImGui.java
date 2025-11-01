package com.openmason.ui.components.textureCreator;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.components.textureCreator.dialogs.NewTextureDialog;
import com.openmason.ui.components.textureCreator.icons.TextureToolIconManager;
import com.openmason.ui.components.textureCreator.panels.*;
import com.openmason.ui.dialogs.ExportFormatDialog;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.services.StatusService;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main UI interface for the Texture Creator tool.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only handles UI rendering
 * - Delegates business logic to TextureCreatorController
 *
 * @author Open Mason Team
 */
public class TextureCreatorImGui {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorImGui.class);

    // Dependencies
    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final FileDialogService fileDialogService;
    private final TextureCreatorPreferences preferences;

    // UI Panels
    private final ToolbarPanel toolbarPanel;
    private final ToolOptionsBar toolOptionsBar;
    private final CanvasPanel canvasPanel;
    private final LayerPanelRenderer layerPanel;
    private final ColorPanel colorPanel;
    private final PreferencesPanel preferencesPanel;

    // Dialogs
    private final NewTextureDialog newTextureDialog;
    private final ImportPNGDialog importPNGDialog;
    private final ExportFormatDialog exportFormatDialog;

    // Window visibility flags
    private boolean showPreferencesWindow = false;

    // Pending import state (stores file path while dialog is open)
    private String pendingImportPath = null;

    // Floating paste layer tracking (for non-destructive paste preview)
    private Integer floatingPasteLayerIndex = null; // Index of temporary paste layer, null if none
    private int originalActiveLayerIndex = -1; // Original active layer before paste
    private com.openmason.ui.components.textureCreator.tools.DrawingTool toolBeforePaste = null; // Tool to restore after paste

    // Callback for returning to Home screen
    private Runnable backToHomeCallback;

    /**
     * Create texture creator UI.
     */
    public TextureCreatorImGui() {
        this.state = new TextureCreatorState();
        this.controller = new TextureCreatorController(state);

        // Initialize preferences with persistence
        PreferencesManager preferencesManager = new PreferencesManager();
        this.preferences = new TextureCreatorPreferences(preferencesManager);

        // Initialize file dialog service
        StatusService statusService = new StatusService();
        this.fileDialogService = new FileDialogService(statusService);

        // Initialize dialogs
        this.newTextureDialog = new NewTextureDialog();
        this.importPNGDialog = new ImportPNGDialog();
        this.exportFormatDialog = new ExportFormatDialog();

        // Initialize panels
        this.toolbarPanel = new ToolbarPanel();
        this.toolOptionsBar = new ToolOptionsBar();
        this.canvasPanel = new CanvasPanel();
        this.layerPanel = new LayerPanelRenderer();
        this.colorPanel = new ColorPanel();
        this.preferencesPanel = new PreferencesPanel();

        // Load color history from preferences
        colorPanel.setColorHistory(preferences.getColorHistory());

        // Wire up SelectionManager across components
        toolbarPanel.setSelectionManager(state.getSelectionManager());
        logger.debug("Selection manager wired to toolbar panel (move tool)");

        // Wire up preferences to toolbar (for move tool rotation speed, etc.)
        toolbarPanel.setPreferences(preferences);
        logger.debug("Preferences wired to toolbar panel (move tool)");

        // Set default tool
        state.setCurrentTool(toolbarPanel.getCurrentTool());

        logger.info("Texture Creator UI initialized with preferences persistence, color history, and selection management");
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This is required for infinite dragging in the move tool.
     *
     * @param windowHandle the GLFW window handle
     */
    public void setWindowHandle(long windowHandle) {
        toolbarPanel.setWindowHandle(windowHandle);
        logger.debug("Window handle set for texture creator (mouse capture enabled)");
    }

    /**
     * Render the texture creator interface.
     * Called every frame.
     */
    public void render() {
        // Handle keyboard shortcuts first (before rendering)
        handleKeyboardShortcuts();

        // Render main menu bar
        renderMenuBar();

        // Render tool options toolbar (below menu bar)
        renderToolOptionsBar();

        // Render dockspace container
        renderDockSpace();

        // Render all tool windows (they will dock into the dockspace)
        renderToolsPanel();
        renderCanvasPanel();
        renderLayersPanel();
        renderColorPanel();

        // Render preferences window if visible
        if (showPreferencesWindow) {
            renderPreferencesWindow();
        }

        // Render dialogs
        newTextureDialog.render();
        importPNGDialog.render();
        exportFormatDialog.render();

        // Check for confirmed new texture selection
        TextureCreatorState.CanvasSize selectedSize = newTextureDialog.getSelectedCanvasSize();
        if (selectedSize != null) {
            controller.newTexture(selectedSize);
        }

        // Check for confirmed import PNG selection
        TextureCreatorState.CanvasSize importSize = importPNGDialog.getSelectedCanvasSize();
        if (importSize != null && pendingImportPath != null) {
            boolean success = controller.importTexture(pendingImportPath, importSize);
            if (success) {
                logger.info("Successfully imported PNG: {} to {} canvas", pendingImportPath, importSize.getDisplayName());
            } else {
                logger.error("Failed to import PNG: {}", pendingImportPath);
            }
            pendingImportPath = null; // Clear pending path
        }
    }

    /**
     * Render main docking space container.
     * Follows the same pattern as MainImGuiInterface.
     * Adjusts positioning to account for tool options toolbar.
     */
    private void renderDockSpace() {
        int windowFlags = ImGuiWindowFlags.NoDocking;

        ImGuiViewport viewport = ImGui.getMainViewport();

        // Calculate toolbar height (0 if not visible)
        float toolbarHeight = toolOptionsBar.getHeight(state.getCurrentTool());

        // Position dockspace below menu bar and toolbar
        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY() + toolbarHeight);
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY() - toolbarHeight);
        ImGui.setNextWindowViewport(viewport.getID());

        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        windowFlags |= ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove |
                ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;

        ImGui.begin("TextureCreatorDockspace", windowFlags);
        ImGui.popStyleVar(3);

        int dockspaceId = ImGui.getID("TextureCreatorDockSpace");
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);

        ImGui.end();
    }

    /**
     * Render tools panel window.
     */
    private void renderToolsPanel() {
        if (ImGui.begin("Tools")) {
            toolbarPanel.render();

            // Update current tool in state from toolbar selection
            // Note: No special handling needed anymore since paste uses move tool
            if (toolbarPanel.getCurrentTool() != state.getCurrentTool()) {
                state.setCurrentTool(toolbarPanel.getCurrentTool());
            }
        }
        ImGui.end();
    }

    /**
     * Render canvas panel window.
     */
    private void renderCanvasPanel() {
        if (ImGui.begin("Canvas")) {
            PixelCanvas compositedCanvas = controller.getCompositedCanvas();
            PixelCanvas activeCanvas = controller.getActiveLayerCanvas();
            PixelCanvas backgroundCanvas = controller.getBackgroundCanvas();  // All layers except active

            // Wire up SelectionManager to active canvas
            if (activeCanvas != null) {
                activeCanvas.setSelectionManager(state.getSelectionManager());
            }
            if (compositedCanvas != null) {
                compositedCanvas.setSelectionManager(state.getSelectionManager());
            }

            if (compositedCanvas != null) {
                // Display composited view, but draw on active layer
                // Pass backgroundCanvas for multi-layer transform preview
                canvasPanel.render(compositedCanvas, activeCanvas, backgroundCanvas,
                                 controller.getCanvasState(),
                                 state.getCurrentTool(), colorPanel.getCurrentColor(),
                                 state.getCurrentSelection(),  // Current selection region
                                 state.getShowGrid().get(),
                                 preferences.getGridOpacity(),
                                 preferences.getCubeNetOverlayOpacity(),
                                 controller.getCommandHistory(),
                                 controller::notifyLayerModified,
                                 colorPanel::setColor,  // Color picker callback
                                 (color) -> {  // Color used callback
                                     colorPanel.addColorToHistory(color);
                                     preferences.setColorHistory(colorPanel.getColorHistory());
                                 },
                                 state::setCurrentSelection);  // Selection created callback
            } else {
                ImGui.text("No layers");
            }
        }
        ImGui.end();
    }

    /**
     * Render layers panel window.
     */
    private void renderLayersPanel() {
        if (ImGui.begin("Layers")) {
            layerPanel.render(controller.getLayerManager(), controller.getCommandHistory());
        }
        ImGui.end();
    }

    /**
     * Render color panel window.
     */
    private void renderColorPanel() {
        if (ImGui.begin("Color")) {
            colorPanel.render();

            // Update color in state
            state.setCurrentColor(colorPanel.getCurrentColor());
        }
        ImGui.end();
    }

    /**
     * Render preferences window.
     */
    private void renderPreferencesWindow() {
        ImBoolean isOpen = new ImBoolean(showPreferencesWindow);

        if (ImGui.begin("Preferences", isOpen)) {
            preferencesPanel.render(preferences);
        }
        ImGui.end();

        // Update visibility flag
        showPreferencesWindow = isOpen.get();
    }

    /**
     * Render tool options toolbar.
     * Only visible when the current tool has options to display.
     */
    private void renderToolOptionsBar() {
        toolOptionsBar.render(state.getCurrentTool());
    }

    /**
     * Render menu bar.
     */
    private void renderMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                if (ImGui.menuItem("New", "Ctrl+N")) {
                    newTextureDialog.show();
                }
                if (ImGui.menuItem("Open Project...", "Ctrl+O")) {
                    handleOpenProject();
                }
                if (ImGui.menuItem("Save Project", "Ctrl+S")) {
                    handleSaveProject();
                }
                if (ImGui.menuItem("Save Project As...", "Ctrl+Shift+S")) {
                    handleSaveProjectAs();
                }
                ImGui.separator();
                if (ImGui.menuItem("Import PNG...")) {
                    handleImportPNG();
                }
                if (ImGui.beginMenu("Export")) {
                    if (ImGui.menuItem("Export...", "Ctrl+E")) {
                        handleExport();
                    }
                    if (ImGui.menuItem("Export as PNG...")) {
                        handleExportPNG();
                    }
                    if (ImGui.menuItem("Export as OMT...")) {
                        handleExportOMT();
                    }
                    ImGui.endMenu();
                }
                ImGui.separator();
                if (ImGui.menuItem("Home Screen")) {
                    if (backToHomeCallback != null) {
                        backToHomeCallback.run();
                    }
                }
                ImGui.separator();
                if (ImGui.menuItem("Exit")) {
                    // TODO: Handle exit
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("Edit")) {
                boolean canUndo = controller.getCommandHistory().canUndo();
                boolean canRedo = controller.getCommandHistory().canRedo();
                boolean hasSelection = state.getCurrentSelection() != null && !state.getCurrentSelection().isEmpty();

                if (ImGui.menuItem("Undo", "Ctrl+Z", false, canUndo)) {
                    controller.undo();
                }
                if (ImGui.menuItem("Redo", "Ctrl+Y", false, canRedo)) {
                    controller.redo();
                }
                ImGui.separator();
                if (ImGui.menuItem("Delete Selection", "Del", false, hasSelection)) {
                    controller.deleteSelection();
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("View")) {
                ImGui.menuItem("Grid", "G", state.getShowGrid());

                ImGui.separator();

                if (ImGui.menuItem("Zoom In", "+")) {
                    controller.getCanvasState().zoomIn(1.2f);
                }
                if (ImGui.menuItem("Zoom Out", "-")) {
                    controller.getCanvasState().zoomOut(1.2f);
                }
                if (ImGui.menuItem("Reset View", "0")) {
                    controller.getCanvasState().resetView();
                }

                ImGui.endMenu();
            }

            // Preferences button - standalone for easy access
            // Style it to look like a menu item (flat appearance)
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();

            // Apply flat styling to match menu items
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.40f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.26f, 0.59f, 0.98f, 1.0f);

            if (ImGui.button("Preferences")) {
                showPreferencesWindow = !showPreferencesWindow;
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Open preferences window (Ctrl+,)");
            }

            ImGui.popStyleColor(3);

            // Status info on right side
            ImGui.sameLine(ImGui.getWindowSizeX() - 300);
            ImGui.text(String.format("Canvas: %s | Zoom: %.0f%%",
                                    state.getCurrentCanvasSize().getDisplayName(),
                                    controller.getCanvasState().getZoomLevel() * 100));

            ImGui.endMainMenuBar();
        }
    }

    /**
     * Update texture creator state.
     * @param deltaTime time since last frame
     */
    public void update(float deltaTime) {
        // Handle keyboard shortcuts in render() now to avoid double calls
        // (This method kept for API compatibility but doesn't call handleKeyboardShortcuts)
    }

    /**
     * Handle keyboard shortcuts.
     */
    private void handleKeyboardShortcuts() {
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        boolean shift = ImGui.getIO().getKeyShift();

        // File operations
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_O)) {
            handleOpenProject();
        }
        if (ctrl && shift && ImGui.isKeyPressed(GLFW.GLFW_KEY_S)) {
            handleSaveProjectAs();
        } else if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_S)) {
            handleSaveProject();
        }
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_E)) {
            handleExport();
        }

        // Preferences
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_COMMA)) {
            showPreferencesWindow = !showPreferencesWindow;
        }

        // Undo/Redo
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_Z)) {
            controller.undo();
        }
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_Y)) {
            controller.redo();
        }

        // Clipboard operations
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_C)) {
            if (state.hasSelection()) {
                controller.copySelection();
                logger.info("Keyboard shortcut: Copy (Ctrl+C)");
            }
        }
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_X)) {
            if (state.hasSelection()) {
                controller.cutSelection();
                logger.info("Keyboard shortcut: Cut (Ctrl+X)");
            }
        }
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_V)) {
            if (controller.canPaste()) {
                activatePasteTool();
                logger.info("Keyboard shortcut: Paste (Ctrl+V)");
            }
        }

        // Grid toggle
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_G)) {
            state.getShowGrid().set(!state.getShowGrid().get());
        }

        // Enter key: Commit move tool transformation or paste
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ENTER) || ImGui.isKeyPressed(GLFW.GLFW_KEY_KP_ENTER)) {
            if (hasFloatingPasteLayer()) {
                // Commit floating paste layer
                var moveTool = toolbarPanel.getMoveToolInstance();
                if (moveTool != null && moveTool.getPendingCommand() != null) {
                    // Execute the move transformation command first
                    var pendingCmd = moveTool.getPendingCommand();
                    if (pendingCmd.hasChanges()) {
                        controller.getCommandHistory().executeCommand(pendingCmd);
                        moveTool.clearPendingCommand();
                        // CRITICAL: Notify layer modified to trigger immediate visual update
                        controller.notifyLayerModified();
                    }
                }
                commitFloatingPasteLayer();
                logger.info("Keyboard shortcut: Commit paste (Enter)");
            } else if (toolbarPanel.getCurrentTool() instanceof com.openmason.ui.components.textureCreator.tools.move.MoveToolController) {
                // Regular move tool commit
                var moveTool = (com.openmason.ui.components.textureCreator.tools.move.MoveToolController) toolbarPanel.getCurrentTool();
                if (moveTool.isActive() && moveTool.getPendingCommand() != null) {
                    var pendingCmd = moveTool.getPendingCommand();
                    if (pendingCmd.hasChanges()) {
                        controller.getCommandHistory().executeCommand(pendingCmd);
                        var transformedSelection = pendingCmd.getTransformedSelection();
                        if (transformedSelection != null) {
                            state.setCurrentSelection(transformedSelection);
                        }
                        moveTool.clearPendingCommand();
                        moveTool.reset();

                        // CRITICAL: Notify layer modified to trigger immediate visual update
                        controller.notifyLayerModified();

                        // Switch to grabber tool after committing move
                        for (var tool : toolbarPanel.getTools()) {
                            if (tool instanceof com.openmason.ui.components.textureCreator.tools.grabber.GrabberTool) {
                                toolbarPanel.setCurrentTool(tool);
                                break;
                            }
                        }

                        logger.info("Keyboard shortcut: Commit move (Enter) - switched to Grabber tool");
                    }
                }
            }
        }

        // ESC key: Cancel move tool transformation, paste, or clear selection
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (hasFloatingPasteLayer()) {
                // Cancel floating paste layer
                var moveTool = toolbarPanel.getMoveToolInstance();
                if (moveTool != null) {
                    if (moveTool.isMouseCaptured()) {
                        moveTool.forceReleaseMouse();
                    }
                    moveTool.cancelAndReset(controller.getActiveLayerCanvas());
                }
                cancelFloatingPasteLayer();
                logger.info("Keyboard shortcut: Cancel paste (ESC)");
            } else if (toolbarPanel.getCurrentTool() instanceof com.openmason.ui.components.textureCreator.tools.move.MoveToolController) {
                // Regular move tool cancel
                var moveTool = (com.openmason.ui.components.textureCreator.tools.move.MoveToolController) toolbarPanel.getCurrentTool();
                if (moveTool.isMouseCaptured()) {
                    moveTool.forceReleaseMouse();
                    logger.info("Keyboard shortcut: Release mouse capture (ESC)");
                } else if (moveTool.isActive()) {
                    moveTool.cancelAndReset(controller.getActiveLayerCanvas());
                    state.clearSelection();
                    logger.info("Keyboard shortcut: Cancel move (ESC)");
                }
            } else {
                // Just clear selection
                state.clearSelection();
            }
        }
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_DELETE) || ImGui.isKeyPressed(GLFW.GLFW_KEY_BACKSPACE)) {
            controller.deleteSelection();
        }

        // Zoom
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_EQUAL) || ImGui.isKeyPressed(GLFW.GLFW_KEY_KP_ADD)) {
            controller.getCanvasState().zoomIn(1.2f);
        }
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_MINUS) || ImGui.isKeyPressed(GLFW.GLFW_KEY_KP_SUBTRACT)) {
            controller.getCanvasState().zoomOut(1.2f);
        }
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_0) || ImGui.isKeyPressed(GLFW.GLFW_KEY_KP_0)) {
            controller.getCanvasState().resetView();
        }
    }

    /**
     * Handle opening project file (.OMT).
     */
    private void handleOpenProject() {
        fileDialogService.showOpenOMTDialog(filePath -> {
            logger.info("Opening project file: {}", filePath);
            boolean success = controller.loadProject(filePath);
            if (success) {
                logger.info("Successfully loaded project: {}", filePath);
            } else {
                logger.error("Failed to load project: {}", filePath);
            }
        });
    }

    /**
     * Handle saving project (smart save).
     * If project has a file path, save directly. Otherwise, show Save As dialog.
     */
    private void handleSaveProject() {
        if (state.hasFilePath() && state.isProjectFile()) {
            // Save directly to existing file
            logger.info("Saving project to: {}", state.getCurrentFilePath());
            boolean success = controller.saveProject(state.getCurrentFilePath());
            if (success) {
                logger.info("Successfully saved project");
            } else {
                logger.error("Failed to save project");
            }
        } else {
            // Show Save As dialog
            handleSaveProjectAs();
        }
    }

    /**
     * Handle saving project as (always shows dialog).
     */
    private void handleSaveProjectAs() {
        fileDialogService.showSaveOMTDialog(filePath -> {
            logger.info("Saving project as: {}", filePath);
            boolean success = controller.saveProject(filePath);
            if (success) {
                logger.info("Successfully saved project: {}", filePath);
            } else {
                logger.error("Failed to save project: {}", filePath);
            }
        });
    }

    /**
     * Handle importing PNG with intelligent dimension detection.
     * - Detects PNG dimensions
     * - Auto-imports if exact match (16x16 or 64x48)
     * - Shows dialog for other sizes
     */
    private void handleImportPNG() {
        fileDialogService.showOpenPNGDialog(filePath -> {
            logger.info("Detecting PNG dimensions: {}", filePath);

            // Detect PNG dimensions
            int[] dimensions = controller.getImporter().getPNGDimensions(filePath);

            if (dimensions == null) {
                logger.error("Failed to detect PNG dimensions: {}", filePath);
                return;
            }

            int width = dimensions[0];
            int height = dimensions[1];

            logger.debug("PNG dimensions detected: {}x{}", width, height);

            // Check for exact match - auto-import without dialog
            if ((width == 16 && height == 16) || (width == 64 && height == 48)) {
                TextureCreatorState.CanvasSize targetSize =
                    (width == 16) ? TextureCreatorState.CanvasSize.SIZE_16x16
                                  : TextureCreatorState.CanvasSize.SIZE_64x48;

                logger.info("Exact match detected, auto-importing to {} canvas", targetSize.getDisplayName());

                boolean success = controller.importTexture(filePath, targetSize);
                if (success) {
                    logger.info("Successfully auto-imported PNG: {}", filePath);
                } else {
                    logger.error("Failed to auto-import PNG: {}", filePath);
                }
            } else {
                // Non-matching size - show dialog for user to choose target
                logger.info("Non-standard size ({}x{}), showing import dialog", width, height);
                pendingImportPath = filePath;
                importPNGDialog.show(width, height);
            }
        });
    }

    /**
     * Handle export (shows format selection dialog).
     */
    private void handleExport() {
        exportFormatDialog.show(format -> {
            switch (format) {
                case PNG:
                    handleExportPNG();
                    break;
                case OMT:
                    handleExportOMT();
                    break;
            }
        });
    }

    /**
     * Handle exporting as PNG (flattened image).
     */
    private void handleExportPNG() {
        fileDialogService.showSavePNGDialog(filePath -> {
            logger.info("Exporting as PNG: {}", filePath);
            boolean success = controller.exportTexture(filePath);
            if (success) {
                logger.info("Successfully exported PNG: {}", filePath);
            } else {
                logger.error("Failed to export PNG: {}", filePath);
            }
        });
    }

    /**
     * Handle exporting as OMT (project copy).
     */
    private void handleExportOMT() {
        fileDialogService.showSaveOMTDialog(filePath -> {
            logger.info("Exporting as OMT: {}", filePath);
            boolean success = controller.saveProject(filePath);
            if (success) {
                logger.info("Successfully exported OMT: {}", filePath);
            } else {
                logger.error("Failed to export OMT: {}", filePath);
            }
        });
    }

    /**
     * Activate paste tool with floating layer preview.
     * Creates a temporary layer with pasted content that floats above the active layer.
     * User can transform it with move tool, then commit (Enter) or cancel (ESC).
     */
    private void activatePasteTool() {
        if (!controller.canPaste()) {
            logger.warn("Cannot paste - clipboard is empty");
            return;
        }

        // Get clipboard data
        var clipboard = controller.getClipboard();
        PixelCanvas clipboardCanvas = clipboard.getClipboardCanvas();

        if (clipboardCanvas == null) {
            logger.warn("Cannot paste - clipboard canvas is null");
            return;
        }

        // Get move tool from toolbar (reuse it for paste operations)
        var moveTool = toolbarPanel.getMoveToolInstance();
        if (moveTool == null) {
            logger.error("Cannot paste - move tool not available in toolbar");
            return;
        }

        var layerManager = controller.getLayerManager();

        // Save original active layer index
        originalActiveLayerIndex = layerManager.getActiveLayerIndex();

        // Create a new temporary "floating" layer for the paste preview
        // This layer will contain the pasted content
        var floatingLayer = new com.openmason.ui.components.textureCreator.layers.Layer(
            "[Floating Paste]",
            layerManager.getLayer(0).getCanvas().getWidth(),
            layerManager.getLayer(0).getCanvas().getHeight()
        );

        // Apply clipboard content to the floating layer
        var floatingCanvas = floatingLayer.getCanvas();
        for (int y = 0; y < clipboardCanvas.getHeight(); y++) {
            for (int x = 0; x < clipboardCanvas.getWidth(); x++) {
                int targetX = clipboard.getSourceX() + x;
                int targetY = clipboard.getSourceY() + y;

                if (floatingCanvas.isValidCoordinate(targetX, targetY)) {
                    int color = clipboardCanvas.getPixel(x, y);
                    // Check transparency based on preference
                    // If skipTransparentPixels is true, only paste non-transparent pixels
                    // If skipTransparentPixels is false, paste all pixels including transparent ones
                    boolean skipTransparent = preferences.isSkipTransparentPixelsOnPaste();
                    int[] rgba = PixelCanvas.unpackRGBA(color);
                    if (!skipTransparent || rgba[3] > 0) {
                        floatingCanvas.setPixel(targetX, targetY, color);
                    }
                }
            }
        }

        // Add floating layer on top of all other layers
        layerManager.addLayerAt(layerManager.getLayerCount(), floatingLayer);
        floatingPasteLayerIndex = layerManager.getLayerCount() - 1;

        // Make the floating layer active (so move tool works on it)
        layerManager.setActiveLayer(floatingPasteLayerIndex);

        // Create a rectangular selection for the pasted area
        var pasteSelection = new com.openmason.ui.components.textureCreator.selection.RectangularSelection(
            clipboard.getSourceX(),
            clipboard.getSourceY(),
            clipboard.getSourceX() + clipboardCanvas.getWidth() - 1,
            clipboard.getSourceY() + clipboardCanvas.getHeight() - 1
        );

        // Set this as the active selection
        state.setCurrentSelection(pasteSelection);

        // Remember current tool so we can switch back after paste
        toolBeforePaste = state.getCurrentTool();

        // Activate move tool with the pasted selection
        // User can now drag to reposition, Enter to commit, ESC to cancel (which will undo the paste)
        state.setCurrentTool(moveTool);
        toolbarPanel.setCurrentTool(moveTool);

        logger.info("Paste activated using move tool at ({}, {})", clipboard.getSourceX(), clipboard.getSourceY());
    }

    /**
     * Commit the floating paste layer by merging it down to the original active layer.
     * Called when user presses Enter after pasting.
     */
    public void commitFloatingPasteLayer() {
        if (floatingPasteLayerIndex == null) {
            return; // No floating layer to commit
        }

        var layerManager = controller.getLayerManager();

        // Get the floating layer
        var floatingLayer = layerManager.getLayer(floatingPasteLayerIndex);

        // Restore original active layer
        layerManager.setActiveLayer(originalActiveLayerIndex);

        // Create a DrawCommand to record the merge operation for undo support
        var originalLayer = layerManager.getActiveLayer();
        var originalCanvas = originalLayer.getCanvas();
        var floatingCanvas = floatingLayer.getCanvas();

        DrawCommand mergeCommand = new DrawCommand(originalCanvas, "Paste");

        // Merge floating layer down to original layer with undo tracking
        for (int y = 0; y < floatingCanvas.getHeight(); y++) {
            for (int x = 0; x < floatingCanvas.getWidth(); x++) {
                int floatingPixel = floatingCanvas.getPixel(x, y);
                int[] rgba = PixelCanvas.unpackRGBA(floatingPixel);

                // Only merge non-transparent pixels
                if (rgba[3] > 0 && originalCanvas.isValidCoordinate(x, y)) {
                    int oldPixel = originalCanvas.getPixel(x, y);
                    mergeCommand.recordPixelChange(x, y, oldPixel, floatingPixel);
                    originalCanvas.setPixel(x, y, floatingPixel);
                }
            }
        }

        // Execute the merge command through undo system (if any changes were made)
        if (mergeCommand.hasChanges()) {
            controller.getCommandHistory().executeCommand(mergeCommand);
        }

        // Notify that the original layer was modified
        controller.notifyLayerModified();

        // Remove the floating layer (now that pixels are merged down)
        layerManager.removeLayer(floatingPasteLayerIndex);

        // Clear floating layer state
        floatingPasteLayerIndex = null;
        originalActiveLayerIndex = -1;

        // Restore previous tool
        restoreToolBeforePaste();

        logger.info("Floating paste layer committed and merged");
    }

    /**
     * Cancel the floating paste layer by removing it without merging.
     * Called when user presses ESC during paste.
     */
    public void cancelFloatingPasteLayer() {
        if (floatingPasteLayerIndex == null) {
            return; // No floating layer to cancel
        }

        var layerManager = controller.getLayerManager();

        // Restore original active layer
        layerManager.setActiveLayer(originalActiveLayerIndex);

        // Remove the floating layer (discards pasted content)
        layerManager.removeLayer(floatingPasteLayerIndex);

        // Clear floating layer state
        floatingPasteLayerIndex = null;
        originalActiveLayerIndex = -1;

        // Clear selection
        state.setCurrentSelection(null);

        // Restore previous tool
        restoreToolBeforePaste();

        logger.info("Floating paste layer canceled and removed");
    }

    /**
     * Check if there's an active floating paste layer.
     * @return true if paste operation is in progress
     */
    public boolean hasFloatingPasteLayer() {
        return floatingPasteLayerIndex != null;
    }

    /**
     * Restore the tool that was active before paste was initiated.
     */
    private void restoreToolBeforePaste() {
        if (toolBeforePaste != null) {
            state.setCurrentTool(toolBeforePaste);
            toolbarPanel.setCurrentTool(toolBeforePaste);
            toolBeforePaste = null;
            logger.debug("Restored previous tool after paste");
        }
    }

    /**
     * Get the texture creator state.
     * @return state object
     */
    public TextureCreatorState getState() {
        return state;
    }

    /**
     * Get the texture creator controller.
     * @return controller
     */
    public TextureCreatorController getController() {
        return controller;
    }

    /**
     * Set callback for returning to Home screen.
     * This callback will be invoked when the user selects "Home Screen" from the File menu.
     */
    public void setBackToHomeCallback(Runnable callback) {
        this.backToHomeCallback = callback;
    }

    /**
     * Cleanup resources on shutdown.
     */
    public void dispose() {
        // Save color history before disposing
        preferences.setColorHistory(colorPanel.getColorHistory());

        // Cleanup OpenGL resources
        canvasPanel.dispose();
        layerPanel.dispose();
        TextureToolIconManager.getInstance().dispose();

        logger.info("Texture Creator UI disposed (color history saved, layer thumbnails cleaned up, icons cleaned up)");
    }
}
