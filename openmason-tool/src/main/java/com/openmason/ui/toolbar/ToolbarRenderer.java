package com.openmason.ui.toolbar;

import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.services.ModelOperationService;
import com.openmason.ui.services.PerformanceService;
import com.openmason.ui.services.StatusService;
import com.openmason.ui.services.ViewportOperationService;
import com.openmason.ui.state.ModelState;
import com.openmason.ui.state.UIVisibilityState;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiWindowFlags;

/**
 * Toolbar renderer component.
 * Follows Single Responsibility Principle - only renders toolbar.
 * Follows DRY - eliminates duplicated toolbar rendering code.
 */
public class ToolbarRenderer {

    private final UIVisibilityState uiState;
    private final ModelState modelState;
    private final ModelOperationService modelOperations;
    private final ViewportOperationService viewportOperations;
    private final PerformanceService performanceService;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;

    private OpenMason3DViewport viewport;

    public ToolbarRenderer(UIVisibilityState uiState, ModelState modelState,
                           ModelOperationService modelOperations, ViewportOperationService viewportOperations,
                           PerformanceService performanceService, StatusService statusService,
                           FileDialogService fileDialogService) {
        this.uiState = uiState;
        this.modelState = modelState;
        this.modelOperations = modelOperations;
        this.viewportOperations = viewportOperations;
        this.performanceService = performanceService;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
    }

    /**
     * Set viewport reference.
     */
    public void setViewport(OpenMason3DViewport viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the toolbar.
     */
    public void render() {
        if (!uiState.getShowToolbar().get()) {
            return;
        }

        // Position toolbar directly under the menu bar
        ImGuiViewport mainViewport = ImGui.getMainViewport();
        float menuBarHeight = ImGui.getFrameHeight();

        ImGui.setNextWindowPos(mainViewport.getWorkPosX(), mainViewport.getWorkPosY() + menuBarHeight);
        ImGui.setNextWindowSize(mainViewport.getWorkSizeX(), ImGui.getFrameHeight() + 8.0f);

        int toolbarFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize |
                ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoScrollbar |
                ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoBringToFrontOnFocus;

        if (ImGui.begin("##Toolbar", uiState.getShowToolbar(), toolbarFlags)) {
            renderHideButton();
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();

            renderFileOperations();
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();

            renderViewOperations();
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();

            renderToolOperations();
            ImGui.sameLine();

            renderStatusDisplay();
        }
        ImGui.end();
    }

    /**
     * Render hide toolbar button.
     */
    private void renderHideButton() {
        if (ImGui.smallButton("Hide Toolbar")) {
            uiState.getShowToolbar().set(false);
        }
    }

    /**
     * Render file operation buttons.
     */
    private void renderFileOperations() {
        if (ImGui.button("New##toolbar")) {
            modelOperations.newModel();
        }
        ImGui.sameLine();

        if (ImGui.button("Open##toolbar")) {
            modelOperations.openModel();
        }
        ImGui.sameLine();

        if (ImGui.button("Save##toolbar") && modelState.isModelLoaded() && modelState.hasUnsavedChanges()) {
            modelOperations.saveModel();
        }
    }

    /**
     * Render view operation buttons.
     */
    private void renderViewOperations() {
        if (ImGui.button("Reset View##toolbar")) {
            viewportOperations.resetView(viewport);
        }
        ImGui.sameLine();

        if (ImGui.button("Zoom In##toolbar") && viewport != null) {
            viewport.getCamera().zoom(1.0f);
        }
        ImGui.sameLine();

        if (ImGui.button("Zoom Out##toolbar") && viewport != null) {
            viewport.getCamera().zoom(-1.0f);
        }
        ImGui.sameLine();

        if (ImGui.button("Fit to View##toolbar")) {
            viewportOperations.fitToView();
        }
    }

    /**
     * Render tool operation buttons.
     */
    private void renderToolOperations() {
        if (ImGui.button("Validate##toolbar") && modelState.isModelLoaded()) {
            modelOperations.validateModel();
        }
        ImGui.sameLine();

        if (ImGui.button("Settings##toolbar")) {
            uiState.showPreferences();
        }
    }

    /**
     * Render status display on right side of toolbar.
     */
    private void renderStatusDisplay() {
        // Push remaining content to right side
        float availWidth = ImGui.getContentRegionAvailX();
        float statusWidth = 350.0f;
        if (availWidth > statusWidth + 20.0f) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - statusWidth);
        }

        ImGui.separator();
        ImGui.sameLine();

        // Current model display
        String modelDisplay = modelState.isModelLoaded() ? modelState.getCurrentModelPath() : "None";
        ImGui.text("Model: " + modelDisplay);
        ImGui.sameLine();

        // Progress bar (if active)
        if (performanceService.getProgress().get() > 0.0f) {
            ImGui.separator();
            ImGui.sameLine();
            ImGui.progressBar(performanceService.getProgress().get(), 80.0f, 0.0f);
            ImGui.sameLine();
        }

        // Status message
        ImGui.separator();
        ImGui.sameLine();
        ImGui.text("Status: " + statusService.getStatusMessage());
        ImGui.sameLine();

        // Memory usage
        ImGui.separator();
        ImGui.sameLine();
        ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f,
                String.format("%.1f MB", performanceService.getMemoryUsage()));
        ImGui.sameLine();

        // Frame rate
        ImGui.separator();
        ImGui.sameLine();
        ImGui.textColored(0.0f, 0.5f, 1.0f, 1.0f,
                String.format("%.1f FPS", performanceService.getFrameRate()));
    }
}
