package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.export.PlanItem.Action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plans an SBUI export from an editor document: resolves every dependency (and the components
 * it carries) through the project's sources, checks the closure, and decides per row whether
 * it travels in the source, is collected, or must ship as a shared resource.
 *
 * <p>Blocking (errors): an invalid document (which includes {@code requires} cycles and
 * unlisted references), a required dependency nothing provides, a component needing a shared
 * dependency the table lacks, recursive composition, a collected-entry name collision, a
 * font or sound that would be redistributed without licence provenance, and a sprite reference
 * to a missing or invalid region ({@link SpriteChecks}, #294).
 */
public final class ExportPlanner {

    /**
     * @param packs shared dependency id → resource pack it ships in (shared exports)
     */
    public record Request(ExportMode mode, Map<String, String> packs) {
        public Request {
            packs = packs == null ? Map.of() : Map.copyOf(packs);
        }

        public static Request of(ExportMode mode) {
            return new Request(mode, Map.of());
        }
    }

    private ExportPlanner() {
    }

    public static ExportPlan plan(OmuiArchive doc, List<? extends AssetSource> sources, Request request) {
        UiDiagnostics d = new UiDiagnostics();
        try {
            OmuiWriter.entries(doc);
        } catch (UiFormatException e) {
            d.addAll(e.diagnostics());
        }
        AssetResolver resolver = AssetResolver.forDocument(doc, sources);
        Resolution resolution = resolver.resolveAll();
        d.addAll(resolution.diagnostics());
        Map<String, OmuiArchive> components = ComponentClosure.check(doc, resolution, d);
        SpriteChecks.check(doc, components.values(), resolution, d);
        unused(doc, d);

        List<PlanItem> items = new ArrayList<>();
        Map<String, UiBytes> collected = new LinkedHashMap<>();
        for (UiDependency row : doc.dependencies().entries()) {
            items.add(item(row, resolver, resolution, request, collected, d));
        }
        collisions(doc, collected, d);

        List<HostRequirement> apis = new ArrayList<>(doc.manifest().hostApis());
        List<HostRequirement> providers = new ArrayList<>(doc.manifest().providers());
        components.values().forEach(c -> {
            apis.addAll(c.manifest().hostApis());
            providers.addAll(c.manifest().providers());
        });
        Map<String, String> packs = new LinkedHashMap<>();
        request.packs().forEach((id, pack) -> {
            if (doc.dependencies().find(id) == null) {
                d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", "Pack assignment for '" + id
                        + "', which is not a dependency");
            } else if (!collected.containsKey(id)) {
                packs.put(id, pack);
            }
        });
        return new ExportPlan(doc.manifest().documentId(), request.mode(), items, union(apis), union(providers),
                collected, packs, d.list());
    }

    private static PlanItem item(UiDependency row, AssetResolver resolver, Resolution resolution, Request request,
                                 Map<String, UiBytes> collected, UiDiagnostics d) {
        ResolvedAsset a = resolution.get(row.id());
        boolean redistributable = row.license() != null
                || (row.kind() != UiDependency.Kind.FONT && row.kind() != UiDependency.Kind.SOUND);
        String pack = request.packs().get(row.id());
        List<String> shadowed = shadowed(resolver, row, a, d);
        Action action;
        if (a == null) {
            action = row.optional() ? Action.OMITTED : Action.MISSING;
        } else if (!a.id().equals(row.id())) {
            action = Action.FALLBACK;
        } else if (row.mode() == UiDependency.Mode.EMBEDDED) {
            action = Action.SOURCE_EMBEDDED;
            if (!redistributable) {
                d.error(Code.MISSING_FIELD, OmuiFormat.DEPENDENCIES, "", "Embedded " + row.kind().wire() + " '"
                        + row.id() + "' would be redistributed without licence provenance");
            }
        } else if (request.mode() == ExportMode.COLLECT_ALL && redistributable) {
            action = Action.COLLECTED;
            collected.put(row.id(), a.bytes());
        } else {
            action = Action.SHIPS_SHARED;
            if (!redistributable && request.mode() == ExportMode.COLLECT_ALL) {
                String msg = "Cannot collect " + row.kind().wire() + " '" + row.id() + "' without licence provenance";
                if (row.optional()) {
                    d.warning(Code.MISSING_FIELD, OmuiFormat.DEPENDENCIES, "", msg + "; it stays shared");
                } else {
                    d.error(Code.MISSING_FIELD, OmuiFormat.DEPENDENCIES, "", msg);
                }
            } else if (!redistributable) {
                d.warning(Code.MISSING_FIELD, OmuiFormat.DEPENDENCIES, "", "Shared " + row.kind().wire() + " '"
                        + row.id() + "' has no licence provenance");
            }
        }
        return new PlanItem(row.id(), row.kind(), action, row.optional(), a == null ? null : a.origin(),
                a == null ? null : a.describe(), row.sha256(), a == null ? null : a.sha256(),
                a == null ? row.size() : a.bytes().size(), action == Action.SHIPS_SHARED ? pack : null, row.license(),
                action == Action.FALLBACK ? a.id() : null, shadowed);
    }

    /** Lower-precedence sources holding the same id with other bytes: a collision worth knowing. */
    private static List<String> shadowed(AssetResolver resolver, UiDependency row, ResolvedAsset winner,
                                         UiDiagnostics d) {
        List<ResolvedAsset> all = resolver.candidates(row.id());
        if (winner == null || all.size() < 2) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (ResolvedAsset other : all.subList(1, all.size())) {
            if (!other.sha256().equals(all.getFirst().sha256())) {
                out.add(other.describe());
            }
        }
        if (!out.isEmpty()) {
            d.warning(Code.ASSET_SHADOWED, OmuiFormat.DEPENDENCIES, "", "'" + row.id() + "' resolves to "
                    + all.getFirst().describe() + ", shadowing different content at " + String.join(", ", out));
        }
        return out;
    }

    private static void collisions(OmuiArchive doc, Map<String, UiBytes> collected, UiDiagnostics d) {
        List<String> names = new ArrayList<>();
        for (String id : collected.keySet()) {
            String entry = SbuiExporter.collectedEntry(doc.dependencies().find(id));
            String problem = EntryPaths.problem(entry);
            if (problem != null) {
                d.error(Code.UNSAFE_ENTRY_PATH, entry, "", "Cannot collect '" + id + "': " + problem);
            }
            names.add(entry);
        }
        EntryPaths.checkAll(names, d);
    }

    private static void unused(OmuiArchive doc, UiDiagnostics d) {
        Set<String> used = DependencyRefs.reachable(doc, DependencyRefs.referenced(doc));
        for (UiDependency row : doc.dependencies().entries()) {
            if (!used.contains(row.id())) {
                d.info(Code.UNUSED_DEPENDENCY, OmuiFormat.DEPENDENCIES, "", "'" + row.id()
                        + "' is listed but nothing references it; it still ships");
            }
        }
    }

    /** Highest version per id; optional only when every document marks it optional. */
    private static List<HostRequirement> union(List<HostRequirement> all) {
        Map<String, HostRequirement> out = new TreeMap<>(UiValue.KEY_ORDER);
        for (HostRequirement h : all) {
            HostRequirement prev = out.get(h.id());
            out.put(h.id(), prev == null ? new HostRequirement(h.id(), h.version(), h.optional(), Map.of())
                    : new HostRequirement(h.id(), Math.max(prev.version(), h.version()), prev.optional() && h.optional(),
                    Map.of()));
        }
        return List.copyOf(out.values());
    }
}
