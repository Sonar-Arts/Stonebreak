package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiStyleSheet.StyleRule;
import com.openmason.engine.format.omui.UiStyleSheet.StyleTransition;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks style sheets, graphs, animation clips and embedded script ids. */
final class PartValidator {

    private static final Pattern NODE_KIND = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_.-]{1,128}");

    private PartValidator() {
    }

    static void validate(OmuiArchive a, Set<String> nodeIds, UiDiagnostics d) {
        a.styles().values().forEach(s -> style(s, a.dependencies(), d));
        a.graphs().values().forEach(g -> graph(g, d));
        a.animations().values().forEach(c -> clip(c, nodeIds, d));
        for (String id : a.scripts().keySet()) {
            partId(id, OmuiFormat.scriptEntry(id), d);
        }
    }

    private static boolean partId(String id, String entry, UiDiagnostics d) {
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            d.error(Code.INVALID_ID, entry, "", "Invalid part id '" + id + "'");
            return false;
        }
        return true;
    }

    /** NaN-safe: true only for finite seconds in [0, MAX_SECONDS]. */
    private static boolean seconds(double s) {
        return s >= 0 && s <= OmuiFormat.MAX_SECONDS;
    }

    private static void style(UiStyleSheet s, OmuiArchive.UiDependencies deps, UiDiagnostics d) {
        String e = OmuiFormat.styleEntry(s.id());
        partId(s.id(), e, d);
        for (String state : s.customStates()) {
            if (!UiSelectors.isIdent(state) || UiSelectors.BUILT_IN_STATES.contains(state)) {
                d.error(Code.INVALID_ID, e, "/customStates", "Invalid or built-in custom state '" + state + "'");
            }
        }
        for (String var : s.variables().keySet()) {
            if (!UiStyleProperties.isCustom(var)) {
                d.error(Code.INVALID_ID, e, "/variables", "Variable '" + var + "' must look like --name");
            }
        }
        Set<String> states = Set.copyOf(s.customStates());
        for (int i = 0; i < s.rules().size(); i++) {
            StyleRule rule = s.rules().get(i);
            String ptr = "/rules/" + i;
            String problem = UiSelectors.problem(rule.selector(), states);
            if (problem != null) {
                d.error(Code.INVALID_VALUE, e, ptr + "/selector", problem);
            }
            TreeValidator.style(rule.style(), e, ptr + "/style", deps, d);
            for (int t = 0; t < rule.transitions().size(); t++) {
                StyleTransition tr = rule.transitions().get(t);
                if (!UiStyleProperties.isKnown(tr.property()) && !tr.property().equals("all")) {
                    d.warning(Code.UNKNOWN_FIELD_PRESERVED, e, ptr + "/transitions/" + t + "/property",
                            "Unknown transition property '" + tr.property() + "'");
                }
                if (!seconds(tr.duration()) || !seconds(tr.delay())) {
                    d.error(Code.INVALID_VALUE, e, ptr + "/transitions/" + t, "Duration and delay must be in [0, "
                            + OmuiFormat.MAX_SECONDS + "] s");
                }
                if (t > 0 && rule.transitions().get(t - 1).property().equals(tr.property())) {
                    d.error(Code.DUPLICATE_ID, e, ptr + "/transitions/" + t, "Property transitions twice");
                }
            }
        }
    }

    private static void graph(UiGraph g, UiDiagnostics d) {
        String e = OmuiFormat.graphEntry(g.id());
        partId(g.id(), e, d);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < g.variables().size(); i++) {
            UiGraph.GraphVariable v = g.variables().get(i);
            if (!OmuiFormat.LOCAL_ID.matcher(v.name()).matches() || !names.add(v.name())) {
                d.error(Code.DUPLICATE_ID, e, "/variables/" + i, "Invalid or duplicate variable '" + v.name() + "'");
            }
            if (v.defaultValue() != null && !v.type().accepts(v.defaultValue())) {
                d.error(Code.WRONG_TYPE, e, "/variables/" + i + "/default", "Default does not fit " + v.type().wire());
            }
        }
        body(g.nodes(), g.edges(), e, "", d);
        names.clear();
        for (int i = 0; i < g.functions().size(); i++) {
            UiGraph.GraphFunction f = g.functions().get(i);
            String ptr = "/functions/" + i;
            if (!OmuiFormat.LOCAL_ID.matcher(f.id()).matches() || !names.add(f.id())) {
                d.error(Code.DUPLICATE_ID, e, ptr + "/id", "Invalid or duplicate function '" + f.id() + "'");
            }
            body(f.nodes(), f.edges(), e, ptr, d);
        }
    }

    private static void body(List<GraphNode> nodes, List<GraphEdge> edges, String e, String ptr, UiDiagnostics d) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            if (!OmuiFormat.LOCAL_ID.matcher(n.id()).matches() || !ids.add(n.id())) {
                d.error(Code.DUPLICATE_ID, e, ptr + "/nodes/" + i + "/id", "Invalid or duplicate node '" + n.id() + "'");
            }
            if (n.kindVersion() < 1 || n.kindVersion() > OmuiFormat.MAX_VERSION
                    || !(Math.abs(n.x()) <= OmuiFormat.MAX_COORD) || !(Math.abs(n.y()) <= OmuiFormat.MAX_COORD)) {
                d.error(Code.INVALID_VALUE, e, ptr + "/nodes/" + i, "kindVersion >= 1 and |x|, |y| <= "
                        + OmuiFormat.MAX_COORD + " required");
            }
            if (!NODE_KIND.matcher(n.kind()).matches()) {
                d.error(Code.INVALID_VALUE, e, ptr + "/nodes/" + i + "/kind", "Invalid node kind '" + n.kind() + "'");
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge edge = edges.get(i);
            String at = ptr + "/edges/" + i;
            if (!ids.contains(edge.fromNode()) || !ids.contains(edge.toNode())) {
                d.error(Code.UNRESOLVED_REFERENCE, e, at, "Edge connects a missing node");
            }
            if (!UiSelectors.isIdent(edge.fromPort()) || !UiSelectors.isIdent(edge.toPort())) {
                d.error(Code.INVALID_ID, e, at, "Invalid port name");
            }
            if (i > 0 && edges.get(i - 1).equals(edge)) {
                d.error(Code.DUPLICATE_ID, e, at, "Duplicate edge");
            }
        }
    }

    private static void clip(UiAnimationClip c, Set<String> nodeIds, UiDiagnostics d) {
        String e = OmuiFormat.animationEntry(c.id());
        partId(c.id(), e, d);
        if (!seconds(c.duration())) {
            d.error(Code.INVALID_VALUE, e, "/duration", "Duration must be in [0, " + OmuiFormat.MAX_SECONDS + "] s");
        }
        for (int i = 0; i < c.tracks().size(); i++) {
            AnimTrack t = c.tracks().get(i);
            String ptr = "/tracks/" + i;
            if (!nodeIds.contains(t.target())) {
                d.error(Code.UNRESOLVED_REFERENCE, e, ptr + "/target", "No node with id '" + t.target() + "'");
            }
            String problem = UiPaths.targetProblem(t.property());
            if (problem != null) {
                d.error(Code.INVALID_VALUE, e, ptr + "/property", problem);
            }
            if (i > 0 && AnimTrack.ORDER.compare(c.tracks().get(i - 1), t) == 0) {
                d.error(Code.DUPLICATE_ID, e, ptr, "Two tracks animate " + t.target() + " " + t.property());
            }
            double previous = -1;
            for (int k = 0; k < t.keys().size(); k++) {
                AnimKey key = t.keys().get(k);
                if (!(key.time() >= 0) || !(key.time() > previous) || !(key.time() <= c.duration())) {
                    d.error(Code.INVALID_VALUE, e, ptr + "/keys/" + k + "/time",
                            "Key times must increase strictly and stay within the duration");
                }
                previous = key.time();
            }
        }
        for (int i = 0; i < c.events().size(); i++) {
            double time = c.events().get(i).time();
            if (!(time >= 0) || !(time <= c.duration())) {
                d.error(Code.INVALID_VALUE, e, "/events/" + i + "/time", "Event after the end of the clip");
            }
        }
    }
}
