package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;
import com.openmason.engine.ui.graph.GraphDiagnostic.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved, checked form of one graph (#291): every node with its kind and ports, every
 * link indexed, every body split into units (the nodes one event or function runs), and every
 * problem found on the way. The compiler only runs on a plan without errors.
 *
 * <p>Checks, in order: kinds and versions; properties (targets, variables, functions, clips,
 * paths); links (ports exist, direction, types, one link per data input and per exec output);
 * inputs (required, literal types); function shapes; data cycles; recursion; waits where
 * nothing may wait; exec loops without a wait; values read outside their event; unreachable
 * statements.
 */
final class GraphPlan {

    enum Role { EVENT, STATEMENT, PURE, ENTRY, RETURN, INVALID }

    /** A resolved node. */
    static final class Node {
        final GraphNode node;
        final NodeKind kind;
        final NodePorts ports;
        final Role role;

        Node(GraphNode node, NodeKind kind, NodePorts ports, Role role) {
            this.node = node;
            this.kind = kind;
            this.ports = ports;
            this.role = role;
        }

        String id() {
            return node.id();
        }
    }

    /** The nodes one entry runs: an event's chain, or a function body. */
    static final class Unit {
        final Node entry;
        /** Statements in first-visit order, the entry first. */
        final LinkedHashSet<String> nodes = new LinkedHashSet<>();
        /** Statements reached by more than one link: compiled once as a local function. */
        final Set<String> blocks = new LinkedHashSet<>();
        final boolean mayWait;

        Unit(Node entry, boolean mayWait) {
            this.entry = entry;
            this.mayWait = mayWait;
        }
    }

    /** The event graph or one function. */
    static final class Body {
        final GraphFunction function;
        final KindContext ctx;
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, GraphEdge> dataIn = new HashMap<>();
        final Map<String, GraphEdge> execOut = new HashMap<>();
        final Map<String, List<GraphEdge>> dataOut = new HashMap<>();
        final List<Unit> units = new ArrayList<>();

        Body(GraphFunction function, KindContext ctx) {
            this.function = function;
            this.ctx = ctx;
        }

        String fnId() {
            return function == null ? "" : function.id();
        }

        GraphEdge in(String node, String port) {
            return dataIn.get(key(node, port));
        }

        GraphEdge exec(String node, String port) {
            return execOut.get(key(node, port));
        }

        boolean used(String node, String port) {
            List<GraphEdge> l = dataOut.get(key(node, port));
            return l != null && !l.isEmpty();
        }
    }

    /** Facts about a graph function. */
    static final class FnInfo {
        final GraphFunction fn;
        final boolean exec;
        boolean latent;
        final boolean converter;

        FnInfo(GraphFunction fn) {
            this.fn = fn;
            this.exec = CallKinds.isExec(fn);
            this.converter = !exec && CallKinds.ports(fn.inputs(), false).size() == 1
                && CallKinds.ports(fn.outputs(), false).size() == 1 && CallKinds.execOutputs(fn).isEmpty();
        }

        PortType resultType() {
            return CallKinds.ports(fn.outputs(), false).getFirst().type();
        }
    }

    final UiGraph graph;
    final GraphEnvironment env;
    final List<GraphDiagnostic> diagnostics = new ArrayList<>();
    final Body events;
    final Map<String, Body> functions = new LinkedHashMap<>();
    final Map<String, FnInfo> fnInfo = new LinkedHashMap<>();

    private GraphPlan(UiGraph graph, GraphEnvironment env) {
        this.graph = graph;
        this.env = env;
        this.events = new Body(null, new KindContext(env, graph, null));
    }

    static String key(String node, String port) {
        return node + "\u0000" + port;
    }

    static GraphPlan build(UiGraph graph, GraphEnvironment env) {
        GraphPlan p = new GraphPlan(graph, env);
        p.run();
        return p;
    }

    boolean hasErrors() {
        return diagnostics.stream().anyMatch(GraphDiagnostic::isError);
    }

    private void run() {
        checkVariables();
        for (GraphFunction f : graph.functions()) {
            fnInfo.put(f.id(), new FnInfo(f));
            checkSignature(f);
        }
        resolve(events, graph.nodes(), graph.edges());
        for (GraphFunction f : graph.functions()) {
            Body b = new Body(f, new KindContext(env, graph, f));
            functions.put(f.id(), b);
            resolve(b, f.nodes(), f.edges());
        }
        List<Body> all = bodies();
        all.forEach(this::dataCycles);
        recursion();
        latency();
        for (Body b : all) {
            if (b.function == null) {
                eventUnits(b);
            } else {
                functionUnit(b);
            }
            b.units.forEach(u -> unitChecks(b, u));
            unreachable(b);
        }
        // A node in several units (a loop reached from two events) reports each problem once.
        List<GraphDiagnostic> distinct = new ArrayList<>(new LinkedHashSet<>(diagnostics));
        diagnostics.clear();
        diagnostics.addAll(distinct);
    }

    List<Body> bodies() {
        List<Body> all = new ArrayList<>();
        all.add(events);
        all.addAll(functions.values());
        return all;
    }

    // ── reporting ───────────────────────────────────────────────────────────

    private void report(Severity s, Code c, Body b, String node, String port, String message) {
        diagnostics.add(new GraphDiagnostic(s, c, graph.id(), b == null ? "" : b.fnId(), node, port, message));
    }

    private void error(Code c, Body b, String node, String port, String message) {
        report(Severity.ERROR, c, b, node, port, message);
    }

    // ── declarations ────────────────────────────────────────────────────────

    private void checkVariables() {
        Set<String> seen = new HashSet<>();
        for (UiGraph.GraphVariable v : graph.variables()) {
            if (!Ports.IDENT.matcher(v.name()).matches() || !seen.add(v.name())) {
                error(Code.DUPLICATE_NAME, null, "", "", "variable '" + v.name() + "' is not a unique identifier");
            }
        }
    }

    private void checkSignature(GraphFunction f) {
        Body b = new Body(f, null);
        Set<String> names = new HashSet<>();
        for (UiGraph.GraphPort p : f.inputs()) {
            portName(b, p, names, "input");
        }
        names.clear();
        for (UiGraph.GraphPort p : f.outputs()) {
            portName(b, p, names, "output");
        }
        if (CallKinds.ports(f.inputs(), true).size() > 1) {
            error(Code.FUNCTION_SHAPE, b, "", "", "a function has at most one exec input");
        }
        if (!CallKinds.isExec(f) && !CallKinds.execOutputs(f).isEmpty()) {
            error(Code.FUNCTION_SHAPE, b, "", "", "exec outputs need an exec input");
        }
    }

    private void portName(Body b, UiGraph.GraphPort p, Set<String> names, String what) {
        if (!Ports.IDENT.matcher(p.name()).matches() || !names.add(p.name())) {
            error(Code.FUNCTION_SHAPE, b, "", "", what + " '" + p.name() + "' is not a unique identifier");
        }
        if (PortType.fromWire(p.type()) == null) {
            error(Code.FUNCTION_SHAPE, b, "", "", what + " '" + p.name() + "' has unknown type '" + p.type() + "'");
        }
    }

    // ── nodes and links ─────────────────────────────────────────────────────

    private void resolve(Body b, List<GraphNode> nodes, List<GraphEdge> edges) {
        int entries = 0;
        int returns = 0;
        for (GraphNode n : nodes) {
            Node r = node(b, n);
            b.nodes.put(n.id(), r);
            entries += r.role == Role.ENTRY ? 1 : 0;
            returns += r.role == Role.RETURN ? 1 : 0;
        }
        if (b.function != null) {
            if (entries != 1) {
                error(Code.FUNCTION_SHAPE, b, "", "", "function " + b.fnId() + " needs exactly one entry node, has "
                    + entries);
            }
            if (!fnInfo.get(b.fnId()).exec && returns != 1) {
                error(Code.FUNCTION_SHAPE, b, "", "", "pure function " + b.fnId()
                    + " needs exactly one return node, has " + returns);
            }
        }
        for (GraphEdge e : edges) {
            link(b, e);
        }
        for (Node n : b.nodes.values()) {
            if (n.role != Role.INVALID) {
                inputs(b, n);
            }
        }
    }

    private Node node(Body b, GraphNode n) {
        NodeKind kind = NodeKinds.get(n.kind());
        if (kind == null) {
            error(Code.UNKNOWN_KIND, b, n.id(), "", "unknown node kind '" + n.kind() + "'");
            return new Node(n, null, NodePorts.NONE, Role.INVALID);
        }
        if (n.kindVersion() > kind.version()) {
            error(Code.NEWER_KIND_VERSION, b, n.id(), "", n.kind() + " version " + n.kindVersion()
                + " is newer than this compiler's " + kind.version());
            return new Node(n, kind, NodePorts.NONE, Role.INVALID);
        }
        boolean bodyNode = NodeKinds.isEntry(n.kind()) || NodeKinds.isReturn(n.kind());
        if (bodyNode && b.function == null) {
            error(Code.FUNCTION_SHAPE, b, n.id(), "", "function entry and return nodes belong in a function body");
            return new Node(n, kind, NodePorts.NONE, Role.INVALID);
        }
        if (kind.isEvent() && b.function != null) {
            error(Code.FUNCTION_SHAPE, b, n.id(), "", "events belong in the event graph, not in function " + b.fnId());
            return new Node(n, kind, NodePorts.NONE, Role.INVALID);
        }
        int before = errorCount();
        props(b, n, kind);
        kind.check(b.ctx, n, new NodeKind.Checks() {
            @Override
            public void error(Code code, String port, String message) {
                report(Severity.ERROR, code, b, n.id(), port, message);
            }

            @Override
            public void warning(Code code, String port, String message) {
                report(Severity.WARNING, code, b, n.id(), port, message);
            }
        });
        NodePorts ports;
        try {
            ports = kind.ports(b.ctx, n);
        } catch (RuntimeException e) {
            error(Code.INVALID_PROP, b, n.id(), "", "cannot resolve ports: " + e.getMessage());
            return new Node(n, kind, NodePorts.NONE, Role.INVALID);
        }
        Role role;
        if (NodeKinds.isEntry(n.kind())) {
            role = Role.ENTRY;
        } else if (NodeKinds.isReturn(n.kind())) {
            role = Role.RETURN;
        } else if (kind.isEvent()) {
            role = Role.EVENT;
        } else if (ports.hasExecInput()) {
            role = kind.statement() != null ? Role.STATEMENT : Role.INVALID;
        } else {
            role = kind.pure() != null ? Role.PURE : Role.INVALID;
        }
        if (role == Role.INVALID && errorCount() == before) {
            error(Code.INVALID_PROP, b, n.id(), "", n.kind() + " cannot be used this way"
                + (ports.inputs().isEmpty() && ports.outputs().isEmpty() ? " (its properties resolve no ports)" : ""));
        }
        return new Node(n, kind, ports, role);
    }

    private int errorCount() {
        return (int) diagnostics.stream().filter(GraphDiagnostic::isError).count();
    }

    /** Generic property checks from the kind's {@link PropSpec}s. */
    private void props(Body b, GraphNode n, NodeKind kind) {
        for (PropSpec spec : kind.props()) {
            UiValue v = n.props().get(spec.name());
            boolean empty = v == null || v instanceof UiValue.Null || v instanceof UiValue.Str s && s.value().isEmpty();
            if (empty) {
                if (spec.required()) {
                    error(spec.kind() == PropSpec.Kind.ELEMENT ? Code.MISSING_ELEMENT : Code.INVALID_PROP, b, n.id(), "",
                        "property '" + spec.name() + "' is required (" + spec.doc() + ")");
                }
                continue;
            }
            String text = v instanceof UiValue.Str s ? s.value() : null;
            String problem = switch (spec.kind()) {
                case BOOL -> v instanceof UiValue.Bool ? null : "must be true or false";
                case INT -> v instanceof UiValue.Num num && num.isIntegral() ? null : "must be an integer";
                case PARAMS -> params(v);
                case NAMES -> v instanceof UiValue.Arr ? null : "must be a list of names";
                default -> text == null ? "must be text" : null;
            };
            if (problem == null && text != null) {
                problem = textProblem(b, spec, text);
            }
            if (problem != null) {
                Code code = switch (spec.kind()) {
                    case ELEMENT -> Code.MISSING_ELEMENT;
                    case VARIABLE -> Code.UNKNOWN_VARIABLE;
                    case FUNCTION -> Code.UNKNOWN_FUNCTION;
                    case CLIP -> Code.UNKNOWN_CLIP;
                    default -> Code.INVALID_PROP;
                };
                error(code, b, n.id(), "", "property '" + spec.name() + "' " + problem);
            }
        }
    }

    private String textProblem(Body b, PropSpec spec, String text) {
        return switch (spec.kind()) {
            case ENUM -> spec.options().contains(text) ? null : "must be one of " + spec.options();
            case IDENT -> Ports.IDENT.matcher(text).matches() ? null : "'" + text + "' is not an identifier";
            case ELEMENT -> env.element(text) != null ? null : "names no element '" + text + "' in "
                + env.document().manifest().documentId();
            case VARIABLE -> b.ctx.variable(text) != null ? null : "names no graph variable '" + text + "'";
            case FUNCTION -> b.ctx.function(text) != null ? null : "names no function '" + text + "' in this graph";
            case CLIP -> env.clips().contains(text) ? null : "names no clip '" + text + "' (clips: " + env.clips() + ")";
            case MODULE -> env.modules().contains(text) ? null : "names no module '" + text + "' (modules: "
                + env.modules() + ")";
            case DATA_PATH -> {
                try {
                    DataPath p = DataPath.parse(text);
                    yield p.relative() ? "must be an absolute host path (session.online)" : null;
                } catch (RuntimeException e) {
                    yield "'" + text + "' is not a data path";
                }
            }
            case EVENT_NAME -> eventName(text) ? null : "'" + text + "' is not a UI event";
            case STYLE_PROPERTY -> com.openmason.engine.format.omui.UiStyleProperties.isKnown(text)
                || com.openmason.engine.format.omui.UiStyleProperties.isCustom(text) ? null
                : "'" + text + "' is not a style property";
            default -> null;
        };
    }

    private static boolean eventName(String name) {
        for (var t : com.openmason.engine.ui.runtime.input.UiEventType.values()) {
            if (t.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-').equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String params(UiValue v) {
        if (!(v instanceof UiValue.Arr a)) {
            return "must be a list of {name, type}";
        }
        Set<String> names = new HashSet<>();
        for (UiValue item : a.items()) {
            if (!(item instanceof UiValue.Obj o) || !(o.get("name") instanceof UiValue.Str n)
                || !Ports.IDENT.matcher(n.value()).matches() || !names.add(n.value())
                || PortSpec.EXEC_IN.equals(n.value()) || PortSpec.THEN.equals(n.value())) {
                return "has a parameter without a unique identifier name";
            }
            if (o.get("type") instanceof UiValue.Str t && (PortType.fromWire(t.value()) == null
                || PortType.fromWire(t.value()).isExec())) {
                return "has a parameter of unknown type '" + t.value() + "'";
            }
        }
        return null;
    }

    private void link(Body b, GraphEdge e) {
        Node from = b.nodes.get(e.fromNode());
        Node to = b.nodes.get(e.toNode());
        if (from == null || to == null) {
            error(Code.BROKEN_LINK, b, e.toNode(), e.toPort(), "link from a missing node");
            return;
        }
        if (from.role == Role.INVALID || to.role == Role.INVALID) {
            return; // the node's own error says why
        }
        PortSpec out = from.ports.output(e.fromPort());
        PortSpec in = to.ports.input(e.toPort());
        if (out == null) {
            Code c = from.ports.input(e.fromPort()) != null ? Code.WRONG_DIRECTION : Code.BROKEN_LINK;
            error(c, b, from.id(), e.fromPort(), from.node.kind() + " has no output '" + e.fromPort() + "'");
            return;
        }
        if (in == null) {
            Code c = to.ports.output(e.toPort()) != null ? Code.WRONG_DIRECTION : Code.BROKEN_LINK;
            error(c, b, to.id(), e.toPort(), to.node.kind() + " has no input '" + e.toPort() + "'");
            return;
        }
        if (in.type().accepts(out.type()) == PortType.Assign.NONE) {
            error(Code.TYPE_MISMATCH, b, to.id(), e.toPort(), "cannot link " + out.type().wire() + " "
                + from.id() + "." + e.fromPort() + " to " + in.type().wire() + " input '" + e.toPort() + "'");
            return;
        }
        if (in.isExec()) {
            if (b.execOut.putIfAbsent(key(from.id(), e.fromPort()), e) != null) {
                error(Code.DUPLICATE_LINK, b, from.id(), e.fromPort(), "exec output '" + e.fromPort()
                    + "' has more than one link; use a Sequence");
            }
        } else {
            if (b.dataIn.putIfAbsent(key(to.id(), e.toPort()), e) != null) {
                error(Code.DUPLICATE_LINK, b, to.id(), e.toPort(), "data input '" + e.toPort() + "' has more than one link");
                return;
            }
            b.dataOut.computeIfAbsent(key(from.id(), e.fromPort()), k -> new ArrayList<>()).add(e);
        }
    }

    private void inputs(Body b, Node n) {
        for (PortSpec p : n.ports.dataInputs()) {
            if (b.in(n.id(), p.name()) != null) {
                continue;
            }
            UiValue lit = n.node.inputs().get(p.name());
            if (lit != null) {
                if (!p.type().acceptsLiteral(lit)) {
                    error(Code.INVALID_LITERAL, b, n.id(), p.name(), "literal " + lit.typeName() + " does not fit "
                        + p.type().wire());
                }
            } else if (p.defaultValue() == null && !p.optional()) {
                error(Code.REQUIRED_INPUT, b, n.id(), p.name(), "input '" + p.name() + "' (" + p.type().wire()
                    + ") needs a link or a value");
            }
        }
        for (String k : n.node.inputs().keySet()) {
            if (n.ports.input(k) == null) {
                report(Severity.WARNING, Code.BROKEN_LINK, b, n.id(), k, "value for a port the node does not have"
                    + " (ignored)");
            }
        }
    }

    // ── cycles, recursion, waits ────────────────────────────────────────────

    /** A pure node that (through other pure nodes) reads itself. */
    private void dataCycles(Body b) {
        Map<String, Integer> state = new HashMap<>();
        for (Node n : b.nodes.values()) {
            if (n.role == Role.PURE) {
                pureVisit(b, n, state);
            }
        }
    }

    private boolean pureVisit(Body b, Node n, Map<String, Integer> state) {
        Integer s = state.get(n.id());
        if (s != null) {
            if (s == 1) {
                error(Code.SYNC_CYCLE, b, n.id(), "", "data cycle: " + n.id() + " depends on its own output");
                return false;
            }
            return true;
        }
        state.put(n.id(), 1);
        for (PortSpec p : n.ports.dataInputs()) {
            GraphEdge e = b.in(n.id(), p.name());
            Node src = e == null ? null : b.nodes.get(e.fromNode());
            if (src != null && src.role == Role.PURE && !pureVisit(b, src, state)) {
                state.put(n.id(), 2);
                return false;
            }
        }
        state.put(n.id(), 2);
        return true;
    }

    /** Graph functions may not call themselves, directly or through others (synchronous recursion). */
    private void recursion() {
        Map<String, Set<String>> calls = new LinkedHashMap<>();
        for (Body b : functions.values()) {
            Set<String> c = new LinkedHashSet<>();
            for (Node n : b.nodes.values()) {
                if (NodeKinds.functionCall().equals(n.node.kind()) && KindContext.str(n.node, "function") != null) {
                    c.add(KindContext.str(n.node, "function"));
                }
            }
            calls.put(b.fnId(), c);
        }
        for (String f : calls.keySet()) {
            if (reaches(calls, f, f, new HashSet<>())) {
                error(Code.SYNC_CYCLE, functions.get(f), "", "", "function " + f + " calls itself (directly or"
                    + " through another function); graph functions may not recurse");
            }
        }
    }

    private static boolean reaches(Map<String, Set<String>> calls, String from, String target, Set<String> seen) {
        for (String c : calls.getOrDefault(from, Set.of())) {
            if (c.equals(target) || seen.add(c) && reaches(calls, c, target, seen)) {
                return true;
            }
        }
        return false;
    }

    /** Which functions may wait: any latent node, or a call to a function that may. */
    private void latency() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Body b : functions.values()) {
                FnInfo info = fnInfo.get(b.fnId());
                if (!info.latent && b.nodes.values().stream().anyMatch(n -> latent(b, n))) {
                    info.latent = true;
                    changed = true;
                }
            }
        }
        for (Body b : functions.values()) {
            FnInfo info = fnInfo.get(b.fnId());
            if (!info.exec && info.latent) {
                error(Code.LATENT_IN_SYNC, b, "", "", "pure function " + b.fnId() + " may not wait");
            }
        }
    }

    boolean latent(Body b, Node n) {
        if (n.kind == null || n.role == Role.INVALID) {
            return false;
        }
        if (NodeKinds.functionCall().equals(n.node.kind())) {
            FnInfo f = fnInfo.get(KindContext.str(n.node, "function"));
            return f != null && f.latent;
        }
        return n.kind.latent(b.ctx, n.node);
    }

    // ── units ───────────────────────────────────────────────────────────────

    private void eventUnits(Body b) {
        for (Node n : b.nodes.values()) {
            if (n.role == Role.EVENT) {
                Unit u = new Unit(n, n.kind.event().mayWait());
                walk(b, u, n);
                b.units.add(u);
            }
        }
    }

    private void functionUnit(Body b) {
        Node entry = b.nodes.values().stream().filter(n -> n.role == Role.ENTRY).findFirst().orElse(null);
        if (entry == null) {
            return;
        }
        Unit u = new Unit(entry, true);
        FnInfo info = fnInfo.get(b.fnId());
        if (info.exec) {
            walk(b, u, entry);
        } else {
            u.nodes.add(entry.id());
            b.nodes.values().stream().filter(n -> n.role == Role.RETURN).forEach(r -> u.nodes.add(r.id()));
            for (Node n : b.nodes.values()) {
                if (n.role == Role.STATEMENT) {
                    error(Code.FUNCTION_SHAPE, b, n.id(), "", "pure function " + b.fnId()
                        + " cannot run statements; give it an exec input");
                }
            }
        }
        b.units.add(u);
    }

    /** Statements reachable from the entry, in first-visit order, with the in-degree of each. */
    private void walk(Body b, Unit u, Node entry) {
        Map<String, Integer> degree = new HashMap<>();
        List<Node> stack = new ArrayList<>();
        stack.add(entry);
        u.nodes.add(entry.id());
        while (!stack.isEmpty()) {
            Node n = stack.removeLast();
            List<PortSpec> outs = n.ports.execOutputs();
            for (int i = outs.size() - 1; i >= 0; i--) {
                GraphEdge e = b.exec(n.id(), outs.get(i).name());
                if (e == null) {
                    continue;
                }
                degree.merge(e.toNode(), 1, Integer::sum);
                Node next = b.nodes.get(e.toNode());
                if (next != null && u.nodes.add(next.id())) {
                    stack.add(next);
                }
            }
        }
        // Re-order to depth-first pre-order from the entry (stable, readable output).
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        preorder(b, entry, ordered);
        u.nodes.clear();
        u.nodes.addAll(ordered);
        degree.forEach((id, d) -> {
            if (d > 1 && !id.equals(entry.id())) {
                u.blocks.add(id);
            }
        });
        loops(b, u, entry);
    }

    private void preorder(Body b, Node n, LinkedHashSet<String> out) {
        if (!out.add(n.id())) {
            return;
        }
        for (PortSpec p : n.ports.execOutputs()) {
            GraphEdge e = b.exec(n.id(), p.name());
            Node next = e == null ? null : b.nodes.get(e.toNode());
            if (next != null) {
                preorder(b, next, out);
            }
        }
    }

    /** Every exec loop must contain a node that waits; otherwise it would spin within one call. */
    private void loops(Body b, Unit u, Node entry) {
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        loopVisit(b, u, entry, state, path);
    }

    private void loopVisit(Body b, Unit u, Node n, Map<String, Integer> state, List<String> path) {
        state.put(n.id(), 1);
        path.add(n.id());
        for (PortSpec p : n.ports.execOutputs()) {
            GraphEdge e = b.exec(n.id(), p.name());
            Node next = e == null ? null : b.nodes.get(e.toNode());
            if (next == null) {
                continue;
            }
            Integer s = state.get(next.id());
            if (s == null) {
                loopVisit(b, u, next, state, path);
            } else if (s == 1) {
                List<String> cycle = path.subList(path.indexOf(next.id()), path.size());
                boolean waits = cycle.stream().anyMatch(id -> latent(b, b.nodes.get(id)));
                if (!waits) {
                    error(Code.SYNC_CYCLE, b, next.id(), "", "exec loop " + String.join(" > ", cycle) + " > "
                        + next.id() + " never waits: it would run forever within one event; add a Wait");
                }
            }
        }
        path.removeLast();
        state.put(n.id(), 2);
    }

    private void unitChecks(Body b, Unit u) {
        Map<String, Set<String>> dom = dominators(b, u);
        for (String id : u.nodes) {
            Node n = b.nodes.get(id);
            if (!u.mayWait && n != u.entry && latent(b, n)) {
                error(Code.LATENT_IN_SYNC, b, id, "", n.node.kind() + " waits, but "
                    + u.entry.node.kind() + " runs synchronously (nothing after it may wait)");
            }
            for (PortSpec p : n.ports.dataInputs()) {
                scope(b, u, dom, n, p.name(), n, p.name(), new HashSet<>());
            }
        }
    }

    /**
     * Statements that run before each statement on every path from the unit's entry (its
     * dominators, itself included). In a pure function every node runs "after" the entry.
     */
    private static Map<String, Set<String>> dominators(Body b, Unit u) {
        Map<String, Set<String>> preds = new HashMap<>();
        for (String id : u.nodes) {
            Node n = b.nodes.get(id);
            for (PortSpec p : n.ports.execOutputs()) {
                GraphEdge e = b.exec(id, p.name());
                if (e != null && u.nodes.contains(e.toNode())) {
                    preds.computeIfAbsent(e.toNode(), k -> new HashSet<>()).add(id);
                }
            }
        }
        Map<String, Set<String>> dom = new HashMap<>();
        String entry = u.entry.id();
        for (String id : u.nodes) {
            Set<String> start = new HashSet<>();
            if (id.equals(entry) || !preds.containsKey(id)) {
                start.add(id);
                start.add(entry);
            } else {
                start.addAll(u.nodes);
            }
            dom.put(id, start);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : u.nodes) {
                Set<String> ps = preds.get(id);
                if (id.equals(entry) || ps == null) {
                    continue;
                }
                Set<String> next = null;
                for (String p : ps) {
                    if (next == null) {
                        next = new HashSet<>(dom.get(p));
                    } else {
                        next.retainAll(dom.get(p));
                    }
                }
                next.add(id);
                if (!next.equals(dom.get(id))) {
                    dom.put(id, next);
                    changed = true;
                }
            }
        }
        return dom;
    }

    /**
     * A value a statement reads (directly or through pure nodes) must come from a statement
     * that runs before it on every path in the same unit; problems are reported at the
     * reader's own input port.
     */
    private void scope(Body b, Unit u, Map<String, Set<String>> dom, Node reader, String readerPort, Node at,
                       String port, Set<String> seen) {
        GraphEdge e = b.in(at.id(), port);
        Node src = e == null ? null : b.nodes.get(e.fromNode());
        if (src == null || src.role == Role.INVALID) {
            return;
        }
        if (src.role == Role.PURE) {
            if (seen.add(src.id())) {
                for (PortSpec p : src.ports.dataInputs()) {
                    scope(b, u, dom, reader, readerPort, src, p.name(), seen);
                }
            }
        } else if (src == reader) {
            error(Code.OUT_OF_SCOPE, b, reader.id(), readerPort, "reads its own output " + e.fromPort());
        } else if (!u.nodes.contains(src.id())) {
            error(Code.OUT_OF_SCOPE, b, reader.id(), readerPort, "reads " + src.id() + "." + e.fromPort()
                + ", which does not run in " + u.entry.id() + "; share values through a variable");
        } else if (!dom.getOrDefault(reader.id(), Set.of()).contains(src.id())) {
            error(Code.OUT_OF_SCOPE, b, reader.id(), readerPort, "reads " + src.id() + "." + e.fromPort()
                + ", which does not always run before it (later in the chain, or on another branch);"
                + " share values through a variable");
        }
    }

    private void unreachable(Body b) {
        Set<String> reached = new HashSet<>();
        b.units.forEach(u -> reached.addAll(u.nodes));
        for (Node n : b.nodes.values()) {
            if ((n.role == Role.STATEMENT || n.role == Role.RETURN && fnInfo.containsKey(b.fnId())
                && fnInfo.get(b.fnId()).exec) && !reached.contains(n.id())) {
                report(Severity.WARNING, Code.UNREACHABLE, b, n.id(), "", "no event or entry reaches this node;"
                    + " it is not compiled");
            }
        }
    }
}
