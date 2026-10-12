package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.graph.Ports.in;
import static com.openmason.engine.ui.graph.Ports.out;

/**
 * Requests to the host: declared Java actions (#289), navigation, closing, sounds and the
 * console; and events between scripts and graphs: component signals and custom events.
 */
final class HostKinds {

    static final String INVOKE = "ui:action.invoke";
    static final String REQUEST = "ui:action.request";
    static final String EMIT = "ui:signal.emit";
    static final String RAISE = "ui:event.raise";

    private HostKinds() {
    }

    static List<NodeKind> all() {
        return List.of(
            NodeKind.builder(INVOKE, "Invoke Action", "Actions")
                .doc("Invokes a declared host action and waits for it: 'ok' with its result, or the error.")
                .prop(PropSpec.required("action", PropSpec.Kind.ACTION, "action id (ns:id)"))
                .prop(PropSpec.optional("args", PropSpec.Kind.NAMES, null, "argument names"))
                .ports((ctx, n) -> NodePorts.builder().in(Ports.EXEC).out(Ports.THEN).in(args(n))
                    .out(out("result", PortType.ANY, "the action's result"))
                    .out(out("ok", PortType.BOOL, "true when it succeeded"))
                    .out(out("error", PortType.STRING, "why it failed, else nil")).build())
                .latent()
                .check(HostKinds::checkArgs)
                .statement(e -> {
                    String r = e.local("result");
                    String err = e.local("err");
                    e.line("local " + r + ", " + err + " = ui.await(ui.action(" + e.quote(e.prop("action")) + ", "
                        + Ports.table(e, args(e.node())) + "))");
                    e.set("result", r);
                    e.set("ok", err + " == nil");
                    e.set("error", err);
                })
                .build(),
            NodeKind.builder(REQUEST, "Request Action", "Actions")
                .doc("Invokes a declared host action without waiting for it.")
                .prop(PropSpec.required("action", PropSpec.Kind.ACTION, "action id (ns:id)"))
                .prop(PropSpec.optional("args", PropSpec.Kind.NAMES, null, "argument names"))
                .ports((ctx, n) -> NodePorts.builder().in(Ports.EXEC).out(Ports.THEN).in(args(n)).build())
                .check(HostKinds::checkArgs)
                .statement(e -> e.line("ui.request(" + e.quote(e.prop("action")) + ", " + Ports.table(e, args(e.node())) + ")"))
                .build(),
            NodeKind.builder("ui:navigate", "Navigate", "Navigation")
                .doc("Asks the host to navigate to a screen.")
                .ports(Ports.statement(in("target", PortType.STRING, "", "screen id")))
                .statement(e -> e.line("ui.navigate(" + e.in("target") + ")"))
                .build(),
            NodeKind.builder("ui:screen.close", "Close Screen", "Navigation")
                .doc("Asks the host to close this screen.")
                .ports(Ports.statement())
                .statement(e -> e.line("ui.close()"))
                .build(),
            NodeKind.builder("ui:sound.play", "Play Sound", "Host")
                .ports(Ports.statement(in("sound", PortType.STRING, "", "sound id"), in("volume", PortType.NUMBER, 1, "")))
                .statement(e -> e.line("ui.sound(" + e.in("sound") + ", { volume = " + e.in("volume") + " })"))
                .build(),
            NodeKind.builder("ui:log", "Log", "Host")
                .doc("Writes to the script console.")
                .ports(Ports.statement(PortSpec.optional("message", PortType.ANY, "")))
                .statement(e -> e.line("ui.log(" + e.in("message") + ")"))
                .build(),
            NodeKind.builder(EMIT, "Emit Signal", "Events")
                .doc("Raises one of this component's declared signals (component graphs only).")
                .prop(PropSpec.required("signal", PropSpec.Kind.SIGNAL, "a signal this component declares"))
                .ports((ctx, n) -> NodePorts.builder().in(Ports.EXEC).out(Ports.THEN)
                    .in(Ports.signalArgs(Ports.signal(ctx.env().ownContract(), KindContext.str(n, "signal")))).build())
                .check((ctx, n, out) -> {
                    UiDocument.ComponentDef own = ctx.env().ownContract();
                    if (own == null) {
                        out.error(Code.UNKNOWN_SIGNAL, "", "only a component's graph can emit signals");
                    } else if (Ports.signal(own, KindContext.str(n, "signal")) == null) {
                        out.error(Code.UNKNOWN_SIGNAL, "", "this component declares no signal '"
                            + KindContext.str(n, "signal") + "' (signals: "
                            + own.events().stream().map(UiDocument.EventDef::name).toList() + ")");
                    }
                })
                .statement(e -> e.line("ui.emit(" + e.quote(e.prop("signal")) + ", " + Ports.table(e,
                    Ports.signalArgs(Ports.signal(e.context().env().ownContract(), e.prop("signal")))) + ")"))
                .build(),
            NodeKind.builder(RAISE, "Raise Custom Event", "Events")
                .doc("Raises a custom event of this screen; Lua (ui.on) and graph handlers run after the current one.")
                .prop(PropSpec.required("name", PropSpec.Kind.IDENT, "event name"))
                .prop(PropSpec.optional("params", PropSpec.Kind.PARAMS, null, "typed arguments"))
                .ports((ctx, n) -> NodePorts.builder().in(Ports.EXEC).out(Ports.THEN).in(Ports.params(n, "params")).build())
                .statement(e -> e.line("ui.raise(" + e.quote(e.prop("name")) + ", "
                    + Ports.table(e, Ports.params(e.node(), "params")) + ")"))
                .build());
    }

    /** Action arguments: one optional {@code any} input per name. */
    static List<PortSpec> args(GraphNode n) {
        List<PortSpec> out = new ArrayList<>();
        for (String name : KindContext.names(n, "args")) {
            if (Ports.IDENT.matcher(name).matches()) {
                out.add(PortSpec.optional(name, PortType.ANY, "argument " + name));
            }
        }
        return out;
    }

    private static void checkArgs(KindContext ctx, GraphNode n, NodeKind.Checks out) {
        String action = KindContext.str(n, "action");
        if (action != null && !action.matches("[a-z0-9_.-]{1,64}:[a-z0-9_.-]+(/[a-z0-9_.-]+)*")) {
            out.error(Code.INVALID_PROP, "", "action id '" + action + "' is not ns:id (stonebreak:screen.pause.resume)");
        }
        for (String name : KindContext.names(n, "args")) {
            if (!Ports.IDENT.matcher(name).matches() || name.equals(PortSpec.EXEC_IN)) {
                out.error(Code.INVALID_PROP, "", "argument name '" + name + "' is not an identifier");
            }
        }
    }
}
