package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Union of runtime requirements over the documents an export embeds: highest ui API, every
 * required feature, and per host contract/provider the highest version, optional only when
 * every document marks it optional. All documents must share one layout semantics.
 */
record Requirements(int uiApi, String layoutSemantics, List<String> requires, List<HostRequirement> hostApis,
                    List<HostRequirement> providers) {

    static Requirements union(List<? extends UiRequirements> docs, UiDiagnostics d) {
        int api = 0;
        String layout = null;
        TreeSet<String> requires = new TreeSet<>(UiValue.KEY_ORDER);
        Map<String, HostRequirement> apis = new TreeMap<>(UiValue.KEY_ORDER);
        Map<String, HostRequirement> providers = new TreeMap<>(UiValue.KEY_ORDER);
        for (UiRequirements r : docs) {
            api = Math.max(api, r.uiApi());
            if (layout == null) {
                layout = r.layoutSemantics();
            } else if (!layout.equals(r.layoutSemantics())) {
                d.error(Code.INCONSISTENT_MANIFEST, SbuiFormat.MANIFEST, "/layoutSemantics",
                        "Embedded documents mix layout semantics '" + layout + "' and '" + r.layoutSemantics() + "'");
            }
            requires.addAll(r.requires());
            merge(apis, r.hostApis());
            merge(providers, r.providers());
        }
        return new Requirements(api, layout == null ? "" : layout, List.copyOf(requires),
                new ArrayList<>(apis.values()), new ArrayList<>(providers.values()));
    }

    private static void merge(Map<String, HostRequirement> into, List<HostRequirement> add) {
        for (HostRequirement h : add) {
            HostRequirement prev = into.get(h.id());
            if (prev == null) {
                into.put(h.id(), new HostRequirement(h.id(), h.version(), h.optional(), Map.of()));
            } else {
                into.put(h.id(), new HostRequirement(h.id(), Math.max(prev.version(), h.version()),
                        prev.optional() && h.optional(), Map.of()));
            }
        }
    }

    /** Reports anything in {@code this} that {@code declared} does not cover. */
    void checkCoveredBy(UiRequirements declared, UiDiagnostics d) {
        String e = SbuiFormat.MANIFEST;
        if (declared.uiApi() < uiApi) {
            d.error(Code.INCONSISTENT_MANIFEST, e, "/uiApi", "Declares ui API " + declared.uiApi() + " < " + uiApi);
        }
        if (!declared.layoutSemantics().equals(layoutSemantics)) {
            d.error(Code.INCONSISTENT_MANIFEST, e, "/layoutSemantics", "Does not match the embedded documents");
        }
        for (String f : requires) {
            if (!declared.requires().contains(f)) {
                d.error(Code.INCONSISTENT_MANIFEST, e, "/requires", "Missing required feature '" + f + "'");
            }
        }
        covered(hostApis, declared.hostApis(), "/hostApis", d);
        covered(providers, declared.providers(), "/providers", d);
    }

    private static void covered(List<HostRequirement> need, List<HostRequirement> have, String ptr, UiDiagnostics d) {
        for (HostRequirement n : need) {
            HostRequirement h = have.stream().filter(x -> x.id().equals(n.id())).findFirst().orElse(null);
            if (h == null || h.version() < n.version() || (h.optional() && !n.optional())) {
                d.error(Code.INCONSISTENT_MANIFEST, SbuiFormat.MANIFEST, ptr,
                        "Does not declare " + n.id() + " v" + n.version() + (n.optional() ? "" : " (required)"));
            }
        }
    }
}
