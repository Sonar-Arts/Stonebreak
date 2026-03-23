package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.input.KnifeSnapSettings;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the tool pane that overlays the left edge of the 3D viewport.
 * Appears instantly when a tool button is toggled. Uses theme-derived
 * colours to match the rest of the Open Mason UI.
 */
public class ToolPaneRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ToolPaneRenderer.class);

    private static final float PANE_WIDTH = 220.0f;
    private static final float CLOSE_BTN_SIZE = 20.0f;
    private static final float ICON_HALF = 4.5f;
    private static final float ICON_STROKE = 1.4f;

    private static final float[] KNIFE_INCREMENT_PRESETS = {1.0f, 0.5f, 0.25f, 0.125f, 0.0625f};
    private static final String[] KNIFE_INCREMENT_LABELS = {"1.0", "0.5", "0.25", "0.125", "0.0625"};

    private final ViewportUIState state;
    private final ViewportActions actions;
    private final ViewportController viewport;

    private final ImBoolean knifeSnapEnabled = new ImBoolean();

    public ToolPaneRenderer(ViewportUIState state, ViewportActions actions,
                            ViewportController viewport) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
    }

    /**
     * Render the pane overlaying the left edge of the viewport image.
     * Appears/disappears instantly.
     */
    public void render(float imageX, float imageY, float imageWidth, float imageHeight) {
        ViewportUIState.ActiveToolPane pane = state.getActiveToolPane();
        if (pane == ViewportUIState.ActiveToolPane.NONE) {
            return;
        }

        ImGui.setNextWindowPos(imageX, imageY);
        ImGui.setNextWindowSize(PANE_WIDTH, imageHeight);

        int flags = ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoCollapse
                | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoFocusOnAppearing;

        // Use theme WindowBg with slight transparency
        ImVec4 winBg = ImGui.getStyle().getColor(ImGuiCol.WindowBg);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, winBg.x, winBg.y, winBg.z, 0.96f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10, 8);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6, 5);

        if (ImGui.begin("##ToolPane", flags)) {
            renderHeader(pane);
            ImGui.separator();
            ImGui.spacing();

            switch (pane) {
                case CAMERA -> renderCameraPane();
                case RENDERING -> renderRenderingPane();
                case TRANSFORM -> renderTransformPane();
                case KNIFE_SNAP -> renderKnifeSnapPane();
                default -> {}
            }
        }
        ImGui.end();

        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    // ========== Header ==========

    private void renderHeader(ViewportUIState.ActiveToolPane pane) {
        String title = switch (pane) {
            case CAMERA -> "Camera";
            case RENDERING -> "Rendering";
            case TRANSFORM -> "Transform";
            case KNIFE_SNAP -> "Knife Snap";
            case NONE -> "";
        };

        ImGui.textUnformatted(title);

        // Close button — draw-list X icon matching WindowTitleBar style
        ImGui.sameLine(ImGui.getContentRegionAvailX() - CLOSE_BTN_SIZE + 4);

        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
        ImVec4 hoverBg = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, hoverBg.x, hoverBg.y, hoverBg.z, 0.12f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, hoverBg.x, hoverBg.y, hoverBg.z, 0.20f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 0);

        // Position for the icon
        float btnScreenX = ImGui.getCursorScreenPosX();
        float btnScreenY = ImGui.getCursorScreenPosY();

        if (ImGui.button("##closePane", CLOSE_BTN_SIZE, CLOSE_BTN_SIZE)) {
            state.closeToolPane();
        }

        // Draw X icon
        ImVec4 textCol = ImGui.getStyle().getColor(ImGuiCol.Text);
        int iconColor = ImGui.isItemHovered()
                ? ImGui.getColorU32(1.0f, 0.4f, 0.4f, 1.0f)
                : ImGui.getColorU32(textCol.x, textCol.y, textCol.z, 0.6f);
        float cx = btnScreenX + CLOSE_BTN_SIZE * 0.5f;
        float cy = btnScreenY + CLOSE_BTN_SIZE * 0.5f;
        ImGui.getWindowDrawList().addLine(
                cx - ICON_HALF, cy - ICON_HALF,
                cx + ICON_HALF, cy + ICON_HALF,
                iconColor, ICON_STROKE);
        ImGui.getWindowDrawList().addLine(
                cx + ICON_HALF, cy - ICON_HALF,
                cx - ICON_HALF, cy + ICON_HALF,
                iconColor, ICON_STROKE);

        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
    }

    // ========== Camera ==========

    private void renderCameraPane() {
        label("Position");

        labeledSlider("Distance", "##cam_dist", state.getCameraDistance().getData(),
                1.0f, 20.0f, "%.1f", actions::updateCameraDistance);

        labeledSlider("Pitch", "##cam_pitch", state.getCameraPitch().getData(),
                -89.0f, 89.0f, "%.1f\u00B0", actions::updateCameraPitch);

        labeledSlider("Yaw", "##cam_yaw", state.getCameraYaw().getData(),
                -180.0f, 180.0f, "%.1f\u00B0", actions::updateCameraYaw);

        ImGui.spacing();
        label("Field of View");

        labeledSlider("FOV", "##cam_fov", state.getCameraFOV().getData(),
                30.0f, 120.0f, "%.0f\u00B0", actions::updateCameraFOV);

        ImGui.spacing();
        ImGui.spacing();

        if (ImGui.button("Reset Camera", -1, 0)) {
            actions.resetCamera();
        }
    }

    // ========== Rendering ==========

    private void renderRenderingPane() {
        label("Render Mode");

        ImGui.setNextItemWidth(-1);
        if (ImGui.combo("##render_mode", state.getCurrentRenderModeIndex(), state.getRenderModes())) {
            actions.updateRenderMode();
        }
    }

    // ========== Transform ==========

    private void renderTransformPane() {
        boolean gizmoEnabled = viewport.isGizmoEnabled();
        if (ImGui.checkbox("Show Gizmo", new ImBoolean(gizmoEnabled))) {
            viewport.setGizmoEnabled(!gizmoEnabled);
        }

        ImGui.spacing();
        label("Mode");

        GizmoState.Mode currentMode = viewport.getGizmoMode();

        float btnW = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * 2) / 3.0f;
        modeButton("Translate", "G", GizmoState.Mode.TRANSLATE, currentMode, btnW);
        ImGui.sameLine();
        modeButton("Rotate", "R", GizmoState.Mode.ROTATE, currentMode, btnW);
        ImGui.sameLine();
        modeButton("Scale", "S", GizmoState.Mode.SCALE, currentMode, btnW);

        ImGui.spacing();

        if (currentMode == GizmoState.Mode.SCALE) {
            boolean uniform = viewport.getGizmoUniformScaling();
            if (ImGui.checkbox("Uniform Scaling", uniform)) {
                actions.toggleUniformScaling();
            }
        }

        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.Text, 0.50f, 0.50f, 0.50f, 1.0f);
        String hint = switch (currentMode) {
            case TRANSLATE -> "Drag arrows to move along an axis.";
            case ROTATE -> "Drag rings to rotate around an axis.";
            case SCALE -> viewport.getGizmoUniformScaling()
                    ? "Drag any handle to scale uniformly."
                    : "Drag handles to scale per axis.";
        };
        ImGui.textWrapped(hint);
        ImGui.popStyleColor();
    }

    // ========== Knife Snap ==========

    private void renderKnifeSnapPane() {
        KnifeSnapSettings settings = viewport.getKnifeSnapSettings();
        if (settings == null) {
            ImGui.textDisabled("Not available");
            return;
        }

        knifeSnapEnabled.set(settings.isEnabled());
        if (ImGui.checkbox("Enable Snap", knifeSnapEnabled)) {
            settings.setEnabled(knifeSnapEnabled.get());
        }

        ImGui.spacing();
        label("Snap Increment");

        ImGui.setNextItemWidth(-1);
        float currentIncrement = settings.getIncrement();
        String preview = formatKnifeIncrement(currentIncrement);

        if (ImGui.beginCombo("##knifeIncrement", preview)) {
            for (int i = 0; i < KNIFE_INCREMENT_PRESETS.length; i++) {
                boolean isSelected = Math.abs(currentIncrement - KNIFE_INCREMENT_PRESETS[i]) < 0.0001f;
                if (ImGui.selectable(KNIFE_INCREMENT_LABELS[i], isSelected)) {
                    settings.setIncrement(KNIFE_INCREMENT_PRESETS[i]);
                }
                if (isSelected) {
                    ImGui.setItemDefaultFocus();
                }
            }
            ImGui.endCombo();
        }
    }

    // ========== Helpers ==========

    /** Dimmed section label using theme TextDisabled color. */
    private void label(String text) {
        ImVec4 col = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, col.x, col.y, col.z, col.w);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.spacing();
    }

    /** Label + full-width slider. */
    private void labeledSlider(String label, String id, float[] data,
                               float min, float max, String fmt, Runnable onChange) {
        ImGui.textUnformatted(label);
        ImGui.setNextItemWidth(-1);
        if (ImGui.sliderFloat(id, data, min, max, fmt)) {
            onChange.run();
        }
        ImGui.spacing();
    }

    /** Transform mode toggle button with theme-aware active highlight. */
    private void modeButton(String label, String shortcut, GizmoState.Mode mode,
                            GizmoState.Mode current, float width) {
        boolean active = (current == mode);
        if (active) {
            ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
            ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.80f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.65f);
        }
        if (ImGui.button(label, width, 0)) {
            actions.setGizmoMode(mode);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(label + " (" + shortcut + ")");
        }
        if (active) {
            ImGui.popStyleColor(3);
        }
    }

    private String formatKnifeIncrement(float increment) {
        for (int i = 0; i < KNIFE_INCREMENT_PRESETS.length; i++) {
            if (Math.abs(increment - KNIFE_INCREMENT_PRESETS[i]) < 0.0001f) {
                return KNIFE_INCREMENT_LABELS[i];
            }
        }
        return String.valueOf(increment);
    }
}
