package com.openmason.engine.ui.graph.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiGraph.GraphPort;
import com.openmason.engine.format.omui.UiGraph.GraphVariable;
import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.GraphCodec;
import com.openmason.engine.ui.graph.CompiledGraph;
import com.openmason.engine.ui.graph.DocumentEnvironment;
import com.openmason.engine.ui.graph.GraphCompiler;
import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.engine.ui.graph.GraphEnvironment;
import com.openmason.engine.ui.graph.GraphScripts;
import com.openmason.engine.ui.graph.KindContext;
import com.openmason.engine.ui.graph.LuaFunction;
import com.openmason.engine.ui.graph.NodeKind;
import com.openmason.engine.ui.graph.NodeKinds;
import com.openmason.engine.ui.graph.NodePorts;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.PortType;
import com.openmason.engine.ui.graph.PropSpec;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.input.UiEventType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Headless editing of one behavior graph inside its document (#291): the model behind the Open
 * Mason graph editor, usable from tests and automation without a UI.
 *
 * <p>Every edit is one undoable step on the whole archive (graph, its layout entry and, for
 * "Convert to script", the new module); consecutive moves with the same drag token merge into
 * one step. The graph is always the canonical source: the editor never touches generated code.
 * {@link #diagnostics()} re-validates against the document after each change, so broken links,
 * missing elements and cycles show while editing, located at their nodes.
 *
 * <p>Bodies are addressed by function id: {@code ""} is the event graph.
 */
public final class GraphEditor {

    /** Clipboard payload format (canonical JSON of a mini graph). */
    public static final String CLIP_FORMAT = "omui-graph-clip";
    private static final int HISTORY = 200;

    /** One undoable change; it remembers which graph was open before and after it. */
    private record Step(String label, OmuiArchive before, OmuiArchive after, String merge, String graphBefore,
                        String graphAfter) {
    }

    private String graphId;
    private final UiDocumentSource source;
    private OmuiArchive doc;
    private OmuiArchive saved;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private GraphEnvironment env;
    private Object envKey;
    private List<GraphDiagnostic> diagnostics;
    private UiGraph diagnosed;

    private GraphEditor(OmuiArchive doc, String graphId, UiDocumentSource source) {
        this.doc = doc;
        this.saved = doc;
        this.graphId = graphId;
        this.source = source == null ? UiDocumentSource.EMPTY : source;
    }

    /**
     * Edits {@code graphId} of {@code doc}, creating an empty graph when there is none (the
     * creation is not an undo step: it is the editor's starting point).
     *
     * @param source components and shared scripts, for signals, element paths and Lua signatures
     */
    public static GraphEditor open(OmuiArchive doc, String graphId, UiDocumentSource source) {
        Objects.requireNonNull(doc, "doc");
        if (!OmuiFormat.PART_ID.matcher(graphId).matches()) {
            throw new IllegalArgumentException("'" + graphId + "' is not a part id (lowercase, digits, _ - /)");
        }
        OmuiArchive start = doc.graphs().containsKey(graphId) ? doc
            : doc.withGraph(new UiGraph(graphId, List.of(), List.of(), List.of(), List.of(), Map.of()));
        GraphEditor e = new GraphEditor(start, graphId, source);
        e.saved = doc;
        return e;
    }

    // ── state ───────────────────────────────────────────────────────────────

    public String graphId() {
        return graphId;
    }

    /** The document with every edit applied: save this. */
    public OmuiArchive document() {
        return doc;
    }

    public UiGraph graph() {
        return doc.graphs().get(graphId);
    }

    public GraphLayout layout() {
        return GraphLayout.read(doc.editor().get(GraphLayout.entry(graphId)));
    }

    public boolean dirty() {
        return doc != saved;
    }

    /** The current document was written; {@link #dirty()} is false until the next edit. */
    public void markSaved() {
        saved = doc;
    }

    /** Replaces the document from outside (a reload) and clears the history. */
    public void reset(OmuiArchive newDoc) {
        doc = newDoc.graphs().containsKey(graphId) ? newDoc
            : newDoc.withGraph(new UiGraph(graphId, List.of(), List.of(), List.of(), List.of(), Map.of()));
        saved = newDoc;
        undo.clear();
        redo.clear();
        diagnostics = null;
        env = null;
    }

    public GraphEnvironment environment() {
        Object key = List.of(doc.document(), doc.scripts(), doc.dependencies(), doc.animations().keySet());
        if (env == null || !key.equals(envKey)) {
            env = DocumentEnvironment.of(doc, source);
            envKey = key;
        }
        return env;
    }

    /** Problems of the current graph, errors first (re-validated after each edit). */
    public List<GraphDiagnostic> diagnostics() {
        if (diagnostics == null || diagnosed != graph()) {
            diagnosed = graph();
            diagnostics = GraphCompiler.validate(diagnosed, environment()).stream()
                .sorted(Comparator.comparing(GraphDiagnostic::severity).reversed()).toList();
        }
        return diagnostics;
    }

    /** Diagnostics of one node (for badges on the canvas). */
    public List<GraphDiagnostic> diagnostics(String function, String nodeId) {
        return diagnostics().stream().filter(d -> d.function().equals(function) && d.node().equals(nodeId)).toList();
    }

    // ── undo ────────────────────────────────────────────────────────────────

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Label of the step {@link #undo()} would revert, or null. */
    public String undoLabel() {
        return undo.isEmpty() ? null : undo.peek().label();
    }

    public String redoLabel() {
        return redo.isEmpty() ? null : redo.peek().label();
    }

    /** Reverts the last step and returns to the graph it was made in. */
    public boolean undo() {
        Step s = undo.poll();
        if (s == null) {
            return false;
        }
        doc = s.before();
        graphId = doc.graphs().containsKey(s.graphBefore()) ? s.graphBefore() : graphId;
        redo.push(s);
        return true;
    }

    public boolean redo() {
        Step s = redo.poll();
        if (s == null) {
            return false;
        }
        doc = s.after();
        graphId = doc.graphs().containsKey(s.graphAfter()) ? s.graphAfter() : graphId;
        undo.push(s);
        return true;
    }

    /** The document's graphs, by id. */
    public List<String> graphIds() {
        return List.copyOf(doc.graphs().keySet());
    }

    /**
     * Edits another graph of the same document, keeping one history for all of them (undo
     * returns to the graph a step was made in) and the dirty state against the last save. A
     * graph that does not exist yet is created as an undoable step.
     */
    public void switchGraph(String id) {
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("'" + id + "' is not a part id (lowercase, digits, _ - /)");
        }
        endDrag();
        if (!doc.graphs().containsKey(id)) {
            apply("New graph " + id, doc.withGraph(new UiGraph(id, List.of(), List.of(), List.of(), List.of(), Map.of())),
                null, id);
        }
        graphId = id;
    }

    /** Ends move merging: the next move starts a new undo step. */
    public void endDrag() {
        if (!undo.isEmpty() && undo.peek().merge() != null) {
            Step top = undo.pop();
            undo.push(new Step(top.label(), top.before(), top.after(), null, top.graphBefore(), top.graphAfter()));
        }
    }

    private void apply(String label, OmuiArchive next, String merge) {
        apply(label, next, merge, graphId);
    }

    private void apply(String label, OmuiArchive next, String merge, String graphAfter) {
        if (next.equals(doc)) {
            return;
        }
        Step top = undo.peek();
        if (merge != null && top != null && merge.equals(top.merge()) && graphId.equals(top.graphAfter())) {
            undo.pop();
            undo.push(new Step(label, top.before(), next, merge, top.graphBefore(), graphAfter));
        } else {
            undo.push(new Step(label, doc, next, merge, graphId, graphAfter));
            while (undo.size() > HISTORY) {
                undo.removeLast();
            }
        }
        redo.clear();
        doc = next;
    }

    private void edit(String label, UnaryOperator<UiGraph> g) {
        apply(label, doc.withGraph(g.apply(graph())), null);
    }

    private void editLayout(String label, UnaryOperator<GraphLayout> l) {
        GraphLayout next = l.apply(layout());
        apply(label, withLayout(doc, next), null);
    }

    private OmuiArchive withLayout(OmuiArchive d, GraphLayout layout) {
        String entry = GraphLayout.entry(graphId);
        if (layout.isEmpty()) {
            if (!d.editor().containsKey(entry)) {
                return d;
            }
            Map<String, UiBytes> editor = new LinkedHashMap<>(d.editor());
            editor.remove(entry);
            return new OmuiArchive(d.manifest(), d.document(), d.styles(), d.graphs(), d.animations(), d.scripts(),
                d.dependencies(), d.assets(), editor, d.extraEntries());
        }
        return d.withEditorEntry(entry, layout.toBytes());
    }

    // ── bodies ──────────────────────────────────────────────────────────────

    public List<GraphNode> nodes(String function) {
        GraphFunction f = fn(function);
        return f == null ? graph().nodes() : f.nodes();
    }

    public List<GraphEdge> edges(String function) {
        GraphFunction f = fn(function);
        return f == null ? graph().edges() : f.edges();
    }

    public GraphNode node(String function, String id) {
        for (GraphNode n : nodes(function)) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }

    public KindContext context(String function) {
        return new KindContext(environment(), graph(), fn(function));
    }

    /** The node's kind, or null for an unknown kind. */
    public NodeKind kind(String function, String id) {
        GraphNode n = node(function, id);
        return n == null ? null : NodeKinds.get(n.kind());
    }

    /** Resolved ports (none for an unknown kind). */
    public NodePorts ports(String function, String id) {
        GraphNode n = node(function, id);
        NodeKind k = n == null ? null : NodeKinds.get(n.kind());
        if (k == null) {
            return NodePorts.NONE;
        }
        try {
            return k.ports(context(function), n);
        } catch (RuntimeException e) {
            return NodePorts.NONE;
        }
    }

    private GraphFunction fn(String function) {
        if (function == null || function.isEmpty()) {
            return null;
        }
        for (GraphFunction f : graph().functions()) {
            if (f.id().equals(function)) {
                return f;
            }
        }
        throw new IllegalArgumentException("graph " + graphId + " has no function " + function);
    }

    private static UiGraph withBody(UiGraph g, String function, List<GraphNode> nodes, List<GraphEdge> edges) {
        if (function == null || function.isEmpty()) {
            return new UiGraph(g.id(), g.variables(), nodes, edges, g.functions(), g.unknown());
        }
        List<GraphFunction> fns = new ArrayList<>();
        for (GraphFunction f : g.functions()) {
            fns.add(f.id().equals(function) ? new GraphFunction(f.id(), f.inputs(), f.outputs(), nodes, edges,
                f.unknown()) : f);
        }
        return new UiGraph(g.id(), g.variables(), g.nodes(), g.edges(), fns, g.unknown());
    }

    private static GraphNode moved(GraphNode n, double x, double y) {
        return new GraphNode(n.id(), n.kind(), n.kindVersion(), clamp(x), clamp(y), n.inputs(), n.props(), n.unknown());
    }

    private static double clamp(double v) {
        return Math.clamp(Math.rint(v), -OmuiFormat.MAX_COORD, OmuiFormat.MAX_COORD);
    }

    // ── nodes ───────────────────────────────────────────────────────────────

    /** A unique node id from a hint ({@code branch}, {@code branch_2}, ...). */
    public String freshId(String function, String hint) {
        String base = hint.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0)) && base.charAt(0) != '_') {
            base = "n_" + base;
        }
        base = base.length() > 56 ? base.substring(0, 56) : base;
        Set<String> used = new HashSet<>();
        nodes(function).forEach(n -> used.add(n.id()));
        String id = base;
        for (int i = 2; used.contains(id); i++) {
            id = base + "_" + i;
        }
        return id;
    }

    private static String hint(String kind, Map<String, UiValue> props) {
        if (props.get("function") instanceof UiValue.Str s && !s.value().isEmpty()) {
            return s.value();
        }
        String tail = kind.substring(kind.indexOf(':') + 1);
        int dot = tail.lastIndexOf('.');
        return (dot >= 0 ? tail.substring(dot + 1) : tail).replace('-', '_');
    }

    /** Adds a node of {@code kind} at (x, y) with initial properties; returns its id. */
    public String addNode(String function, String kind, Map<String, UiValue> props, double x, double y) {
        NodeKind k = NodeKinds.get(kind);
        Map<String, UiValue> p = new LinkedHashMap<>();
        if (k != null) {
            for (PropSpec spec : k.props()) {
                if (spec.defaultValue() != null && !(spec.defaultValue() instanceof UiValue.Null)) {
                    p.put(spec.name(), spec.defaultValue());
                }
            }
        }
        if (props != null) {
            p.putAll(props);
        }
        String id = freshId(function, hint(kind, p));
        GraphNode n = new GraphNode(id, kind, k == null ? 1 : k.version(), clamp(x), clamp(y), Map.of(), p, Map.of());
        List<GraphNode> nodes = new ArrayList<>(nodes(function));
        nodes.add(n);
        edit("Add " + (k == null ? kind : k.title()), g -> withBody(g, function, nodes, edges(function)));
        return id;
    }

    /** Adds the node a palette entry describes. */
    public String addNode(String function, PaletteEntry entry, double x, double y) {
        return addNode(function, entry.kind(), entry.props(), x, y);
    }

    /** Removes nodes and every link touching them (and their group memberships). */
    public void removeNodes(String function, Collection<String> ids) {
        Set<String> gone = new HashSet<>(ids);
        if (gone.isEmpty()) {
            return;
        }
        List<GraphNode> nodes = nodes(function).stream().filter(n -> !gone.contains(n.id())).toList();
        List<GraphEdge> edges = edges(function).stream()
            .filter(e -> !gone.contains(e.fromNode()) && !gone.contains(e.toNode())).toList();
        UiGraph g = withBody(graph(), function, nodes, edges);
        GraphLayout l = layout();
        List<GraphLayout.Group> groups = new ArrayList<>();
        for (GraphLayout.Group gr : l.groups()) {
            if (!gr.function().equals(function == null ? "" : function)) {
                groups.add(gr);
                continue;
            }
            List<String> kept = gr.nodes().stream().filter(id -> !gone.contains(id)).toList();
            if (!kept.isEmpty()) {
                groups.add(new GraphLayout.Group(gr.id(), gr.function(), gr.title(), kept, gr.color()));
            }
        }
        apply("Delete " + gone.size() + (gone.size() == 1 ? " node" : " nodes"),
            withLayout(doc.withGraph(g), l.withGroups(groups)), null);
    }

    /**
     * Moves nodes by (dx, dy). Calls sharing {@code dragToken} merge into one undo step (one
     * drag); null never merges.
     */
    public void moveNodes(String function, Collection<String> ids, double dx, double dy, String dragToken) {
        Set<String> set = new HashSet<>(ids);
        List<GraphNode> nodes = nodes(function).stream()
            .map(n -> set.contains(n.id()) ? moved(n, n.x() + dx, n.y() + dy) : n).toList();
        apply("Move", doc.withGraph(withBody(graph(), function, nodes, edges(function))),
            dragToken == null ? null : "move:" + dragToken);
    }

    /** Places one node exactly (inspector edits, auto-layout). */
    public void placeNode(String function, String id, double x, double y) {
        List<GraphNode> nodes = nodes(function).stream().map(n -> n.id().equals(id) ? moved(n, x, y) : n).toList();
        edit("Move", g -> withBody(g, function, nodes, edges(function)));
    }

    /** Sets ({@code value != null}) or removes a property. */
    public void setProp(String function, String id, String name, UiValue value) {
        updateNode(function, id, "Set " + name, n -> {
            Map<String, UiValue> p = new LinkedHashMap<>(n.props());
            if (value == null) {
                p.remove(name);
            } else {
                p.put(name, value);
            }
            return new GraphNode(n.id(), n.kind(), n.kindVersion(), n.x(), n.y(), n.inputs(), p, n.unknown());
        });
    }

    /** Sets ({@code value != null}) or removes the literal of an unconnected input. */
    public void setInput(String function, String id, String port, UiValue value) {
        updateNode(function, id, "Set " + port, n -> {
            Map<String, UiValue> in = new LinkedHashMap<>(n.inputs());
            if (value == null) {
                in.remove(port);
            } else {
                in.put(port, value);
            }
            return new GraphNode(n.id(), n.kind(), n.kindVersion(), n.x(), n.y(), in, n.props(), n.unknown());
        });
    }

    private void updateNode(String function, String id, String label, UnaryOperator<GraphNode> f) {
        if (node(function, id) == null) {
            throw new IllegalArgumentException("no node " + id);
        }
        List<GraphNode> nodes = nodes(function).stream().map(n -> n.id().equals(id) ? f.apply(n) : n).toList();
        edit(label, g -> withBody(g, function, nodes, edges(function)));
    }

    // ── layout ──────────────────────────────────────────────────────────────

    /** Column and row pitch of {@link #arrange}, in canvas units. */
    public static final double ARRANGE_COLUMN = 280;
    public static final double ARRANGE_ROW = 150;

    /**
     * Lays a body out (one undo step): each event (or the function entry) gets a band, its
     * statements in columns by exec depth with branch arms stacked; the pure nodes a column
     * reads go below it; nodes no entry reaches go last. Annotations are left alone.
     */
    public void arrange(String function) {
        List<GraphNode> all = nodes(function);
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        all.forEach(n -> byId.put(n.id(), n));
        Map<String, List<String>> execNext = new HashMap<>();
        Map<String, List<String>> readers = new HashMap<>();
        for (GraphEdge e : edges(function)) {
            PortSpec out = ports(function, e.fromNode()).output(e.fromPort());
            if (out != null && out.isExec()) {
                execNext.computeIfAbsent(e.fromNode(), k -> new ArrayList<>()).add(e.toNode());
            } else {
                readers.computeIfAbsent(e.fromNode(), k -> new ArrayList<>()).add(e.toNode());
            }
        }
        List<String> entries = new ArrayList<>();
        for (GraphNode n : all) {
            NodeKind k = NodeKinds.get(n.kind());
            NodePorts p = ports(function, n.id());
            if (k != null && (k.isEvent() || NodeKinds.isEntry(n.kind())) || !p.hasExecInput() && !p.execOutputs().isEmpty()) {
                entries.add(n.id());
            }
        }
        Map<String, double[]> pos = new LinkedHashMap<>();
        double bandY = 0;
        for (String entry : entries) {
            if (pos.containsKey(entry)) {
                continue;
            }
            // Breadth-first: column = exec depth at first visit; rows fill per column.
            Map<String, Integer> col = new LinkedHashMap<>();
            ArrayDeque<String> queue = new ArrayDeque<>(List.of(entry));
            col.put(entry, 0);
            while (!queue.isEmpty()) {
                String id = queue.poll();
                for (String next : execNext.getOrDefault(id, List.of())) {
                    if (!col.containsKey(next) && !pos.containsKey(next)) {
                        col.put(next, col.get(id) + 1);
                        queue.add(next);
                    }
                }
            }
            Map<Integer, Integer> rows = new HashMap<>();
            for (Map.Entry<String, Integer> c : col.entrySet()) {
                int r = rows.merge(c.getValue(), 1, Integer::sum) - 1;
                pos.put(c.getKey(), new double[]{c.getValue() * ARRANGE_COLUMN, bandY + r * ARRANGE_ROW});
            }
            // Pure nodes below the first column that reads them (then their own inputs below that).
            int deepest = rows.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            boolean placed = true;
            while (placed) {
                placed = false;
                for (GraphNode n : all) {
                    if (pos.containsKey(n.id()) || ports(function, n.id()).hasExecInput()
                        || !ports(function, n.id()).execOutputs().isEmpty()) {
                        continue;
                    }
                    for (String reader : readers.getOrDefault(n.id(), List.of())) {
                        double[] at = pos.get(reader);
                        if (at != null && (col.containsKey(reader) || at[1] >= bandY)) {
                            int c = (int) Math.round(at[0] / ARRANGE_COLUMN);
                            int r = Math.max(rows.getOrDefault(c, 0), deepest);
                            rows.put(c, r + 1);
                            pos.put(n.id(), new double[]{c * ARRANGE_COLUMN - ARRANGE_COLUMN / 2,
                                bandY + r * ARRANGE_ROW + ARRANGE_ROW / 2});
                            placed = true;
                            break;
                        }
                    }
                }
            }
            int height = rows.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            bandY += (height + 1) * ARRANGE_ROW;
        }
        int x = 0;
        for (GraphNode n : all) {
            if (!pos.containsKey(n.id())) {
                pos.put(n.id(), new double[]{x * ARRANGE_COLUMN, bandY});
                x++;
            }
        }
        List<GraphNode> moved = all.stream().map(n -> moved(n, pos.get(n.id())[0], pos.get(n.id())[1])).toList();
        edit("Arrange", g -> withBody(g, function, moved, edges(function)));
    }

    // ── links ───────────────────────────────────────────────────────────────

    /** An endpoint of a would-be link. */
    public record End(String node, String port, boolean output) {
    }

    /**
     * Why a link between two ports cannot exist, or null when it can. Either end may be the
     * output; the editor drags from whichever port the user grabbed.
     */
    public String canConnect(String function, String nodeA, String portA, String nodeB, String portB) {
        End[] ends = orient(function, nodeA, portA, nodeB, portB);
        if (ends == null) {
            return "connect an output to an input";
        }
        if (ends[0].node().equals(ends[1].node())) {
            return "a node cannot link to itself";
        }
        PortSpec out = ports(function, ends[0].node()).output(ends[0].port());
        PortSpec in = ports(function, ends[1].node()).input(ends[1].port());
        if (in.type().accepts(out.type()) == PortType.Assign.NONE) {
            return out.type().isExec() || in.type().isExec() ? "exec pins only link to exec pins"
                : out.type().wire() + " does not convert to " + in.type().wire();
        }
        return null;
    }

    /** {output end, input end}, or null when the two ports are not one output and one input. */
    private End[] orient(String function, String nodeA, String portA, String nodeB, String portB) {
        NodePorts a = ports(function, nodeA);
        NodePorts b = ports(function, nodeB);
        if (a.output(portA) != null && b.input(portB) != null) {
            return new End[]{new End(nodeA, portA, true), new End(nodeB, portB, false)};
        }
        if (a.input(portA) != null && b.output(portB) != null) {
            return new End[]{new End(nodeB, portB, true), new End(nodeA, portA, false)};
        }
        return null;
    }

    /**
     * Links two ports (in either order). A data input keeps one link and an exec output one
     * link, so an existing one there is replaced.
     *
     * @return null when linked, else why not (nothing changes)
     */
    public String connect(String function, String nodeA, String portA, String nodeB, String portB) {
        String why = canConnect(function, nodeA, portA, nodeB, portB);
        if (why != null) {
            return why;
        }
        End[] ends = orient(function, nodeA, portA, nodeB, portB);
        boolean exec = ports(function, ends[0].node()).output(ends[0].port()).isExec();
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphEdge e : edges(function)) {
            boolean replaced = exec ? e.fromNode().equals(ends[0].node()) && e.fromPort().equals(ends[0].port())
                : e.toNode().equals(ends[1].node()) && e.toPort().equals(ends[1].port());
            if (!replaced) {
                edges.add(e);
            }
        }
        edges.add(new GraphEdge(ends[0].node(), ends[0].port(), ends[1].node(), ends[1].port(), Map.of()));
        // A data link supersedes the input's literal.
        List<GraphNode> nodes = nodes(function);
        if (!exec) {
            nodes = nodes.stream().map(n -> {
                if (!n.id().equals(ends[1].node()) || !n.inputs().containsKey(ends[1].port())) {
                    return n;
                }
                Map<String, UiValue> in = new LinkedHashMap<>(n.inputs());
                in.remove(ends[1].port());
                return new GraphNode(n.id(), n.kind(), n.kindVersion(), n.x(), n.y(), in, n.props(), n.unknown());
            }).toList();
        }
        List<GraphNode> fnodes = nodes;
        edit("Link", g -> withBody(g, function, fnodes, edges));
        return null;
    }

    public void disconnect(String function, GraphEdge edge) {
        List<GraphEdge> edges = edges(function).stream().filter(e -> !sameEnds(e, edge)).toList();
        edit("Unlink", g -> withBody(g, function, nodes(function), edges));
    }

    /** Removes every link at one port. */
    public void disconnectPort(String function, String node, String port) {
        List<GraphEdge> edges = edges(function).stream().filter(e ->
            !(e.fromNode().equals(node) && e.fromPort().equals(port) || e.toNode().equals(node) && e.toPort().equals(port)))
            .toList();
        edit("Unlink", g -> withBody(g, function, nodes(function), edges));
    }

    private static boolean sameEnds(GraphEdge a, GraphEdge b) {
        return a.fromNode().equals(b.fromNode()) && a.fromPort().equals(b.fromPort()) && a.toNode().equals(b.toNode())
            && a.toPort().equals(b.toPort());
    }

    // ── variables and functions ─────────────────────────────────────────────

    public void addVariable(String name, ValueType type, UiValue defaultValue) {
        if (graph().variables().stream().anyMatch(v -> v.name().equals(name))) {
            throw new IllegalArgumentException("variable " + name + " exists");
        }
        List<GraphVariable> vars = new ArrayList<>(graph().variables());
        vars.add(new GraphVariable(name, type, defaultValue, Map.of()));
        edit("Add variable " + name, g -> new UiGraph(g.id(), vars, g.nodes(), g.edges(), g.functions(), g.unknown()));
    }

    public void setVariable(String name, ValueType type, UiValue defaultValue) {
        List<GraphVariable> vars = graph().variables().stream()
            .map(v -> v.name().equals(name) ? new GraphVariable(name, type, defaultValue, v.unknown()) : v).toList();
        edit("Edit variable " + name, g -> new UiGraph(g.id(), vars, g.nodes(), g.edges(), g.functions(), g.unknown()));
    }

    public void removeVariable(String name) {
        List<GraphVariable> vars = graph().variables().stream().filter(v -> !v.name().equals(name)).toList();
        edit("Delete variable " + name, g -> new UiGraph(g.id(), vars, g.nodes(), g.edges(), g.functions(), g.unknown()));
    }

    /** Renames a variable and every node that names it. */
    public void renameVariable(String from, String to) {
        List<GraphVariable> vars = graph().variables().stream()
            .map(v -> v.name().equals(from) ? new GraphVariable(to, v.type(), v.defaultValue(), v.unknown()) : v).toList();
        UiGraph g = renameProp(graph(), "variable", from, to, null);
        apply("Rename variable " + from, doc.withGraph(new UiGraph(g.id(), vars, g.nodes(), g.edges(), g.functions(),
            g.unknown())), null);
    }

    /** Adds a function with its entry node (and, for a pure function, a return node). */
    public void addFunction(String id, List<GraphPort> inputs, List<GraphPort> outputs) {
        if (graph().functions().stream().anyMatch(f -> f.id().equals(id))) {
            throw new IllegalArgumentException("function " + id + " exists");
        }
        boolean exec = inputs.stream().anyMatch(p -> GraphPort.EXEC.equals(p.type()));
        List<GraphNode> body = new ArrayList<>();
        body.add(new GraphNode("entry", "ui:function.entry", 1, 0, 0, Map.of(), Map.of(), Map.of()));
        body.add(new GraphNode("result", "ui:function.return", 1, 400, 0, Map.of(), Map.of(), Map.of()));
        List<GraphEdge> edges = exec ? List.of(new GraphEdge("entry", PortSpec.THEN, "result", PortSpec.EXEC_IN, Map.of()))
            : List.of();
        List<GraphFunction> fns = new ArrayList<>(graph().functions());
        fns.add(new GraphFunction(id, inputs, outputs, body, edges, Map.of()));
        edit("Add function " + id, g -> new UiGraph(g.id(), g.variables(), g.nodes(), g.edges(), fns, g.unknown()));
    }

    public void setSignature(String id, List<GraphPort> inputs, List<GraphPort> outputs) {
        List<GraphFunction> fns = graph().functions().stream().map(f -> f.id().equals(id)
            ? new GraphFunction(id, inputs, outputs, f.nodes(), f.edges(), f.unknown()) : f).toList();
        edit("Edit function " + id, g -> new UiGraph(g.id(), g.variables(), g.nodes(), g.edges(), fns, g.unknown()));
    }

    public void removeFunction(String id) {
        List<GraphFunction> fns = graph().functions().stream().filter(f -> !f.id().equals(id)).toList();
        GraphLayout l = layout();
        GraphLayout pruned = new GraphLayout(l.comments().stream().filter(c -> !c.function().equals(id)).toList(),
            l.groups().stream().filter(gr -> !gr.function().equals(id)).toList());
        UiGraph g = graph();
        apply("Delete function " + id, withLayout(doc.withGraph(new UiGraph(g.id(), g.variables(), g.nodes(), g.edges(),
            fns, g.unknown())), pruned), null);
    }

    /** Renames a function, its call nodes and its layout annotations. */
    public void renameFunction(String from, String to) {
        if (graph().functions().stream().anyMatch(f -> f.id().equals(to))) {
            throw new IllegalArgumentException("function " + to + " exists");
        }
        UiGraph g = renameProp(graph(), "function", from, to, NodeKinds.functionCall());
        List<GraphFunction> fns = g.functions().stream().map(f -> f.id().equals(from)
            ? new GraphFunction(to, f.inputs(), f.outputs(), f.nodes(), f.edges(), f.unknown()) : f).toList();
        GraphLayout l = layout();
        GraphLayout moved = new GraphLayout(
            l.comments().stream().map(c -> c.function().equals(from) ? new GraphLayout.Comment(c.id(), to, c.x(), c.y(),
                c.width(), c.height(), c.text(), c.color()) : c).toList(),
            l.groups().stream().map(gr -> gr.function().equals(from) ? new GraphLayout.Group(gr.id(), to, gr.title(),
                gr.nodes(), gr.color()) : gr).toList());
        apply("Rename function " + from, withLayout(doc.withGraph(new UiGraph(g.id(), g.variables(), g.nodes(),
            g.edges(), fns, g.unknown())), moved), null);
    }

    /** Every body's nodes whose {@code prop} equals {@code from} get {@code to} (only {@code kind}, when given). */
    private static UiGraph renameProp(UiGraph g, String prop, String from, String to, String kind) {
        UnaryOperator<List<GraphNode>> fix = nodes -> nodes.stream().map(n -> {
            if ((kind != null && !kind.equals(n.kind())) || !(n.props().get(prop) instanceof UiValue.Str s)
                || !s.value().equals(from)) {
                return n;
            }
            Map<String, UiValue> p = new LinkedHashMap<>(n.props());
            p.put(prop, UiValue.of(to));
            return new GraphNode(n.id(), n.kind(), n.kindVersion(), n.x(), n.y(), n.inputs(), p, n.unknown());
        }).toList();
        List<GraphFunction> fns = g.functions().stream().map(f -> new GraphFunction(f.id(), f.inputs(), f.outputs(),
            fix.apply(f.nodes()), f.edges(), f.unknown())).toList();
        return new UiGraph(g.id(), g.variables(), fix.apply(g.nodes()), g.edges(), fns, g.unknown());
    }

    // ── comments and groups ─────────────────────────────────────────────────

    public String addComment(String function, double x, double y, double width, double height, String text) {
        GraphLayout l = layout();
        String id = layoutId(l, "comment");
        List<GraphLayout.Comment> cs = new ArrayList<>(l.comments());
        cs.add(new GraphLayout.Comment(id, function, clamp(x), clamp(y), Math.max(40, width), Math.max(30, height),
            text, ""));
        editLayout("Add comment", lay -> lay.withComments(cs));
        return id;
    }

    /** Replaces a comment's frame and text (one undo step; drags may pass a token to merge). */
    public void editComment(String id, double x, double y, double width, double height, String text, String color,
                            String dragToken) {
        GraphLayout l = layout();
        List<GraphLayout.Comment> cs = l.comments().stream().map(c -> c.id().equals(id)
            ? new GraphLayout.Comment(id, c.function(), clamp(x), clamp(y), Math.max(40, width), Math.max(30, height),
                text, color) : c).toList();
        apply("Edit comment", withLayout(doc, l.withComments(cs)), dragToken == null ? null : "comment:" + dragToken);
    }

    /** Moves a comment and the nodes inside its frame, as one drag. */
    public void moveComment(String id, double dx, double dy, String dragToken) {
        GraphLayout l = layout();
        GraphLayout.Comment c = l.comments().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        Set<String> inside = new HashSet<>();
        for (GraphNode n : nodes(c.function())) {
            if (c.contains(n.x(), n.y())) {
                inside.add(n.id());
            }
        }
        List<GraphNode> nodes = nodes(c.function()).stream()
            .map(n -> inside.contains(n.id()) ? moved(n, n.x() + dx, n.y() + dy) : n).toList();
        List<GraphLayout.Comment> cs = l.comments().stream().map(x -> x.id().equals(id)
            ? new GraphLayout.Comment(id, x.function(), clamp(x.x() + dx), clamp(x.y() + dy), x.width(), x.height(),
                x.text(), x.color()) : x).toList();
        OmuiArchive next = withLayout(doc.withGraph(withBody(graph(), c.function(), nodes, edges(c.function()))),
            l.withComments(cs));
        apply("Move comment", next, dragToken == null ? null : "comment:" + dragToken);
    }

    public void removeComment(String id) {
        GraphLayout l = layout();
        editLayout("Delete comment", lay -> lay.withComments(l.comments().stream().filter(c -> !c.id().equals(id)).toList()));
    }

    public String group(String function, Collection<String> nodeIds, String title) {
        GraphLayout l = layout();
        String id = layoutId(l, "group");
        List<GraphLayout.Group> gs = new ArrayList<>(l.groups());
        gs.add(new GraphLayout.Group(id, function, title, List.copyOf(new LinkedHashSet<>(nodeIds)), ""));
        editLayout("Group", lay -> lay.withGroups(gs));
        return id;
    }

    public void ungroup(String id) {
        GraphLayout l = layout();
        editLayout("Ungroup", lay -> lay.withGroups(l.groups().stream().filter(g -> !g.id().equals(id)).toList()));
    }

    /** The groups a node belongs to (selection expands to whole groups). */
    public List<GraphLayout.Group> groupsOf(String function, String nodeId) {
        String f = function == null ? "" : function;
        return layout().groups().stream().filter(g -> g.function().equals(f) && g.nodes().contains(nodeId)).toList();
    }

    private static String layoutId(GraphLayout l, String base) {
        Set<String> used = new HashSet<>();
        l.comments().forEach(c -> used.add(c.id()));
        l.groups().forEach(g -> used.add(g.id()));
        String id = base + "_1";
        for (int i = 2; used.contains(id); i++) {
            id = base + "_" + i;
        }
        return id;
    }

    // ── clipboard ───────────────────────────────────────────────────────────

    /** The selected nodes and the links among them, as clipboard text (canonical JSON). */
    public String copy(String function, Collection<String> ids) {
        Set<String> set = new HashSet<>(ids);
        List<GraphNode> nodes = nodes(function).stream().filter(n -> set.contains(n.id())).toList();
        List<GraphEdge> edges = edges(function).stream()
            .filter(e -> set.contains(e.fromNode()) && set.contains(e.toNode())).toList();
        UiValue.Obj g = GraphCodec.write(new UiGraph(CLIP_FORMAT, List.of(), nodes, edges, List.of(), Map.of()));
        return new String(CanonicalJson.write(g), StandardCharsets.UTF_8);
    }

    /**
     * Pastes clipboard text with fresh node ids (links among the pasted nodes follow), its
     * top-left node at (x, y). Returns the new ids; unreadable text pastes nothing.
     */
    public List<String> paste(String function, String clip, double x, double y) {
        UiGraph g = readClip(clip);
        if (g == null || g.nodes().isEmpty()) {
            return List.of();
        }
        double minX = g.nodes().stream().mapToDouble(GraphNode::x).min().orElse(0);
        double minY = g.nodes().stream().mapToDouble(GraphNode::y).min().orElse(0);
        Map<String, String> ids = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>(nodes(function));
        Set<String> used = new HashSet<>();
        nodes.forEach(n -> used.add(n.id()));
        List<String> created = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            String id = n.id();
            for (int i = 2; used.contains(id); i++) {
                id = (n.id().length() > 56 ? n.id().substring(0, 56) : n.id()) + "_" + i;
            }
            used.add(id);
            ids.put(n.id(), id);
            created.add(id);
            nodes.add(new GraphNode(id, n.kind(), n.kindVersion(), clamp(x + n.x() - minX), clamp(y + n.y() - minY),
                n.inputs(), n.props(), n.unknown()));
        }
        List<GraphEdge> edges = new ArrayList<>(edges(function));
        for (GraphEdge e : g.edges()) {
            if (ids.containsKey(e.fromNode()) && ids.containsKey(e.toNode())) {
                edges.add(new GraphEdge(ids.get(e.fromNode()), e.fromPort(), ids.get(e.toNode()), e.toPort(), Map.of()));
            }
        }
        edit("Paste " + created.size() + (created.size() == 1 ? " node" : " nodes"),
            gr -> withBody(gr, function, nodes, edges));
        return created;
    }

    /** Copy + paste offset by (40, 40). */
    public List<String> duplicate(String function, Collection<String> ids) {
        List<GraphNode> sel = nodes(function).stream().filter(n -> ids.contains(n.id())).toList();
        if (sel.isEmpty()) {
            return List.of();
        }
        double minX = sel.stream().mapToDouble(GraphNode::x).min().orElse(0);
        double minY = sel.stream().mapToDouble(GraphNode::y).min().orElse(0);
        return paste(function, copy(function, ids), minX + 40, minY + 40);
    }

    private static UiGraph readClip(String clip) {
        if (clip == null || clip.isBlank()) {
            return null;
        }
        try {
            UiDiagnostics d = new UiDiagnostics();
            UiValue root = CanonicalJson.parse(clip.getBytes(StandardCharsets.UTF_8), "clipboard", d);
            if (root == null || d.hasErrors() || !(root instanceof UiValue.Obj o)
                || !(o.get("id") instanceof UiValue.Str s) || !CLIP_FORMAT.equals(s.value())) {
                return null;
            }
            UiGraph g = GraphCodec.read(CLIP_FORMAT, "clipboard", root,
                com.openmason.engine.format.omui.ArchiveLimits.DEFAULT, d);
            return d.hasErrors() ? null : g;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── palette, pickers and references ─────────────────────────────────────

    /** Palette entries usable in {@code function}'s body that match {@code query}, best first. */
    public List<PaletteEntry> palette(String function, String query) {
        boolean inFunction = function != null && !function.isEmpty();
        List<PaletteEntry> all = new ArrayList<>();
        for (NodeKind k : NodeKinds.all()) {
            boolean bodyNode = NodeKinds.isEntry(k.id()) || NodeKinds.isReturn(k.id());
            if (k.isEvent() && inFunction || bodyNode && !inFunction
                || k.id().equals(NodeKinds.functionCall()) || k.id().equals(NodeKinds.luaCall())) {
                continue;
            }
            all.add(new PaletteEntry(k.id(), k.title(), k.category(), k.doc(), Map.of()));
        }
        for (GraphVariable v : graph().variables()) {
            all.add(new PaletteEntry("ui:variable.get", "Get " + v.name(), "Variables", v.type().wire(),
                Map.of("variable", UiValue.of(v.name()))));
            all.add(new PaletteEntry("ui:variable.set", "Set " + v.name(), "Variables", v.type().wire(),
                Map.of("variable", UiValue.of(v.name()))));
        }
        for (GraphFunction f : graph().functions()) {
            all.add(new PaletteEntry(NodeKinds.functionCall(), f.id(), "Functions", "graph function",
                Map.of("function", UiValue.of(f.id()))));
        }
        for (LuaFunction f : environment().luaFunctions()) {
            Map<String, UiValue> p = new LinkedHashMap<>();
            p.put("function", UiValue.of(f.name()));
            if (!f.module().isEmpty()) {
                p.put("module", UiValue.of(f.module()));
            }
            all.add(new PaletteEntry(NodeKinds.luaCall(), f.label(), "Lua", f.doc().isEmpty()
                ? "Lua function" + (f.async() ? " (async)" : "") : f.doc(), p));
        }
        return all.stream().filter(e -> e.matches(query)).sorted(Comparator.comparingInt(e -> e.rank(query))).toList();
    }

    /**
     * Values an inspector may offer for a property: element paths, variables, functions,
     * modules, clips, signals of the target, UI events, enum options, style properties. Empty
     * when the property is free text.
     */
    public List<String> options(String function, String nodeId, String prop) {
        GraphNode n = node(function, nodeId);
        NodeKind k = n == null ? null : NodeKinds.get(n.kind());
        PropSpec spec = k == null ? null : k.prop(prop);
        if (spec == null) {
            return List.of();
        }
        GraphEnvironment e = environment();
        return switch (spec.kind()) {
            case ELEMENT -> e.elements().stream().map(GraphEnvironment.ElementInfo::path).toList();
            case VARIABLE -> graph().variables().stream().map(GraphVariable::name).toList();
            case FUNCTION -> graph().functions().stream().map(GraphFunction::id).toList();
            case MODULE -> {
                List<String> m = new ArrayList<>();
                m.add("");
                m.addAll(e.modules());
                yield m;
            }
            case LUA_FUNCTION -> {
                String module = n.props().get("module") instanceof UiValue.Str s ? s.value() : "";
                yield e.luaFunctions().stream().filter(f -> f.module().equals(module)).map(LuaFunction::name).toList();
            }
            case CLIP -> List.copyOf(e.clips());
            case SIGNAL -> {
                GraphEnvironment.ElementInfo target = e.element(KindContext.str(n, "target"));
                var contract = n.kind().equals("ui:event.signal") ? (target == null ? null : target.contract())
                    : e.ownContract();
                yield contract == null ? List.of() : contract.events().stream().map(ev -> ev.name()).toList();
            }
            case EVENT_NAME -> java.util.Arrays.stream(UiEventType.values())
                .map(t -> t.name().toLowerCase(Locale.ROOT).replace('_', '-')).toList();
            case ENUM -> spec.options();
            case STYLE_PROPERTY -> List.copyOf(UiStyleProperties.names());
            default -> List.of();
        };
    }

    /** Element paths a node targets ("jump to element"). */
    public List<String> elementTargets(String function, String nodeId) {
        GraphNode n = node(function, nodeId);
        NodeKind k = n == null ? null : NodeKinds.get(n.kind());
        if (k == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (PropSpec p : k.props()) {
            if (p.kind() == PropSpec.Kind.ELEMENT && n.props().get(p.name()) instanceof UiValue.Str s) {
                out.add(s.value());
            }
        }
        return out;
    }

    /** Nodes of every body that target {@code elementPath} (the reverse jump, from the preview). */
    public List<String[]> nodesTargeting(String elementPath) {
        List<String[]> out = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        bodies.add("");
        graph().functions().forEach(f -> bodies.add(f.id()));
        for (String b : bodies) {
            for (GraphNode n : nodes(b)) {
                if (elementTargets(b, n.id()).contains(elementPath)) {
                    out.add(new String[]{b, n.id()});
                }
            }
        }
        return out;
    }

    // ── code ────────────────────────────────────────────────────────────────

    /** The Lua the graph compiles to now (for the "Lua" view); null with errors. */
    public CompiledGraph compile(boolean debug) {
        return GraphCompiler.compile(graph(), environment(), debug);
    }

    /**
     * "Convert to script": copies the generated Lua into a new editable module
     * {@code scripts/<moduleId>.lua} (one undo step). The graph stays; it is still canonical
     * for its own code, and the copy is the author's from now on.
     *
     * @return the graph's errors when it does not compile (nothing changes), else empty
     */
    public List<GraphDiagnostic> convertToScript(String moduleId) {
        if (!OmuiFormat.PART_ID.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("'" + moduleId + "' is not a part id");
        }
        if (doc.scripts().containsKey(moduleId)) {
            throw new IllegalArgumentException("scripts/" + moduleId + ".lua exists");
        }
        CompiledGraph c = compile(false);
        if (!c.ok()) {
            return c.errors();
        }
        apply("Convert " + graphId + " to script", doc.withScript(moduleId, GraphScripts.convert(c)), null);
        return List.of();
    }

    /** Port types as the inspector shows them ({@code exec}, {@code int}, ...). */
    public static String typeName(PortType t) {
        return t.wire();
    }
}
