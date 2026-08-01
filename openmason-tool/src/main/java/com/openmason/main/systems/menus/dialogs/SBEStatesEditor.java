package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.main.systems.mortar.core.MortarRegionPool;
import imgui.ImGui;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Editable states section for the SBE editor.
 *
 * <p>Owns the actual per-state byte payloads (model override and animation
 * clip). Existing states from a loaded SBE start with their original embedded
 * bytes; new states or replaced assets pull bytes from a user-picked file. On
 * save, this component produces a {@link SBEFormat.StateEntry} list plus a
 * filename-keyed byte map that
 * {@link com.openmason.engine.format.sbe.SBESerializer#exportFromDocument} consumes.
 *
 * <p>Mirrors {@link SBOStatesEditor} structurally so the two formats share the
 * same authoring idioms: rows are cards with a Mortar {@link RowHeaderStrip}
 * (STATE badge, name, model/clip summary, remove) over the ImGui name input
 * and asset slots, with per-row inline validation and an ImGui fallback.
 */
public final class SBEStatesEditor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SBEStatesEditor.class);

    private static final class Row {
        final ImString name = new ImString(64);

        byte[] modelBytes;
        String modelSourceLabel;

        byte[] clipBytes;
        String clipSourceLabel;

        Row(String initialName) {
            if (initialName != null) name.set(initialName);
        }

        boolean hasModel() { return modelBytes != null; }
        boolean hasClip() { return clipBytes != null; }
    }

    private final Runnable onDirty;
    private final Consumer<Consumer<String>> omoPicker;
    private final Consumer<Consumer<String>> omaPicker;

    private final List<Row> rows = new ArrayList<>();

    private final MortarRegionPool headerPool = new MortarRegionPool();

    public SBEStatesEditor(Runnable onDirty,
                            Consumer<Consumer<String>> omoPicker,
                            Consumer<Consumer<String>> omaPicker) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.omoPicker = omoPicker;
        this.omaPicker = omaPicker;
    }

    // ========================================================================
    // Loading and saving
    // ========================================================================

    /**
     * Populate rows from a parsed SBE. The {@code stateAssetBytes} map is the
     * filename → bytes map returned by {@code SBEParser.parseRaw}.
     */
    public void load(SBEFormat.Document doc, Map<String, byte[]> stateAssetBytes) {
        rows.clear();
        for (SBEFormat.StateEntry e : doc.states()) {
            Row r = new Row(e.name());
            if (e.hasModelOverride()) {
                byte[] bytes = stateAssetBytes != null
                        ? stateAssetBytes.get(e.modelOverride().filename())
                        : null;
                r.modelBytes = bytes;
                r.modelSourceLabel = "(original)";
            }
            if (e.hasAnimation()) {
                byte[] bytes = stateAssetBytes != null
                        ? stateAssetBytes.get(e.animation().filename())
                        : null;
                r.clipBytes = bytes;
                r.clipSourceLabel = "(original)";
            }
            rows.add(r);
        }
    }

    /**
     * Builds {@link SBEFormat.StateEntry} stubs for the document. Filenames are
     * deterministic from the state name; checksums and clip metadata are
     * recomputed by the serializer on save, so they're left as placeholders here.
     */
    public List<SBEFormat.StateEntry> toStateEntries() {
        List<SBEFormat.StateEntry> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            String name = r.name.get().trim();

            SBEFormat.AssetRef model = r.hasModel()
                    ? new SBEFormat.AssetRef(SBEFormat.stateModelPath(name), "")
                    : null;

            SBEFormat.AnimationRef anim = r.hasClip()
                    ? new SBEFormat.AnimationRef(
                            SBEFormat.stateClipPath(name), "", name,
                            0f, 30f, false, List.of())
                    : null;

            out.add(new SBEFormat.StateEntry(name, model, anim));
        }
        return out;
    }

    /**
     * Per-state asset byte map for {@code exportFromDocument}, keyed by the
     * ZIP entry filename the serializer expects.
     */
    public Map<String, byte[]> stateAssetBytesByFilename() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Row r : rows) {
            String name = r.name.get().trim();
            if (r.hasModel()) out.put(SBEFormat.stateModelPath(name), r.modelBytes);
            if (r.hasClip())  out.put(SBEFormat.stateClipPath(name), r.clipBytes);
        }
        return out;
    }

    /**
     * Validate that every row has a non-blank, unique name. Returns null when
     * valid, otherwise a human-readable error message.
     */
    public String validate() {
        Set<String> duplicates = duplicateNames();
        for (int i = 0; i < rows.size(); i++) {
            String error = validateRow(rows.get(i), i, duplicates);
            if (error != null) return error;
        }
        return null;
    }

    private static String validateRow(Row r, int index, Set<String> duplicates) {
        String name = r.name.get().trim();
        if (name.isBlank()) return "State " + (index + 1) + " has no name.";
        if (duplicates.contains(name)) return "Duplicate state name: " + name;
        return null;
    }

    private Set<String> duplicateNames() {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (Row r : rows) {
            String name = r.name.get().trim();
            if (!name.isBlank() && !seen.add(name)) duplicates.add(name);
        }
        return duplicates;
    }

    public boolean hasRows() { return !rows.isEmpty(); }

    // ========================================================================
    // Rendering
    // ========================================================================

    public void render() {
        boolean mortar = headerPool.isAvailable();

        if (rows.isEmpty()) {
            ImGui.textDisabled("No states declared. Add one to attach a per-state model override or animation clip.");
        } else {
            ImGui.text("Declared states (" + rows.size() + ")");
            ImGui.textDisabled("Per-state model overrides fall back to the base OMO when unset.");
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        Set<String> duplicates = duplicateNames();
        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbe_state_row_" + i);

            boolean removeRequested = mortar
                    ? renderRowHeaderMortar(i, row)
                    : renderRowHeaderFallback(row);
            if (removeRequested) removeIndex = i;

            renderRowDetails(row);

            String error = validateRow(row, i, duplicates);
            if (error != null) EditorWidgets.inlineError(error);

            ImGui.dummy(0, 8);
            ImGui.popID();
        }

        headerPool.trim(rows.size());

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            onDirty.run();
        }

        if (ImGui.button("+ Add state")) {
            rows.add(new Row("state" + (rows.size() + 1)));
            onDirty.run();
        }
    }

    /** Mortar card header; returns true when remove was clicked. */
    private boolean renderRowHeaderMortar(int i, Row row) {
        String name = row.name.get().trim();
        String detail = assetSummary(row);

        RowHeaderStrip.Result result = RowHeaderStrip.render(
                headerPool.get(i), "STATE", false,
                name.isEmpty() ? "(unnamed)" : name, name.isEmpty(), detail,
                List.of());

        if ("remove".equals(result.hovered())) {
            ImGui.setTooltip("Remove this state");
        }
        return result.removeClicked();
    }

    private static String assetSummary(Row row) {
        String model = row.hasModel()
                ? (row.modelSourceLabel != null ? row.modelSourceLabel : "(loaded)")
                : "base OMO";
        String clip = row.hasClip()
                ? (row.clipSourceLabel != null ? row.clipSourceLabel : "(loaded)")
                : "none";
        return "model: " + model + "   clip: " + clip;
    }

    /** Plain-ImGui header for when no Skija context exists. */
    private boolean renderRowHeaderFallback(Row row) {
        ImGui.textDisabled(assetSummary(row));
        ImGui.sameLine();
        return EditorWidgets.dangerButton("Remove", 70.0f);
    }

    /** Shared ImGui edit widgets under the header (both render paths). */
    private void renderRowDetails(Row row) {
        ImGui.dummy(0, 2);
        ImGui.textDisabled("  ");
        ImGui.sameLine();
        ImGui.pushItemWidth(180.0f);
        if (ImGui.inputTextWithHint("Name##state", "state name", row.name)) onDirty.run();
        ImGui.popItemWidth();

        EditorWidgets.assetSlot("Model:", row.modelSourceLabel, row.modelBytes,
                () -> pickModel(row), () -> clearModel(row));
        EditorWidgets.assetSlot("Clip:", row.clipSourceLabel, row.clipBytes,
                () -> pickClip(row), () -> clearClip(row));
    }

    // ========================================================================
    // Asset pickers
    // ========================================================================

    private void pickModel(Row row) {
        if (omoPicker == null) return;
        omoPicker.accept(picked -> readBytesInto(picked, row, true));
    }

    private void pickClip(Row row) {
        if (omaPicker == null) return;
        omaPicker.accept(picked -> readBytesInto(picked, row, false));
    }

    private void readBytesInto(String picked, Row row, boolean model) {
        if (picked == null || picked.isBlank()) return;
        try {
            Path path = Path.of(picked);
            byte[] bytes = Files.readAllBytes(path);
            String label = path.getFileName().toString();
            if (model) {
                row.modelBytes = bytes;
                row.modelSourceLabel = label;
            } else {
                row.clipBytes = bytes;
                row.clipSourceLabel = label;
            }
            onDirty.run();
        } catch (IOException e) {
            logger.error("Failed to read state asset {}", picked, e);
        }
    }

    private void clearModel(Row row) {
        row.modelBytes = null;
        row.modelSourceLabel = null;
        onDirty.run();
    }

    private void clearClip(Row row) {
        row.clipBytes = null;
        row.clipSourceLabel = null;
        onDirty.run();
    }

    /** Release the pooled Mortar header regions before the SkijaContext closes. */
    @Override
    public void close() {
        headerPool.close();
    }
}
