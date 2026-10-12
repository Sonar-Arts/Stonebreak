package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The dependency rows a document needs to place a shared component (#324). At runtime and in an
 * export every shared dependency of every carried component, nested ones included, resolves
 * through the placing document's single table ({@code ComponentClosure}), so placing
 * {@code card} (which places {@code btn}, which uses a texture) must list all three, and each
 * component's row {@code requires} its closure so embedding it later takes everything along.
 */
public final class ComponentDependencies {

    private static final int MAX_DEPTH = 32;

    /** A shared row for a component found in the project, or null. */
    @FunctionalInterface
    public interface RowSource {
        UiDependency row(String componentId) throws java.io.IOException;
    }

    private ComponentDependencies() {
    }

    /**
     * @param rowOf     a fresh shared row for a project component (current hash), or null
     * @param archiveOf the source of a project component, or null
     * @return the component's own row (with {@code requires} = its closure) first, then every row
     *         its closure needs; empty when {@code componentId} is not a project component
     */
    public static List<UiDependency> rows(String componentId, RowSource rowOf, Function<String, OmuiArchive> archiveOf)
            throws java.io.IOException {
        UiDependency row = rowOf.row(componentId);
        if (row == null) {
            return List.of();
        }
        Map<String, UiDependency> closureRows = new LinkedHashMap<>();
        Set<String> visiting = new HashSet<>();
        visiting.add(componentId);
        Set<String> needs = closure(archiveOf.apply(componentId), rowOf, archiveOf, closureRows, visiting, 0);
        List<UiDependency> out = new ArrayList<>();
        out.add(withRequires(row, needs));
        out.addAll(closureRows.values());
        return out;
    }

    /** The ids {@code comp} needs listed (shared rows and their closures); fills {@code rows}. */
    private static Set<String> closure(OmuiArchive comp, RowSource rowOf, Function<String, OmuiArchive> archiveOf,
                                       Map<String, UiDependency> rows, Set<String> visiting, int depth)
            throws java.io.IOException {
        Set<String> needs = new LinkedHashSet<>();
        if (comp == null || depth > MAX_DEPTH) {
            return needs;
        }
        for (UiDependency r : comp.dependencies().entries()) {
            if (r.mode() == UiDependency.Mode.SHARED) {
                needs.add(r.id());
                if (r.kind() == UiDependency.Kind.COMPONENT && visiting.add(r.id())) {
                    Set<String> nested = closure(archiveOf.apply(r.id()), rowOf, archiveOf, rows, visiting, depth + 1);
                    UiDependency fresh = rowOf.row(r.id());
                    rows.putIfAbsent(r.id(), withRequires(fresh != null ? fresh : r, nested));
                    needs.addAll(nested);
                } else {
                    rows.putIfAbsent(r.id(), r);
                }
            } else if (r.kind() == UiDependency.Kind.COMPONENT && r.entry() != null) {
                // embedded in the component: its own shared needs must still be listed by the placer
                UiBytes bytes = comp.assets().get(r.entry());
                if (bytes != null) {
                    try {
                        needs.addAll(closure(OmuiReader.read(bytes.toArray()).archive(), rowOf, archiveOf, rows,
                            visiting, depth + 1));
                    } catch (RuntimeException | com.openmason.engine.format.omui.UiFormatException e) {
                        // unreadable embedded component: the export reports it
                    }
                }
            }
        }
        return needs;
    }

    private static UiDependency withRequires(UiDependency r, Set<String> requires) {
        Set<String> all = new LinkedHashSet<>(r.requires());
        all.addAll(requires);
        return new UiDependency(r.id(), r.kind(), r.version(), r.sha256(), r.size(), r.mode(), r.entry(), r.sourceHint(),
            List.copyOf(all), r.optional(), r.fallback(), r.license(), r.unknown());
    }
}
