package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphPlan.Body;
import com.openmason.engine.ui.graph.GraphPlan.FnInfo;
import com.openmason.engine.ui.graph.GraphPlan.Node;
import com.openmason.engine.ui.graph.GraphPlan.Role;
import com.openmason.engine.ui.graph.GraphPlan.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the Lua of a checked {@link GraphPlan}.
 *
 * <p><b>Shape.</b> Variables are chunk locals (per script context, so per component instance);
 * functions are fields of {@code F}, event handlers of {@code H}; {@code G.on_open} attaches
 * handlers and starts open events as tasks, {@code G.update} and {@code G.on_close} run their
 * events. Inside a unit, outputs that later nodes read are unit locals; a statement reached by
 * several links (a join or a loop) is a local function entered by {@code return b()}: Lua's
 * proper tail calls keep loops flat, and since every chain ends at its transfer, the transfer
 * is always the last statement of its block. Pure nodes are evaluated into temporaries right
 * before the statement that reads them, inside a {@code do} block that closes before the
 * statement's continuation, so nesting follows branches, not chain length.
 */
final class LuaGenerator {

    /** Unit locals beyond this live in a table ({@code o.x}): Lua allows 200 locals per function. */
    private static final int MAX_LOCALS = 120;
    private static final Set<String> RESERVED = Set.of("ui", "script", "dbg", "dbgv", "G", "F", "H", "V", "o",
        "text", "math", "string", "table", "utf8", "coroutine", "tostring", "tonumber", "ipairs", "pairs", "next",
        "select", "type", "error", "assert", "pcall", "xpcall", "require", "print", "rawget", "rawset", "rawequal",
        "rawlen", "setmetatable", "getmetatable", "load", "ev", "args", "dt", "value", "state", "_ENV", "_G");

    private final GraphPlan plan;
    private final boolean debug;
    private final String sha;
    private final String inputs;
    private final LuaWriter w = new LuaWriter(RESERVED);
    private final Set<String> helpers = new LinkedHashSet<>();
    private final Map<String, String> vars = new HashMap<>();

    private Body body;
    private Unit unit;
    private final Map<String, String> outs = new HashMap<>();
    private final Map<String, String> blocks = new HashMap<>();
    private final Map<String, String> params = new HashMap<>();

    LuaGenerator(GraphPlan plan, boolean debug, String sha, String inputs) {
        this.plan = plan;
        this.debug = debug;
        this.sha = sha;
        this.inputs = inputs;
    }

    String generate() {
        boolean tableVars = plan.graph.variables().size() > MAX_LOCALS;
        for (UiGraph.GraphVariable v : plan.graph.variables()) {
            vars.put(v.name(), tableVars ? LuaText.index("V", v.name()) : w.fresh("v_" + v.name()));
        }
        if (tableVars) {
            w.line("local V = {}");
        }
        for (UiGraph.GraphVariable v : plan.graph.variables()) {
            w.line((tableVars ? "" : "local ") + vars.get(v.name()) + " = " + LuaText.literal(initial(v)));
        }
        functions();
        converters();
        List<Node> events = new ArrayList<>();
        for (Unit u : plan.events.units) {
            events.add(u.entry);
            handler(u);
        }
        hooks(events);
        return header() + w;
    }

    private String header() {
        StringBuilder h = new StringBuilder();
        h.append("-- ").append(GraphCompiler.COMPILER).append(' ').append(GraphCompiler.VERSION).append(": graphs/")
            .append(plan.graph.id()).append(".graph.json, source sha256 ").append(sha)
            .append(debug ? " (debug build)" : "").append('\n');
        h.append("-- Generated from the behavior graph, which stays canonical: never edit this chunk. \"-- @node\"\n");
        h.append("-- lines map the code below them to graph nodes (errors, traces, breakpoints).\n");
        h.append(GraphInputs.headerLine(inputs)).append('\n');
        h.append("local ui, script, dbg, dbgv = ...\n");
        h.append("local G, F, H = {}, {}, {}\n");
        for (String name : helpers) {
            h.append(Helpers.SOURCES.get(name)).append('\n');
        }
        return h.toString();
    }

    private static UiValue initial(UiGraph.GraphVariable v) {
        if (v.defaultValue() != null && !(v.defaultValue() instanceof UiValue.Null)) {
            return v.defaultValue();
        }
        return switch (v.type()) {
            case BOOL -> UiValue.FALSE;
            case INT, NUMBER -> UiValue.of(0);
            case STRING -> UiValue.of("");
            case LIST -> new UiValue.Arr(List.of());
            case OBJECT -> UiValue.Obj.EMPTY;
            default -> UiValue.NULL;
        };
    }

    // ── functions and converters ────────────────────────────────────────────

    private String fnName(String id) {
        return LuaText.index("F", id);
    }

    private void functions() {
        for (Body b : plan.functions.values()) {
            if (b.units.isEmpty()) {
                continue;
            }
            begin(b, b.units.getFirst());
            List<String> names = new ArrayList<>();
            for (PortSpec p : CallKinds.ports(b.function.inputs(), false)) {
                String n = w.fresh("a_" + p.name());
                params.put(p.name(), n);
                names.add(n);
            }
            w.blank();
            w.marker(loc(unit.entry), unit.entry.node.kind());
            w.open(fnName(b.fnId()) + " = function(" + String.join(", ", names) + ")");
            if (plan.fnInfo.get(b.fnId()).exec) {
                unitBody();
            } else {
                for (PortSpec p : unit.entry.ports.dataOutputs()) {
                    outs.put(GraphPlan.key(unit.entry.id(), p.name()), params.get(p.name()));
                }
                Node ret = b.nodes.values().stream().filter(n -> n.role == Role.RETURN).findFirst().orElseThrow();
                chain(ret.id(), true);
            }
            w.close("end");
            w.unmark();
        }
    }

    private void converters() {
        for (FnInfo f : plan.fnInfo.values()) {
            if (f.converter && !plan.functions.get(f.fn.id()).units.isEmpty()) {
                PortType t = f.resultType();
                String result = t == PortType.ANY ? "any" : t.wire() + "?";
                w.blank();
                w.comment("Pure one-in-one-out function: also a binding converter.");
                w.line("ui.converter(" + LuaText.quote(f.fn.id()) + ", { result = " + LuaText.quote(result)
                    + ", to = " + fnName(f.fn.id()) + " })");
            }
        }
    }

    // ── events ──────────────────────────────────────────────────────────────

    private String handlerName(Node n) {
        return LuaText.index("H", n.id());
    }

    private void handler(Unit u) {
        begin(plan.events, u);
        NodeKind.EventSpec ev = u.entry.kind.event();
        w.blank();
        w.marker(loc(u.entry), u.entry.node.kind());
        w.open(handlerName(u.entry) + " = function(" + ev.params() + ")");
        unitBody();
        w.close("end");
        w.unmark();
    }

    private void hooks(List<Node> events) {
        // Custom events attach at load, so a raise from any on_open (code-behind runs first) reaches them.
        List<Node> custom = events.stream().filter(e -> EventKinds.CUSTOM.equals(e.node.kind())).toList();
        if (!custom.isEmpty()) {
            w.blank();
            for (Node e : custom) {
                w.marker(loc(e), e.node.kind());
                w.line(e.kind.event().register().apply(new Emitter(plan.events, e), handlerName(e)));
            }
            w.unmark();
        }
        w.blank();
        w.open("function G.on_open()");
        for (Node e : events) {
            if (e.kind.event().trigger() == NodeKind.Trigger.HANDLER && !custom.contains(e)) {
                w.marker(loc(e), e.node.kind());
                w.line(e.kind.event().register().apply(new Emitter(plan.events, e), handlerName(e)));
            }
        }
        for (Node e : events) {
            if (e.kind.event().trigger() == NodeKind.Trigger.OPEN) {
                w.marker(loc(e), e.node.kind());
                w.line("ui.async(" + handlerName(e) + ")");
            }
        }
        w.unmark();
        w.close("end");
        hook(events, NodeKind.Trigger.UPDATE, "function G.update(dt)", "(dt)");
        hook(events, NodeKind.Trigger.CLOSE, "function G.on_close()", "()");
        w.blank();
        w.line("return G");
    }

    private void hook(List<Node> events, NodeKind.Trigger trigger, String head, String call) {
        List<Node> matching = events.stream().filter(e -> e.kind.event().trigger() == trigger).toList();
        if (matching.isEmpty()) {
            return;
        }
        w.blank();
        w.open(head);
        for (Node e : matching) {
            w.marker(loc(e), e.node.kind());
            w.line(handlerName(e) + call);
        }
        w.unmark();
        w.close("end");
    }

    // ── units ───────────────────────────────────────────────────────────────

    private void begin(Body b, Unit u) {
        body = b;
        unit = u;
        outs.clear();
        blocks.clear();
        params.clear();
    }

    private static String loc(Body b, Node n) {
        return b.function == null ? n.id() : "fn:" + b.fnId() + "/" + n.id();
    }

    private String loc(Node n) {
        return loc(body, n);
    }

    /** Declarations, event outputs, join/loop functions, then the entry's chain. */
    private void unitBody() {
        Node entry = unit.entry;
        List<String[]> eventOuts = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        int count = 0;
        for (String id : unit.nodes) {
            Node n = body.nodes.get(id);
            for (PortSpec p : n.ports.dataOutputs()) {
                if (body.used(id, p.name()) && n.role != Role.ENTRY) {
                    count++;
                }
            }
        }
        boolean table = count + unit.blocks.size() > MAX_LOCALS;
        Set<String> fields = new java.util.HashSet<>();
        if (table) {
            w.line("local o = {}");
        }
        for (String id : unit.nodes) {
            Node n = body.nodes.get(id);
            for (PortSpec p : n.ports.dataOutputs()) {
                if (!body.used(id, p.name())) {
                    continue;
                }
                if (n.role == Role.ENTRY) {
                    outs.put(GraphPlan.key(id, p.name()), params.get(p.name()));
                    continue;
                }
                String name = table ? field(fields, id + "_" + p.name()) : w.fresh("o_" + id + "_" + p.name());
                outs.put(GraphPlan.key(id, p.name()), name);
                if (n == entry && n.role == Role.EVENT) {
                    eventOuts.add(new String[]{p.name(), name});
                } else if (!table) {
                    declared.add(name);
                }
            }
        }
        Emitter entryEmit = new Emitter(body, entry);
        for (String[] o : eventOuts) {
            String expr = entry.kind.event().output().apply(entryEmit, o[0]);
            w.line((table ? "" : "local ") + o[1] + " = " + expr);
            if (debug) {
                w.line("dbgv(" + LuaText.quote(loc(entry)) + ", " + LuaText.quote(o[0]) + ", " + o[1] + ")");
            }
        }
        if (debug) {
            w.line("dbg(" + LuaText.quote(loc(entry)) + ")");
        }
        if (!declared.isEmpty()) {
            w.line("local " + String.join(", ", declared));
        }
        if (!unit.blocks.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String id : unit.blocks) {
                String n = table ? field(fields, "b_" + id) : w.fresh("b_" + id);
                blocks.put(id, n);
                names.add(n);
            }
            if (!table) {
                w.line("local " + String.join(", ", names));
            }
            for (String id : unit.blocks) {
                w.marker(loc(body.nodes.get(id)), body.nodes.get(id).node.kind());
                w.open(blocks.get(id) + " = function()");
                chain(id, true);
                w.close("end");
                w.marker(loc(entry), entry.node.kind());
            }
        }
        follow(entry, PortSpec.THEN);
    }

    /** A unique {@code o.<name>} field of the unit table (long ids may sanitize alike). */
    private static String field(Set<String> used, String base) {
        String b = LuaText.sanitize(base);
        String n = b;
        for (int i = 2; !used.add(n); i++) {
            n = b + "_" + i;
        }
        return "o." + n;
    }

    /** The chain an exec output leads to (nothing when unlinked). */
    private void follow(Node n, String execPort) {
        GraphEdge e = body.exec(n.id(), execPort);
        if (e != null) {
            chain(e.toNode(), false);
        }
    }

    /** Writes a statement and what follows it; a join or loop target is entered by tail call. */
    private void chain(String id, boolean defining) {
        Node n = body.nodes.get(id);
        if (!defining && blocks.containsKey(id)) {
            w.line("return " + blocks.get(id) + "()");
            return;
        }
        w.marker(loc(n), n.node.kind());
        if (debug) {
            w.line("dbg(" + LuaText.quote(loc(n)) + ")");
        }
        Map<String, String> temps = new HashMap<>();
        List<Node> pure = pureDeps(n);
        boolean wrapped = !pure.isEmpty() && n.role != Role.RETURN;
        if (wrapped) {
            w.open("do");
        }
        for (Node p : pure) {
            Emitter pe = new Emitter(body, p, temps);
            w.marker(loc(p), p.node.kind());
            p.kind.pure().accept(pe);
            if (debug) {
                for (PortSpec o : p.ports.dataOutputs()) {
                    String t = temps.get(GraphPlan.key(p.id(), o.name()));
                    if (t != null) {
                        w.line("dbgv(" + LuaText.quote(loc(p)) + ", " + LuaText.quote(o.name()) + ", " + t + ")");
                    }
                }
            }
        }
        w.marker(loc(n), n.node.kind());
        if (n.role == Role.RETURN) {
            ret(n, temps);
            return;
        }
        Emitter se = new Emitter(body, n, temps);
        n.kind.statement().accept(se);
        w.marker(loc(n), n.node.kind());
        if (wrapped) {
            w.close("end");
        }
        if (!n.kind.ownsFlow()) {
            follow(n, PortSpec.THEN);
        }
    }

    /** {@code return [exit,] outputs...}: the exit index when the function has several exec outputs. */
    private void ret(Node n, Map<String, String> temps) {
        FnInfo info = plan.fnInfo.get(body.fnId());
        Emitter e = new Emitter(body, n, temps);
        List<String> values = new ArrayList<>();
        List<PortSpec> execs = CallKinds.execOutputs(info.fn);
        if (execs.size() > 1) {
            String chosen = KindContext.str(n.node, "output");
            int index = 0;
            for (int i = 0; i < execs.size(); i++) {
                if (execs.get(i).name().equals(chosen)) {
                    index = i;
                }
            }
            values.add(Integer.toString(index + 1));
        }
        for (PortSpec p : n.ports.dataInputs()) {
            values.add(e.in(p.name()));
        }
        w.line(values.isEmpty() ? "do return end" : "do return " + String.join(", ", values) + " end");
    }

    /** Pure nodes a node reads, transitively, dependencies first. */
    private List<Node> pureDeps(Node n) {
        LinkedHashMap<String, Node> out = new LinkedHashMap<>();
        collect(n, out);
        return new ArrayList<>(out.values());
    }

    private void collect(Node n, LinkedHashMap<String, Node> out) {
        for (PortSpec p : n.ports.dataInputs()) {
            GraphEdge e = body.in(n.id(), p.name());
            Node src = e == null ? null : body.nodes.get(e.fromNode());
            if (src != null && src.role == Role.PURE && !out.containsKey(src.id())) {
                collect(src, out);
                out.put(src.id(), src);
            }
        }
    }

    // ── the Emit a kind writes through ──────────────────────────────────────

    private final class Emitter implements Emit.Statement, Emit.Pure {
        private final Body b;
        private final Node n;
        private final Map<String, String> temps;

        Emitter(Body b, Node n) {
            this(b, n, new HashMap<>());
        }

        Emitter(Body b, Node n, Map<String, String> temps) {
            this.b = b;
            this.n = n;
            this.temps = temps;
        }

        @Override
        public UiGraph.GraphNode node() {
            return n.node;
        }

        @Override
        public KindContext context() {
            return b.ctx;
        }

        @Override
        public String prop(String name) {
            String v = KindContext.str(n.node, name);
            if (v != null) {
                return v;
            }
            PropSpec spec = n.kind.prop(name);
            return spec != null && spec.defaultValue() instanceof UiValue.Str s ? s.value() : null;
        }

        @Override
        public boolean bool(String name, boolean fallback) {
            PropSpec spec = n.kind.prop(name);
            boolean def = spec != null && spec.defaultValue() instanceof UiValue.Bool bv ? bv.value() : fallback;
            return KindContext.bool(n.node, name, def);
        }

        @Override
        public String in(String port) {
            PortSpec target = n.ports.input(port);
            GraphEdge e = b.in(n.id(), port);
            if (e != null && target != null) {
                Node src = b.nodes.get(e.fromNode());
                PortSpec from = src.ports.output(e.fromPort());
                String expr = src.role == Role.PURE ? temps.get(GraphPlan.key(src.id(), e.fromPort()))
                    : outs.get(GraphPlan.key(src.id(), e.fromPort()));
                if (expr == null) {
                    expr = "nil";
                }
                if (from != null && target.type().accepts(from.type()) == PortType.Assign.TO_STRING) {
                    expr = helper(Helpers.TEXT) + "(" + expr + ")";
                }
                return expr;
            }
            UiValue lit = n.node.inputs().get(port);
            if (lit != null) {
                return LuaText.literal(lit);
            }
            if (target != null && target.defaultValue() != null) {
                return LuaText.literal(target.defaultValue());
            }
            return "nil";
        }

        @Override
        public boolean has(String port) {
            return b.in(n.id(), port) != null || n.node.inputs().containsKey(port);
        }

        @Override
        public String lit(UiValue value) {
            return LuaText.literal(value);
        }

        @Override
        public String quote(String text) {
            return LuaText.quote(text == null ? "" : text);
        }

        @Override
        public String element(String prop) {
            return "ui.get(" + quote(prop(prop)) + ")";
        }

        @Override
        public String var(String variable) {
            String v = vars.get(variable);
            return v == null ? "nil" : v;
        }

        @Override
        public String function(String id) {
            return fnName(id);
        }

        @Override
        public void line(String lua) {
            w.line(lua);
        }

        @Override
        public String local(String hint) {
            return w.fresh(hint);
        }

        @Override
        public String helper(String name) {
            if (!Helpers.SOURCES.containsKey(name)) {
                throw new IllegalArgumentException("no helper " + name);
            }
            helpers.add(name);
            return name;
        }

        @Override
        public String out(String port) {
            return temps.computeIfAbsent(GraphPlan.key(n.id(), port), k -> w.fresh("t_" + n.id() + "_" + port));
        }

        @Override
        public void set(String port, String luaExpr) {
            String o = outs.get(GraphPlan.key(n.id(), port));
            if (o == null) {
                return;
            }
            w.line(o + " = " + luaExpr);
            if (debug) {
                w.line("dbgv(" + quote(loc(b, n)) + ", " + quote(port) + ", " + o + ")");
            }
        }

        @Override
        public boolean used(String port) {
            return outs.containsKey(GraphPlan.key(n.id(), port));
        }

        @Override
        public void open(String lua) {
            w.marker(loc(b, n), n.node.kind());
            w.open(lua);
        }

        @Override
        public void mid(String lua) {
            w.marker(loc(b, n), n.node.kind());
            w.mid(lua);
        }

        @Override
        public void close(String lua) {
            w.marker(loc(b, n), n.node.kind());
            w.close(lua);
        }

        @Override
        public void cont(String execPort) {
            follow(n, execPort);
            w.marker(loc(b, n), n.node.kind());
        }

        @Override
        public boolean connected(String execPort) {
            return b.exec(n.id(), execPort) != null;
        }

        @Override
        public String contFunction(String execPort) {
            GraphEdge e = b.exec(n.id(), execPort);
            if (e == null) {
                return null;
            }
            String name = w.fresh("run_" + n.id() + "_" + execPort);
            w.open("local function " + name + "()");
            chain(e.toNode(), false);
            w.marker(loc(b, n), n.node.kind());
            w.close("end");
            return name;
        }
    }
}
