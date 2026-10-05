package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference rule of #287, and the checks that keep it honest.
 *
 * <p><b>Identity references use stable node ids</b> (element keys at runtime): clip tracks
 * ({@code target}), graph nodes ({@code props.target}), instance overrides, slot hosts, binding
 * owners, and script lookups by key ({@code ui.get("resume")}, #292). Renaming or reparenting a
 * node never breaks them.
 *
 * <p><b>{@code #name} is a style handle.</b> Selectors and {@code ui.q("#name")} match it, so
 * renaming a node changes what they select. {@link #renameImpact} lists every such use: the
 * sheet rules it can rewrite for the editor, and the script mentions it never rewrites (Lua is
 * never rewritten, #285) but reports so the author can.
 */
public final class UiReferences {

    /** Where an identity reference lives. */
    public enum Kind { CLIP_TRACK, GRAPH_TARGET, OVERRIDE }

    /**
     * @param where     {@code animations/open#tracks/0}, {@code graphs/behaviors#nodes/on_resume}
     * @param targetKey the element key it must resolve to in an instance of the document
     */
    public record Reference(Kind kind, String where, String targetKey) {
    }

    /**
     * Uses of a node's {@code #name} that a rename affects.
     *
     * @param rewrittenSheets in-archive sheets with {@code #old} replaced by {@code #new} in selectors
     * @param ruleChanges     {@code sheetId#rules/i} of every rewritten rule
     * @param scriptMentions  {@code scriptId:line} of every {@code "#old"} string in Lua (not rewritten)
     */
    public record RenameImpact(String oldName, String newName, Map<String, UiStyleSheet> rewrittenSheets,
                               List<String> ruleChanges, List<String> scriptMentions) {
    }

    private UiReferences() {
    }

    /** Every identity reference the document's own parts make (clips, graphs, overrides). */
    public static List<Reference> identityReferences(OmuiArchive doc) {
        List<Reference> out = new ArrayList<>();
        doc.animations().forEach((id, clip) -> {
            List<UiAnimationClip.AnimTrack> tracks = clip.tracks();
            for (int i = 0; i < tracks.size(); i++) {
                out.add(new Reference(Kind.CLIP_TRACK, "animations/" + id + "#tracks/" + i, tracks.get(i).target()));
            }
        });
        doc.graphs().forEach((id, graph) -> graphTargets(id, graph, out));
        overrides(doc.document().root(), "", out);
        return out;
    }

    private static void graphTargets(String graphId, UiGraph graph, List<Reference> out) {
        for (UiGraph.GraphNode n : graph.nodes()) {
            if (n.props().get("target") instanceof UiValue.Str s) {
                out.add(new Reference(Kind.GRAPH_TARGET, "graphs/" + graphId + "#nodes/" + n.id(), s.value()));
            }
        }
        for (UiGraph.GraphFunction f : graph.functions()) {
            for (UiGraph.GraphNode n : f.nodes()) {
                if (n.props().get("target") instanceof UiValue.Str s) {
                    out.add(new Reference(Kind.GRAPH_TARGET, "graphs/" + graphId + "#functions/" + f.id() + "/" + n.id(),
                        s.value()));
                }
            }
        }
    }

    private static void overrides(UiNode node, String prefix, List<Reference> out) {
        if (node.instance() != null) {
            for (UiNode.InstanceOverride o : node.instance().overrides()) {
                out.add(new Reference(Kind.OVERRIDE, "document#" + prefix + node.id(),
                    prefix + node.id() + "/" + o.target()));
            }
            node.instance().slots().values().forEach(list -> list.forEach(c -> overrides(c, prefix, out)));
        }
        node.children().forEach(c -> overrides(c, prefix, out));
    }

    /**
     * Identity references of {@code ui}'s document that do not resolve to an element of the
     * running instance, as diagnostics. Empty after any rename or reparent.
     */
    public static List<UiRuntimeDiagnostic> unresolved(UiDocumentInstance ui) {
        List<UiRuntimeDiagnostic> out = new ArrayList<>();
        for (Reference r : identityReferences(ui.document())) {
            if (ui.find(r.targetKey()) == null) {
                out.add(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.OVERRIDE_TARGET_MISSING, r.targetKey(),
                    r.kind() + " at " + r.where() + " names an element that does not exist"));
            }
        }
        return out;
    }

    /** What renaming the node {@code nodeId} to {@code newName} affects in {@code doc}. */
    public static RenameImpact renameImpact(OmuiArchive doc, String nodeId, String newName) {
        String oldName = doc.document().root().flatten().stream()
            .filter(n -> n.id().equals(nodeId)).map(UiNode::name).findFirst().orElse(null);
        Map<String, UiStyleSheet> rewritten = new LinkedHashMap<>();
        List<String> ruleChanges = new ArrayList<>();
        List<String> mentions = new ArrayList<>();
        if (oldName == null || oldName.equals(newName)) {
            return new RenameImpact(oldName, newName, rewritten, ruleChanges, mentions);
        }
        Pattern selector = Pattern.compile("#" + Pattern.quote(oldName) + "(?![A-Za-z0-9_-])");
        doc.styles().forEach((id, sheet) -> {
            List<UiStyleSheet.StyleRule> rules = new ArrayList<>();
            boolean changed = false;
            for (int i = 0; i < sheet.rules().size(); i++) {
                UiStyleSheet.StyleRule r = sheet.rules().get(i);
                Matcher m = selector.matcher(r.selector());
                if (m.find()) {
                    changed = true;
                    ruleChanges.add(id + "#rules/" + i);
                    rules.add(new UiStyleSheet.StyleRule(m.replaceAll(Matcher.quoteReplacement("#" + newName)), r.style(),
                        r.transitions(), r.unknown()));
                } else {
                    rules.add(r);
                }
            }
            if (changed) {
                rewritten.put(id, new UiStyleSheet(sheet.id(), sheet.variables(), sheet.customStates(), rules,
                    sheet.unknown()));
            }
        });
        Pattern literal = Pattern.compile("[\"'][^\"'\\n]*#" + Pattern.quote(oldName) + "(?![A-Za-z0-9_-])");
        doc.scripts().forEach((id, source) -> {
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (literal.matcher(lines[i]).find()) {
                    mentions.add(id + ":" + (i + 1));
                }
            }
        });
        return new RenameImpact(oldName, newName, rewritten, ruleChanges, mentions);
    }
}
