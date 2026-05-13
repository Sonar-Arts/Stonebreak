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
 * Editable variants section for the SBE editor.
 *
 * <p>Variants are the identity axis (default/angus/highland/jersey...) — each
 * may carry its own OMO override or fall back to the base OMO. Mirrors
 * {@link SBEStatesEditor} structurally but exposes only the single model slot:
 * variants do not bind animation clips (clips live on states).
 */
public final class SBEVariantsEditor {

    private static final Logger logger = LoggerFactory.getLogger(SBEVariantsEditor.class);

    private static final class Row {
        final ImString name = new ImString(64);

        byte[] modelBytes;
        String modelSourceLabel;

        Row(String initialName) {
            if (initialName != null) name.set(initialName);
        }

        boolean hasModel() { return modelBytes != null; }
    }

    private final Runnable onDirty;
    private final Consumer<Consumer<String>> omoPicker;

    private final List<Row> rows = new ArrayList<>();

    public SBEVariantsEditor(Runnable onDirty,
                              Consumer<Consumer<String>> omoPicker) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.omoPicker = omoPicker;
    }

    // ========================================================================
    // Loading and saving
    // ========================================================================

    /**
     * Populate rows from a parsed SBE. The {@code assetBytes} map is the
     * filename → bytes map returned by {@code SBEParser.parseRaw}.
     */
    public void load(SBEFormat.Document doc, Map<String, byte[]> assetBytes) {
        rows.clear();
        for (SBEFormat.VariantEntry e : doc.variants()) {
            Row r = new Row(e.name());
            if (e.hasModelOverride()) {
                byte[] bytes = assetBytes != null
                        ? assetBytes.get(e.modelOverride().filename())
                        : null;
                r.modelBytes = bytes;
                r.modelSourceLabel = "(original)";
            }
            rows.add(r);
        }
    }

    /**
     * Builds {@link SBEFormat.VariantEntry} stubs for the document. Filenames
     * are deterministic from the variant name; checksums are recomputed by the
     * serializer on save, so they're left as placeholders here.
     */
    public List<SBEFormat.VariantEntry> toVariantEntries() {
        List<SBEFormat.VariantEntry> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            String name = r.name.get().trim();
            SBEFormat.AssetRef model = r.hasModel()
                    ? new SBEFormat.AssetRef(SBEFormat.variantModelPath(name), "")
                    : null;
            out.add(new SBEFormat.VariantEntry(name, model));
        }
        return out;
    }

    /**
     * Per-variant asset byte map for {@code exportFromDocument}, keyed by the
     * ZIP entry filename the serializer expects.
     */
    public Map<String, byte[]> variantAssetBytesByFilename() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Row r : rows) {
            String name = r.name.get().trim();
            if (r.hasModel()) out.put(SBEFormat.variantModelPath(name), r.modelBytes);
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
            if (name.isBlank()) return "Variant " + (i + 1) + " has no name.";
            if (!seen.add(name)) return "Duplicate variant name: " + name;
        }
        return null;
    }

    public boolean hasRows() { return !rows.isEmpty(); }

    // ========================================================================
    // Rendering
    // ========================================================================

    public void render() {
        if (rows.isEmpty()) {
            ImGui.textDisabled("No variants declared. Add one to attach a per-variant OMO override.");
        } else {
            ImGui.text("Declared variants (" + rows.size() + "):");
            ImGui.textDisabled("Per-variant model overrides fall back to the base OMO when unset.");
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbe_variant_row_" + i);

            ImGui.pushItemWidth(180.0f);
            if (ImGui.inputTextWithHint("##name", "variant name", row.name)) onDirty.run();
            ImGui.popItemWidth();

            ImGui.sameLine();
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, ImColor.rgba(0.5f, 0.18f, 0.18f, 0.45f));
            if (ImGui.button("Remove", 70.0f, 0.0f)) {
                removeIndex = i;
            }
            ImGui.popStyleColor();

            renderAssetRow("Model:", row.modelSourceLabel, row.modelBytes,
                    () -> pickModel(row), () -> clearModel(row));

            ImGui.dummy(0, 6);
            ImGui.popID();
        }

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            onDirty.run();
        }

        if (ImGui.button("+ Add variant")) {
            rows.add(new Row("variant" + (rows.size() + 1)));
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
        omoPicker.accept(picked -> readBytesInto(picked, row));
    }

    private void readBytesInto(String picked, Row row) {
        if (picked == null || picked.isBlank()) return;
        try {
            Path path = Path.of(picked);
            byte[] bytes = Files.readAllBytes(path);
            row.modelBytes = bytes;
            row.modelSourceLabel = path.getFileName().toString();
            onDirty.run();
        } catch (IOException e) {
            logger.error("Failed to read variant asset {}", picked, e);
        }
    }

    private void clearModel(Row row) {
        row.modelBytes = null;
        row.modelSourceLabel = null;
        onDirty.run();
    }

    private static String humanBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
