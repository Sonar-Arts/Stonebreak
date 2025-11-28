package com.openmason.main.systems.menus.textureCreator.panels;

import com.openmason.main.systems.menus.textureCreator.SymmetryState;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Symmetry/Mirror mode panel for texture editor.
 */
public class SymmetryPanel {

    private static final Logger logger = LoggerFactory.getLogger(SymmetryPanel.class);

    // ImGui state holders
    private final ImInt selectedModeIndex = new ImInt(0);
    private final ImInt offsetXSlider = new ImInt(0);
    private final ImInt offsetYSlider = new ImInt(0);
    private final ImBoolean showAxisLinesCheckbox = new ImBoolean(true);

    // Per-tool checkboxes
    private final ImBoolean pencilToolEnabled = new ImBoolean(true);
    private final ImBoolean brushToolEnabled = new ImBoolean(true);
    private final ImBoolean eraserToolEnabled = new ImBoolean(true);
    private final ImBoolean fillToolEnabled = new ImBoolean(false);
    private final ImBoolean lineToolEnabled = new ImBoolean(false);
    private final ImBoolean shapeToolEnabled = new ImBoolean(false);

    // Offset limits
    private static final int MAX_OFFSET = 100;
    private static final int MIN_OFFSET = -100;

    /**
     * Create symmetry panel.
     */
    public SymmetryPanel() {
        logger.debug("Symmetry panel created");
    }

    /**
     * Render symmetry panel.
     *
     * @param symmetryState Symmetry state to modify
     * @param canvasWidth Current canvas width (for offset range context)
     * @param canvasHeight Current canvas height (for offset range context)
     */
    public void render(SymmetryState symmetryState, int canvasWidth, int canvasHeight) {
        if (symmetryState == null) {
            ImGui.text("No symmetry state available");
            return;
        }

        // Sync ImGui state with symmetry state
        syncStateFromSymmetry(symmetryState);

        ImGui.spacing();

        // Section 1: Mode Selection
        renderSectionHeader("Symmetry Mode");
        ImGui.indent();
        renderModeSelector(symmetryState);
        ImGui.unindent();

        ImGui.spacing();
        ImGui.spacing();

        // Section 2: Axis Offsets (only if mode is not NONE)
        if (symmetryState.getMode() != SymmetryState.SymmetryMode.NONE) {
            renderSectionHeader("Axis Position");
            ImGui.indent();
            renderAxisOffsets(symmetryState, canvasWidth, canvasHeight);
            ImGui.unindent();

            ImGui.spacing();
            ImGui.spacing();

            // Section 3: Display Options
            renderSectionHeader("Display");
            ImGui.indent();
            renderDisplayOptions(symmetryState);
            ImGui.unindent();

            ImGui.spacing();
            ImGui.spacing();

            // Section 4: Tool Settings
            renderSectionHeader("Enabled Tools");
            ImGui.indent();
            renderToolToggles(symmetryState);
            ImGui.unindent();

            ImGui.spacing();
            ImGui.spacing();
        }

        ImGui.separator();
        ImGui.spacing();

        // Reset button
        renderResetButton(symmetryState);
    }

    // ========================================
    // Section Renderers
    // ========================================

    /**
     * Render symmetry mode selector.
     */
    private void renderModeSelector(SymmetryState symmetryState) {
        ImGui.text("Select symmetry mode:");
        ImGui.spacing();

        SymmetryState.SymmetryMode[] modes = SymmetryState.SymmetryMode.values();
        String[] modeNames = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeNames[i] = modes[i].getDisplayName();
        }

        // Radio buttons for mode selection
        for (int i = 0; i < modes.length; i++) {
            if (ImGui.radioButton(modeNames[i] + "##mode_" + i, selectedModeIndex.get() == i)) {
                selectedModeIndex.set(i);
                symmetryState.setMode(modes[i]);
                logger.debug("Symmetry mode changed to: {}", modes[i]);
            }

            // Tooltip with description
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(modes[i].getDescription());
            }
        }
    }

    /**
     * Render axis offset sliders.
     */
    private void renderAxisOffsets(SymmetryState symmetryState, int canvasWidth, int canvasHeight) {
        ImGui.text("Offset from center (pixels):");
        ImGui.spacing();

        // X Offset
        renderSliderSetting(
            "X Offset",
            "Horizontal offset from canvas center.\n" +
            "Negative = left, Positive = right\n" +
            "Canvas center: " + (canvasWidth / 2) + " pixels",
            offsetXSlider,
                symmetryState::setAxisOffsetX
        );

        // Y Offset
        renderSliderSetting(
            "Y Offset",
            "Vertical offset from canvas center.\n" +
            "Negative = up, Positive = down\n" +
            "Canvas center: " + (canvasHeight / 2) + " pixels",
            offsetYSlider,
                symmetryState::setAxisOffsetY
        );
    }

    /**
     * Render display options.
     */
    private void renderDisplayOptions(SymmetryState symmetryState) {
        renderCheckboxSetting(
                showAxisLinesCheckbox,
            symmetryState::setShowAxisLines
        );
    }

    /**
     * Render per-tool enable/disable toggles.
     */
    private void renderToolToggles(SymmetryState symmetryState) {
        ImGui.text("Enable symmetry for these tools:");
        ImGui.spacing();

        // Pixel-based tools
        ImGui.textDisabled("Drawing Tools:");
        ImGui.indent();
        renderToolCheckbox("Pencil", pencilToolEnabled, "PencilTool", symmetryState);
        renderToolCheckbox("Brush", brushToolEnabled, "BrushTool", symmetryState);
        renderToolCheckbox("Eraser", eraserToolEnabled, "EraserTool", symmetryState);
        ImGui.unindent();

        ImGui.spacing();

        // Other tools
        ImGui.textDisabled("Other Tools:");
        ImGui.indent();
        renderToolCheckbox("Fill Bucket", fillToolEnabled, "FillTool", symmetryState);
        renderToolCheckbox("Line", lineToolEnabled, "LineTool", symmetryState);
        renderToolCheckbox("Shapes", shapeToolEnabled, "ShapeTool", symmetryState);
        ImGui.unindent();
    }

    /**
     * Render a single tool checkbox.
     */
    private void renderToolCheckbox(String displayName, ImBoolean checkbox, String toolClassName, SymmetryState symmetryState) {
        if (ImGui.checkbox(displayName + "##" + toolClassName, checkbox)) {
            symmetryState.setEnabledForTool(toolClassName, checkbox.get());
            logger.debug("Symmetry {} for tool: {}", checkbox.get() ? "enabled" : "disabled", toolClassName);
        }
    }

    /**
     * Render reset to defaults button.
     */
    private void renderResetButton(SymmetryState symmetryState) {
        if (ImGui.button("Reset to Defaults", 150, 0)) {
            symmetryState.resetToDefaults();
            syncStateFromSymmetry(symmetryState); // Refresh UI
            logger.info("Symmetry settings reset to defaults");
        }
    }

    // ========================================
    // DRY Helper Methods
    // ========================================

    /**
     * Render a section header.
     */
    private void renderSectionHeader(String title) {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        imgui.ImVec2 textSize = ImGui.calcTextSize(title);

        int borderColor = ImGui.colorConvertFloat4ToU32(0.26f, 0.59f, 0.98f, 1.0f); // Bright blue

        float borderWidth = 4.0f;
        float padding = 8.0f;
        float height = textSize.y + padding * 2;
        float boxWidth = textSize.x + padding * 2;

        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Left accent bar
        drawList.addRectFilled(
            cursorPos.x,
            cursorPos.y,
            cursorPos.x + borderWidth,
            cursorPos.y + height,
            borderColor,
            2.0f
        );

        // Compact background box
        int bgColor = ImGui.colorConvertFloat4ToU32(0.25f, 0.28f, 0.35f, 1.0f);
        drawList.addRectFilled(
            cursorPos.x + borderWidth,
            cursorPos.y,
            cursorPos.x + borderWidth + boxWidth,
            cursorPos.y + height,
            bgColor,
            2.0f
        );

        // Render text
        ImGui.setCursorScreenPos(cursorPos.x + borderWidth + padding, cursorPos.y + padding);
        ImGui.text(title);

        // Reset cursor
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y + height);
        ImGui.spacing();
        ImGui.spacing();

        // Separator
        renderBigSeparator();
    }

    /**
     * Render a prominent separator line.
     */
    private void renderBigSeparator() {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availWidth = ImGui.getContentRegionAvailX();
        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        int separatorColor = ImGui.colorConvertFloat4ToU32(0.5f, 0.5f, 0.55f, 0.6f);
        drawList.addLine(
            cursorPos.x,
            cursorPos.y,
            cursorPos.x + availWidth,
            cursorPos.y,
            separatorColor,
            2.0f
        );

        ImGui.dummy(0, 8.0f);
    }

    /**
     * Render a slider setting (integer version).
     */
    private void renderSliderSetting(String label, String tooltip, ImInt value,
                                     Consumer<Integer> onChanged) {
        ImGui.text(label);
        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        if (ImGui.sliderInt("##" + label, value.getData(), SymmetryPanel.MIN_OFFSET, SymmetryPanel.MAX_OFFSET, "%d px")) {
            onChanged.accept(value.get());
        }

        ImGui.spacing();
    }

    /**
     * Render a checkbox setting.
     */
    private void renderCheckboxSetting(ImBoolean value,
                                       Consumer<Boolean> onChanged) {
        if (ImGui.checkbox("Show Axis Lines", value)) {
            onChanged.accept(value.get());
        }

        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Display visual indicators showing the symmetry axes on the canvas.\nWhen enabled, you'll see lines marking the horizontal and/or vertical symmetry axes.");
        }

        ImGui.spacing();
    }

    /**
     * Sync ImGui state from symmetry state.
     */
    private void syncStateFromSymmetry(SymmetryState symmetryState) {
        // Sync mode
        selectedModeIndex.set(symmetryState.getMode().ordinal());

        // Sync offsets
        offsetXSlider.set(symmetryState.getAxisOffsetX());
        offsetYSlider.set(symmetryState.getAxisOffsetY());

        // Sync display options
        showAxisLinesCheckbox.set(symmetryState.isShowAxisLines());

        // Sync tool toggles
        pencilToolEnabled.set(symmetryState.isEnabledForTool("PencilTool"));
        brushToolEnabled.set(symmetryState.isEnabledForTool("BrushTool"));
        eraserToolEnabled.set(symmetryState.isEnabledForTool("EraserTool"));
        fillToolEnabled.set(symmetryState.isEnabledForTool("FillTool"));
        lineToolEnabled.set(symmetryState.isEnabledForTool("LineTool"));
        shapeToolEnabled.set(symmetryState.isEnabledForTool("ShapeTool"));
    }
}
