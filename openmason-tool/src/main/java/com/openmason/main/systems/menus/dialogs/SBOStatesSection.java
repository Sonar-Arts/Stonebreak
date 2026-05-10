package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reusable "States" section for SBO export modals (1.3+).
 *
 * <p>Renders the "Enable States" toggle and the dynamic list of state rows
 * (name + asset path + default radio + remove). Owns the in-memory state of
 * the rows so both export windows can embed the same widget without
 * duplicating buffer management.
 *
 * <p>Each row's asset is either an OMO (model SBO) or an OMT (texture SBO);
 * the dialog kind is fixed at construction via the {@code modelKind} flag and
 * the supplied file-picker callback.
 */
public final class SBOStatesSection {

    /** True when this section gathers OMO paths (model SBO), false for OMT. */
    private final boolean modelKind;

    /** File-picker invoker — called with a Consumer that receives the chosen path. */
    private final Consumer<Consumer<String>> filePicker;

    /**
     * Source for the "current asset" path so the default-state row can be
     * pre-filled. Returns the active OMO/OMT path, or null if none is loaded.
     */
    private final Supplier<String> currentAssetPath;

    private final ImBoolean enabled = new ImBoolean(false);
    private final List<Row> rows = new ArrayList<>();
    private int defaultRowIndex = 0;

    /** Set when the enable toggle is flipped on; consumed by next render to seed rows. */
    private boolean needsSeed = false;

    public SBOStatesSection(boolean modelKind,
                            Consumer<Consumer<String>> filePicker,
                            Supplier<String> currentAssetPath) {
        this.modelKind = modelKind;
        this.filePicker = filePicker;
        this.currentAssetPath = currentAssetPath;
    }

    public boolean isEnabled() { return enabled.get(); }

    /** Reset to defaults — typically called on dialog show(). */
    public void reset() {
        enabled.set(false);
        rows.clear();
        defaultRowIndex = 0;
        needsSeed = false;
    }

    /**
     * Render the section. {@code formOffsetX}, {@code labelWidth},
     * {@code inputWidth} and {@code rowSpacing} should match the host window's
     * form metrics so layout stays consistent.
     */
    public void render(float formOffsetX, float labelWidth, float inputWidth, float rowSpacing) {
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("States (Optional)");
        ImGui.spacing();

        ImGui.setCursorPosX(formOffsetX);
        if (ImGui.checkbox("Enable named states##sbo_states_toggle", enabled)) {
            if (enabled.get()) needsSeed = true;
            else { rows.clear(); defaultRowIndex = 0; }
        }

        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(modelKind
                ? "Each state uses its own .OMO model"
                : "Each state uses its own .OMT texture");

        if (!enabled.get()) {
            ImGui.dummy(0, rowSpacing);
            return;
        }

        if (needsSeed && rows.isEmpty()) {
            seedDefaultRows();
            needsSeed = false;
        }

        ImGui.dummy(0, rowSpacing);

        float totalWidth = labelWidth + inputWidth;
        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);

            // Row 1: name + default radio
            ImGui.setCursorPosX(formOffsetX);
            ImGui.pushItemWidth(140.0f);
            ImGui.inputTextWithHint("##sbo_state_name_" + i, "state name", row.name);
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.radioButton("default##sbo_state_default_" + i, defaultRowIndex == i)) {
                defaultRowIndex = i;
            }

            ImGui.sameLine();
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, ImColor.rgba(0.5f, 0.18f, 0.18f, 0.45f));
            if (ImGui.button("X##sbo_state_remove_" + i, 22.0f, 0.0f)) {
                removeIndex = i;
            }
            ImGui.popStyleColor();

            // Row 2: path + browse
            ImGui.setCursorPosX(formOffsetX);
            float pathWidth = totalWidth - 80.0f;
            ImGui.pushItemWidth(pathWidth);
            String hint = modelKind ? "path/to/state.omo" : "path/to/state.omt";
            ImGui.inputTextWithHint("##sbo_state_path_" + i, hint, row.path);
            ImGui.popItemWidth();

            ImGui.sameLine();
            final int rowIdx = i;
            if (ImGui.button("Browse##sbo_state_browse_" + i, 70.0f, 0.0f)) {
                filePicker.accept(picked -> {
                    if (picked != null && !picked.isBlank()) {
                        rows.get(rowIdx).path.set(picked);
                    }
                });
            }

            ImGui.dummy(0, rowSpacing);
        }

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            if (defaultRowIndex >= rows.size()) defaultRowIndex = Math.max(0, rows.size() - 1);
            else if (defaultRowIndex > removeIndex) defaultRowIndex--;
        }

        ImGui.setCursorPosX(formOffsetX);
        if (ImGui.button("+ Add state##sbo_state_add", 110.0f, 0.0f)) {
            rows.add(new Row("", ""));
        }

        ImGui.dummy(0, rowSpacing);
    }

    /**
     * Seed the rows with the current asset as the default, plus an empty
     * second row. Saves a click — most users will want at least 2 states
     * with the current model/texture as the default.
     */
    private void seedDefaultRows() {
        String current = currentAssetPath.get();
        if (current != null && !current.isBlank()) {
            String defaultName = deriveStateName(current, modelKind ? ".omo" : ".omt");
            rows.add(new Row(defaultName, current));
        } else {
            rows.add(new Row("default", ""));
        }
        rows.add(new Row("", ""));
        defaultRowIndex = 0;
    }

    private static String deriveStateName(String path, String ext) {
        String fname = Path.of(path).getFileName().toString();
        if (fname.toLowerCase().endsWith(ext)) {
            fname = fname.substring(0, fname.length() - ext.length());
        }
        return fname.isBlank() ? "default" : fname.toLowerCase();
    }

    public List<SBOFormat.StateSpec> toStateSpecs() {
        List<SBOFormat.StateSpec> specs = new ArrayList<>(rows.size());
        for (Row r : rows) {
            specs.add(new SBOFormat.StateSpec(r.name.get().trim(), r.path.get().trim()));
        }
        return specs;
    }

    public String getDefaultStateName() {
        if (rows.isEmpty() || defaultRowIndex < 0 || defaultRowIndex >= rows.size()) {
            return "";
        }
        return rows.get(defaultRowIndex).name.get().trim();
    }

    /** Render an inline help banner explaining states briefly. Optional. */
    public void renderHelp(float formOffsetX, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImVec2 pos = ImGui.getCursorScreenPos();
        float padding = 6.0f;
        float h = ImGui.getTextLineHeight() * 2 + padding * 2;
        draw.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + h,
                ImColor.rgba(0.36f, 0.61f, 0.84f, 0.10f), 4.0f);
        ImGui.setCursorScreenPos(pos.x + padding, pos.y + padding);
        ImGui.textDisabled("States let one SBO carry multiple visual variants");
        ImGui.setCursorScreenPos(pos.x + padding, pos.y + padding + ImGui.getTextLineHeight());
        ImGui.textDisabled("(e.g. wooden_bucket: empty / water / milk).");
        ImGui.setCursorScreenPos(pos.x, pos.y + h);
    }

    private static final class Row {
        final ImString name = new ImString(64);
        final ImString path = new ImString(512);

        Row(String n, String p) {
            if (n != null) name.set(n);
            if (p != null) path.set(p);
        }
    }
}
