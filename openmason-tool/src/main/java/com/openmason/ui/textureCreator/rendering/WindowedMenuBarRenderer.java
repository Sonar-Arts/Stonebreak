package com.openmason.ui.textureCreator.rendering;

import com.openmason.ui.LogoManager;
import com.openmason.ui.textureCreator.TextureCreatorController;
import com.openmason.ui.textureCreator.TextureCreatorState;
import com.openmason.ui.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.ui.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.textureCreator.dialogs.NewTextureDialog;
import com.openmason.ui.dialogs.ExportFormatDialog;
import com.openmason.ui.menus.AboutMenuHandler;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;

/**
 * Renders a window-based menu bar for Texture Creator (for use inside a separate window).
 * Unlike MenuBarRenderer which uses ImGui.beginMainMenuBar(), this renders inside a parent window.
 *
 * Follows DRY and SOLID principles by reusing menu logic from MenuBarRenderer.
 * Uses composition over inheritance to share menu rendering code.
 *
 * @author Open Mason Team
 */
public class WindowedMenuBarRenderer {
    private static final float ZOOM_FACTOR = 1.2f;
    private static final float MENU_BAR_HEIGHT = 26.0f;

    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final FileOperationsCoordinator fileOperations;
    private final NewTextureDialog newTextureDialog;
    private final ImportPNGDialog importPNGDialog;
    private final ExportFormatDialog exportFormatDialog;
    private final LogoManager logoManager;
    private final AboutMenuHandler aboutMenu;

    private long windowHandle;
    private Runnable backToHomeCallback;
    private Runnable onPreferencesToggle;
    private Runnable onNoiseFilterToggle;
    private Runnable onSymmetryToggle;
    private Runnable onLayersPanelToggle;
    private Runnable onColorPanelToggle;
    private ImBoolean showLayersPanel;
    private ImBoolean showColorPanel;

    /**
     * Create windowed menu bar renderer.
     */
    public WindowedMenuBarRenderer(TextureCreatorState state,
                                   TextureCreatorController controller,
                                   FileOperationsCoordinator fileOperations,
                                   NewTextureDialog newTextureDialog,
                                   ImportPNGDialog importPNGDialog,
                                   ExportFormatDialog exportFormatDialog,
                                   LogoManager logoManager,
                                   AboutMenuHandler aboutMenu) {
        this.state = state;
        this.controller = controller;
        this.fileOperations = fileOperations;
        this.newTextureDialog = newTextureDialog;
        this.importPNGDialog = importPNGDialog;
        this.exportFormatDialog = exportFormatDialog;
        this.logoManager = logoManager;
        this.aboutMenu = aboutMenu;
    }

    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
    }

    public void setBackToHomeCallback(Runnable callback) {
        this.backToHomeCallback = callback;
    }

    public void setOnPreferencesToggle(Runnable callback) {
        this.onPreferencesToggle = callback;
    }

    public void setOnNoiseFilterToggle(Runnable callback) {
        this.onNoiseFilterToggle = callback;
    }

    public void setOnSymmetryToggle(Runnable callback) {
        this.onSymmetryToggle = callback;
    }

    public void setOnLayersPanelToggle(Runnable callback, ImBoolean showLayersPanel) {
        this.onLayersPanelToggle = callback;
        this.showLayersPanel = showLayersPanel;
    }

    public void setOnColorPanelToggle(Runnable callback, ImBoolean showColorPanel) {
        this.onColorPanelToggle = callback;
        this.showColorPanel = showColorPanel;
    }

    /**
     * Render the menu bar as a fixed-height horizontal bar inside a window.
     */
    public void render() {
        // Apply menu bar styling
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4.0f, 2.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4.0f, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f, 2.0f);

        // Create a fixed-height child window for the menu bar
        int windowFlags = ImGuiWindowFlags.NoScrollbar |
                          ImGuiWindowFlags.NoScrollWithMouse |
                          ImGuiWindowFlags.MenuBar;

        if (ImGui.beginChild("##TextureEditorMenuBar", 0, MENU_BAR_HEIGHT, false, windowFlags)) {
            if (ImGui.beginMenuBar()) {
                renderMenuBarContent();
                ImGui.endMenuBar();
            }
        }
        ImGui.endChild();

        // Pop styling
        ImGui.popStyleVar(3);
    }

    /**
     * Render menu bar content.
     */
    private void renderMenuBarContent() {
        // Render logo with separator if available
        if (logoManager != null) {
            logoManager.renderMenuBarLogo();
            ImGui.sameLine();
            renderSeparator();
            ImGui.sameLine();
        }

        // Render all menus
        renderFileMenu();
        renderEditMenu();
        renderViewMenu();
        renderFiltersMenu();
        if (aboutMenu != null) {
            aboutMenu.render();
        }

        // Render preferences button with separator
        renderPreferencesButton();

        // Render status info on right side
        renderStatusInfo();
    }

    private void renderFileMenu() {
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("New", "Ctrl+N")) {
                newTextureDialog.show();
            }
            if (ImGui.menuItem("Open Project...", "Ctrl+O")) {
                fileOperations.openProject();
            }
            if (ImGui.menuItem("Save Project", "Ctrl+S")) {
                fileOperations.saveProject();
            }
            if (ImGui.menuItem("Save Project As...", "Ctrl+Shift+S")) {
                fileOperations.saveProjectAs();
            }

            ImGui.separator();

            if (ImGui.menuItem("Import PNG...")) {
                fileOperations.importPNG(importPNGDialog);
            }

            if (ImGui.beginMenu("Export")) {
                if (ImGui.menuItem("Export...", "Ctrl+E")) {
                    exportFormatDialog.show(format -> {
                        switch (format) {
                            case PNG: fileOperations.exportPNG(); break;
                            case OMT: fileOperations.exportOMT(); break;
                        }
                    });
                }
                if (ImGui.menuItem("Export as PNG...")) {
                    fileOperations.exportPNG();
                }
                if (ImGui.menuItem("Export as OMT...")) {
                    fileOperations.exportOMT();
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
                if (windowHandle != 0) {
                    GLFW.glfwSetWindowShouldClose(windowHandle, true);
                }
            }

            ImGui.endMenu();
        }
    }

    private void renderEditMenu() {
        if (ImGui.beginMenu("Edit")) {
            boolean canUndo = controller.getCommandHistory().canUndo();
            boolean canRedo = controller.getCommandHistory().canRedo();
            boolean hasSelection = state.getCurrentSelection() != null &&
                                 !state.getCurrentSelection().isEmpty();

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

            ImGui.separator();

            if (ImGui.menuItem("Symmetry...")) {
                if (onSymmetryToggle != null) {
                    onSymmetryToggle.run();
                }
            }

            ImGui.endMenu();
        }
    }

    private void renderViewMenu() {
        if (ImGui.beginMenu("View")) {
            // Panels submenu
            if (ImGui.beginMenu("Panels")) {
                if (showLayersPanel != null && ImGui.menuItem("Layers", null, showLayersPanel.get())) {
                    if (onLayersPanelToggle != null) {
                        onLayersPanelToggle.run();
                    }
                }
                if (showColorPanel != null && ImGui.menuItem("Color", null, showColorPanel.get())) {
                    if (onColorPanelToggle != null) {
                        onColorPanelToggle.run();
                    }
                }
                ImGui.endMenu();
            }

            ImGui.separator();

            ImGui.menuItem("Grid", "G", state.getShowGrid());

            ImGui.separator();

            if (ImGui.menuItem("Zoom In", "+")) {
                controller.getCanvasState().zoomIn(ZOOM_FACTOR);
            }
            if (ImGui.menuItem("Zoom Out", "-")) {
                controller.getCanvasState().zoomOut(ZOOM_FACTOR);
            }
            if (ImGui.menuItem("Reset View", "0")) {
                controller.getCanvasState().resetView();
            }

            ImGui.endMenu();
        }
    }

    private void renderFiltersMenu() {
        if (ImGui.beginMenu("Filters")) {
            if (ImGui.menuItem("Noise...")) {
                if (onNoiseFilterToggle != null) {
                    onNoiseFilterToggle.run();
                }
            }

            ImGui.endMenu();
        }
    }

    private void renderPreferencesButton() {
        renderSeparator();
        ImGui.sameLine();

        // Apply transparent button styling
        ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.26f, 0.59f, 0.98f, 1.0f);

        if (ImGui.button("Preferences")) {
            if (onPreferencesToggle != null) {
                onPreferencesToggle.run();
            }
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open preferences window (Ctrl+,)");
        }

        ImGui.popStyleColor(3);
    }

    private void renderStatusInfo() {
        // Position status info on the right side
        float textWidth = ImGui.calcTextSize(String.format("Canvas: %s | Zoom: %.0f%%",
            state.getCurrentCanvasSize().getDisplayName(),
            controller.getCanvasState().getZoomLevel() * 100)).x;

        ImGui.sameLine(ImGui.getContentRegionAvailX() - textWidth);
        ImGui.text(String.format("Canvas: %s | Zoom: %.0f%%",
            state.getCurrentCanvasSize().getDisplayName(),
            controller.getCanvasState().getZoomLevel() * 100));
    }

    private void renderSeparator() {
        ImGui.separator();
    }

    /**
     * Get the height of the menu bar for layout calculations.
     */
    public float getHeight() {
        return MENU_BAR_HEIGHT;
    }
}
