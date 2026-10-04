package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.io.EntryPaths;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Structural validation of a decoded {@link OmuiArchive}. Runs on every read and before
 * every write, so an invalid document can neither be loaded nor saved. Widget property
 * descriptors, selector matching and graph typing are validated by their owning runtime
 * issues (#287, #291); this layer guarantees identity, references and syntax.
 */
public final class OmuiValidator {

    private OmuiValidator() {
    }

    public static List<UiDiagnostic> validate(OmuiArchive archive) {
        UiDiagnostics d = new UiDiagnostics();
        validate(archive, d);
        return d.list();
    }

    public static void validate(OmuiArchive archive, UiDiagnostics d) {
        manifest(archive.manifest(), d);
        Set<String> nodeIds = TreeValidator.validate(archive, d);
        PartValidator.validate(archive, nodeIds, d);
        DependencyValidator.validate(archive, d);
        for (String name : archive.extraEntries().keySet()) {
            d.info(Code.UNKNOWN_ENTRY, name, "", "Unrecognized entry preserved verbatim");
        }
        entryNames(archive, d);
    }

    /**
     * Every entry the writer would emit must be a safe, collision-free archive name — checked
     * here so an in-memory document with, say, a sheet called {@code con} fails with a
     * diagnostic instead of at ZIP-writing time.
     */
    private static void entryNames(OmuiArchive a, UiDiagnostics d) {
        List<String> names = new ArrayList<>(List.of(OmuiFormat.MANIFEST, OmuiFormat.DOCUMENT, OmuiFormat.DEPENDENCIES));
        a.styles().keySet().forEach(id -> names.add(OmuiFormat.styleEntry(id)));
        a.graphs().keySet().forEach(id -> names.add(OmuiFormat.graphEntry(id)));
        a.animations().keySet().forEach(id -> names.add(OmuiFormat.animationEntry(id)));
        a.scripts().keySet().forEach(id -> names.add(OmuiFormat.scriptEntry(id)));
        names.addAll(a.assets().keySet());
        names.addAll(a.editor().keySet());
        names.addAll(a.extraEntries().keySet());
        EntryPaths.checkAll(names, d);
    }

    /** Reports {@code requires} entries this reader does not support. */
    public static void requiredFeatures(List<String> requires, UiDiagnostics d) {
        for (String feature : requires) {
            if (!OmuiFormat.SUPPORTED_FEATURES.contains(feature)) {
                d.error(Code.UNSUPPORTED_REQUIRED_FEATURE, OmuiFormat.MANIFEST, "/requires",
                        "Required feature '" + feature + "' is not supported by this reader (schema "
                                + OmuiFormat.SCHEMA_VERSION + ")");
            }
        }
    }

    private static void manifest(UiManifest m, UiDiagnostics d) {
        String e = OmuiFormat.MANIFEST;
        if (!OmuiFormat.LOGICAL_ID.matcher(m.documentId()).matches()) {
            d.error(Code.INVALID_ID, e, "/documentId", "Invalid document id '" + m.documentId() + "'");
        }
        if (m.schemaVersion().major() != OmuiFormat.SCHEMA_VERSION.major()) {
            d.error(Code.UNSUPPORTED_SCHEMA_VERSION, e, "/schemaVersion",
                    "Schema " + m.schemaVersion() + " cannot be written as major " + OmuiFormat.SCHEMA_VERSION.major());
        }
        if (m.uiApi() < 0 || m.uiApi() > OmuiFormat.MAX_VERSION) {
            d.error(Code.INVALID_VALUE, e, "/uiApi", "uiApi must be in [0, " + OmuiFormat.MAX_VERSION + "]");
        }
        requiredFeatures(m.requires(), d);
        host(m.hostApis(), "/hostApis", d);
        host(m.providers(), "/providers", d);
    }

    private static void host(List<HostRequirement> list, String ptr, UiDiagnostics d) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            HostRequirement h = list.get(i);
            if (!OmuiFormat.LOGICAL_ID.matcher(h.id()).matches() && !OmuiFormat.WIDGET_TYPE.matcher(h.id()).matches()) {
                d.error(Code.INVALID_ID, OmuiFormat.MANIFEST, ptr + "/" + i + "/id", "Invalid host id '" + h.id() + "'");
            }
            if (h.version() < 1 || h.version() > OmuiFormat.MAX_VERSION) {
                d.error(Code.INVALID_VALUE, OmuiFormat.MANIFEST, ptr + "/" + i + "/version", "Version must be >= 1");
            }
            if (!seen.add(h.id())) {
                d.error(Code.DUPLICATE_ID, OmuiFormat.MANIFEST, ptr + "/" + i + "/id", "'" + h.id() + "' listed twice");
            }
        }
    }

    /**
     * Detects recursive composition across documents: {@code root} instantiates a component
     * that (transitively) instantiates {@code root} or itself. {@code lookup} returns the
     * component document for a dependency id, or {@code null} when it is not available (that
     * case is a resolver concern, not a cycle).
     */
    public static void componentCycles(OmuiArchive root, Function<String, OmuiArchive> lookup, UiDiagnostics d) {
        walk(root, lookup, new LinkedHashSet<>(), new HashSet<>(), d);
    }

    private static void walk(OmuiArchive doc, Function<String, OmuiArchive> lookup, LinkedHashSet<String> path,
                             Set<String> done, UiDiagnostics d) {
        String id = doc.manifest().documentId();
        if (path.contains(id)) {
            List<String> cycle = new ArrayList<>(path);
            cycle = cycle.subList(cycle.indexOf(id), cycle.size());
            d.error(Code.RECURSIVE_COMPONENT, OmuiFormat.DOCUMENT, "",
                    "Recursive composition: " + String.join(" -> ", cycle) + " -> " + id);
            return;
        }
        if (!done.add(id)) {
            return;
        }
        path.add(id);
        Set<String> refs = new LinkedHashSet<>();
        for (UiNode n : doc.document().root().flatten()) {
            if (n.instance() != null) {
                refs.add(n.instance().component());
            }
        }
        for (String ref : refs) {
            OmuiArchive child = lookup.apply(ref);
            if (child != null) {
                walk(child, lookup, path, done, d);
            }
        }
        path.remove(id);
    }
}
