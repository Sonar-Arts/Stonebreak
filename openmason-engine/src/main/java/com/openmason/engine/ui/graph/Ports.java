package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Port and property helpers shared by the node-kind tables. */
final class Ports {

    static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final PortSpec EXEC = PortSpec.exec(PortSpec.EXEC_IN);
    static final PortSpec THEN = PortSpec.exec(PortSpec.THEN);

    private Ports() {
    }

    static PortSpec in(String name, PortType type, String doc) {
        return PortSpec.data(name, type, doc);
    }

    static PortSpec in(String name, PortType type, Object def, String doc) {
        return PortSpec.data(name, type, value(def), doc);
    }

    static PortSpec out(String name, PortType type, String doc) {
        return PortSpec.data(name, type, doc);
    }

    static UiValue value(Object v) {
        return switch (v) {
            case null -> UiValue.NULL;
            case UiValue u -> u;
            case Boolean b -> UiValue.of(b);
            case Number n -> UiValue.of(n.doubleValue());
            default -> UiValue.of(v.toString());
        };
    }

    /** A statement: exec in, {@code then} out, plus data ports. */
    static NodePorts statement(PortSpec... data) {
        NodePorts.Builder b = NodePorts.builder().in(EXEC).out(THEN);
        for (PortSpec p : data) {
            b.in(p);
        }
        return b.build();
    }

    /** {@code [{name, type}]} parameters of a custom event; malformed rows are skipped. */
    static List<PortSpec> params(GraphNode node, String prop) {
        List<PortSpec> out = new ArrayList<>();
        if (!(node.props().get(prop) instanceof UiValue.Arr a)) {
            return out;
        }
        for (UiValue item : a.items()) {
            if (item instanceof UiValue.Obj o && o.get("name") instanceof UiValue.Str n && IDENT.matcher(n.value()).matches()) {
                PortType t = o.get("type") instanceof UiValue.Str s && PortType.fromWire(s.value()) != null
                    && !PortType.fromWire(s.value()).isExec() ? PortType.fromWire(s.value()) : PortType.ANY;
                out.add(new PortSpec(n.value(), t, null, true, ""));
            }
        }
        return out;
    }

    /** Ports for the arguments of a component signal. */
    static List<PortSpec> signalArgs(UiDocument.EventDef ev) {
        List<PortSpec> out = new ArrayList<>();
        if (ev != null) {
            for (UiDocument.Param p : ev.args()) {
                out.add(new PortSpec(p.name(), PortType.of(p.type()), p.defaultValue(), true, ""));
            }
        }
        return out;
    }

    static UiDocument.EventDef signal(UiDocument.ComponentDef def, String name) {
        if (def == null || name == null) {
            return null;
        }
        for (UiDocument.EventDef e : def.events()) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** The port type of a graph variable, or ANY when it does not exist. */
    static PortType variableType(KindContext ctx, GraphNode node) {
        var v = ctx.variable(KindContext.str(node, "variable"));
        return v == null ? PortType.ANY : PortType.of(v.type());
    }

    static boolean numeric(ValueType t) {
        return t == ValueType.INT || t == ValueType.NUMBER;
    }

    /** {@code {a = x, b = y}} from named inputs. */
    static String table(Emit e, List<PortSpec> ports) {
        if (ports.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < ports.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(LuaText.field(ports.get(i).name())).append(" = ").append(e.in(ports.get(i).name()));
        }
        return sb.append(" }").toString();
    }
}
