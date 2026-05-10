package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import imgui.ImColor;
import imgui.ImGui;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
public final class SBOStatesEditor {

    private static final Logger logger = LoggerFactory.getLogger(SBOStatesEditor.class);

    private static final class Row {
        final ImString name = new ImString(64);
        byte[] bytes;
        String sourceLabel;

        Row(String n, byte[] b, String label) {
            if (n != null) name.set(n);
            this.bytes = b;
            this.sourceLabel = label;
        }
    }

    private final Runnable onDirty;
    private final Consumer<Consumer<String>> omoPicker;
    private final Consumer<Consumer<String>> omtPicker;

    private final List<Row> rows = new ArrayList<>();
    private int defaultRowIndex = 0;
    private boolean modelKind = true;

    public SBOStatesEditor(Runnable onDirty,
                           Consumer<Consumer<String>> omoPicker,
                           Consumer<Consumer<String>> omtPicker) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.omoPicker = omoPicker;
        this.omtPicker = omtPicker;
    }

    public void load(SBOFormat.Document doc,
                     Map<String, byte[]> stateBytes,
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
                rows.add(new Row(e.name(), bytes, "(original)"));
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
            out.add(new SBOFormat.StateEntry(name, filename, modelKind, ""));
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

    /**
     * Validates that every row has a non-blank, unique name and bytes loaded.
     * Returns {@code null} when valid, or a human-readable error message.
     */
    public String validate() {
        if (rows.isEmpty()) return null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String name = r.name.get().trim();
            if (name.isBlank()) return "State " + (i + 1) + " has no name.";
            if (!seen.add(name)) return "Duplicate state name: " + name;
            if (r.bytes == null || r.bytes.length == 0) {
                return "State '" + name + "' has no asset bytes.";
            }
        }
        if (defaultRowIndex < 0 || defaultRowIndex >= rows.size()) {
            return "No default state selected.";
        }
        return null;
    }

    public void render() {
        if (rows.isEmpty()) {
            ImGui.textDisabled("No states declared. This SBO has a single embedded "
                    + (modelKind ? ".OMO model." : ".OMT texture."));
        } else {
            ImGui.text("Declared states (" + rows.size() + "):");
            ImGui.textDisabled(modelKind
                    ? "Each state carries its own .OMO model."
                    : "Each state carries its own .OMT texture.");
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbo_state_row_" + i);

            ImGui.pushItemWidth(160.0f);
            if (ImGui.inputTextWithHint("##name", "state name", row.name)) onDirty.run();
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.radioButton("default", defaultRowIndex == i)) {
                defaultRowIndex = i;
                onDirty.run();
            }

            ImGui.sameLine();
            if (ImGui.button("Replace asset...")) {
                pickAsset(row);
            }

            ImGui.sameLine();
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, ImColor.rgba(0.5f, 0.18f, 0.18f, 0.45f));
            if (ImGui.button("Remove", 70.0f, 0.0f)) {
                removeIndex = i;
            }
            ImGui.popStyleColor();

            String size = row.bytes != null ? humanBytes(row.bytes.length) : "no bytes";
            ImGui.textDisabled("  source: " + (row.sourceLabel != null ? row.sourceLabel : "?")
                    + "  (" + size + ")");

            ImGui.dummy(0, 4);
            ImGui.popID();
        }

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

    private static String humanBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
