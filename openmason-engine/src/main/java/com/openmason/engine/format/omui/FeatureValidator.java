package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that every optional feature a document uses is declared in its manifest's
 * {@code requires} (see {@link UiFeatures}). Without that, an older reader would meet an
 * unknown widget, keyword, property or pseudo-state with no way to say "upgrade" instead of
 * "corrupt", or would silently drop interaction metadata (#288).
 */
final class FeatureValidator {

    private final Set<String> declared;
    private final UiDiagnostics d;

    private FeatureValidator(Set<String> declared, UiDiagnostics d) {
        this.declared = declared;
        this.d = d;
    }

    static void validate(OmuiArchive archive, UiDiagnostics d) {
        FeatureValidator v = new FeatureValidator(Set.copyOf(archive.manifest().requires()), d);
        v.node(archive.document().root(), "/root");
        archive.styles().forEach((id, sheet) -> {
            List<UiStyleSheet.StyleRule> rules = sheet.rules();
            for (int i = 0; i < rules.size(); i++) {
                String entry = OmuiFormat.styleEntry(id);
                v.selector(rules.get(i).selector(), entry, "/rules/" + i + "/selector");
                v.style(rules.get(i).style(), entry, "/rules/" + i + "/style");
            }
        });
    }

    private void node(UiNode n, String ptr) {
        String widget = UiWidgets.requiredFeature(n.type());
        if (widget != null && !declared.contains(widget)) {
            d.error(Code.UNDECLARED_FEATURE, OmuiFormat.DOCUMENT, ptr + "/type",
                    n.type() + " needs \"" + widget + "\" in the manifest's requires");
        }
        props(n.props(), ptr + "/props");
        style(n.style(), OmuiFormat.DOCUMENT, ptr + "/style");
        if (n.instance() != null) {
            List<UiNode.InstanceOverride> overrides = n.instance().overrides();
            for (int i = 0; i < overrides.size(); i++) {
                props(overrides.get(i).props(), ptr + "/instance/overrides/" + i + "/props");
                style(overrides.get(i).style(), OmuiFormat.DOCUMENT, ptr + "/instance/overrides/" + i + "/style");
            }
            for (Map.Entry<String, List<UiNode>> slot : n.instance().slots().entrySet()) {
                for (int i = 0; i < slot.getValue().size(); i++) {
                    node(slot.getValue().get(i), ptr + "/instance/slots/" + slot.getKey() + "/" + i);
                }
            }
        }
        for (int i = 0; i < n.children().size(); i++) {
            node(n.children().get(i), ptr + "/children/" + i);
        }
    }

    private void props(Map<String, UiValue> props, String ptr) {
        for (String name : props.keySet()) {
            String f = UiFeatures.forProp(name);
            if (f != null && !declared.contains(f)) {
                d.error(Code.UNDECLARED_FEATURE, OmuiFormat.DOCUMENT, ptr + "/" + name,
                        "property " + name + " needs \"" + f + "\" in the manifest's requires");
            }
        }
    }

    private void selector(String selector, String entry, String ptr) {
        String f = UiFeatures.forSelector(selector);
        if (f != null && !declared.contains(f)) {
            d.error(Code.UNDECLARED_FEATURE, entry, ptr,
                    "selector " + selector + " uses a pseudo-state that needs \"" + f + "\" in the manifest's requires");
        }
    }

    private void style(Map<String, UiValue> style, String entry, String ptr) {
        String f = UiFeatures.forStyle(style);
        if (f != null && !declared.contains(f)) {
            d.error(Code.UNDECLARED_FEATURE, entry, ptr + "/overflow",
                    "overflow: scroll needs \"" + f + "\" in the manifest's requires");
        }
    }
}
