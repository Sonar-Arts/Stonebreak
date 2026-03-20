package com.openmason.main.systems.menus.toolbars;

import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.services.ViewportOperationService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.ViewportController;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Blender-style model editor toolbar.
 * Flat transparent buttons with thin vertical separators between logical groups.
 * Extends BaseToolbarRenderer for consistent Blender-like aesthetics.
 */
public class ModelEditorToolbarRenderer extends BaseToolbarRenderer {

    private final UIVisibilityState uiState;
    private final ModelState modelState;
    private final ModelOperationService modelOperations;
    private final ViewportOperationService viewportOperations;
    private final StatusService statusService;

    private ViewportController viewport;

    public ModelEditorToolbarRenderer(UIVisibilityState uiState, ModelState modelState,
                           ModelOperationService modelOperations, ViewportOperationService viewportOperations,
                           StatusService statusService) {
        this.uiState = uiState;
        this.modelState = modelState;
        this.modelOperations = modelOperations;
        this.viewportOperations = viewportOperations;
        this.statusService = statusService;
    }

    /**
     * Set viewport reference.
     */
    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the Blender-style toolbar inline.
     */
    public void render() {
        if (!uiState.getShowToolbar().get()) {
            return;
        }

        // Toolbar layout: compact spacing, flat buttons
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 2.0f, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8.0f, 4.0f);

        pushFlatButtonStyle();

        // --- File operations group ---
        renderFileOperations();

        // --- Thin separator ---
        renderThinSeparator();

        // --- View operations group ---
        renderViewOperations();

        // --- Thin separator ---
        renderThinSeparator();

        // --- Zoom controls group ---
        renderZoomControls();

        // --- Model path on right side ---
        renderModelInfo();

        popFlatButtonStyle();

        ImGui.popStyleVar(2);

        // Clean bottom border line
        renderBottomBorder();
    }

    /**
     * Render file operation buttons (Open, Save).
     */
    private void renderFileOperations() {
        if (renderFlatButton("Open", "Open model file (Ctrl+O)")) {
            modelOperations.openModel();
        }
        ImGui.sameLine(0.0f, 2.0f);

        boolean canSave = modelState.isModelLoaded() && modelState.hasUnsavedChanges();
        String saveTooltip = canSave ? "Save current model (Ctrl+S)" : "No unsaved changes";

        // Dim the save button text when nothing to save
        if (!canSave) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.6f);
        }
        if (renderFlatButton("Save", saveTooltip) && canSave) {
            modelOperations.saveModel();
        }
        if (!canSave) {
            ImGui.popStyleColor(1);
        }
    }

    /**
     * Render view operation buttons (Reset, Fit).
     */
    private void renderViewOperations() {
        if (renderFlatButton("Reset", "Reset camera to default (Numpad 0)")) {
            viewportOperations.resetView(viewport);
        }
        ImGui.sameLine(0.0f, 2.0f);

        if (renderFlatButton("Fit", "Fit model in view (Numpad .)") && viewport != null) {
            viewportOperations.fitToView();
        }
    }

    /**
     * Render zoom control buttons (+, -).
     */
    private void renderZoomControls() {
        if (renderCompactFlatButton("+", "Zoom in") && viewport != null) {
            viewport.getCamera().zoom(1.0f);
        }
        ImGui.sameLine(0.0f, 2.0f);

        if (renderCompactFlatButton("-", "Zoom out") && viewport != null) {
            viewport.getCamera().zoom(-1.0f);
        }
    }

    /**
     * Render model path info on the right side of the toolbar.
     * Displayed as subtle dimmed text, Blender-style.
     */
    private void renderModelInfo() {
        if (!modelState.isModelLoaded()) {
            return;
        }

        String modelPath = modelState.getCurrentModelPath();
        if (modelPath == null || modelPath.isEmpty()) {
            return;
        }

        // Calculate right-aligned position
        float textWidth = ImGui.calcTextSize(modelPath).x;
        float availableWidth = ImGui.getContentRegionAvailX();
        float rightPadding = 8.0f;

        if (availableWidth > textWidth + rightPadding) {
            ImGui.sameLine(0.0f, 0.0f);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + availableWidth - textWidth - rightPadding);

            // Subtle dimmed text for the path
            ImVec4 textColor = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
            ImGui.pushStyleColor(ImGuiCol.Text, textColor.x, textColor.y, textColor.z, 0.7f);
            ImGui.text(modelPath);
            ImGui.popStyleColor(1);
        }
    }
}
