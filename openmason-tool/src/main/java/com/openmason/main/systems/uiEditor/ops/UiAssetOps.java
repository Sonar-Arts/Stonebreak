package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.io.GraphCodec;
import com.openmason.engine.format.omui.io.StateMachineCodec;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.edit.AssetEdit;
import com.openmason.engine.ui.assets.edit.CollisionPolicy;
import com.openmason.engine.ui.assets.edit.EmbedOperations;
import com.openmason.engine.ui.assets.edit.ExtractOperations;
import com.openmason.engine.ui.assets.edit.RelinkOperations;
import com.openmason.main.systems.uiEditor.command.AnimationCommands;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.service.UiImageAssets;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code ui_ops} ops for a document's dependency table (textures, sprite sheets, fonts,
 * sounds, shared scripts and style sheets, components), its behavior graphs and its animation
 * state machines (#324 follow-up): what an agent needs to finish a screen without the author.
 * Table edits run the same #285 operations as the UI Assets panel (embed, refresh, extract,
 * relink), so their project writes are part of the batch's one undo step and are reverted when a
 * later op fails.
 */
final class UiAssetOps {

    private UiAssetOps() {
    }

    // ── dependency rows ─────────────────────────────────────────────────────

    /**
     * {@code add_dependency {path | id, kind?, embed?, optional?, fallback?, requires?, license?}}:
     * a project file ({@code path}, project-relative) or an id found in the project or the game's
     * packaged assets ({@code id} + {@code kind}). A sprite sheet also adds the texture it was
     * authored against. Re-adding a listed id of the same kind changes nothing.
     */
    static void add(UiOpScope s, JsonNode o) throws UiCommandException {
        UiProjectContext project = s.components.project();
        UiDependency row;
        if (o.hasNonNull("path")) {
            Path file = projectFile(project, o.get("path").asText());
            UiDependency.Kind kind = o.hasNonNull("kind") ? kind(o.get("kind").asText())
                : UiImageAssets.kindOf(file.getFileName().toString());
            if (kind == null) {
                throw new UiCommandException("Cannot tell the kind of " + o.get("path").asText() + ": pass kind ("
                    + kinds() + ")");
            }
            String id = o.hasNonNull("id") ? o.get("id").asText() : UiImageAssets.idFor(project, file, kind);
            requireId(id);
            if (kind == UiDependency.Kind.SPRITES) {
                try {
                    s.run(UiImageAssets.addSheet(project, new UiImageAssets.ProjectImage(file,
                        project.relative(file), kind, id)));
                } catch (IOException e) {
                    throw new UiCommandException("Cannot read sprite sheet " + o.get("path").asText() + ": "
                        + e.getMessage(), e);
                }
                row = s.ctx.doc().dependencies().find(id);
            } else {
                try {
                    row = project.sharedRow(id, kind, file);
                } catch (IOException e) {
                    throw new UiCommandException("Cannot read " + o.get("path").asText() + ": " + e.getMessage(), e);
                }
            }
        } else {
            String id = o.get("id").asText();
            requireId(id);
            if (!o.hasNonNull("kind")) {
                throw new UiCommandException("add_dependency by id needs kind (" + kinds() + "), or pass path");
            }
            UiDependency.Kind kind = kind(o.get("kind").asText());
            row = findShared(project, id, kind);
        }
        UiDependency listed = s.ctx.doc().dependencies().find(row.id());
        if (listed != null && listed.kind() != row.kind()) {
            throw new UiCommandException("'" + row.id() + "' is already listed as a " + listed.kind().wire()
                + " dependency");
        }
        if (listed == null) {
            s.run(DocumentCommands.ensureDependency(withOptions(row, o)));
        } else if (hasOptions(o)) {
            replaceRow(s, withOptions(listed, o));
        }
        if (o.path("embed").asBoolean(false)) {
            embed(s, row.id());
        }
    }

    /** {@code set_dependency {id, optional?, fallback?, requires?, license?}}. */
    static void set(UiOpScope s, JsonNode o) throws UiCommandException {
        UiDependency row = row(s, o.get("id").asText());
        replaceRow(s, withOptions(row, o));
    }

    /**
     * {@code remove_dependency {id, force?}}: refused while the document still references the id
     * (a widget prop, style, binding, graph literal or another row's requires) unless {@code force}.
     */
    static void remove(UiOpScope s, JsonNode o) throws UiCommandException {
        String id = o.get("id").asText();
        OmuiArchive d = s.ctx.doc();
        UiDependency row = row(s, id);
        boolean force = o.path("force").asBoolean(false);
        if (!force) {
            List<String> users = new ArrayList<>();
            if (DependencyRefs.referenced(d).contains(id)) {
                users.add("the document's content");
            }
            for (UiDependency other : d.dependencies().entries()) {
                if (other.requires().contains(id)) {
                    users.add("row " + other.id() + " (requires)");
                }
                if (id.equals(other.fallback())) {
                    users.add("row " + other.id() + " (fallback)");
                }
            }
            if (id.equals(d.document().codeBehind())) {
                users.add("the code-behind");
            }
            if (!users.isEmpty()) {
                throw new UiCommandException("'" + id + "' is still used by " + String.join(", ", users)
                    + " (pass force:true to remove it anyway)");
            }
        }
        List<UiDependency> rows = new ArrayList<>(d.dependencies().entries());
        rows.remove(row);
        OmuiArchive next = d.withDependencies(new OmuiArchive.UiDependencies(rows, d.dependencies().unknown()));
        if (row.entry() != null && rows.stream().noneMatch(r -> row.entry().equals(r.entry()))) {
            Map<String, com.openmason.engine.format.omui.UiBytes> assets = new LinkedHashMap<>(next.assets());
            assets.remove(row.entry()); // the snapshot goes with its last row
            next = new OmuiArchive(next.manifest(), next.document(), next.styles(), next.graphs(), next.animations(),
                next.stateMachines(), next.scripts(), next.dependencies(), assets, next.editor(), next.extraEntries());
        }
        s.ctx.setDoc(next);
    }

    /** {@code embed_dependency {id}}: snapshot the shared row (and its closure) into the document. */
    static void embed(UiOpScope s, String id) throws UiCommandException {
        List<AssetSource> sources = sources(s);
        assetEdit(s, () -> EmbedOperations.embed(s.ctx.doc(), id, sources));
    }

    /** {@code refresh_dependency {id}}: re-take an embedded snapshot from where it came from. */
    static void refresh(UiOpScope s, JsonNode o) throws UiCommandException {
        List<AssetSource> sources = sources(s);
        assetEdit(s, () -> EmbedOperations.refresh(s.ctx.doc(), o.get("id").asText(), sources));
    }

    /** {@code extract_dependency {id, collision?}}: write a snapshot into the project and share it. */
    static void extract(UiOpScope s, JsonNode o) throws UiCommandException {
        ProjectAssetSource source = projectSource(s);
        CollisionPolicy policy = CollisionPolicy.FAIL;
        if (o.hasNonNull("collision")) {
            policy = switch (o.get("collision").asText()) {
                case "fail" -> CollisionPolicy.FAIL;
                case "keep_project" -> CollisionPolicy.KEEP_PROJECT;
                case "replace" -> CollisionPolicy.REPLACE;
                default -> throw new UiCommandException("collision is fail, keep_project or replace");
            };
        }
        CollisionPolicy p = policy;
        assetEdit(s, () -> ExtractOperations.extractToProject(s.ctx.doc(), o.get("id").asText(), source, p));
    }

    /** {@code relink_dependency {id, path}}: point a shared row at another project file (new hash and hint). */
    static void relink(UiOpScope s, JsonNode o) throws UiCommandException {
        ProjectAssetSource source = projectSource(s);
        String path = o.get("path").asText();
        projectFile(s.components.project(), path); // existence + sandbox check with a teaching message
        assetEdit(s, () -> RelinkOperations.relink(s.ctx.doc(), o.get("id").asText(), path, source));
    }

    // ── graphs and state machines ───────────────────────────────────────────

    /** {@code put_graph {graph}}: adds or replaces a behavior graph (wire JSON, {@code id} included). */
    static void putGraph(UiOpScope s, JsonNode o) throws UiCommandException {
        JsonNode json = o.get("graph");
        String id = json.path("id").asText();
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            throw new UiCommandException("graph.id '" + id + "' is not a valid graph id (lowercase, digits, _ - /)");
        }
        UiDiagnostics d = new UiDiagnostics();
        UiGraph graph = GraphCodec.read(id, OmuiFormat.graphEntry(id), UiJson.value(json),
            com.openmason.engine.format.omui.ArchiveLimits.DEFAULT, d);
        if (d.hasErrors() || graph == null) {
            throw new UiCommandException("Graph '" + id + "': " + (d.list().isEmpty() ? "unreadable"
                : d.list().getFirst().message()));
        }
        s.ctx.setDoc(s.ctx.doc().withGraph(graph));
    }

    /** {@code remove_graph {id}}: removes a graph and its editor layout. */
    static void removeGraph(UiOpScope s, JsonNode o) throws UiCommandException {
        String id = o.get("id").asText();
        OmuiArchive d = s.ctx.doc();
        if (!d.graphs().containsKey(id)) {
            throw new UiCommandException("No graph '" + id + "'; graphs: " + d.graphs().keySet());
        }
        Map<String, UiGraph> graphs = new LinkedHashMap<>(d.graphs());
        graphs.remove(id);
        Map<String, com.openmason.engine.format.omui.UiBytes> editor = new LinkedHashMap<>(d.editor());
        editor.keySet().removeIf(k -> k.startsWith(OmuiFormat.EDITOR_DIR + "graphs/" + id + "."));
        s.ctx.setDoc(new OmuiArchive(d.manifest(), d.document(), d.styles(), graphs, d.animations(),
            d.stateMachines(), d.scripts(), d.dependencies(), d.assets(), editor, d.extraEntries()));
    }

    /** {@code put_state_machine {machine}}: adds or replaces an animation state machine (wire JSON, {@code id} included). */
    static void putStateMachine(UiOpScope s, JsonNode o) throws UiCommandException {
        JsonNode json = o.get("machine");
        String id = json.path("id").asText();
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            throw new UiCommandException("machine.id '" + id + "' is not a valid id (lowercase, digits, _ - /)");
        }
        UiDiagnostics d = new UiDiagnostics();
        UiStateMachine m = StateMachineCodec.read(id, OmuiFormat.stateMachineEntry(id), UiJson.value(json), d);
        if (d.hasErrors() || m == null) {
            throw new UiCommandException("State machine '" + id + "': " + (d.list().isEmpty() ? "unreadable"
                : d.list().getFirst().message()));
        }
        s.run(AnimationCommands.putStateMachine(m, null));
    }

    /** {@code remove_state_machine {id}}. */
    static void removeStateMachine(UiOpScope s, JsonNode o) throws UiCommandException {
        s.run(AnimationCommands.removeStateMachine(o.get("id").asText()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean hasOptions(JsonNode o) {
        return o.has("optional") || o.has("fallback") || o.has("requires") || o.has("license");
    }

    private static UiDependency withOptions(UiDependency r, JsonNode o) throws UiCommandException {
        boolean optional = o.has("optional") ? o.get("optional").asBoolean() : r.optional();
        String fallback = o.has("fallback") ? UiOpScope.optText(o, "fallback") : r.fallback();
        List<String> requires = o.has("requires") ? UiOpScope.strings(o, "requires") : r.requires();
        String license = o.has("license") ? UiOpScope.optText(o, "license") : r.license();
        if (fallback != null) {
            requireId(fallback);
        }
        for (String req : requires) {
            requireId(req);
        }
        return new UiDependency(r.id(), r.kind(), r.version(), r.sha256(), r.size(), r.mode(), r.entry(),
            r.sourceHint(), requires, optional, fallback, license, r.unknown());
    }

    private static void replaceRow(UiOpScope s, UiDependency next) {
        OmuiArchive d = s.ctx.doc();
        List<UiDependency> rows = new ArrayList<>();
        for (UiDependency r : d.dependencies().entries()) {
            rows.add(r.id().equals(next.id()) ? next : r);
        }
        s.ctx.setDoc(d.withDependencies(new OmuiArchive.UiDependencies(rows, d.dependencies().unknown())));
    }

    private static UiDependency row(UiOpScope s, String id) throws UiCommandException {
        UiDependency row = s.ctx.doc().dependencies().find(id);
        if (row == null) {
            List<String> ids = s.ctx.doc().dependencies().entries().stream().map(UiDependency::id).toList();
            throw new UiCommandException("'" + id + "' is not a dependency of this document; rows: " + ids);
        }
        return row;
    }

    private static void requireId(String id) throws UiCommandException {
        if (!OmuiFormat.LOGICAL_ID.matcher(id).matches()) {
            throw new UiCommandException("'" + id + "' is not a dependency id (namespace:path, lowercase)");
        }
    }

    static UiDependency.Kind kind(String wire) throws UiCommandException {
        for (UiDependency.Kind k : UiDependency.Kind.values()) {
            if (k.wire().equals(wire.toLowerCase(Locale.ROOT))) {
                return k;
            }
        }
        throw new UiCommandException("Unknown kind '" + wire + "'; kinds: " + kinds());
    }

    private static String kinds() {
        return String.join(", ", java.util.Arrays.stream(UiDependency.Kind.values()).map(UiDependency.Kind::wire).toList());
    }

    /** A project file named by a project-relative path, refused when outside the project (no escapes). */
    private static Path projectFile(UiProjectContext project, String rel) throws UiCommandException {
        if (project == null || project.root() == null) {
            throw new UiCommandException("A path needs an open project (or add the asset by id + kind)");
        }
        Path root = project.root().toAbsolutePath().normalize();
        Path file = root.resolve(rel).normalize();
        try {
            if (!file.startsWith(root) || Files.exists(file) && !file.toRealPath().startsWith(root.toRealPath())) {
                throw new UiCommandException("'" + rel + "' is outside the project");
            }
        } catch (IOException e) {
            throw new UiCommandException("Cannot read " + rel + ": " + e.getMessage(), e);
        }
        if (!Files.isRegularFile(file)) {
            throw new UiCommandException("No project file '" + rel + "' (paths are project-relative, e.g."
                + " UI/stonebreak/ui/textures/panel.sbt)");
        }
        return file;
    }

    /** A shared row for {@code id} found in the project or the game's packaged assets. */
    private static UiDependency findShared(UiProjectContext project, String id, UiDependency.Kind kind)
            throws UiCommandException {
        List<AssetSource> sources = project == null ? List.of() : project.sources();
        for (AssetSource src : sources) {
            try {
                ResolvedAsset a = src.find(id, kind, null);
                if (a != null) {
                    String hint = src instanceof ProjectAssetSource ? a.location() : null;
                    return UiDependency.shared(id, kind, a.sha256(), a.bytes().size(), hint);
                }
            } catch (IOException e) {
                throw new UiCommandException("Cannot read " + id + " from " + src.name() + ": " + e.getMessage(), e);
            }
        }
        throw new UiCommandException("No " + kind.wire() + " '" + id + "' in the project or the game's packaged"
            + " assets (add a project file with path instead)");
    }

    private static List<AssetSource> sources(UiOpScope s) throws UiCommandException {
        UiProjectContext project = s.components.project();
        if (project == null) {
            throw new UiCommandException("This op needs the project's asset sources (an open UI editor)");
        }
        return project.sources();
    }

    private static ProjectAssetSource projectSource(UiOpScope s) throws UiCommandException {
        UiProjectContext project = s.components.project();
        ProjectAssetSource source = project == null ? null : project.projectSource();
        if (source == null) {
            throw new UiCommandException("This op writes into the project: open a project first");
        }
        return source;
    }

    @FunctionalInterface
    private interface Planner {
        AssetEdit plan() throws UiFormatException, IOException;
    }

    /** Runs a #285 asset edit inside the batch (its project writes join the batch's undo step). */
    private static void assetEdit(UiOpScope s, Planner planner) throws UiCommandException {
        AssetEdit edit;
        try {
            edit = planner.plan();
        } catch (UiFormatException e) {
            throw new UiCommandException(e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message(), e);
        } catch (IOException e) {
            throw new UiCommandException(e.getMessage(), e);
        }
        UiCommand c = DocumentCommands.assetEdit(edit);
        s.run(c);
    }
}
