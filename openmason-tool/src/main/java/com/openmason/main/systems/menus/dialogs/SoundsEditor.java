package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import imgui.ImColor;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Editable Sounds section shared by the SBO editor (1.7+) and SBE editor
 * (1.4+). Each row is one {@link SoundDef}: an event name bound to an audio
 * sample that is either <em>embedded</em> in the archive (bytes owned here,
 * loaded from the parsed file or a user-picked {@code .wav}) or
 * <em>referenced</em> by game classpath resource path (nothing embedded).
 *
 * <p>On save the section produces the {@link SoundData} plus a filename-keyed
 * byte map for the embedded samples. Embedded entry paths are regenerated as
 * {@code sounds/<event>_<n>.<ext>} — the shared SBO/SBE convention
 * ({@code SBOFormat.soundEntryPath}/{@code SBEFormat.soundEntryPath}) — and
 * checksums are left as stubs for the serializer to recompute, mirroring how
 * {@link SBOStatesEditor} handles state assets.
 */
public final class SoundsEditor {

    private static final Logger logger = LoggerFactory.getLogger(SoundsEditor.class);

    /** Suggested event names shown as a hint; names are free-form. */
    private static final String EVENT_HINT =
            "Standard block events: break, hit, place, step. "
                    + "Entity events: hurt, death, ambient. Custom names are allowed.";

    private static final class Row {
        final ImString event = new ImString(64);
        final ImString resourcePath = new ImString(256);
        boolean embedded;
        byte[] bytes;            // embedded sample bytes (null until picked/loaded)
        String sourceLabel;      // filename shown for embedded samples
        String extension = "wav";
        final ImFloat volume = new ImFloat(1.0f);
        final ImBoolean variation = new ImBoolean(true);
        final ImFloat pitchMin = new ImFloat(0.9f);
        final ImFloat pitchMax = new ImFloat(1.1f);
    }

    private final Runnable onDirty;
    private final Consumer<Consumer<String>> audioPicker;
    private final List<Row> rows = new ArrayList<>();

    public SoundsEditor(Runnable onDirty, Consumer<Consumer<String>> audioPicker) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.audioPicker = audioPicker;
    }

    /**
     * Populate from a parsed manifest section.
     *
     * @param sounds        the manifest {@code sounds[]}, or null when absent
     * @param embeddedBytes lookup from embedded entry filename to raw bytes
     *                      (the parse result's sound-bytes map)
     */
    public void load(SoundData sounds, Function<String, byte[]> embeddedBytes) {
        rows.clear();
        if (sounds == null) return;
        for (SoundDef def : sounds.sounds()) {
            Row row = new Row();
            row.event.set(def.event());
            row.volume.set(def.volume());
            row.variation.set(def.variation());
            row.pitchMin.set(def.pitchMin());
            row.pitchMax.set(def.pitchMax());
            if (def.isEmbedded()) {
                row.embedded = true;
                row.bytes = embeddedBytes != null ? embeddedBytes.apply(def.filename()) : null;
                row.sourceLabel = "(original)";
                row.extension = extensionOf(def.filename());
            } else {
                row.embedded = false;
                row.resourcePath.set(def.resourcePath());
            }
            rows.add(row);
        }
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * The edited section as manifest data, or null when no sounds are
     * declared. Embedded defs carry regenerated entry paths and stub
     * checksums (recomputed by the serializer from the byte map).
     */
    public SoundData toSoundData() {
        if (rows.isEmpty()) return null;
        List<SoundDef> defs = new ArrayList<>(rows.size());
        Map<String, Integer> perEventIndex = new HashMap<>();
        for (Row row : rows) {
            String event = row.event.get().trim();
            if (row.embedded) {
                int index = perEventIndex.merge(event, 1, Integer::sum) - 1;
                defs.add(new SoundDef(event, entryPath(event, index, row.extension), "",
                        null, row.volume.get(), row.pitchMin.get(), row.pitchMax.get(),
                        row.variation.get()));
            } else {
                defs.add(new SoundDef(event, null, null, row.resourcePath.get().trim(),
                        row.volume.get(), row.pitchMin.get(), row.pitchMax.get(),
                        row.variation.get()));
            }
        }
        return new SoundData(defs);
    }

    /**
     * Embedded sample bytes keyed by entry filename, aligned with the paths
     * {@link #toSoundData()} generates. Feed to the serializer's save call.
     */
    public Map<String, byte[]> soundBytesByFilename() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, Integer> perEventIndex = new HashMap<>();
        for (Row row : rows) {
            String event = row.event.get().trim();
            if (row.embedded) {
                int index = perEventIndex.merge(event, 1, Integer::sum) - 1;
                out.put(entryPath(event, index, row.extension), row.bytes);
            }
        }
        return out;
    }

    /**
     * Returns {@code null} when valid, or a human-readable error message.
     */
    public String validate() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String event = row.event.get().trim();
            if (event.isBlank()) return "Sound " + (i + 1) + " has no event name.";
            if (row.embedded) {
                if (row.bytes == null || row.bytes.length == 0) {
                    return "Sound '" + event + "' has no audio file picked.";
                }
            } else {
                String path = row.resourcePath.get().trim();
                if (path.isBlank()) return "Sound '" + event + "' has no resource path.";
                if (!path.startsWith("/")) {
                    return "Sound '" + event + "' resource path must be absolute"
                            + " (start with /), e.g. /sounds/GrassWalk.wav.";
                }
            }
            if (!(row.volume.get() > 0f)) {
                return "Sound '" + event + "' volume must be > 0.";
            }
            if (row.variation.get()
                    && (!(row.pitchMin.get() > 0f) || row.pitchMax.get() < row.pitchMin.get())) {
                return "Sound '" + event + "' pitch range must satisfy 0 < min <= max.";
            }
        }
        return null;
    }

    public void render() {
        if (rows.isEmpty()) {
            ImGui.textDisabled("No sounds declared. The object is silent.");
        } else {
            ImGui.text("Declared sounds (" + rows.size() + "):");
        }
        ImGui.textDisabled(EVENT_HINT);
        ImGui.textDisabled("Several entries on one event = random pick per trigger.");

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sound_row_" + i);

            // Line 1: event name, source kind, remove.
            ImGui.pushItemWidth(140.0f);
            if (ImGui.inputTextWithHint("##event", "event name", row.event)) onDirty.run();
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.radioButton("Resource", !row.embedded)) {
                if (row.embedded) { row.embedded = false; onDirty.run(); }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Reference a .wav shipped in the game's /sounds/ resources."
                        + " Many objects can share one sample without duplicating audio.");
            }
            ImGui.sameLine();
            if (ImGui.radioButton("Embedded", row.embedded)) {
                if (!row.embedded) {
                    row.embedded = true;
                    onDirty.run();
                    if (row.bytes == null) pickAudio(row);
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Bundle the audio bytes inside this file —"
                        + " the asset stays self-contained.");
            }

            ImGui.sameLine();
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, ImColor.rgba(0.5f, 0.18f, 0.18f, 0.45f));
            if (ImGui.button("Remove", 70.0f, 0.0f)) {
                removeIndex = i;
            }
            ImGui.popStyleColor();

            // Line 2: the sample source.
            if (row.embedded) {
                String size = row.bytes != null ? humanBytes(row.bytes.length) : "no audio";
                ImGui.textDisabled("  sample: " + (row.sourceLabel != null ? row.sourceLabel : "(unset)")
                        + "  (" + size + ")");
                ImGui.sameLine();
                if (ImGui.smallButton(row.bytes == null ? "Pick audio..." : "Replace audio...")) {
                    pickAudio(row);
                }
            } else {
                ImGui.textDisabled("  resource:");
                ImGui.sameLine();
                ImGui.pushItemWidth(320.0f);
                if (ImGui.inputTextWithHint("##resource", "/sounds/Example.wav", row.resourcePath)) {
                    onDirty.run();
                }
                ImGui.popItemWidth();
            }

            // Line 3: playback parameters.
            ImGui.textDisabled("  ");
            ImGui.sameLine();
            ImGui.pushItemWidth(90.0f);
            if (ImGui.inputFloat("Volume", row.volume)) onDirty.run();
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.checkbox("Pitch variation", row.variation)) onDirty.run();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("The walking-sound noise-alteration algorithm: each playback"
                        + " draws a random pitch from the min/max range so repeated triggers"
                        + " don't sound identical. Unchecked = natural pitch.");
            }
            if (row.variation.get()) {
                ImGui.sameLine();
                ImGui.pushItemWidth(70.0f);
                if (ImGui.inputFloat("Min##pitch", row.pitchMin)) onDirty.run();
                ImGui.sameLine();
                if (ImGui.inputFloat("Max##pitch", row.pitchMax)) onDirty.run();
                ImGui.popItemWidth();
            }

            ImGui.dummy(0, 4);
            ImGui.popID();
        }

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            onDirty.run();
        }

        if (ImGui.button("+ Add sound")) {
            Row added = new Row();
            added.event.set("step");
            rows.add(added);
            onDirty.run();
        }
    }

    private void pickAudio(Row row) {
        if (audioPicker == null) return;
        audioPicker.accept(picked -> {
            if (picked == null || picked.isBlank()) return;
            try {
                Path path = Path.of(picked);
                row.bytes = Files.readAllBytes(path);
                row.sourceLabel = path.getFileName().toString();
                row.extension = extensionOf(path.getFileName().toString());
                onDirty.run();
            } catch (IOException ex) {
                logger.error("Failed to read audio sample {}", picked, ex);
                row.sourceLabel = "(read failed)";
            }
        });
    }

    /** Shared SBO 1.7 / SBE 1.4 embedded-sound entry-path convention. */
    private static String entryPath(String event, int index, String extension) {
        String ext = extension == null || extension.isBlank() ? "wav" : extension;
        return "sounds/" + event + "_" + index + "." + ext;
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "wav";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1 ? filename.substring(dot + 1) : "wav";
    }

    private static String humanBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }
}
