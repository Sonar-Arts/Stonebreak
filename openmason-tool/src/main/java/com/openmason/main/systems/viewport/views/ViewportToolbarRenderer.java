package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.toolbars.BaseToolbarRenderer;
import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;

/**
 * Single-row flat toolbar for the 3D viewport, Blender-style.
 * Replaces the old View/Display/Tools tab bar: gizmo mode segmented control,
 * compact view/camera/render combos, display toggles, view actions, and
 * right-aligned tool pane launchers all share one row. Every control keeps a
 * tooltip since labels are compact; all accent colors derive from the live
 * ImGui theme.
 */
public class ViewportToolbarRenderer extends BaseToolbarRenderer {

    private static final float GIZMO_BUTTON_WIDTH = 62.0f;
    private static final float FRAME_PADDING_X = 8.0f;
    private static final float FRAME_PADDING_Y = 5.0f;
    private static final float ITEM_SPACING_X = 4.0f;

    private final ViewportUIState state;
    private final ViewportActions actions;
    private final ViewportController viewport;

    private final ImVec2 textSize = new ImVec2();
    private final ImVec2 avail = new ImVec2();

    public ViewportToolbarRenderer(ViewportUIState state, ViewportActions actions,
                                   ViewportController viewport) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
    }

    /**
     * Render the toolbar row. Caller is inside the viewport window, before the
     * 3D image.
     */
    public void render() {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, FRAME_PADDING_X, FRAME_PADDING_Y);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, ITEM_SPACING_X, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);

        renderGizmoModes();
        renderGroupSeparator();
        renderModeCombos();
        renderGroupSeparator();
        renderDisplayToggles();
        renderGroupSeparator();
        renderViewActions();
        renderPaneLaunchers();

        ImGui.popStyleVar(3);
    }

    // ===========================
    // Group separators
    // ===========================

    private static final float SEPARATOR_SPACING = 8.0f;

    /** Vertical border line between toolbar groups, matched to the row height. */
    private void renderGroupSeparator() {
        ImGui.sameLine(0.0f, SEPARATOR_SPACING);
        renderSeparatorLine();
    }

    /**
     * Draw the separator line at the current cursor and advance past it.
     * Caller must already be on the toolbar row (after a sameLine).
     */
    private void renderSeparatorLine() {
        ImVec2 pos = ImGui.getCursorScreenPos();
        float height = ImGui.getFrameHeight();

        ImVec4 sep = ImGui.getStyle().getColor(ImGuiCol.Separator);
        int color = ImGui.colorConvertFloat4ToU32(sep.x, sep.y, sep.z, 0.6f);
        ImGui.getWindowDrawList().addLine(pos.x, pos.y + 2.0f, pos.x, pos.y + height - 2.0f, color, 1.0f);

        ImGui.dummy(1.0f, height);
        ImGui.sameLine(0.0f, SEPARATOR_SPACING);
    }

    // ===========================
    // Gizmo mode segmented control
    // ===========================

    private void renderGizmoModes() {
        GizmoState.Mode current = viewport.getGizmoMode();

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 1.0f, 4.0f);
        gizmoModeButton("Move", "G", GizmoState.Mode.TRANSLATE, current);
        ImGui.sameLine();
        gizmoModeButton("Rotate", "R", GizmoState.Mode.ROTATE, current);
        ImGui.sameLine();
        gizmoModeButton("Scale", "S", GizmoState.Mode.SCALE, current);
        ImGui.popStyleVar();
    }

    private void gizmoModeButton(String label, String shortcut, GizmoState.Mode mode,
                                 GizmoState.Mode current) {
        boolean active = (mode == current);
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.75f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.90f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 1.0f);
        } else {
            ImVec4 frame = ImGui.getStyle().getColor(ImGuiCol.FrameBg);
            ImVec4 hover = ImGui.getStyle().getColor(ImGuiCol.HeaderHovered);
            ImGui.pushStyleColor(ImGuiCol.Button, frame.x, frame.y, frame.z, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, hover.x, hover.y, hover.z, 0.5f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.7f);
        }

        if (ImGui.button(label, GIZMO_BUTTON_WIDTH, 0)) {
            actions.setGizmoMode(mode);
        }
        ImGui.popStyleColor(3);

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(label + " (" + shortcut + ")");
        }
    }

    // ===========================
    // View / camera / render combos
    // ===========================

    private void renderModeCombos() {
        ImGui.setNextItemWidth(comboWidth(state.getViewModes()));
        if (ImGui.combo("##viewmode", state.getCurrentViewModeIndex(), state.getViewModes())) {
            actions.updateViewMode();
        }
        renderTooltip("View projection");

        ImGui.sameLine();
        ImGui.setNextItemWidth(comboWidth(state.getCameraModes()));
        if (ImGui.combo("##cameramode", state.getCurrentCameraModeIndex(), state.getCameraModes())) {
            actions.updateCameraMode();
        }
        renderTooltip("Camera navigation mode");

        ImGui.sameLine();
        ImGui.setNextItemWidth(comboWidth(state.getRenderModes()));
        if (ImGui.combo("##rendermode", state.getCurrentRenderModeIndex(), state.getRenderModes())) {
            actions.updateRenderMode();
        }
        renderTooltip("Render mode");
    }

    /**
     * Width that fits the longest item in the combo: text + frame padding on
     * both sides + the square arrow button (one frame height wide).
     */
    private float comboWidth(String[] items) {
        float maxText = 0.0f;
        for (String item : items) {
            ImGui.calcTextSize(textSize, item);
            maxText = Math.max(maxText, textSize.x);
        }
        return maxText + FRAME_PADDING_X * 2 + ImGui.getFrameHeight();
    }

    // ===========================
    // Display toggles
    // ===========================

    private void renderDisplayToggles() {
        displayToggle("Grid", state.getGridVisible(), "Show grid (Ctrl+G)", actions::toggleGrid);
        ImGui.sameLine();
        displayToggle("Axes", state.getAxesVisible(), "Show axis lines", actions::toggleAxes);
        ImGui.sameLine();
        displayToggle("Snap", state.getGridSnappingEnabled(), "Snap transforms to the grid",
                actions::toggleGridSnapping);
        ImGui.sameLine();
        displayToggle("Mesh", state.getShowVertices(), "Show mesh vertices", actions::toggleShowVertices);
    }

    /**
     * Flat toggle button bound to an ImBoolean. Flips the state first, then
     * runs the sync action — ViewportActions toggle methods read the already
     * flipped state and push it to the viewport (checkbox semantics).
     */
    private void displayToggle(String label, ImBoolean value, String tooltip, Runnable sync) {
        boolean on = value.get();
        if (on) {
            pushHighlightedFlatButtonStyle();
        } else {
            pushFlatButtonStyle();
        }

        if (ImGui.button(label)) {
            value.set(!on);
            sync.run();
        }

        if (on) {
            popHighlightedFlatButtonStyle();
        } else {
            popFlatButtonStyle();
        }
        renderTooltip(tooltip);
    }

    // ===========================
    // View actions
    // ===========================

    private void renderViewActions() {
        pushFlatButtonStyle();
        if (ImGui.button("Reset")) {
            actions.resetView();
        }
        renderTooltip("Reset the view to its default position");

        ImGui.sameLine();
        if (ImGui.button("Fit")) {
            actions.fitToView();
        }
        renderTooltip("Fit the model in the viewport");
        popFlatButtonStyle();
    }

    // ===========================
    // Tool pane launchers (right-aligned)
    // ===========================

    private static final String[] PANE_LABELS = {"Camera", "Rendering", "Transform"};
    private static final ViewportUIState.ActiveToolPane[] PANE_TARGETS = {
            ViewportUIState.ActiveToolPane.CAMERA,
            ViewportUIState.ActiveToolPane.RENDERING,
            ViewportUIState.ActiveToolPane.TRANSFORM
    };

    private void renderPaneLaunchers() {
        ImGui.sameLine(0.0f, SEPARATOR_SPACING);

        // Right-align the group when there is room; otherwise flow inline.
        // Group width includes the leading separator line and its spacing.
        float total = 1.0f + SEPARATOR_SPACING + ITEM_SPACING_X * (PANE_LABELS.length - 1);
        for (String label : PANE_LABELS) {
            ImGui.calcTextSize(textSize, label);
            total += textSize.x + FRAME_PADDING_X * 2;
        }
        ImGui.getContentRegionAvail(avail);
        if (avail.x > total) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + avail.x - total);
        }
        renderSeparatorLine();

        ViewportUIState.ActiveToolPane activePane = state.getActiveToolPane();
        for (int i = 0; i < PANE_LABELS.length; i++) {
            boolean active = (activePane == PANE_TARGETS[i]);
            if (active) {
                pushHighlightedFlatButtonStyle();
            } else {
                pushFlatButtonStyle();
            }

            if (ImGui.button(PANE_LABELS[i])) {
                state.toggleToolPane(PANE_TARGETS[i]);
            }

            if (active) {
                popHighlightedFlatButtonStyle();
            } else {
                popFlatButtonStyle();
            }
            renderTooltip("Toggle the " + PANE_LABELS[i] + " panel");

            if (i < PANE_LABELS.length - 1) {
                ImGui.sameLine();
            }
        }
    }
}
