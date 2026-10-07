package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;

import java.util.List;

import static com.openmason.engine.ui.graph.Ports.EXEC;
import static com.openmason.engine.ui.graph.Ports.in;
import static com.openmason.engine.ui.graph.Ports.out;

/** Execution flow: branches, sequences, loops and waits. A wait becomes a coroutine yield. */
final class FlowKinds {

    static final String BRANCH = "ui:flow.branch";
    static final String SEQUENCE = "ui:flow.sequence";
    static final String WAIT = "ui:flow.wait";
    static final String FOR_EACH = "ui:flow.for-each";
    static final int MAX_SEQUENCE = 16;

    private FlowKinds() {
    }

    static List<NodeKind> all() {
        return List.of(
            NodeKind.builder(BRANCH, "Branch", "Flow")
                .doc("Runs 'true' or 'false' depending on the condition.")
                .ports(NodePorts.builder().in(EXEC).in(in("condition", PortType.BOOL, false, "the test"))
                    .out(PortSpec.exec("true")).out(PortSpec.exec("false")).build())
                .flow(e -> {
                    e.open("if " + e.in("condition") + " then");
                    e.cont("true");
                    if (e.connected("false")) {
                        e.mid("else");
                        e.cont("false");
                    }
                    e.close("end");
                })
                .build(),
            NodeKind.builder(SEQUENCE, "Sequence", "Flow")
                .doc("Runs each output in order; a wait inside one output delays the next.")
                .prop(PropSpec.optional("count", PropSpec.Kind.INT, UiValue.of(2), "number of outputs (2-16)"))
                .ports((ctx, n) -> {
                    NodePorts.Builder b = NodePorts.builder().in(EXEC);
                    for (int i = 0; i < count(n); i++) {
                        b.out(PortSpec.exec("then" + i));
                    }
                    return b.build();
                })
                .check((ctx, n, out) -> {
                    if (n.props().get("count") instanceof UiValue.Num c && c.isIntegral() // non-integers: the INT check
                        && (c.value() < 2 || c.value() > MAX_SEQUENCE)) {
                        out.error(Code.INVALID_PROP, "", "count must be an integer from 2 to " + MAX_SEQUENCE);
                    }
                })
                .flow(e -> {
                    int n = count(e.node());
                    String[] fns = new String[n];
                    for (int i = 0; i < n; i++) {
                        fns[i] = e.contFunction("then" + i);
                    }
                    for (String f : fns) {
                        if (f != null) {
                            e.line(f + "()");
                        }
                    }
                })
                .build(),
            NodeKind.builder(WAIT, "Wait", "Flow")
                .doc("Waits the given UI time, then continues. Closing or reloading the screen ends the wait.")
                .ports(Ports.statement(in("seconds", PortType.NUMBER, 0.5, "delay in seconds")))
                .latent()
                .statement(e -> e.line("ui.await(ui.sleep(" + e.in("seconds") + "))"))
                .build(),
            NodeKind.builder(FOR_EACH, "For Each", "Flow")
                .doc("Runs 'body' once per list item, then 'completed'.")
                .ports(NodePorts.builder().in(EXEC).in(in("list", PortType.LIST, "the items"))
                    .out(PortSpec.exec("body")).out(PortSpec.exec("completed"))
                    .out(out("item", PortType.ANY, "the current item")).out(out("index", PortType.INT, "1-based index"))
                    .build())
                .flow(e -> {
                    String body = e.contFunction("body");
                    String i = e.local("i");
                    String item = e.local("item");
                    e.open("for " + i + ", " + item + " in ipairs(" + e.in("list") + " or {}) do");
                    e.set("item", item);
                    e.set("index", i);
                    if (body != null) {
                        e.line(body + "()");
                    }
                    e.close("end");
                    e.cont("completed");
                })
                .build());
    }

    static int count(com.openmason.engine.format.omui.UiGraph.GraphNode n) {
        if (n.props().get("count") instanceof UiValue.Num c && c.isIntegral()) {
            return (int) Math.clamp(c.value(), 2, MAX_SEQUENCE);
        }
        return 2;
    }
}
