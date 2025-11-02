package com.openmason.ui.components.textureCreator.rendering;

import com.openmason.ui.components.textureCreator.TextureCreatorController;
import com.openmason.ui.components.textureCreator.TextureCreatorState;
import com.openmason.ui.components.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.ui.components.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.components.textureCreator.dialogs.NewTextureDialog;
import com.openmason.ui.dialogs.ExportFormatDialog;
import imgui.ImGui;
import imgui.type.ImBoolean;
import org.lwjgl.glfw.GLFW;

/**
 * Renders the main menu bar for Texture Creator.
 * Follows Single Responsibility Principle - only handles menu rendering.
 *
 * @author Open Mason Team
 */
public class MenuBarRenderer {
    private static final float ZOOM_FACTOR = 1.2f;

    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final FileOperationsCoordinator fileOperations;
    private final NewTextureDialog newTextureDialog;
    private final ImportPNGDialog importPNGDialog;
    private final ExportFormatDialog exportFormatDialog;

    private long windowHandle;
    private Runnable backToHomeCallback;
    private Runnable onPreferencesToggle;
    private Runnable onNoiseFilterToggle;

    /**
     * Create menu bar renderer.
     */
    public MenuBarRenderer(TextureCreatorState state,
                          TextureCreatorController controller,
                          FileOperationsCoordinator fileOperations,
                          NewTextureDialog newTextureDialog,
                          ImportPNGDialog importPNGDialog,
                          ExportFormatDialog exportFormatDialog) {
        this.state = state;
        this.controller = controller;
        this.fileOperations = fileOperations;
        this.newTextureDialog = newTextureDialog;
        this.importPNGDialog = importPNGDialog;
        this.exportFormatDialog = exportFormatDialog;
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
     * Render the complete menu bar.
     */
    public void render() {
        if (ImGui.beginMainMenuBar()) {
            renderFileMenu();
            renderEditMenu();
            renderViewMenu();
            renderFiltersMenu();
            renderPreferencesButton();
            renderStatusInfo();
            ImGui.endMainMenuBar();
        }
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

            ImGui.endMenu();
        }
    }

    /**
     * Render View menu.
     */
    private void renderViewMenu() {
        if (ImGui.beginMenu("View")) {
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
     * Render Preferences button.
     */
    private void renderPreferencesButton() {
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();

        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.40f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.26f, 0.59f, 0.98f, 1.0f);

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
