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
        if (!v.declared.contains(UiFeatures.SPRITES)) {
            String ref = UiFeatures.firstSpriteRef(archive);
            if (ref != null) {
                d.error(Code.UNDECLARED_FEATURE, OmuiFormat.MANIFEST, "/requires", "sprite reference '" + ref
                        + "' needs \"" + UiFeatures.SPRITES + "\" in the manifest's requires");
            }
        }
        if (!archive.stateMachines().isEmpty() && !v.declared.contains(UiFeatures.STATES)) {
            d.error(Code.UNDECLARED_FEATURE, OmuiFormat.MANIFEST, "/requires", "state machines need \""
                    + UiFeatures.STATES + "\" in the manifest's requires");
        }
        archive.styles().forEach((id, sheet) -> {
            List<UiStyleSheet.StyleRule> rules = sheet.rules();
            for (int i = 0; i < rules.size(); i++) {
                String entry = OmuiFormat.styleEntry(id);
                v.selector(rules.get(i).selector(), entry, "/rules/" + i + "/selector");
                v.style(rules.get(i).style(), entry, "/rules/" + i + "/style");
                List<UiStyleSheet.StyleTransition> transitions = rules.get(i).transitions();
                for (int t = 0; t < transitions.size(); t++) {
                    if (transitions.get(t).bezier() != null) {
                        v.need(UiFeatures.MOTION, entry, "/rules/" + i + "/transitions/" + t + "/bezier",
                                "a bezier timing curve");
                    }
                }
            }
        });
        archive.animations().forEach((id, clip) -> {
            String entry = OmuiFormat.animationEntry(id);
            List<UiAnimationClip.AnimTrack> tracks = clip.tracks();
            for (int t = 0; t < tracks.size(); t++) {
                UiAnimationClip.AnimTrack track = tracks.get(t);
                String f = UiFeatures.forTrack(track.property());
                if (f != null) {
                    v.need(f, entry, "/tracks/" + t + "/property", "track " + track.property());
                }
                for (int k = 0; k < track.keys().size(); k++) {
                    if (track.keys().get(k).bezier() != null) {
                        v.need(UiFeatures.MOTION, entry, "/tracks/" + t + "/keys/" + k + "/bezier",
                                "a bezier timing curve");
                    }
                }
            }
        });
    }

    private void need(String feature, String entry, String ptr, String what) {
        if (!declared.contains(feature)) {
            d.error(Code.UNDECLARED_FEATURE, entry, ptr, what + " needs \"" + feature + "\" in the manifest's requires");
        }
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
        for (Map.Entry<String, UiValue> e : style.entrySet()) {
            String f = UiFeatures.forStyle(e.getKey(), e.getValue());
            if (f != null) {
                String what = "overflow".equals(e.getKey()) ? "overflow: scroll" : "property " + e.getKey();
                need(f, entry, ptr + "/" + e.getKey(), what);
            }
        }
    }
}
