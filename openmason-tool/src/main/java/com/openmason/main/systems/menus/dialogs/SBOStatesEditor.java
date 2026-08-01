package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
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
 * Editable States section for the SBO editor.
 *
 * <p>Unlike {@link SBOStatesSection} (which collects source paths for the
 * export-from-scratch flow), this component owns the actual state byte
 * payloads. Existing states from the loaded SBO start out with their original
 * embedded bytes; new states load bytes from a user-picked OMO/OMT file. On
 * save, the editor produces a {@link SBOFormat.StateEntry} list plus a name
 * map of bytes that {@link com.openmason.engine.format.sbo.SBOSerializer#exportFromDocument}
 * consumes directly.
 *
 * <p>Rows render as cards: a Mortar {@link RowHeaderStrip} (DEFAULT/STATE
 * badge, state name, source summary, Make default / Replace asset pills,
 * remove) over the ImGui edit widgets, with per-row inline validation. Plain
 * ImGui fallback when no Skija context exists.
 */
public final class SBOStatesEditor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SBOStatesEditor.class);

    private static final class Row {
        final ImString name = new ImString(64);
        byte[] bytes;
        String sourceLabel;
        // Optional animation clip (1.6+, model SBOs only)
        byte[] clipBytes;
        String clipLabel;
        boolean clipLoop;

        Row(String n, byte[] b, String label) {
            if (n != null) name.set(n);
            this.bytes = b;
            this.sourceLabel = label;
        }

        boolean hasClip() {
            return clipBytes != null && clipBytes.length > 0;
        }
    }

    private final Runnable onDirty;
    private final Consumer<Consumer<String>> omoPicker;
    private final Consumer<Consumer<String>> omtPicker;
    private final Consumer<Consumer<String>> clipPicker;

    private final List<Row> rows = new ArrayList<>();
    private int defaultRowIndex = 0;
    private boolean modelKind = true;

    private final MortarRegionPool headerPool = new MortarRegionPool();

    public SBOStatesEditor(Runnable onDirty,
                           Consumer<Consumer<String>> omoPicker,
                           Consumer<Consumer<String>> omtPicker,
                           Consumer<Consumer<String>> clipPicker) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.omoPicker = omoPicker;
        this.omtPicker = omtPicker;
        this.clipPicker = clipPicker;
    }

    public void load(SBOFormat.Document doc,
                     Map<String, byte[]> stateBytes,
                     Map<String, byte[]> stateClipBytes,
                     byte[] defaultBytes) {
        rows.clear();
        defaultRowIndex = 0;
        this.modelKind = doc.isModelBearing();
        if (doc.hasStates()) {
            int i = 0;
            for (SBOFormat.StateEntry e : doc.states()) {
                byte[] bytes = stateBytes != null ? stateBytes.get(e.name()) : null;
                if (bytes == null && e.name().equals(doc.defaultStateName())) {
                    bytes = defaultBytes;
                }
                Row row = new Row(e.name(), bytes, "(original)");
                if (e.hasAnimation()) {
                    row.clipBytes = stateClipBytes != null ? stateClipBytes.get(e.name()) : null;
                    row.clipLabel = e.animation().clipName() != null
                            ? e.animation().clipName() : "(original)";
                    row.clipLoop = e.animation().loop();
                }
                rows.add(row);
                if (e.name().equals(doc.defaultStateName())) defaultRowIndex = i;
                i++;
            }
        }
    }

    public boolean hasStates() {
        return !rows.isEmpty();
    }

    /** Returns the default state's bytes, or {@code fallback} when stateless. */
    public byte[] defaultBytes(byte[] fallback) {
        if (rows.isEmpty() || defaultRowIndex < 0 || defaultRowIndex >= rows.size()) {
            return fallback;
        }
        byte[] b = rows.get(defaultRowIndex).bytes;
        return b != null ? b : fallback;
    }

    /** Returns the default state name, or {@code null} when stateless. */
    public String defaultStateName() {
        if (rows.isEmpty() || defaultRowIndex < 0 || defaultRowIndex >= rows.size()) {
            return null;
        }
        return rows.get(defaultRowIndex).name.get().trim();
    }

    /**
     * Builds {@link SBOFormat.StateEntry} stubs for the document. Filename and
     * checksum are placeholders — the serializer rebuilds them from the actual
     * bytes on save.
     */
    public List<SBOFormat.StateEntry> toStateEntries() {
        if (rows.isEmpty()) return List.of();
        List<SBOFormat.StateEntry> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            String name = r.name.get().trim();
            String filename = SBOFormat.STATES_DIR_PREFIX + name + "/"
                    + (modelKind ? SBOFormat.EMBEDDED_OMO_FILENAME : SBOFormat.EMBEDDED_OMT_FILENAME);
            // Animation ref stub: only the loop flag is authored here — the
            // serializer re-probes file/checksum/clipName/duration/fps from
            // the bytes on save but preserves this loop value.
            SBOFormat.AnimationRef anim = r.hasClip()
                    ? new SBOFormat.AnimationRef(SBOFormat.stateClipPath(name), "", null,
                            0f, 0f, r.clipLoop, List.of())
                    : null;
            out.add(new SBOFormat.StateEntry(name, filename, modelKind, "", anim));
        }
        return out;
    }

    /** Per-state byte map for {@code exportFromDocument}. */
    public Map<String, byte[]> stateBytesByName() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Row r : rows) {
            out.put(r.name.get().trim(), r.bytes);
        }
        return out;
    }

    /** Per-state clip byte map for {@code exportFromDocument} (1.6+). */
    public Map<String, byte[]> stateClipBytesByName() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.hasClip()) out.put(r.name.get().trim(), r.clipBytes);
        }
        return out;
    }

    /**
     * Validates that every row has a non-blank, unique name and bytes loaded.
     * Returns {@code null} when valid, or a human-readable error message.
     */
    public String validate() {
        if (rows.isEmpty()) return null;
        Set<String> duplicates = duplicateNames();
        for (int i = 0; i < rows.size(); i++) {
            String error = validateRow(rows.get(i), i, duplicates);
            if (error != null) return error;
        }
        if (defaultRowIndex < 0 || defaultRowIndex >= rows.size()) {
            return "No default state selected.";
        }
        return null;
    }

    private static String validateRow(Row r, int index, Set<String> duplicates) {
        String name = r.name.get().trim();
        if (name.isBlank()) return "State " + (index + 1) + " has no name.";
        if (duplicates.contains(name)) return "Duplicate state name: " + name;
        if (r.bytes == null || r.bytes.length == 0) {
            return "State '" + name + "' has no asset bytes.";
        }
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

    public void render() {
        boolean mortar = headerPool.isAvailable();

        if (rows.isEmpty()) {
            ImGui.textDisabled("No states declared. This SBO has a single embedded "
                    + (modelKind ? ".OMO model." : ".OMT texture."));
        } else {
            ImGui.text("Declared states (" + rows.size() + ")");
            ImGui.textDisabled(modelKind
                    ? "Each state carries its own .OMO model. The DEFAULT state is what places."
                    : "Each state carries its own .OMT texture. The DEFAULT state is what places.");
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        Set<String> duplicates = duplicateNames();
        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbo_state_row_" + i);

            boolean removeRequested = mortar
                    ? renderRowHeaderMortar(i, row)
                    : renderRowHeaderFallback(i, row);
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
            if (defaultRowIndex >= rows.size()) defaultRowIndex = Math.max(0, rows.size() - 1);
            else if (defaultRowIndex > removeIndex) defaultRowIndex--;
            onDirty.run();
        }

        if (ImGui.button("+ Add state")) {
            Row added = new Row("state" + (rows.size() + 1), null, "(unset)");
            rows.add(added);
            if (rows.size() == 1) defaultRowIndex = 0;
            onDirty.run();
            pickAsset(added);
        }
    }

    /** Mortar card header; returns true when remove was clicked. */
    private boolean renderRowHeaderMortar(int i, Row row) {
        String name = row.name.get().trim();
        boolean isDefault = defaultRowIndex == i;
        String size = row.bytes != null ? EditorWidgets.humanBytes(row.bytes.length) : "no bytes";
        String detail = (row.sourceLabel != null ? row.sourceLabel : "?") + "  " + size;

        RowHeaderStrip.Result result = RowHeaderStrip.render(
                headerPool.get(i),
                isDefault ? "DEFAULT" : "STATE", isDefault,
                name.isEmpty() ? "(unnamed)" : name, name.isEmpty(), detail,
                List.of(
                        new RowHeaderStrip.Action("default", "Make default", !isDefault),
                        new RowHeaderStrip.Action("asset", "Replace asset...", true)));

        if (result.hovered() != null) {
            switch (result.hovered()) {
                case "default" -> ImGui.setTooltip(isDefault
                        ? "This is the default state"
                        : "The default state's asset is the block's placed/base look");
                case "asset" -> ImGui.setTooltip(modelKind
                        ? "Swap this state's embedded .OMO model"
                        : "Swap this state's embedded .OMT texture");
                case "remove" -> ImGui.setTooltip("Remove this state");
                default -> { }
            }
        }
        if (result.isClicked("default") && !isDefault) {
            defaultRowIndex = i;
            onDirty.run();
        }
        if (result.isClicked("asset")) {
            pickAsset(row);
        }
        return result.removeClicked();
    }

    /** Plain-ImGui header for when no Skija context exists. */
    private boolean renderRowHeaderFallback(int i, Row row) {
        boolean remove = false;
        if (ImGui.radioButton("default", defaultRowIndex == i)) {
            defaultRowIndex = i;
            onDirty.run();
        }
        ImGui.sameLine();
        String size = row.bytes != null ? EditorWidgets.humanBytes(row.bytes.length) : "no bytes";
        ImGui.textDisabled((row.sourceLabel != null ? row.sourceLabel : "?") + " (" + size + ")");
        ImGui.sameLine();
        if (ImGui.button("Replace asset...")) {
            pickAsset(row);
        }
        ImGui.sameLine();
        if (EditorWidgets.dangerButton("Remove", 70.0f)) {
            remove = true;
        }
        return remove;
    }

    /** Shared ImGui edit widgets under the header (both render paths). */
    private void renderRowDetails(Row row) {
        ImGui.dummy(0, 2);
        ImGui.textDisabled("  ");
        ImGui.sameLine();
        ImGui.pushItemWidth(160.0f);
        if (ImGui.inputTextWithHint("Name##state", "state name", row.name)) onDirty.run();
        ImGui.popItemWidth();

        // Animation clip line (1.6+, model SBOs only)
        if (modelKind && clipPicker != null) {
            ImGui.textDisabled("  ");
            ImGui.sameLine();
            if (row.hasClip()) {
                ImGui.text("Clip: " + (row.clipLabel != null ? row.clipLabel : "?")
                        + "  (" + EditorWidgets.humanBytes(row.clipBytes.length) + ")");
                ImGui.sameLine();
                boolean loopBefore = row.clipLoop;
                if (ImGui.radioButton("Loop##clip_loop", row.clipLoop)) row.clipLoop = true;
                ImGui.sameLine();
                if (ImGui.radioButton("Play once##clip_once", !row.clipLoop)) row.clipLoop = false;
                if (loopBefore != row.clipLoop) onDirty.run();
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Play once: run through a single time and hold the final pose"
                            + " (e.g. a door opening).");
                }
                ImGui.sameLine();
                if (ImGui.smallButton("Replace clip...")) {
                    pickClip(row);
                }
                ImGui.sameLine();
                if (ImGui.smallButton("Clear clip")) {
                    row.clipBytes = null;
                    row.clipLabel = null;
                    onDirty.run();
                }
            } else {
                ImGui.textDisabled("Clip: none");
                ImGui.sameLine();
                if (ImGui.smallButton("Set clip...")) {
                    pickClip(row);
                }
            }
        }
    }

    private void pickAsset(Row row) {
        Consumer<Consumer<String>> picker = modelKind ? omoPicker : omtPicker;
        if (picker == null) return;
        picker.accept(picked -> {
            if (picked == null || picked.isBlank()) return;
            try {
                Path path = Path.of(picked);
                row.bytes = Files.readAllBytes(path);
                row.sourceLabel = path.getFileName().toString();
                onDirty.run();
            } catch (IOException ex) {
                logger.error("Failed to read state asset {}", picked, ex);
                row.sourceLabel = "(read failed)";
            }
        });
    }

    private void pickClip(Row row) {
        if (clipPicker == null) return;
        clipPicker.accept(picked -> {
            if (picked == null || picked.isBlank()) return;
            try {
                Path path = Path.of(picked);
                row.clipBytes = Files.readAllBytes(path);
                row.clipLabel = path.getFileName().toString();
                onDirty.run();
            } catch (IOException ex) {
                logger.error("Failed to read animation clip {}", picked, ex);
            }
        });
    }

    /** Release the pooled Mortar header regions before the SkijaContext closes. */
    @Override
    public void close() {
        headerPool.close();
    }
}
