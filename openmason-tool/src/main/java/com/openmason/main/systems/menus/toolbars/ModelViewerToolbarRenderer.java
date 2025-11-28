package com.openmason.main.systems.menus.toolbars;

import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.services.ViewportOperationService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.ViewportController;
import imgui.ImGui;

/**
 * Model viewer toolbar renderer.
 * Extends BaseToolbarRenderer for consistent styling and DRY principles.
 */
public class ModelViewerToolbarRenderer extends BaseToolbarRenderer {

    private final UIVisibilityState uiState;
    private final ModelState modelState;
    private final ModelOperationService modelOperations;
    private final ViewportOperationService viewportOperations;
    private final StatusService statusService;

    private ViewportController viewport;

    public ModelViewerToolbarRenderer(UIVisibilityState uiState, ModelState modelState,
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
     * Render the toolbar inline (not as a separate window).
     */
    public void render() {
        if (!uiState.getShowToolbar().get()) {
            return;
        }

        // Apply toolbar styling (inherited from BaseToolbarRenderer)
        pushItemSpacing(4.0f, 0.0f);

        // Render toolbar content inline
        renderFileOperations();
        renderSeparator();

        renderViewOperations();
        renderSeparator();

        renderStatusDisplay();

        popItemSpacing();
    }

    /**
     * Render file operation buttons.
     */
    private void renderFileOperations() {
        if (renderButton("Open", "Open model file")) {
            modelOperations.openModel();
        }
        ImGui.sameLine();

        boolean canSave = modelState.isModelLoaded() && modelState.hasUnsavedChanges();
        if (renderButton("Save", canSave ? "Save current model" : "No changes to save") && canSave) {
            modelOperations.saveModel();
        }
    }

    /**
     * Render view operation buttons.
     */
    private void renderViewOperations() {
        if (renderButton("Reset", "Reset camera view")) {
            viewportOperations.resetView(viewport);
        }
        ImGui.sameLine();

        if (renderButton("Fit", "Fit model to view") && viewport != null) {
            viewportOperations.fitToView();
        }
        ImGui.sameLine();

        if (renderButton("+", "Zoom in") && viewport != null) {
            viewport.getCamera().zoom(1.0f);
        }
        ImGui.sameLine();

        if (renderButton("-", "Zoom out") && viewport != null) {
            viewport.getCamera().zoom(-1.0f);
        }
    }

    /**
     * Render status display on right side of toolbar.
     */
    private void renderStatusDisplay() {
        // Current model (only if loaded)
        if (modelState.isModelLoaded()) {
            renderSeparator();
            ImGui.text(modelState.getCurrentModelPath());
        }
    }
}
