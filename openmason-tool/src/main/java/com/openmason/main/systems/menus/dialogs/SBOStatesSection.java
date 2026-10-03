package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.mortar.core.MortarRegionPool;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * "States" section of the SBO export window (1.3+).
 *
 * <p>Renders the "Enable named states" toggle and the dynamic list of state
 * rows as cards, matching {@link SBOStatesEditor}: a Mortar
 * {@link RowHeaderStrip} (DEFAULT/STATE badge, name, source summary, Make
 * default / Replace asset pills, remove) over the ImGui edit widgets, with a
 * plain-ImGui fallback when no Skija context exists. Owns the in-memory state
 * of the rows so the model and texture exports can embed the same widget.
 *
 * <p>Each row's asset is either an OMO (model SBO) or an OMT (texture SBO);
 * the dialog kind is fixed at construction via the {@code modelKind} flag and
 * the supplied file-picker callback.
 *
 * <p>Model-kind sections may additionally attach an optional {@code .omanim}
 * animation clip per state (SBO 1.6+), with a loop-mode selector — clip
 * default, forced loop, or play-once (e.g. a door swing that holds its final
 * pose). Texture-kind sections never show clip controls.
 */
public final class SBOStatesSection implements AutoCloseable {

    /** One pooled Mortar header region per row. */
    private final MortarRegionPool headerPool = new MortarRegionPool();

    /** True when this section gathers OMO paths (model SBO), false for OMT. */
    private final boolean modelKind;

    /** File-picker invoker — called with a Consumer that receives the chosen path. */
    private final Consumer<Consumer<String>> filePicker;

    /** Optional .omanim picker (model SBOs only); null disables clip controls. */
    private final Consumer<Consumer<String>> clipPicker;

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
                            Consumer<Consumer<String>> clipPicker,
                            Supplier<String> currentAssetPath) {
        this.modelKind = modelKind;
        this.filePicker = filePicker;
        this.clipPicker = clipPicker;
        this.currentAssetPath = currentAssetPath;
    }

    /** True when this section offers per-state animation clips. */
    private boolean clipsSupported() {
        return modelKind && clipPicker != null;
    }

    public boolean isEnabled() { return enabled.get(); }

    /** Reset to defaults — typically called on dialog show(). */
    public void reset() {
        enabled.set(false);
        rows.clear();
        defaultRowIndex = 0;
        needsSeed = false;
    }

    /** Render the section into the current window. */
    public void render() {
        ThemedWidgets.sectionLabel("States (Optional)");

        if (ImGui.checkbox("Enable named states##sbo_states_toggle", enabled)) {
            if (enabled.get()) needsSeed = true;
            else { rows.clear(); defaultRowIndex = 0; }
        }
        ImGui.textDisabled(modelKind
                ? "Each state uses its own .OMO model. The DEFAULT state is what places."
                : "Each state uses its own .OMT texture. The DEFAULT state is what places.");

        if (!enabled.get()) {
            return;
        }

        if (needsSeed && rows.isEmpty()) {
            seedDefaultRows();
            needsSeed = false;
        }

        ImGui.dummy(0, 6);

        boolean mortar = headerPool.isAvailable();
        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbo_state_row_" + i);

            boolean removeRequested = mortar ? renderRowHeaderMortar(i, row) : renderRowHeaderFallback(i, row);
            if (removeRequested) removeIndex = i;

            renderRowDetails(row);

            ImGui.dummy(0, 8);
            ImGui.popID();
        }
        headerPool.trim(rows.size());

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            if (defaultRowIndex >= rows.size()) defaultRowIndex = Math.max(0, rows.size() - 1);
            else if (defaultRowIndex > removeIndex) defaultRowIndex--;
        }

        if (ImGui.button("+ Add state##sbo_state_add")) {
            rows.add(new Row("", ""));
        }
    }

    /** Source summary for the header: picked file name, or a hint when empty. */
    private static String assetSummary(Row row) {
        String path = row.path.get().trim();
        if (path.isEmpty()) return "no asset";
        try {
            Path name = Path.of(path).getFileName();
            return name != null ? name.toString() : path;
        } catch (RuntimeException e) {
            return path;
        }
    }

    /** Mortar card header; returns true when remove was clicked. */
    private boolean renderRowHeaderMortar(int i, Row row) {
        String name = row.name.get().trim();
        boolean isDefault = defaultRowIndex == i;

        RowHeaderStrip.Result result = RowHeaderStrip.render(
                headerPool.get(i),
                isDefault ? "DEFAULT" : "STATE", isDefault,
                name.isEmpty() ? "(unnamed)" : name, name.isEmpty(), assetSummary(row),
                List.of(
                        new RowHeaderStrip.Action("default", "Make default", !isDefault),
                        new RowHeaderStrip.Action("asset", "Replace asset...", true)));

        if (result.hovered() != null) {
            switch (result.hovered()) {
                case "default" -> ImGui.setTooltip(isDefault
                        ? "This is the default state"
                        : "The default state's asset is the object's placed/base look");
                case "asset" -> ImGui.setTooltip(modelKind
                        ? "Pick this state's .OMO model"
                        : "Pick this state's .OMT texture");
                case "remove" -> ImGui.setTooltip("Remove this state");
                default -> { }
            }
        }
        if (result.isClicked("default") && !isDefault) defaultRowIndex = i;
        if (result.isClicked("asset")) pickAsset(row);
        return result.removeClicked();
    }

    /** Plain-ImGui header for when no Skija context exists. */
    private boolean renderRowHeaderFallback(int i, Row row) {
        if (ImGui.radioButton("default", defaultRowIndex == i)) defaultRowIndex = i;
        ImGui.sameLine();
        ImGui.textDisabled(assetSummary(row));
        ImGui.sameLine();
        if (ImGui.button("Replace asset...")) pickAsset(row);
        ImGui.sameLine();
        return ThemedWidgets.dangerSoftButton("Remove", EditorWidgets.REMOVE_BUTTON_WIDTH, 0f);
    }

    /** Shared ImGui edit widgets under the header (both render paths). */
    private void renderRowDetails(Row row) {
        ImGui.dummy(0, 2);
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        ImGui.inputTextWithHint("Name##state", "state name", row.name);
        ImGui.popItemWidth();
        ImGui.inputTextWithHint("Source##state", modelKind ? "path/to/state.omo" : "path/to/state.omt", row.path);

        // Model SBOs only: optional animation clip + loop mode
        if (clipsSupported()) {
            ImGui.inputTextWithHint("Clip##state", "animation clip (.omanim, optional)", row.clipPath);
            ImGui.sameLine();
            if (ImGui.smallButton("Browse...##clip")) {
                clipPicker.accept(picked -> {
                    if (picked != null && !picked.isBlank()) row.clipPath.set(picked);
                });
            }
            if (!row.clipPath.get().isBlank()) {
                EditorWidgets.loopModeCombo("Loop mode##state", row.loopMode);
            }
        }
    }

    private void pickAsset(Row row) {
        filePicker.accept(picked -> {
            if (picked != null && !picked.isBlank()) row.path.set(picked);
        });
    }

    /** Release the pooled Mortar header regions before the SkijaContext closes. */
    @Override
    public void close() {
        headerPool.close();
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
            String clip = clipsSupported() ? r.clipPath.get().trim() : "";
            specs.add(new SBOFormat.StateSpec(
                    r.name.get().trim(),
                    r.path.get().trim(),
                    clip.isBlank() ? null : clip,
                    SBOFormat.LoopMode.values()[r.loopMode.get()]));
        }
        return specs;
    }

    public String getDefaultStateName() {
        if (rows.isEmpty() || defaultRowIndex < 0 || defaultRowIndex >= rows.size()) {
            return "";
        }
        return rows.get(defaultRowIndex).name.get().trim();
    }

    private static final class Row {
        final ImString name = new ImString(64);
        final ImString path = new ImString(512);
        final ImString clipPath = new ImString(512);
        final ImInt loopMode = new ImInt(0); // LoopMode ordinal

        Row(String n, String p) {
            if (n != null) name.set(n);
            if (p != null) path.set(p);
        }
    }
}
