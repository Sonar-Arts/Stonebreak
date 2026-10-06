package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;

import java.util.List;

import static com.openmason.engine.ui.graph.Ports.in;
import static com.openmason.engine.ui.graph.Ports.out;

/**
 * Element reads and writes on a target picked by stable node-id path, plus the animation
 * requests that go through the host sampler. Writes follow the same ownership and widget checks
 * as code-behind writes (#289, #292).
 */
final class ElementKinds {

    static final String TWEEN = "ui:anim.tween";
    static final String PLAY = "ui:anim.play";
    static final List<String> EASINGS = List.of("linear", "ease-in", "ease-out", "ease-in-out", "step");
    static final List<String> FILLS = List.of("hold", "release");

    private ElementKinds() {
    }

    private static PropSpec target() {
        return PropSpec.required("target", PropSpec.Kind.ELEMENT, "the element");
    }

    static List<NodeKind> all() {
        return List.of(
            element("ui:element.set-text", "Set Text", "Sets the text property.",
                in("text", PortType.STRING, "", "the new text"),
                e -> e.element("target") + ":setText(" + e.in("text") + ")"),
            element("ui:element.set-visible", "Set Visible", "Shows (display flex) or hides (display none) the element.",
                in("visible", PortType.BOOL, true, ""),
                e -> e.element("target") + ":style(\"display\", " + e.in("visible") + " and \"flex\" or \"none\")"),
            element("ui:element.set-enabled", "Set Enabled", "Enables or disables the element and its subtree.",
                in("enabled", PortType.BOOL, true, ""),
                e -> e.element("target") + ":setEnabled(" + e.in("enabled") + ")"),
            NodeKind.builder("ui:element.set-class", "Set Class", "Elements")
                .doc("Adds (on) or removes (off) a class.")
                .prop(target())
                .prop(PropSpec.required("class", PropSpec.Kind.IDENT, "class name"))
                .ports(Ports.statement(in("on", PortType.BOOL, true, "")))
                .statement(e -> e.line(e.element("target") + ":toggleClass(" + e.quote(e.prop("class")) + ", "
                    + e.in("on") + ")"))
                .build(),
            NodeKind.builder("ui:element.set-style", "Set Style", "Elements")
                .doc("Sets an inline style property; an empty value clears it.")
                .prop(target())
                .prop(PropSpec.required("property", PropSpec.Kind.STYLE_PROPERTY, "style property (opacity, color, ...)"))
                .ports(Ports.statement(PortSpec.optional("value", PortType.ANY, "the value; nil clears")))
                .statement(e -> e.line(e.element("target") + ":style(" + e.quote(e.prop("property")) + ", "
                    + e.in("value") + ")"))
                .build(),
            NodeKind.builder("ui:element.set-prop", "Set Property", "Elements")
                .doc("Sets a widget property (checked against the widget); an empty value clears it.")
                .prop(target())
                .prop(PropSpec.required("property", PropSpec.Kind.WIDGET_PROPERTY, "widget property"))
                .ports(Ports.statement(PortSpec.optional("value", PortType.ANY, "the value; nil clears")))
                .statement(e -> e.line(e.element("target") + ":set(" + e.quote(e.prop("property")) + ", "
                    + e.in("value") + ")"))
                .build(),
            NodeKind.builder("ui:element.focus", "Focus", "Navigation")
                .doc("Moves keyboard and controller focus to the element.")
                .prop(target())
                .ports(Ports.statement())
                .statement(e -> e.line("ui.focus(" + e.element("target") + ")"))
                .build(),
            NodeKind.builder("ui:element.get-prop", "Get Property", "Elements")
                .prop(target())
                .prop(PropSpec.required("property", PropSpec.Kind.WIDGET_PROPERTY, "widget property"))
                .ports(NodePorts.builder().out(out("value", PortType.ANY, "")).build())
                .pure(e -> e.line("local " + e.out("value") + " = " + e.element("target") + ":prop("
                    + e.quote(e.prop("property")) + ")"))
                .build(),
            NodeKind.builder("ui:element.has-class", "Has Class", "Elements")
                .prop(target())
                .prop(PropSpec.required("class", PropSpec.Kind.IDENT, "class name"))
                .ports(NodePorts.builder().out(out("result", PortType.BOOL, "")).build())
                .pure(e -> e.line("local " + e.out("result") + " = " + e.element("target") + ":hasClass("
                    + e.quote(e.prop("class")) + ")"))
                .build(),
            NodeKind.builder(TWEEN, "Tween", "Animation")
                .doc("Animates one style property to a value through the host sampler. With 'wait' the chain"
                    + " continues when the tween completes.")
                .prop(target())
                .prop(PropSpec.required("property", PropSpec.Kind.STYLE_PROPERTY, "style property (opacity, translate-y, color, ...)"))
                .prop(PropSpec.choice("easing", "linear", EASINGS, "easing curve"))
                .prop(PropSpec.optional("wait", PropSpec.Kind.BOOL, UiValue.TRUE, "continue after it completes"))
                .ports(Ports.statement(PortSpec.optional("value", PortType.ANY, "target value"),
                    in("duration", PortType.NUMBER, 0.25, "seconds"), in("delay", PortType.NUMBER, 0, "seconds")))
                .latent((ctx, n) -> KindContext.bool(n, "wait", true))
                .statement(e -> {
                    String call = "ui.tween(" + e.element("target") + ", { [" + e.quote(e.prop("property")) + "] = "
                        + e.in("value") + " }, " + e.in("duration") + ", " + e.quote(e.prop("easing")) + ", { delay = "
                        + e.in("delay") + " })";
                    e.line(e.bool("wait", true) ? "ui.await(" + call + ")" : call);
                })
                .build(),
            NodeKind.builder(PLAY, "Play Clip", "Animation")
                .doc("Plays a timeline clip of this document. With 'wait' the chain continues when it ends. Blend"
                    + " cross-fades from what is shown; clock 'game' freezes with gameplay.")
                .prop(PropSpec.required("clip", PropSpec.Kind.CLIP, "clip id"))
                .prop(PropSpec.choice("loop", "clip", List.of("clip", "once", "loop", "ping-pong"), "clip = the clip's own mode"))
                .prop(PropSpec.optional("wait", PropSpec.Kind.BOOL, UiValue.FALSE, "continue after it ends"))
                .prop(PropSpec.optional("clock", PropSpec.Kind.IDENT, UiValue.of("ui"), "ui, game or a host clock"))
                .prop(PropSpec.choice("fill", "hold", FILLS, "what the end leaves: hold the values or release them"))
                .ports(Ports.statement(in("speed", PortType.NUMBER, 1, "playback speed"),
                    in("blend", PortType.NUMBER, 0, "seconds to cross-fade from what is shown")))
                .latent((ctx, n) -> KindContext.bool(n, "wait", false))
                .statement(e -> {
                    String loop = e.prop("loop");
                    String clock = e.prop("clock");
                    String fill = e.prop("fill");
                    String blend = e.in("blend");
                    // Options at their defaults are left out, so older graphs compile to the same Lua.
                    String opts = "{ speed = " + e.in("speed")
                        + (loop == null || "clip".equals(loop) ? "" : ", loop = " + e.quote(loop))
                        + (clock == null || clock.isEmpty() || "ui".equals(clock) ? "" : ", clock = " + e.quote(clock))
                        + (fill == null || "hold".equals(fill) ? "" : ", fill = " + e.quote(fill))
                        + ("0".equals(blend) ? "" : ", blend = " + blend) + " }";
                    String call = "ui.play(" + e.quote(e.prop("clip")) + ", " + opts + ")";
                    e.line(e.bool("wait", false) ? "ui.await(" + call + ")" : call);
                })
                .build(),
            NodeKind.builder("ui:anim.stop", "Stop Clip", "Animation")
                .doc("Stops this script's playbacks of a clip: hold what is shown, jump to the end, or release to"
                    + " the cascade.")
                .prop(PropSpec.required("clip", PropSpec.Kind.CLIP, "clip id"))
                .prop(PropSpec.choice("how", "hold", List.of("hold", "end", "release"), "what stopping leaves"))
                .ports(Ports.statement())
                .statement(e -> e.line("ui.stop(" + e.quote(e.prop("clip")) + ", " + e.quote(e.prop("how")) + ")"))
                .build(),
            NodeKind.builder("ui:anim.set-speed", "Set Clip Speed", "Animation")
                .doc("Changes the playback rate of a clip's playbacks from now on; 0 pauses.")
                .prop(PropSpec.required("clip", PropSpec.Kind.CLIP, "clip id"))
                .ports(Ports.statement(in("speed", PortType.NUMBER, 1, "playback rate")))
                .statement(e -> e.line("ui.speed(" + e.quote(e.prop("clip")) + ", " + e.in("speed") + ")"))
                .build(),
            NodeKind.builder("ui:anim.seek", "Seek Clip", "Animation")
                .doc("Moves a clip's playbacks to a time; events in between do not fire.")
                .prop(PropSpec.required("clip", PropSpec.Kind.CLIP, "clip id"))
                .ports(Ports.statement(in("time", PortType.NUMBER, 0, "seconds")))
                .statement(e -> e.line("ui.seek(" + e.quote(e.prop("clip")) + ", " + e.in("time") + ")"))
                .build(),
            NodeKind.builder("ui:anim.set-state", "Set UI State", "Animation")
                .doc("Moves a UI state machine of this document to a state; its transition and state clips play."
                    + " With 'wait' the chain continues once the state is reached.")
                .prop(PropSpec.required("machine", PropSpec.Kind.STATE_MACHINE, "state machine id"))
                .prop(PropSpec.required("state", PropSpec.Kind.MACHINE_STATE, "target state"))
                .prop(PropSpec.optional("wait", PropSpec.Kind.BOOL, UiValue.FALSE, "continue once it is reached"))
                .ports(Ports.statement())
                .latent((ctx, n) -> KindContext.bool(n, "wait", false))
                .statement(e -> {
                    String call = "ui.setState(" + e.quote(e.prop("machine")) + ", " + e.quote(e.prop("state")) + ")";
                    e.line(e.bool("wait", false) ? "ui.await(" + call + ")" : call);
                })
                .build(),
            NodeKind.builder("ui:anim.machine-state", "UI State", "Animation")
                .doc("The current state of a UI state machine of this document.")
                .prop(PropSpec.required("machine", PropSpec.Kind.STATE_MACHINE, "state machine id"))
                .ports(NodePorts.builder().out(out("state", PortType.STRING, "")).build())
                .pure(e -> e.line("local " + e.out("state") + " = ui.machineState(" + e.quote(e.prop("machine")) + ")"))
                .build(),
            NodeKind.builder("ui:anim.release", "Release Animation", "Animation")
                .doc("Hands an animated style property back to the cascade, through its declared transition.")
                .prop(target())
                .prop(PropSpec.required("property", PropSpec.Kind.STYLE_PROPERTY, "style property"))
                .ports(Ports.statement())
                .statement(e -> e.line("ui.release(" + e.element("target") + ", " + e.quote(e.prop("property")) + ")"))
                .build());
    }

    private static NodeKind element(String id, String title, String doc, PortSpec value,
                                    java.util.function.Function<Emit, String> code) {
        return NodeKind.builder(id, title, "Elements")
            .doc(doc)
            .prop(target())
            .ports(Ports.statement(value))
            .statement(e -> e.line(code.apply(e)))
            .build();
    }
}
