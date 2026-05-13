package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.SBEFormat;
import imgui.ImColor;
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
 * same authoring idioms.
 */
public final class SBEStatesEditor {

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
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String name = r.name.get().trim();
            if (name.isBlank()) return "State " + (i + 1) + " has no name.";
            if (!seen.add(name)) return "Duplicate state name: " + name;
        }
        return null;
    }

    public boolean hasRows() { return !rows.isEmpty(); }

    // ========================================================================
    // Rendering
    // ========================================================================

    public void render() {
        if (rows.isEmpty()) {
            ImGui.textDisabled("No states declared. Add one to attach a per-state model override or animation clip.");
        } else {
            ImGui.text("Declared states (" + rows.size() + "):");
            ImGui.textDisabled("Per-state model overrides fall back to the base OMO when unset.");
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbe_state_row_" + i);

            ImGui.pushItemWidth(180.0f);
            if (ImGui.inputTextWithHint("##name", "state name", row.name)) onDirty.run();
            ImGui.popItemWidth();

            ImGui.sameLine();
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, ImColor.rgba(0.5f, 0.18f, 0.18f, 0.45f));
            if (ImGui.button("Remove", 70.0f, 0.0f)) {
                removeIndex = i;
            }
            ImGui.popStyleColor();

            renderAssetRow("Model:", row.modelSourceLabel, row.modelBytes,
                    () -> pickModel(row), () -> clearModel(row));
            renderAssetRow("Clip:", row.clipSourceLabel, row.clipBytes,
                    () -> pickClip(row), () -> clearClip(row));

            ImGui.dummy(0, 6);
            ImGui.popID();
        }

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            onDirty.run();
        }

        if (ImGui.button("+ Add state")) {
            rows.add(new Row("state" + (rows.size() + 1)));
            onDirty.run();
        }
    }

    private void renderAssetRow(String label, String sourceLabel, byte[] bytes,
                                 Runnable onPick, Runnable onClear) {
        ImGui.indent(20.0f);
        ImGui.textDisabled(label);
        ImGui.sameLine(80.0f);

        if (bytes != null) {
            ImGui.text(sourceLabel != null ? sourceLabel : "(loaded)");
            ImGui.sameLine();
            ImGui.textDisabled("(" + humanBytes(bytes.length) + ")");
            ImGui.sameLine();
            if (ImGui.smallButton("Replace...")) onPick.run();
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) onClear.run();
        } else {
            ImGui.textDisabled("(unset)");
            ImGui.sameLine();
            if (ImGui.smallButton("Set...")) onPick.run();
        }
        ImGui.unindent(20.0f);
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

    private static String humanBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
