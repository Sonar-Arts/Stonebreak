package com.stonebreak.openmason.editor;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityModelSource;
import com.openmason.engine.rig.Rig;
import com.stonebreak.format.sbe.SBEFormat;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The SBE editor's Character tab (Stonebreak #456, SBE 1.7): the entity's character definition - an {@code .omchar}
 * made in Open Mason's Character workspace (#163) - embedded as {@link SBEFormat#CHARACTER_FILENAME}. Shows what it
 * defines (profile, roles, metrics, sockets) and checks it against the SBE's own model skeleton with the engine
 * ({@code CharacterDefinition.check}): a definition made for another skeleton blocks the save. ASCII strings only.
 */
final class SBECharacterTab {

    private final Runnable markDirty;
    private byte[] bytes;
    private String source;
    private byte[] checkedModel;
    private Report report = Report.NONE;

    /** What the definition says, and whether it fits the model. */
    record Report(String profile, int roles, CharacterDefinition.Metrics metrics, List<String> sockets,
                  List<String> errors, List<String> warnings, String unreadable) {
        static final Report NONE = new Report(null, 0, null, List.of(), List.of(), List.of(), null);
    }

    SBECharacterTab(Runnable markDirty) {
        this.markDirty = markDirty;
    }

    void load(SBEFormat.Document doc, Map<String, byte[]> assets, byte[] model) {
        bytes = doc.hasCharacter() && assets != null ? assets.get(doc.character().filename()) : null;
        source = bytes == null ? null : "(embedded)";
        checkedModel = null;
        check(model);
    }

    /** Embeds a definition (from a file the user or an agent picked). */
    void set(byte[] value, String label, byte[] model) {
        bytes = value;
        source = label;
        checkedModel = null;
        check(model);
        markDirty.run();
    }

    /** Puts back bytes a form reload dropped (not an edit). */
    void restore(byte[] value, byte[] model) {
        bytes = value;
        if (source == null) source = "(embedded)";
        checkedModel = null;
        check(model);
    }

    void clear() {
        if (bytes == null) return;
        bytes = null;
        source = null;
        report = Report.NONE;
        markDirty.run();
    }

    byte[] bytes() {
        return bytes;
    }

    /** The manifest reference to write (the serializer recomputes the checksum), or null. */
    SBEFormat.AssetRef ref() {
        return bytes == null ? null : new SBEFormat.AssetRef(SBEFormat.CHARACTER_FILENAME, "pending");
    }

    /** Null when the draft may be written; else why not. */
    String validate(byte[] model) {
        if (bytes == null) return null;
        check(model);
        if (report.unreadable() != null) return "Character: " + report.unreadable();
        if (!report.errors().isEmpty()) return "Character does not fit the model: " + report.errors().get(0);
        return null;
    }

    Map<String, Object> describe(byte[] model) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (bytes == null) return null;
        check(model);
        out.put("file", SBEFormat.CHARACTER_FILENAME);
        out.put("source", source);
        out.put("profile", report.profile());
        out.put("roles", report.roles());
        if (report.metrics() != null) {
            CharacterDefinition.Metrics m = report.metrics();
            out.put("metrics", Map.of("height", m.height(), "eyeHeight", m.eyeHeight(), "width", m.width(),
                    "hipHeight", m.hipHeight()));
        }
        out.put("sockets", report.sockets());
        out.put("errors", report.errors());
        out.put("warnings", report.warnings());
        if (report.unreadable() != null) out.put("unreadable", report.unreadable());
        return out;
    }

    private void check(byte[] model) {
        if (bytes == null) {
            report = Report.NONE;
            return;
        }
        if (model == checkedModel && report != Report.NONE) return;
        checkedModel = model;
        try (CharacterDefinition def = CharacterDefinition.read(bytes)) {
            List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
            if (model != null) {
                Rig rig = EntityModelLoader.build(EntityModelSource.modelOnly("sbe-character-check", model))
                        .geometryFor("Default").rig().rig();
                for (CharacterDefinition.Issue i : def.check(rig)) {
                    String line = i.code() + (i.subject() == null || i.subject().isBlank() ? "" : " " + i.subject())
                            + ": " + i.message();
                    (i.severity() == CharacterDefinition.Severity.ERROR ? errors : warnings).add(line);
                }
            }
            List<String> sockets = new ArrayList<>();
            def.sockets().forEach(s -> sockets.add(s.name()));
            report = new Report(def.profile(), def.roles().size(), def.metrics(), sockets, errors, warnings, null);
        } catch (Exception e) {
            report = new Report(null, 0, null, List.of(), List.of(), List.of(), e.getMessage());
        }
    }

    void render(byte[] model, Consumer<Consumer<String>> chooseFile, java.util.function.Function<String, byte[]> reader) {
        ImGui.textWrapped("The entity's character definition (.omchar): role map, reference pose, limits, chains, "
                + "sockets and metrics, made in Open Mason's Character workspace. Character and creature types embed one;"
                + " the game reads proportions and sockets from it.");
        ImGui.spacing();
        if (ImGui.button(bytes == null ? "Embed .omchar..." : "Replace...")) {
            chooseFile.accept(path -> {
                byte[] value = reader.apply(path);
                if (value != null) set(value, java.nio.file.Path.of(path).getFileName().toString(), model);
            });
        }
        if (bytes != null) {
            ImGui.sameLine();
            if (ImGui.button("Remove")) clear();
        }
        ImGui.separator();
        if (bytes == null) {
            ImGui.textDisabled("No character definition embedded.");
            return;
        }
        check(model);
        ImGui.text("Source: " + source + "  (" + bytes.length + " bytes)");
        if (report.unreadable() != null) {
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, "Unreadable: " + report.unreadable());
            return;
        }
        ImGui.text("Profile: " + report.profile() + "   roles mapped: " + report.roles());
        if (report.metrics() != null) {
            CharacterDefinition.Metrics m = report.metrics();
            ImGui.text(String.format(java.util.Locale.ROOT, "Height %.3f   eye height %.3f   width %.3f   hip height %.3f",
                    m.height(), m.eyeHeight(), m.width(), m.hipHeight()));
        }
        ImGui.textWrapped("Sockets: " + (report.sockets().isEmpty() ? "(none)" : String.join(", ", report.sockets())));
        if (report.errors().isEmpty()) {
            ImGui.textColored(0.45f, 0.85f, 0.5f, 1f, "Fits the model's skeleton.");
        }
        for (String e : report.errors()) ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, e);
        for (String w : report.warnings()) ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, w);
    }
}
