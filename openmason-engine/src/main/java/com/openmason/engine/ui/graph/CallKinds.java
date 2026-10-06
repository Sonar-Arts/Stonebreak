package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Reusable logic: graph functions (entry, return, call) and calls to declared Lua functions.
 * A graph function with an exec input is a statement (several exec outputs make it a macro-like
 * multi-exit call); one without exec ports is pure. A Lua function becomes a node from its
 * LuaLS signature ({@link LuaSignatures}); both compile to plain Lua calls.
 */
final class CallKinds {

    static final String ENTRY = "ui:function.entry";
    static final String RETURN = "ui:function.return";
    static final String CALL = "ui:function.call";
    static final String LUA_CALL = "lua:call";

    private CallKinds() {
    }

    static List<NodeKind> all() {
        return List.of(
            NodeKind.builder(ENTRY, "Function Entry", "Functions")
                .doc("Where a function body starts; its outputs are the function's inputs.")
                .ports((ctx, n) -> entryPorts(ctx.function()))
                .build(),
            NodeKind.builder(RETURN, "Return", "Functions")
                .doc("Ends the function with its outputs; 'output' picks the exec output that fires at the call.")
                .prop(PropSpec.optional("output", PropSpec.Kind.TEXT, null, "exec output to fire (multi-exit functions)"))
                .ports((ctx, n) -> returnPorts(ctx.function()))
                .check((ctx, n, out) -> {
                    UiGraph.GraphFunction f = ctx.function();
                    String o = KindContext.str(n, "output");
                    if (f != null && o != null && execOutputs(f).stream().noneMatch(p -> p.name().equals(o))) {
                        out.error(Code.INVALID_PROP, "", "function " + f.id() + " has no exec output '" + o + "'");
                    } else if (f != null && o == null && execOutputs(f).size() > 1) {
                        out.error(Code.INVALID_PROP, "", "function " + f.id() + " has several exec outputs: set"
                            + " 'output' to the one this return fires");
                    }
                })
                .build(),
            NodeKind.builder(CALL, "Call Function", "Functions")
                .doc("Calls a function of this graph.")
                .prop(PropSpec.required("function", PropSpec.Kind.FUNCTION, "function id"))
                .ports((ctx, n) -> callPorts(ctx.function(KindContext.str(n, "function"))))
                .flow(CallKinds::emitCall)
                .pure(CallKinds::emitPureCall)
                .build(),
            NodeKind.builder(LUA_CALL, "Call Lua", "Functions")
                .doc("Calls an annotated Lua function of the code-behind or a declared module.")
                .prop(PropSpec.optional("module", PropSpec.Kind.MODULE, UiValue.of(""), "empty = the code-behind"))
                .prop(PropSpec.required("function", PropSpec.Kind.LUA_FUNCTION, "function name"))
                .prop(PropSpec.optional("pure", PropSpec.Kind.BOOL, UiValue.FALSE, "no exec pins: evaluated where read"))
                .ports((ctx, n) -> luaPorts(lua(ctx, n), KindContext.bool(n, "pure", false)))
                .latent((ctx, n) -> {
                    LuaFunction f = lua(ctx, n);
                    return f != null && f.async();
                })
                .check(CallKinds::checkLua)
                .statement(e -> luaCall(e, (Emit.Statement) e))
                .pure(e -> luaCall(e, null))
                .build());
    }

    // ── graph functions ─────────────────────────────────────────────────────

    static List<PortSpec> ports(List<UiGraph.GraphPort> ports, boolean exec) {
        List<PortSpec> out = new ArrayList<>();
        for (UiGraph.GraphPort p : ports) {
            PortType t = PortType.fromWire(p.type());
            if (t == null) {
                t = PortType.ANY;
            }
            if (t.isExec() == exec) {
                out.add(new PortSpec(p.name(), t, null, false, ""));
            }
        }
        return out;
    }

    static List<PortSpec> execOutputs(UiGraph.GraphFunction f) {
        return ports(f.outputs(), true);
    }

    /** A function is a statement when it has an exec input. */
    static boolean isExec(UiGraph.GraphFunction f) {
        return !ports(f.inputs(), true).isEmpty();
    }

    private static NodePorts entryPorts(UiGraph.GraphFunction f) {
        if (f == null) {
            return NodePorts.NONE;
        }
        NodePorts.Builder b = NodePorts.builder();
        if (isExec(f)) {
            b.out(Ports.THEN);
        }
        return b.out(ports(f.inputs(), false)).build();
    }

    private static NodePorts returnPorts(UiGraph.GraphFunction f) {
        if (f == null) {
            return NodePorts.NONE;
        }
        NodePorts.Builder b = NodePorts.builder();
        if (isExec(f)) {
            b.in(Ports.EXEC);
        }
        return b.in(ports(f.outputs(), false)).build();
    }

    private static NodePorts callPorts(UiGraph.GraphFunction f) {
        if (f == null) {
            return NodePorts.NONE;
        }
        NodePorts.Builder b = NodePorts.builder();
        if (isExec(f)) {
            b.in(Ports.EXEC);
        }
        b.in(ports(f.inputs(), false));
        b.out(execOutputs(f));
        return b.out(ports(f.outputs(), false)).build();
    }

    private static String args(Emit e, UiGraph.GraphFunction f) {
        StringJoiner j = new StringJoiner(", ");
        for (PortSpec p : ports(f.inputs(), false)) {
            j.add(e.in(p.name()));
        }
        return j.toString();
    }

    private static void emitCall(Emit.Statement e) {
        UiGraph.GraphFunction f = e.context().function(e.prop("function"));
        List<PortSpec> execs = execOutputs(f);
        List<PortSpec> outs = ports(f.outputs(), false);
        String call = e.function(f.id()) + "(" + args(e, f) + ")";
        List<String> lhs = new ArrayList<>();
        String exit = execs.size() > 1 ? e.local("exit") : null;
        if (exit != null) {
            lhs.add(exit);
        }
        for (PortSpec p : outs) {
            lhs.add(e.local(p.name()));
        }
        e.line(lhs.isEmpty() ? call : "local " + String.join(", ", lhs) + " = " + call);
        for (int i = 0; i < outs.size(); i++) {
            e.set(outs.get(i).name(), lhs.get(i + (exit != null ? 1 : 0)));
        }
        if (exit == null) {
            if (execs.size() == 1) {
                e.cont(execs.getFirst().name());
            }
            return;
        }
        boolean first = true;
        for (int i = 0; i < execs.size(); i++) {
            if (!e.connected(execs.get(i).name())) {
                continue;
            }
            String test = exit + " == " + (i + 1);
            if (first) {
                e.open("if " + test + " then");
                first = false;
            } else {
                e.mid("elseif " + test + " then");
            }
            e.cont(execs.get(i).name());
        }
        if (!first) {
            e.close("end");
        }
    }

    private static void emitPureCall(Emit.Pure e) {
        UiGraph.GraphFunction f = e.context().function(e.prop("function"));
        List<PortSpec> outs = ports(f.outputs(), false);
        String call = e.function(f.id()) + "(" + args(e, f) + ")";
        if (outs.isEmpty()) {
            e.line(call);
            return;
        }
        StringJoiner lhs = new StringJoiner(", ");
        outs.forEach(p -> lhs.add(e.out(p.name())));
        e.line("local " + lhs + " = " + call);
    }

    // ── Lua functions ───────────────────────────────────────────────────────

    static LuaFunction lua(KindContext ctx, GraphNode n) {
        String module = KindContext.str(n, "module");
        return ctx.env().luaFunction(module == null ? "" : module, KindContext.str(n, "function"));
    }

    private static NodePorts luaPorts(LuaFunction f, boolean pure) {
        if (f == null) {
            return NodePorts.NONE;
        }
        NodePorts.Builder b = NodePorts.builder();
        if (!pure) {
            b.in(Ports.EXEC).out(Ports.THEN);
        }
        return b.in(f.params()).out(f.returns()).build();
    }

    private static void checkLua(KindContext ctx, GraphNode n, NodeKind.Checks out) {
        String name = KindContext.str(n, "function");
        String module = KindContext.str(n, "module");
        if (name == null) {
            return;
        }
        LuaFunction f = lua(ctx, n);
        if (f == null) {
            out.error(Code.UNKNOWN_LUA_FUNCTION, "", "no annotated Lua function '" + name + "' in "
                + (module == null || module.isEmpty() ? "the code-behind" : "module " + module)
                + " (a callable function is exported and has a ---@ signature)");
        } else if (f.async() && KindContext.bool(n, "pure", false)) {
            out.error(Code.LATENT_IN_SYNC, "", name + " is ---@async: it may wait, so it cannot be pure");
        }
    }

    private static void luaCall(Emit e, Emit.Statement st) {
        LuaFunction f = lua(e.context(), e.node());
        StringJoiner args = new StringJoiner(", ");
        for (PortSpec p : f.params()) {
            args.add(e.in(p.name()));
        }
        String target;
        if (f.global()) {
            target = f.name();
        } else if (f.module().isEmpty()) {
            target = "script." + f.name();
        } else {
            target = "require(" + e.quote(f.module()) + ")." + f.name();
        }
        String call = target + "(" + args + ")";
        if (f.returns().isEmpty()) {
            e.line(call);
            return;
        }
        StringJoiner lhs = new StringJoiner(", ");
        List<String> names = new ArrayList<>();
        for (PortSpec p : f.returns()) {
            String n = st != null ? e.local(p.name()) : ((Emit.Pure) e).out(p.name());
            names.add(n);
            lhs.add(n);
        }
        e.line("local " + lhs + " = " + call);
        if (st != null) {
            for (int i = 0; i < names.size(); i++) {
                st.set(f.returns().get(i).name(), names.get(i));
            }
        }
    }
}
