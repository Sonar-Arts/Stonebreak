package com.openmason.ui.textureCreator.rendering;

import com.openmason.ui.LogoManager;
import com.openmason.ui.textureCreator.TextureCreatorController;
import com.openmason.ui.textureCreator.TextureCreatorState;
import com.openmason.ui.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.ui.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.textureCreator.dialogs.NewTextureDialog;
import com.openmason.ui.dialogs.ExportFormatDialog;
import com.openmason.ui.menus.BaseMenuBarRenderer;
import com.openmason.ui.menus.AboutMenuHandler;
import imgui.ImGui;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;

/**
 * Renders the main menu bar for Texture Creator.
 * Extends BaseMenuBarRenderer for consistent styling and DRY principles.
 * Follows Single Responsibility Principle - only handles menu rendering.
 *
 * @author Open Mason Team
 */
public class MenuBarRenderer extends BaseMenuBarRenderer {
    private static final float ZOOM_FACTOR = 1.2f;

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
     * Create menu bar renderer.
     *
     * @param state the texture creator state
     * @param controller the texture creator controller
     * @param fileOperations the file operations coordinator
     * @param newTextureDialog the new texture dialog
     * @param importPNGDialog the import PNG dialog
     * @param exportFormatDialog the export format dialog
     * @param logoManager optional logo manager (null to skip logo rendering)
     * @param aboutMenu the about menu handler for About dialog
     */
    public MenuBarRenderer(TextureCreatorState state,
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

    /**
     * Set window handle for exit functionality.
     */
    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
    }

    /**
     * Set callback for returning to home screen.
     */
    public void setBackToHomeCallback(Runnable callback) {
        this.backToHomeCallback = callback;
    }

    /**
     * Set callback for preferences toggle.
     */
    public void setOnPreferencesToggle(Runnable callback) {
        this.onPreferencesToggle = callback;
    }

    /**
     * Set callback for noise filter toggle.
     */
    public void setOnNoiseFilterToggle(Runnable callback) {
        this.onNoiseFilterToggle = callback;
    }

    /**
     * Set callback for symmetry toggle.
     */
    public void setOnSymmetryToggle(Runnable callback) {
        this.onSymmetryToggle = callback;
    }

    /**
     * Set callback for layers panel toggle.
     */
    public void setOnLayersPanelToggle(Runnable callback, ImBoolean showLayersPanel) {
        this.onLayersPanelToggle = callback;
        this.showLayersPanel = showLayersPanel;
    }

    /**
     * Set callback for color panel toggle.
     */
    public void setOnColorPanelToggle(Runnable callback, ImBoolean showColorPanel) {
        this.onColorPanelToggle = callback;
        this.showColorPanel = showColorPanel;
    }

    /**
     * Render menu bar content.
     * Implements BaseMenuBarRenderer template method to provide texture editor menu structure.
     */
    @Override
    protected void renderMenuBarContent() {
        // Render logo with separator (inherited from BaseMenuBarRenderer)
        renderLogoWithSeparator(logoManager);

        // Render all menus
        renderFileMenu();
        renderEditMenu();
        renderViewMenu();
        renderFiltersMenu();
        aboutMenu.render();

        // Render preferences button with separator
        renderPreferencesButton();

        // Render status info on right side
        renderStatusInfo();
    }

    /**
     * Render File menu.
     */
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

    /**
     * Render Edit menu.
     */
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

    /**
     * Render View menu.
     */
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

    /**
     * Render Filters menu.
     */
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

    /**
     * Render Preferences button with separator.
     * Uses shared styling from BaseMenuBarRenderer for consistency.
     */
    private void renderPreferencesButton() {
        // Render separator before preferences button (inherited from BaseMenuBarRenderer)
        renderMenuSeparator();

        // Apply transparent button styling (inherited from BaseMenuBarRenderer)
        pushTransparentButtonStyle();

        if (ImGui.button("Preferences")) {
            if (onPreferencesToggle != null) {
                onPreferencesToggle.run();
            }
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open preferences window (Ctrl+,)");
        }

        // Remove transparent button styling
        popTransparentButtonStyle();
    }

    /**
     * Render status info on right side of menu bar.
     */
    private void renderStatusInfo() {
        ImGui.sameLine(ImGui.getWindowSizeX() - 300);
        ImGui.text(String.format("Canvas: %s | Zoom: %.0f%%",
            state.getCurrentCanvasSize().getDisplayName(),
            controller.getCanvasState().getZoomLevel() * 100));
    }
}
