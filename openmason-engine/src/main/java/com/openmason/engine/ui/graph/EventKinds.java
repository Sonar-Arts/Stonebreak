package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;
import com.openmason.engine.ui.graph.NodeKind.EventSpec;
import com.openmason.engine.ui.graph.NodeKind.Trigger;

import java.util.List;

import static com.openmason.engine.ui.graph.Ports.THEN;
import static com.openmason.engine.ui.graph.Ports.out;

/**
 * Event nodes: where a graph's chains start. Each compiles to a handler; UI events, signals,
 * custom events and data watches are attached in {@code on_open}, so their handlers are tasks
 * the runtime cancels on close and reload, exactly like code-behind handlers.
 */
final class EventKinds {

    static final String OPEN = "ui:event.open";
    static final String CLOSE = "ui:event.close";
    static final String UPDATE = "ui:event.update";
    static final String CLICK = "ui:event.click";
    static final String ELEMENT = "ui:event.element";
    static final String SIGNAL = "ui:event.signal";
    static final String CUSTOM = "ui:event.custom";
    static final String WATCH = "ui:event.watch";

    private EventKinds() {
    }

    static List<NodeKind> all() {
        return List.of(
            NodeKind.builder(OPEN, "On Open", "Events")
                .doc("Runs once after the screen opens and its handlers are attached. May wait.")
                .ports(NodePorts.builder().out(THEN).build())
                .event(new EventSpec(Trigger.OPEN, "", null, null))
                .build(),
            NodeKind.builder(CLOSE, "On Close", "Events")
                .doc("Runs before the screen closes. Synchronous: nothing after it may wait.")
                .ports(NodePorts.builder().out(THEN).build())
                .event(new EventSpec(Trigger.CLOSE, "", null, null))
                .build(),
            NodeKind.builder(UPDATE, "On Update", "Events")
                .doc("Runs every frame with the frame time. Synchronous: nothing after it may wait.")
                .ports(NodePorts.builder().out(THEN).out(out("dt", PortType.NUMBER, "seconds since the last frame")).build())
                .event(new EventSpec(Trigger.UPDATE, "dt", null, (e, port) -> "dt"))
                .build(),
            NodeKind.builder(CLICK, "On Click", "Events")
                .doc("Runs when the target element is clicked (pointer or submit).")
                .prop(PropSpec.required("target", PropSpec.Kind.ELEMENT, "the clicked element"))
                .ports(NodePorts.builder().out(THEN)
                    .out(out("x", PortType.NUMBER, "pointer x, logical px")).out(out("y", PortType.NUMBER, "pointer y, logical px"))
                    .build())
                .event(new EventSpec(Trigger.HANDLER, "ev",
                    (e, h) -> e.element("target") + ":on(\"click\", " + h + ")",
                    (e, port) -> "ev." + port))
                .build(),
            NodeKind.builder(ELEMENT, "On UI Event", "Events")
                .doc("Runs on any UI event of the target element (pointer-enter, change, commit, key-down, ...).")
                .prop(PropSpec.required("target", PropSpec.Kind.ELEMENT, "the element"))
                .prop(new PropSpec("event", PropSpec.Kind.EVENT_NAME, true, UiValue.of("click"), List.of(), "UI event name"))
                .ports(NodePorts.builder().out(THEN)
                    .out(out("value", PortType.ANY, "change/commit value"))
                    .out(out("text", PortType.STRING, "text input"))
                    .out(out("key", PortType.INT, "key code"))
                    .out(out("x", PortType.NUMBER, "pointer x")).out(out("y", PortType.NUMBER, "pointer y"))
                    .build())
                .event(new EventSpec(Trigger.HANDLER, "ev",
                    (e, h) -> e.element("target") + ":on(" + e.quote(e.prop("event")) + ", " + h + ")",
                    (e, port) -> "ev." + port))
                .build(),
            NodeKind.builder(SIGNAL, "On Signal", "Events")
                .doc("Runs when a component instance raises one of its declared signals (ui.emit in its Lua or graph).")
                .prop(PropSpec.required("target", PropSpec.Kind.ELEMENT, "a component instance"))
                .prop(PropSpec.required("signal", PropSpec.Kind.SIGNAL, "a signal its component declares"))
                .ports((ctx, n) -> NodePorts.builder().out(THEN).out(Ports.signalArgs(signalOf(ctx, n))).build())
                .event(new EventSpec(Trigger.HANDLER, "args",
                    (e, h) -> e.element("target") + ":on(" + e.quote(e.prop("signal")) + ", " + h + ")",
                    (e, port) -> LuaText.index("args", port)))
                .check(EventKinds::checkSignal)
                .build(),
            NodeKind.builder(CUSTOM, "On Custom Event", "Events")
                .doc("Runs when this screen (its Lua code-behind or a graph) raises the named event: ui.raise(name, args).")
                .prop(PropSpec.required("name", PropSpec.Kind.IDENT, "event name"))
                .prop(PropSpec.optional("params", PropSpec.Kind.PARAMS, null, "typed arguments"))
                .ports((ctx, n) -> NodePorts.builder().out(THEN).out(Ports.params(n, "params")).build())
                .event(new EventSpec(Trigger.HANDLER, "args",
                    (e, h) -> "ui.on(" + e.quote(e.prop("name")) + ", " + h + ")",
                    (e, port) -> LuaText.index("args", port)))
                .build(),
            NodeKind.builder(WATCH, "On Data Change", "Events")
                .doc("Runs at the next frame whenever host data at the path changes (coalesced).")
                .prop(PropSpec.required("path", PropSpec.Kind.DATA_PATH, "absolute host data path"))
                .ports(NodePorts.builder().out(THEN)
                    .out(out("value", PortType.ANY, "the new value")).out(out("state", PortType.STRING, "ready, loading, missing or failed"))
                    .build())
                .event(new EventSpec(Trigger.HANDLER, "value, state",
                    (e, h) -> "ui.watch(" + e.quote(e.prop("path")) + ", " + h + ")",
                    (e, port) -> port))
                .build());
    }

    static UiDocument.EventDef signalOf(KindContext ctx, GraphNode n) {
        GraphEnvironment.ElementInfo el = ctx.env().element(KindContext.str(n, "target"));
        return el == null ? null : Ports.signal(el.contract(), KindContext.str(n, "signal"));
    }

    private static void checkSignal(KindContext ctx, GraphNode n, NodeKind.Checks out) {
        GraphEnvironment.ElementInfo el = ctx.env().element(KindContext.str(n, "target"));
        if (el == null) {
            return; // reported as MISSING_ELEMENT
        }
        if (!el.isInstance()) {
            out.error(Code.UNKNOWN_SIGNAL, "", "target " + el.path() + " is a " + el.type() + ", not a component instance");
        } else if (el.contract() == null) {
            out.error(Code.UNKNOWN_SIGNAL, "", "the component of " + el.path() + " cannot be resolved");
        } else if (signalOf(ctx, n) == null) {
            out.error(Code.UNKNOWN_SIGNAL, "", "the component of " + el.path() + " declares no signal '"
                + KindContext.str(n, "signal") + "' (signals: "
                + el.contract().events().stream().map(UiDocument.EventDef::name).toList() + ")");
        }
    }
}
