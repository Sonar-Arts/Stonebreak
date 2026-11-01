package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a host (Stonebreak, or the editor preview with fixtures) provides. Integer versions
 * are cumulative: a host at version N of a contract serves documents targeting 1..N.
 *
 * @param uiApi           newest Lua {@code ui} API the host implements
 * @param layoutSemantics layout semantics ids the host can lay out
 * @param hostApis        host contract id → newest implemented version
 * @param providers       provider id → newest implemented version
 */
public record UiHostProfile(int uiApi, Set<String> layoutSemantics, Map<String, Integer> hostApis,
                            Map<String, Integer> providers) {

    public UiHostProfile {
        layoutSemantics = Set.copyOf(layoutSemantics);
        hostApis = Map.copyOf(hostApis);
        providers = Map.copyOf(providers);
    }

    /**
     * @return diagnostics; any error means the host must refuse the document (the game shows
     * them instead of claiming full portability)
     */
    public List<UiDiagnostic> check(UiRequirements r) {
        UiDiagnostics d = new UiDiagnostics();
        OmuiValidator.requiredFeatures(r.requires(), d);
        if (r.uiApi() > uiApi) {
            d.error(Code.UNSUPPORTED_UI_API, OmuiFormat.MANIFEST, "/uiApi",
                    "Document targets ui API " + r.uiApi() + "; host implements " + uiApi);
        }
        if (!layoutSemantics.contains(r.layoutSemantics())) {
            d.error(Code.UNSUPPORTED_LAYOUT_SEMANTICS, OmuiFormat.MANIFEST, "/layoutSemantics",
                    "Host cannot lay out '" + r.layoutSemantics() + "'");
        }
        contracts(r.hostApis(), hostApis, "/hostApis", "host API", d);
        contracts(r.providers(), providers, "/providers", "provider", d);
        return d.list();
    }

    private static void contracts(List<HostRequirement> needed, Map<String, Integer> offered, String ptr,
                                  String what, UiDiagnostics d) {
        for (HostRequirement h : needed) {
            Integer have = offered.get(h.id());
            if (have != null && have >= h.version()) {
                continue;
            }
            String msg = "Requires " + what + " " + h.id() + " v" + h.version()
                    + (have == null ? "; host lacks it" : "; host has v" + have);
            if (h.optional()) {
                d.warning(Code.UNSUPPORTED_HOST_API, OmuiFormat.MANIFEST, ptr, msg + " (optional, degraded)");
            } else {
                d.error(Code.UNSUPPORTED_HOST_API, OmuiFormat.MANIFEST, ptr, msg);
            }
        }
    }
}
