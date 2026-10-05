package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiDocument.ComponentDef;
import com.openmason.engine.format.omui.UiDocument.EventDef;
import com.openmason.engine.format.omui.UiDocument.Param;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiGraph.GraphVariable;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiNode.InstanceOverride;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiStyleSheet.StyleRule;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * Finds and rewrites dependency references in a document.
 *
 * <p>A reference is a structural one ({@code styleSheets}, {@code codeBehind},
 * {@code instance.component}, a row's {@code requires}/{@code fallback}) or a string value
 * exactly equal to a dependency id anywhere in authored values: widget {@code props}, inline and
 * sheet styles, sheet variables, instance params and overrides, parameter defaults, graph
 * literals and clip keys. Logical ids contain a namespace colon and a strict alphabet, so an
 * exact match is never ordinary text.
 *
 * <p>Not rewritten: Lua source (text is never parsed or edited — callers report it) and
 * preserved unknown fields, whose meaning this reader cannot know.
 */
public final class DependencyRefs {

    private DependencyRefs() {
    }

    /** Ids from the document's table that the document references, in code-point order. */
    public static Set<String> referenced(OmuiArchive doc) {
        Set<String> table = new TreeSet<>(UiValue.KEY_ORDER);
        doc.dependencies().entries().forEach(d -> table.add(d.id()));
        Set<String> found = new TreeSet<>(UiValue.KEY_ORDER);
        rewriteContent(doc, s -> {
            if (table.contains(s)) {
                found.add(s);
            }
            return s;
        });
        return found;
    }

    /** {@code id} and every row it transitively {@code requires}, in code-point order. */
    public static Set<String> closure(OmuiArchive doc, String id) {
        return walk(doc, List.of(id), false);
    }

    /**
     * Rows reachable from {@code roots} through {@code requires} and {@code fallback} edges:
     * everything a runtime may load for them.
     */
    public static Set<String> reachable(OmuiArchive doc, Collection<String> roots) {
        return walk(doc, roots, true);
    }

    private static Set<String> walk(OmuiArchive doc, Collection<String> roots, boolean fallbacks) {
        Set<String> seen = new TreeSet<>(UiValue.KEY_ORDER);
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String next = todo.poll();
            UiDependency row = doc.dependencies().find(next);
            if (row != null && seen.add(next)) {
                todo.addAll(row.requires());
                if (fallbacks && row.fallback() != null) {
                    todo.add(row.fallback());
                }
            }
        }
        return seen;
    }

    /** Script ids whose Lua source mentions any of {@code ids} textually (never rewritten). */
    public static Set<String> scriptsMentioning(OmuiArchive doc, Set<String> ids) {
        Set<String> out = new TreeSet<>(UiValue.KEY_ORDER);
        doc.scripts().forEach((id, src) -> {
            for (String dep : ids) {
                if (src.contains(dep)) {
                    out.add(id);
                }
            }
        });
        return out;
    }

    /**
     * Renames dependency ids everywhere: table rows (id, {@code requires}, {@code fallback})
     * and every reference. Ids not in {@code renames} are untouched.
     */
    public static OmuiArchive remap(OmuiArchive doc, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return doc;
        }
        UnaryOperator<String> f = s -> renames.getOrDefault(s, s);
        OmuiArchive out = rewriteContent(doc, f);
        List<UiDependency> rows = doc.dependencies().entries().stream().map(d -> new UiDependency(f.apply(d.id()),
                d.kind(), d.version(), d.sha256(), d.size(), d.mode(), d.entry(), d.sourceHint(),
                d.requires().stream().map(f).toList(), d.optional(), d.fallback() == null ? null : f.apply(d.fallback()),
                d.license(), d.unknown())).toList();
        return out.withDependencies(new UiDependencies(rows, doc.dependencies().unknown()));
    }

    private static OmuiArchive rewriteContent(OmuiArchive doc, UnaryOperator<String> f) {
        OmuiArchive out = doc.withDocument(document(doc.document(), f));
        for (UiStyleSheet s : doc.styles().values()) {
            out = out.withStyle(sheet(s, f));
        }
        for (UiGraph g : doc.graphs().values()) {
            out = out.withGraph(graph(g, f));
        }
        for (UiAnimationClip c : doc.animations().values()) {
            out = out.withAnimation(clip(c, f));
        }
        for (UiDependency d : doc.dependencies().entries()) {
            d.requires().forEach(f::apply);
            if (d.fallback() != null) {
                f.apply(d.fallback());
            }
        }
        return out;
    }

    private static UiDocument document(UiDocument d, UnaryOperator<String> f) {
        ComponentDef c = d.component();
        ComponentDef contract = c == null ? null : new ComponentDef(params(c.params(), f),
                c.events().stream().map(e -> new EventDef(e.name(), params(e.args(), f), e.unknown())).toList(),
                c.slots(), c.unknown());
        return new UiDocument(node(d.root(), f), d.styleSheets().stream().map(f).toList(),
                d.codeBehind() == null ? null : f.apply(d.codeBehind()), contract, d.unknown());
    }

    private static List<Param> params(List<Param> params, UnaryOperator<String> f) {
        return params.stream().map(p -> new Param(p.name(), p.type(), value(p.defaultValue(), f), p.unknown())).toList();
    }

    private static UiNode node(UiNode n, UnaryOperator<String> f) {
        ComponentInstance i = n.instance();
        ComponentInstance inst = null;
        if (i != null) {
            Map<String, List<UiNode>> slots = new LinkedHashMap<>();
            i.slots().forEach((k, v) -> slots.put(k, v.stream().map(c -> node(c, f)).toList()));
            inst = new ComponentInstance(f.apply(i.component()), values(i.params(), f),
                    i.overrides().stream().map(o -> new InstanceOverride(o.target(), values(o.props(), f),
                            values(o.style(), f), o.addClasses(), o.removeClasses(), o.unknown())).toList(),
                    slots, i.unknown());
        }
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), n.classes(), values(n.props(), f),
                values(n.style(), f), n.dataSource(), n.bindings(), inst,
                n.children().stream().map(c -> node(c, f)).toList(), n.unknown());
    }

    private static UiStyleSheet sheet(UiStyleSheet s, UnaryOperator<String> f) {
        return new UiStyleSheet(s.id(), values(s.variables(), f), s.customStates(), s.rules().stream()
                .map(r -> new StyleRule(r.selector(), values(r.style(), f), r.transitions(), r.unknown())).toList(),
                s.unknown());
    }

    private static UiGraph graph(UiGraph g, UnaryOperator<String> f) {
        return new UiGraph(g.id(), g.variables().stream().map(v -> new GraphVariable(v.name(), v.type(),
                value(v.defaultValue(), f), v.unknown())).toList(), graphNodes(g.nodes(), f), g.edges(),
                g.functions().stream().map(fn -> new GraphFunction(fn.id(), fn.inputs(), fn.outputs(),
                        graphNodes(fn.nodes(), f), fn.edges(), fn.unknown())).toList(), g.unknown());
    }

    private static List<GraphNode> graphNodes(List<GraphNode> nodes, UnaryOperator<String> f) {
        return nodes.stream().map(n -> new GraphNode(n.id(), n.kind(), n.kindVersion(), n.x(), n.y(),
                values(n.inputs(), f), values(n.props(), f), n.unknown())).toList();
    }

    private static UiAnimationClip clip(UiAnimationClip c, UnaryOperator<String> f) {
        return new UiAnimationClip(c.id(), c.duration(), c.loop(), c.tracks().stream().map(t -> new AnimTrack(t.target(),
                t.property(), t.keys().stream().map(k -> new AnimKey(k.time(), value(k.value(), f), k.easing(),
                        k.unknown())).toList(), t.unknown())).toList(), c.events(), c.unknown());
    }

    private static Map<String, UiValue> values(Map<String, UiValue> map, UnaryOperator<String> f) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, value(v, f)));
        return out;
    }

    private static UiValue value(UiValue v, UnaryOperator<String> f) {
        return switch (v) {
            case null -> null;
            case UiValue.Str s -> {
                String mapped = f.apply(s.value());
                yield mapped.equals(s.value()) ? s : UiValue.of(mapped);
            }
            case UiValue.Arr a -> new UiValue.Arr(a.items().stream().map(x -> value(x, f)).toList());
            case UiValue.Obj o -> new UiValue.Obj(values(o.fields(), f));
            default -> v;
        };
    }
}
