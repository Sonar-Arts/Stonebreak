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
 * Editable variants section for the SBE editor.
 *
 * <p>Variants are the identity axis (default/angus/highland/jersey...) — each
 * may carry its own OMO override or fall back to the base OMO. Mirrors
 * {@link SBEStatesEditor} structurally (same card layout: Mortar
 * {@link RowHeaderStrip} + ImGui slots + inline validation) but exposes only
 * the single model slot: variants do not bind animation clips (clips live on
 * states).
 */
public final class SBEVariantsEditor implements AutoCloseable {

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

    private final MortarRegionPool headerPool = new MortarRegionPool();

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
        Set<String> duplicates = duplicateNames();
        for (int i = 0; i < rows.size(); i++) {
            String error = validateRow(rows.get(i), i, duplicates);
            if (error != null) return error;
        }
        return null;
    }

    private static String validateRow(Row r, int index, Set<String> duplicates) {
        String name = r.name.get().trim();
        if (name.isBlank()) return "Variant " + (index + 1) + " has no name.";
        if (duplicates.contains(name)) return "Duplicate variant name: " + name;
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
            ImGui.textDisabled("No variants declared. Add one to attach a per-variant OMO override.");
        } else {
            ImGui.text("Declared variants (" + rows.size() + ")");
            ImGui.textDisabled("Per-variant model overrides fall back to the base OMO when unset.");
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        Set<String> duplicates = duplicateNames();
        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sbe_variant_row_" + i);

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

        if (ImGui.button("+ Add variant")) {
            rows.add(new Row("variant" + (rows.size() + 1)));
            onDirty.run();
        }
    }

    /** Mortar card header; returns true when remove was clicked. */
    private boolean renderRowHeaderMortar(int i, Row row) {
        String name = row.name.get().trim();
        String detail = "model: " + (row.hasModel()
                ? (row.modelSourceLabel != null ? row.modelSourceLabel : "(loaded)")
                : "base OMO");

        RowHeaderStrip.Result result = RowHeaderStrip.render(
                headerPool.get(i), "VARIANT", false,
                name.isEmpty() ? "(unnamed)" : name, name.isEmpty(), detail,
                List.of());

        if ("remove".equals(result.hovered())) {
            ImGui.setTooltip("Remove this variant");
        }
        return result.removeClicked();
    }

    /** Plain-ImGui header for when no Skija context exists. */
    private boolean renderRowHeaderFallback(Row row) {
        ImGui.textDisabled("model: " + (row.hasModel()
                ? (row.modelSourceLabel != null ? row.modelSourceLabel : "(loaded)")
                : "base OMO"));
        ImGui.sameLine();
        return EditorWidgets.dangerButton("Remove", 70.0f);
    }

    /** Shared ImGui edit widgets under the header (both render paths). */
    private void renderRowDetails(Row row) {
        ImGui.dummy(0, 2);
        ImGui.textDisabled("  ");
        ImGui.sameLine();
        ImGui.pushItemWidth(180.0f);
        if (ImGui.inputTextWithHint("Name##variant", "variant name", row.name)) onDirty.run();
        ImGui.popItemWidth();

        EditorWidgets.assetSlot("Model:", row.modelSourceLabel, row.modelBytes,
                () -> pickModel(row), () -> clearModel(row));
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

    /** Release the pooled Mortar header regions before the SkijaContext closes. */
    @Override
    public void close() {
        headerPool.close();
    }
}
