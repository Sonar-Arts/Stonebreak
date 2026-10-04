package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.EntryPaths;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks {@code dependencies.json} against the archive: identity, hashes of embedded
 * snapshots, closure references, {@code requires} cycles, portable source hints and orphaned
 * asset entries.
 */
final class DependencyValidator {

    private static final String E = OmuiFormat.DEPENDENCIES;

    private DependencyValidator() {
    }

    static void validate(OmuiArchive a, UiDiagnostics d) {
        List<UiDependency> rows = a.dependencies().entries();
        if (rows.size() > OmuiFormat.MAX_DEPENDENCIES) {
            d.error(Code.LIMIT_EXCEEDED, E, "/dependencies", "More than " + OmuiFormat.MAX_DEPENDENCIES + " rows");
            return;
        }
        Map<String, UiDependency> byId = new HashMap<>();
        Set<String> referencedEntries = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            UiDependency dep = rows.get(i);
            String ptr = "/dependencies/" + i;
            if (!OmuiFormat.LOGICAL_ID.matcher(dep.id()).matches()) {
                d.error(Code.INVALID_ID, E, ptr + "/id", "Invalid dependency id '" + dep.id() + "'");
            }
            if (byId.putIfAbsent(dep.id(), dep) != null) {
                d.error(Code.DUPLICATE_ID, E, ptr + "/id", "Dependency '" + dep.id() + "' is listed twice");
            }
            if (dep.size() < 0) {
                d.error(Code.INVALID_VALUE, E, ptr + "/size", "Size cannot be negative");
            }
            if (!OmuiFormat.SHA256.matcher(dep.sha256()).matches()) {
                d.error(Code.INVALID_VALUE, E, ptr + "/sha256", "Expected 64 lowercase hex digits");
            }
            if (dep.sourceHint() != null && EntryPaths.problem(dep.sourceHint()) != null) {
                d.error(Code.UNSAFE_ENTRY_PATH, E, ptr + "/sourceHint",
                        "Source hints are project-relative paths: " + EntryPaths.problem(dep.sourceHint()));
            }
            if ((dep.kind() == UiDependency.Kind.FONT || dep.kind() == UiDependency.Kind.SOUND) && dep.license() == null) {
                d.warning(Code.MISSING_FIELD, E, ptr + "/license", "Fonts and sounds should carry licence provenance");
            }
            if (dep.mode() == UiDependency.Mode.EMBEDDED) {
                embedded(a, dep, ptr, d);
                referencedEntries.add(dep.entry());
            } else if (dep.entry() != null) {
                d.error(Code.INVALID_VALUE, E, ptr + "/entry", "Shared dependencies have no archive entry");
            }
            if (dep.fallback() != null && !dep.optional()) {
                d.error(Code.INVALID_VALUE, E, ptr + "/fallback", "Only optional dependencies have a fallback");
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            UiDependency dep = rows.get(i);
            for (String req : dep.requires()) {
                if (!byId.containsKey(req)) {
                    d.error(Code.UNRESOLVED_REFERENCE, E, "/dependencies/" + i + "/requires",
                            "'" + req + "' is not in the table");
                }
            }
            if (dep.fallback() != null && !byId.containsKey(dep.fallback())) {
                d.error(Code.UNRESOLVED_REFERENCE, E, "/dependencies/" + i + "/fallback",
                        "'" + dep.fallback() + "' is not in the table");
            }
        }
        cycles(byId, d);
        for (String entry : a.assets().keySet()) {
            if (!referencedEntries.contains(entry)) {
                d.warning(Code.ORPHAN_ENTRY, entry, "", "No embedded dependency references this asset; preserved");
            }
        }
    }

    private static void embedded(OmuiArchive a, UiDependency dep, String ptr, UiDiagnostics d) {
        if (dep.entry() == null || !dep.entry().startsWith(OmuiFormat.ASSETS_DIR)) {
            d.error(Code.INVALID_VALUE, E, ptr + "/entry", "Embedded dependencies live under assets/");
            return;
        }
        UiBytes bytes = a.assets().get(dep.entry());
        if (bytes == null) {
            d.error(Code.MISSING_ENTRY, E, ptr + "/entry", "Embedded snapshot '" + dep.entry() + "' is missing");
        } else if (!bytes.sha256().equals(dep.sha256()) || (dep.size() != 0 && bytes.size() != dep.size())) {
            d.error(Code.HASH_MISMATCH, dep.entry(), "", "Bytes do not match the hash/size recorded for '" + dep.id() + "'");
        }
    }

    /** Reports each {@code requires} cycle once, naming its members in order. */
    private static final int MAX_CYCLE_REPORTS = 16;
    private static final int MAX_CYCLE_PATH = 8;

    /**
     * Reports {@code requires} cycles (at most {@value #MAX_CYCLE_REPORTS}, each naming up to
     * {@value #MAX_CYCLE_PATH} members). Iterative, so a long chain cannot overflow the stack.
     */
    private static void cycles(Map<String, UiDependency> byId, UiDiagnostics d) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on path, 2 = done
        List<String> path = new ArrayList<>();
        List<Integer> next = new ArrayList<>();       // index of the next requirement per path element
        int reported = 0;
        for (String start : byId.keySet().stream().sorted(UiValue.KEY_ORDER).toList()) {
            if (state.containsKey(start)) {
                continue;
            }
            state.put(start, 1);
            path.add(start);
            next.add(0);
            while (!path.isEmpty()) {
                int top = path.size() - 1;
                List<String> requires = byId.get(path.get(top)).requires();
                int i = next.get(top);
                if (i == requires.size()) {
                    state.put(path.removeLast(), 2);
                    next.removeLast();
                    continue;
                }
                next.set(top, i + 1);
                String req = requires.get(i);
                Integer s = state.get(req);
                if (s == null && byId.containsKey(req)) {
                    state.put(req, 1);
                    path.add(req);
                    next.add(0);
                } else if (s != null && s == 1 && reported++ < MAX_CYCLE_REPORTS) {
                    List<String> cycle = path.subList(path.indexOf(req), path.size());
                    String shown = String.join(" -> ", cycle.subList(0, Math.min(cycle.size(), MAX_CYCLE_PATH)));
                    d.error(Code.DEPENDENCY_CYCLE, E, "", "Dependency cycle: " + shown
                            + (cycle.size() > MAX_CYCLE_PATH ? " -> ... (" + cycle.size() + " members)" : "")
                            + " -> " + req);
                }
            }
        }
    }
}
