package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.core.MortarRegionPool;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.parts.MortarBadge;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Editable Sounds section shared by the SBO editor (1.7+) and SBE editor
 * (1.4+). Each row is one {@link SoundDef}: an event name bound to an audio
 * sample that is either <em>embedded</em> in the archive (bytes owned here,
 * loaded from the parsed file or a user-picked {@code .wav}) or
 * <em>referenced</em> by game classpath resource path (nothing embedded).
 *
 * <p>The tab is a MortarUI surface (with a plain-ImGui fallback when no Skija
 * context exists): a quick-add chip bar offers the standard block/entity
 * events (chips carry a count badge once declared), and each sound renders as
 * a card whose Mortar header strip holds the event title, a kind badge, a
 * Resource/Embedded segmented toggle, an OpenAL Play preview (via
 * {@link SoundPreviewService} — pitch variation rolls a random pitch exactly
 * like the game), and a remove button; the editable fields (event name,
 * source, volume/pitch sliders) are ImGui widgets beneath. Validation is
 * surfaced inline per row as well as at save time via {@link #validate()}.
 *
 * <p>On save the section produces the {@link SoundData} plus a filename-keyed
 * byte map for the embedded samples. Embedded entry paths are regenerated as
 * {@code sounds/<event>_<n>.<ext>} — the shared SBO/SBE convention
 * ({@code SBOFormat.soundEntryPath}/{@code SBEFormat.soundEntryPath}) — and
 * checksums are left as stubs for the serializer to recompute, mirroring how
 * {@link SBOStatesEditor} handles state assets.
 */
public final class SoundsEditor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SoundsEditor.class);

    /** Suggested event names shown as a hint; names are free-form. */
    private static final String EVENT_HINT =
            "Several entries on one event = random pick per trigger. Custom event names are allowed.";

    private static final String[] BLOCK_EVENTS = { "step", "break", "place", "hit" };
    private static final String[] ENTITY_EVENTS = { "hurt", "death", "ambient" };

    // Chip bar geometry.
    private static final float CHIP_H = 24f;
    private static final float CHIP_GAP = 6f;
    private static final float CHAR_W = 6.8f;

    // Row header geometry.
    private static final float HEADER_H = 34f;
    private static final float SEG_W = 74f;
    private static final float PLAY_W = 54f;
    private static final float REMOVE_W = 26f;

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
        /** Transient note from the last preview attempt (error text), or null. */
        String previewNote;
    }

    private final Runnable onDirty;
    private final Consumer<Consumer<String>> audioPicker;
    private final List<Row> rows = new ArrayList<>();

    private final MortarRegion chipRegion = new MortarRegion();
    /** One pooled header region per visible row; trimmed as rows shrink. */
    private final MortarRegionPool headerPool = new MortarRegionPool();

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
            String error = validateRow(rows.get(i), i);
            if (error != null) return error;
        }
        return null;
    }

    /** Per-row validation shared by {@link #validate()} and the inline display. */
    private static String validateRow(Row row, int index) {
        String event = row.event.get().trim();
        if (event.isBlank()) return "Sound " + (index + 1) + " has no event name.";
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
        return null;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    public void render() {
        boolean mortar = chipRegion.isAvailable();

        if (rows.isEmpty()) {
            ImGui.textDisabled("No sounds declared - the object is silent."
                    + " Add an event below to give it a voice.");
        } else {
            ImGui.text("Declared sounds (" + rows.size() + ")");
        }
        ImGui.textDisabled(EVENT_HINT);

        ImGui.dummy(0, 4);
        if (mortar) {
            renderQuickAddMortar();
        } else {
            renderQuickAddFallback();
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("sound_row_" + i);

            boolean removeRequested = mortar
                    ? renderRowHeaderMortar(i, row)
                    : renderRowHeaderFallback(row);
            if (removeRequested) removeIndex = i;

            renderRowDetails(row);

            String error = validateRow(row, i);
            if (error != null) {
                ImGui.textColored(1.0f, 0.55f, 0.45f, 1.0f, "  " + error);
            } else if (row.previewNote != null) {
                ImGui.textDisabled("  " + row.previewNote);
            }

            ImGui.dummy(0, 8);
            ImGui.popID();
        }

        headerPool.trim(rows.size());

        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            onDirty.run();
        }
    }

    // ---- quick-add chip bar ------------------------------------------------

    private void renderQuickAddMortar() {
        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        Map<String, Integer> counts = eventCounts();

        List<String> chipEvents = new ArrayList<>();
        chipEvents.addAll(List.of(BLOCK_EVENTS));
        chipEvents.addAll(List.of(ENTITY_EVENTS));

        // Lay chips out with wrapping; the trailing chip adds a custom row.
        float x = 0f;
        float y = 0f;
        List<float[]> layout = new ArrayList<>(); // x, y, w per chip
        for (String event : chipEvents) {
            float w = chipWidth(event, counts.getOrDefault(event, 0));
            if (x + w > availW && x > 0f) {
                x = 0f;
                y += CHIP_H + CHIP_GAP;
            }
            layout.add(new float[] { x, y, w });
            x += w + CHIP_GAP;
        }
        float customW = chipWidth("+ custom", 0);
        if (x + customW > availW && x > 0f) {
            x = 0f;
            y += CHIP_H + CHIP_GAP;
        }
        float customX = x;
        float customY = y;

        chipRegion.begin(availW, customY + CHIP_H + 2f);
        for (int i = 0; i < chipEvents.size(); i++) {
            String event = chipEvents.get(i);
            int count = counts.getOrDefault(event, 0);
            float[] pos = layout.get(i);
            chipRegion.add("chip." + event, pos[0], pos[1], pos[2], CHIP_H,
                    (g, px, py, pw, ph, state) -> paintEventChip(g, px, py, pw, ph, state, event, count));
        }
        chipRegion.add("chip.custom", customX, customY, customW, CHIP_H,
                (g, px, py, pw, ph, state) -> paintEventChip(g, px, py, pw, ph, state, "+ custom", -1));

        MortarFrameResult input = chipRegion.render();
        chipRegion.update(ImGui.getIO().getDeltaTime());

        String hovered = input.hovered();
        if (hovered != null && hovered.startsWith("chip.")) {
            String event = hovered.substring(5);
            ImGui.setTooltip(switch (event) {
                case "custom" -> "Add a sound with a custom event name";
                case "step" -> "Played while walking on this block";
                case "break" -> "Played when the block finishes breaking";
                case "place" -> "Played when the block is placed";
                case "hit" -> "Played periodically while demolishing";
                case "hurt" -> "Played when the entity takes damage";
                case "death" -> "Played when the entity dies";
                case "ambient" -> "Ambient entity voice";
                default -> "Add a '" + event + "' sound";
            });
        }

        String clicked = input.clicked();
        if (clicked != null && clicked.startsWith("chip.")) {
            String event = clicked.substring(5);
            addRow(event.equals("custom") ? "" : event);
        }
    }

    private void renderQuickAddFallback() {
        ImGui.textDisabled("Add:");
        for (String event : BLOCK_EVENTS) {
            ImGui.sameLine();
            if (ImGui.smallButton(event)) addRow(event);
        }
        for (String event : ENTITY_EVENTS) {
            ImGui.sameLine();
            if (ImGui.smallButton(event)) addRow(event);
        }
        ImGui.sameLine();
        if (ImGui.smallButton("+ custom")) addRow("");
    }

    private static float chipWidth(String label, int count) {
        float w = label.length() * CHAR_W + 22f;
        if (count > 0) w += 28f;
        return w;
    }

    private static void paintEventChip(MortarPainter g, float x, float y, float w, float h,
                                       PartState state, String label, int count) {
        float hover = state.hover();
        float press = state.press();

        int fill = Argb.lerp(Argb.withAlpha(g.theme().surface, 0.6f), g.theme().surfaceHover, hover);
        fill = Argb.shade(fill, -0.05f * press);
        g.fillRoundRect(x, y, w, h, h / 2f, fill);
        g.strokeRoundRect(x, y, w, h, h / 2f, 1f,
                Argb.lerp(g.theme().border, g.theme().borderStrong, hover * 0.7f));

        float textX = x + 11f;
        g.text(label, textX, y + h / 2f, MortarPainter.Align.LEFT, Weight.MEDIUM, 12f,
                Argb.lerp(g.theme().textDim, g.theme().text, hover));
        if (count > 0) {
            float labelW = g.measureWidth(label, Weight.MEDIUM, 12f);
            MortarBadge.paint(g, textX + labelW + 5f, y + h / 2f, String.valueOf(count));
        }
    }

    private Map<String, Integer> eventCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Row row : rows) {
            String event = row.event.get().trim();
            if (!event.isEmpty()) counts.merge(event, 1, Integer::sum);
        }
        return counts;
    }

    private void addRow(String event) {
        Row added = new Row();
        added.event.set(event);
        rows.add(added);
        onDirty.run();
    }

    // ---- row header (Mortar) -----------------------------------------------

    /** Draws the header strip for row {@code i}; returns true when remove was clicked. */
    private boolean renderRowHeaderMortar(int i, Row row) {
        MortarRegion region = headerPool.get(i);
        float availW = Math.max(1f, ImGui.getContentRegionAvailX());

        String event = row.event.get().trim();
        String title = event.isEmpty() ? "(unnamed)" : event;
        String kind = kindOf(event);
        String detail = row.embedded
                ? (row.sourceLabel != null ? row.sourceLabel : "no audio")
                        + (row.bytes != null ? "  " + EditorWidgets.humanBytes(row.bytes.length) : "")
                : (row.resourcePath.get().trim().isEmpty() ? "no resource" : row.resourcePath.get().trim());
        boolean playable = row.embedded
                ? row.bytes != null && row.bytes.length > 0
                : row.resourcePath.get().trim().startsWith("/");

        region.begin(availW, HEADER_H);

        // Card-top backdrop.
        region.add("bg", 0f, 0f, availW, HEADER_H, (g, px, py, pw, ph, state) -> {
            g.fillRoundRect(px, py, pw, ph, 8f, Argb.withAlpha(g.theme().surface, 0.65f));
            g.strokeRoundRect(px, py, pw, ph, 8f, 1f, g.theme().border);
        });

        // Title zone: kind badge + event name + dim source detail (clipped).
        float rightZone = SEG_W * 2f + PLAY_W + REMOVE_W + 8f * 3f + 10f;
        float titleZoneW = availW - rightZone - 12f;
        final boolean unnamed = event.isEmpty();
        region.add("deco.title", 10f, 0f, Math.max(20f, titleZoneW), HEADER_H,
                (g, px, py, pw, ph, state) -> {
                    float cy = py + ph / 2f;
                    float bx = px;
                    bx += MortarBadge.paint(g, bx, cy, kind) + 8f;
                    int titleColor = unnamed ? g.theme().textFaint : g.theme().text;
                    g.text(title, bx, cy, MortarPainter.Align.LEFT, Weight.MEDIUM, 13f, titleColor);
                    bx += g.measureWidth(title, Weight.MEDIUM, 13f) + 10f;
                    float remaining = px + pw - bx;
                    if (remaining > 30f) {
                        g.textEllipsized(detail, bx, cy, remaining, Weight.REGULAR, 11f,
                                g.theme().textFaint);
                    }
                });

        // Right-side controls: [Resource|Embedded] segmented, Play, remove.
        float xRemove = availW - REMOVE_W - 4f;
        float xPlay = xRemove - 8f - PLAY_W;
        float xEmb = xPlay - 10f - SEG_W;
        float xRes = xEmb - SEG_W;
        float ctrlY = (HEADER_H - 24f) / 2f;

        region.add("src.res", xRes, ctrlY, SEG_W, 24f, !row.embedded,
                (g, px, py, pw, ph, state) -> paintSegment(g, px, py, pw, ph, state, "Resource", true));
        region.add("src.emb", xEmb, ctrlY, SEG_W, 24f, row.embedded,
                (g, px, py, pw, ph, state) -> paintSegment(g, px, py, pw, ph, state, "Embedded", false));

        final boolean canPlay = playable;
        region.add("play", xPlay, ctrlY, PLAY_W, 24f, (g, px, py, pw, ph, state) ->
                paintPlayPill(g, px, py, pw, ph, state, canPlay));
        region.add("remove", xRemove, ctrlY, REMOVE_W, 24f, (g, px, py, pw, ph, state) -> {
            float hover = state.hover();
            if (hover > 0.02f) {
                g.fillRoundRect(px, py, pw, ph, 6f,
                        Argb.withAlpha(0xFFB44242, 0.30f * hover));
            }
            g.text("×", px + pw / 2f, py + ph / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, 13f,
                    Argb.lerp(g.theme().textDim, 0xFFE07A7A, hover));
        });

        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        String hovered = input.hovered();
        if (hovered != null) {
            switch (hovered) {
                case "src.res" -> ImGui.setTooltip("Reference a .wav shipped in the game's"
                        + " /sounds/ resources. Many objects can share one sample"
                        + " without duplicating audio.");
                case "src.emb" -> ImGui.setTooltip("Bundle the audio bytes inside this file -"
                        + " the asset stays self-contained.");
                case "play" -> ImGui.setTooltip(canPlay
                        ? "Preview at the row's volume" + (row.variation.get()
                                ? " with a random pitch from the range (as in-game)" : "")
                        : "Pick a sample or set a /resource path first");
                case "remove" -> ImGui.setTooltip("Remove this sound");
                default -> { }
            }
        }

        String clicked = input.clicked();
        if (clicked != null) {
            switch (clicked) {
                case "src.res" -> {
                    if (row.embedded) { row.embedded = false; onDirty.run(); }
                }
                case "src.emb" -> {
                    if (!row.embedded) {
                        row.embedded = true;
                        onDirty.run();
                        if (row.bytes == null) pickAudio(row);
                    }
                }
                case "play" -> { if (canPlay) preview(row); }
                case "remove" -> { return true; }
                default -> { }
            }
        }
        return false;
    }

    private static void paintSegment(MortarPainter g, float x, float y, float w, float h,
                                     PartState state, String label, boolean leftEnd) {
        float sel = state.selected();
        float hover = state.hover();
        float radius = h / 2f;

        // Group backdrop half: rounded on the outer end, squared on the inner
        // edge (covered by an overlay rect) so the pair reads as one pill.
        int fill = Argb.lerp(Argb.withAlpha(g.theme().surface, 0.8f), g.theme().surfaceHover, hover * 0.6f);
        fill = Argb.lerp(fill, Argb.withAlpha(g.theme().accent, 0.85f), sel);
        g.fillRoundRect(x, y, w, h, radius, fill);
        if (leftEnd) {
            g.fillRect(x + w - radius, y, radius, h, fill);
        } else {
            g.fillRect(x, y, radius, h, fill);
        }
        int textColor = Argb.lerp(g.theme().textDim, 0xFFFFFFFF, sel);
        textColor = Argb.lerp(textColor, g.theme().text, Math.max(0f, hover - sel));
        g.text(label, x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 11.5f, textColor);
    }

    private static void paintPlayPill(MortarPainter g, float x, float y, float w, float h,
                                      PartState state, boolean enabled) {
        float hover = enabled ? state.hover() : 0f;
        float press = enabled ? state.press() : 0f;

        int fill = enabled
                ? Argb.lerp(g.theme().surface, Argb.withAlpha(g.theme().accent, 0.55f), hover)
                : Argb.withAlpha(g.theme().surface, 0.45f);
        fill = Argb.shade(fill, -0.06f * press);
        g.fillRoundRect(x, y, w, h, h / 2f, fill);
        g.strokeRoundRect(x, y, w, h, h / 2f, 1f,
                enabled ? Argb.lerp(g.theme().border, g.theme().borderStrong, hover)
                        : Argb.withAlpha(g.theme().border, 0.5f));

        int color = enabled
                ? Argb.lerp(g.theme().textDim, g.theme().text, Math.max(hover, press))
                : g.theme().textFaint;
        g.text("Play", x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 12f, color);
    }

    // ---- row header (ImGui fallback) ----------------------------------------

    /** Compact ImGui header; returns true when remove was clicked. */
    private boolean renderRowHeaderFallback(Row row) {
        boolean remove = false;

        String event = row.event.get().trim();
        ImGui.text(event.isEmpty() ? "(unnamed)" : event);
        ImGui.sameLine();
        ImGui.textDisabled("[" + kindOf(event) + "]");

        ImGui.sameLine();
        if (ImGui.radioButton("Resource", !row.embedded)) {
            if (row.embedded) { row.embedded = false; onDirty.run(); }
        }
        ImGui.sameLine();
        if (ImGui.radioButton("Embedded", row.embedded)) {
            if (!row.embedded) {
                row.embedded = true;
                onDirty.run();
                if (row.bytes == null) pickAudio(row);
            }
        }

        boolean playable = row.embedded
                ? row.bytes != null && row.bytes.length > 0
                : row.resourcePath.get().trim().startsWith("/");
        ImGui.sameLine();
        if (!playable) ImGui.beginDisabled();
        if (ImGui.smallButton("Play")) preview(row);
        if (!playable) ImGui.endDisabled();

        ImGui.sameLine();
        if (ImGui.smallButton("Remove")) remove = true;
        return remove;
    }

    // ---- row details (shared ImGui widgets) ---------------------------------

    private void renderRowDetails(Row row) {
        ImGui.dummy(0, 2);
        ImGui.textDisabled("  ");
        ImGui.sameLine();
        ImGui.pushItemWidth(140.0f);
        if (ImGui.inputTextWithHint("Event##name", "event name", row.event)) onDirty.run();
        ImGui.popItemWidth();

        ImGui.sameLine();
        if (row.embedded) {
            String size = row.bytes != null ? EditorWidgets.humanBytes(row.bytes.length) : "no audio";
            ImGui.textDisabled((row.sourceLabel != null ? row.sourceLabel : "(unset)")
                    + " (" + size + ")");
            ImGui.sameLine();
            if (ImGui.smallButton(row.bytes == null ? "Pick audio..." : "Replace audio...")) {
                pickAudio(row);
            }
        } else {
            ImGui.pushItemWidth(300.0f);
            if (ImGui.inputTextWithHint("##resource", "/sounds/Example.wav", row.resourcePath)) {
                onDirty.run();
            }
            ImGui.popItemWidth();
        }

        ImGui.textDisabled("  ");
        ImGui.sameLine();
        ImGui.pushItemWidth(150.0f);
        if (ImGui.sliderFloat("Volume", row.volume.getData(), 0.0f, 2.0f, "%.2f")) onDirty.run();
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
            ImGui.pushItemWidth(100.0f);
            if (ImGui.sliderFloat("Min##pitch", row.pitchMin.getData(), 0.5f, 2.0f, "%.2f")) {
                onDirty.run();
            }
            ImGui.sameLine();
            if (ImGui.sliderFloat("Max##pitch", row.pitchMax.getData(), 0.5f, 2.0f, "%.2f")) {
                onDirty.run();
            }
            ImGui.popItemWidth();
        }
    }

    // ---- preview ------------------------------------------------------------

    private void preview(Row row) {
        float pitch = row.variation.get()
                ? ThreadLocalRandom.current().nextFloat(
                        Math.max(0.05f, row.pitchMin.get()),
                        Math.max(Math.max(0.05f, row.pitchMin.get()) + 0.0001f, row.pitchMax.get()))
                : 1.0f;
        SoundPreviewService preview = SoundPreviewService.instance();
        boolean ok = row.embedded
                ? preview.playEmbedded(row.bytes, row.volume.get(), pitch)
                : preview.playResource(row.resourcePath.get().trim(), row.volume.get(), pitch);
        row.previewNote = ok ? null : preview.lastError();
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
                row.previewNote = null;
                onDirty.run();
            } catch (IOException ex) {
                logger.error("Failed to read audio sample {}", picked, ex);
                row.sourceLabel = "(read failed)";
            }
        });
    }

    // ---- helpers -------------------------------------------------------------

    private static String kindOf(String event) {
        for (String e : BLOCK_EVENTS) {
            if (e.equals(event)) return "BLOCK";
        }
        for (String e : ENTITY_EVENTS) {
            if (e.equals(event)) return "ENTITY";
        }
        return "CUSTOM";
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

    /** Release the Mortar Skija regions. Must run before the SkijaContext closes. */
    @Override
    public void close() {
        chipRegion.close();
        headerPool.close();
    }
}
