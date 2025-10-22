package com.openmason.ui.components.textureCreator;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.icons.TextureToolIconManager;
import com.openmason.ui.components.textureCreator.panels.*;
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
    private final CanvasPanel canvasPanel;
    private final LayerPanelRenderer layerPanel;
    private final ColorPanel colorPanel;
    private final PreferencesPanel preferencesPanel;

    // Window visibility flags
    private boolean showPreferencesWindow = false;

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

        // Initialize panels
        this.toolbarPanel = new ToolbarPanel();
        this.canvasPanel = new CanvasPanel();
        this.layerPanel = new LayerPanelRenderer();
        this.colorPanel = new ColorPanel();
        this.preferencesPanel = new PreferencesPanel();

        // Load color history from preferences
        colorPanel.setColorHistory(preferences.getColorHistory());

        // Set default tool
        state.setCurrentTool(toolbarPanel.getCurrentTool());

        logger.info("Texture Creator UI initialized with preferences persistence and color history");
    }

    /**
     * Render the texture creator interface.
     * Called every frame.
     */
    public void render() {
        // Render main menu bar
        renderMenuBar();

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
    }

    /**
     * Render main docking space container.
     * Follows the same pattern as MainImGuiInterface.
     */
    private void renderDockSpace() {
        int windowFlags = ImGuiWindowFlags.NoDocking;

        ImGuiViewport viewport = ImGui.getMainViewport();
        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY());
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY());
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

            // Update current tool in state
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

            if (compositedCanvas != null) {
                // Display composited view, but draw on active layer
                canvasPanel.render(compositedCanvas, activeCanvas, controller.getCanvasState(),
                                 state.getCurrentTool(), colorPanel.getCurrentColor(),
                                 state.getShowGrid().get(),
                                 preferences.getGridOpacity(),
                                 controller.getCommandHistory(),
                                 controller::notifyLayerModified,
                                 colorPanel::setColor,  // Color picker callback
                                 (color) -> {  // Color used callback
                                     colorPanel.addColorToHistory(color);
                                     preferences.setColorHistory(colorPanel.getColorHistory());
                                 });
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
     * Render menu bar.
     */
    private void renderMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                if (ImGui.menuItem("New", "Ctrl+N")) {
                    // TODO: Show new texture dialog
                }
                if (ImGui.menuItem("Open", "Ctrl+O")) {
                    handleOpenPNG();
                }
                if (ImGui.menuItem("Save", "Ctrl+S")) {
                    handleSavePNG();
                }
                if (ImGui.menuItem("Export", "Ctrl+E")) {
                    handleExportPNG();
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

                if (ImGui.menuItem("Undo", "Ctrl+Z", false, canUndo)) {
                    controller.undo();
                }
                if (ImGui.menuItem("Redo", "Ctrl+Y", false, canRedo)) {
                    controller.redo();
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

                ImGui.separator();

                if (ImGui.menuItem("Preferences", "Ctrl+,")) {
                    showPreferencesWindow = !showPreferencesWindow;
                }
                ImGui.endMenu();
            }

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
        // Handle keyboard shortcuts
        handleKeyboardShortcuts();
    }

    /**
     * Handle keyboard shortcuts.
     */
    private void handleKeyboardShortcuts() {
        boolean ctrl = ImGui.getIO().getKeyCtrl();

        // File operations
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_O)) {
            handleOpenPNG();
        }
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_S)) {
            handleSavePNG();
        }
        if (ctrl && ImGui.isKeyPressed(GLFW.GLFW_KEY_E)) {
            handleExportPNG();
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

        // Grid toggle
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_G)) {
            state.getShowGrid().set(!state.getShowGrid().get());
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
     * Handle opening PNG file.
     */
    private void handleOpenPNG() {
        fileDialogService.showOpenPNGDialog(filePath -> {
            logger.info("Opening PNG file: {}", filePath);
            boolean success = controller.importTexture(filePath);
            if (success) {
                logger.info("Successfully imported PNG: {}", filePath);
            } else {
                logger.error("Failed to import PNG: {}", filePath);
            }
        });
    }

    /**
     * Handle saving PNG file.
     */
    private void handleSavePNG() {
        fileDialogService.showSavePNGDialog(filePath -> {
            logger.info("Saving PNG file: {}", filePath);
            boolean success = controller.exportTexture(filePath);
            if (success) {
                logger.info("Successfully saved PNG: {}", filePath);
            } else {
                logger.error("Failed to save PNG: {}", filePath);
            }
        });
    }

    /**
     * Handle exporting PNG file (same as save for now).
     */
    private void handleExportPNG() {
        handleSavePNG();
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
     * Cleanup resources on shutdown.
     */
    public void dispose() {
        // Save color history before disposing
        preferences.setColorHistory(colorPanel.getColorHistory());

        // Cleanup OpenGL resources
        canvasPanel.dispose();
        TextureToolIconManager.getInstance().dispose();

        logger.info("Texture Creator UI disposed (color history saved, icons cleaned up)");
    }
}
