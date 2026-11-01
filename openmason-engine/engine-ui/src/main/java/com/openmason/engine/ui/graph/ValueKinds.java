package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.openmason.engine.ui.graph.Ports.in;
import static com.openmason.engine.ui.graph.Ports.out;

/**
 * Pure values (comparisons, logic, math, text formatting, data reads, variable reads) and local
 * state. Pure nodes have no exec ports; they are evaluated where a statement reads them, once
 * per statement, into temporaries.
 */
final class ValueKinds {

    static final String VAR_GET = "ui:variable.get";
    static final String VAR_SET = "ui:variable.set";
    static final String VAR_INCREMENT = "ui:variable.increment";
    static final String FORMAT = "ui:format";

    private static final List<String> COMPARE = List.of("eq", "ne", "lt", "le", "gt", "ge");
    private static final List<String> MATH = List.of("add", "sub", "mul", "div", "mod", "min", "max", "pow");

    private ValueKinds() {
    }

    static List<NodeKind> all() {
        return List.of(
            NodeKind.builder("ui:compare", "Compare", "Logic")
                .doc("Compares a and b: eq, ne, lt, le, gt, ge.")
                .prop(PropSpec.choice("op", "eq", COMPARE, "comparison"))
                .ports(NodePorts.builder().in(in("a", PortType.ANY, null, "left")).in(in("b", PortType.ANY, null, "right"))
                    .out(out("result", PortType.BOOL, "")).build())
                .pure(e -> e.line("local " + e.out("result") + " = (" + e.in("a") + " " + compareOp(e.prop("op")) + " "
                    + e.in("b") + ")"))
                .build(),
            binaryBool("ui:logic.and", "And", "and"),
            binaryBool("ui:logic.or", "Or", "or"),
            NodeKind.builder("ui:logic.not", "Not", "Logic")
                .ports(NodePorts.builder().in(in("value", PortType.BOOL, false, "")).out(out("result", PortType.BOOL, "")).build())
                .pure(e -> e.line("local " + e.out("result") + " = not " + e.in("value")))
                .build(),
            NodeKind.builder("ui:select", "Select", "Logic")
                .doc("'a' when the condition holds, else 'b'.")
                .ports(NodePorts.builder().in(in("condition", PortType.BOOL, false, "")).in(in("a", PortType.ANY, null, ""))
                    .in(in("b", PortType.ANY, null, "")).out(out("value", PortType.ANY, "")).build())
                .pure(e -> {
                    String v = e.out("value");
                    e.line("local " + v);
                    e.line("if " + e.in("condition") + " then " + v + " = " + e.in("a") + " else " + v + " = " + e.in("b") + " end");
                })
                .build(),
            NodeKind.builder("ui:is-set", "Is Set", "Logic")
                .doc("True when the value is not nil.")
                .ports(NodePorts.builder().in(PortSpec.optional("value", PortType.ANY, "")).out(out("result", PortType.BOOL, "")).build())
                .pure(e -> e.line("local " + e.out("result") + " = " + e.in("value") + " ~= nil"))
                .build(),
            NodeKind.builder("ui:math", "Math", "Math")
                .doc("a op b: add, sub, mul, div, mod, min, max, pow. With type int, division is floor division.")
                .prop(PropSpec.choice("op", "add", MATH, "operation"))
                .prop(PropSpec.choice("type", "number", List.of("number", "int"), "operand and result type"))
                .ports((ctx, n) -> {
                    PortType t = "int".equals(KindContext.str(n, "type")) ? PortType.INT : PortType.NUMBER;
                    return NodePorts.builder().in(in("a", t, 0, "")).in(in("b", t, 0, "")).out(out("result", t, "")).build();
                })
                .check((ctx, n, out) -> {
                    String op = KindContext.str(n, "op");
                    if ("int".equals(KindContext.str(n, "type")) && "pow".equals(op)) {
                        out.error(Code.INVALID_PROP, "", "pow yields a number; use type number");
                    }
                })
                .pure(e -> e.line("local " + e.out("result") + " = " + math(e)))
                .build(),
            NodeKind.builder("ui:math.round", "Round", "Math")
                .doc("Number to integer: round (half up), floor or ceil.")
                .prop(PropSpec.choice("mode", "round", List.of("round", "floor", "ceil"), ""))
                .ports(NodePorts.builder().in(in("value", PortType.NUMBER, 0, "")).out(out("result", PortType.INT, "")).build())
                .pure(e -> {
                    String v = e.in("value");
                    String expr = switch (String.valueOf(e.prop("mode"))) {
                        case "floor" -> "math.floor(" + v + ")";
                        case "ceil" -> "math.ceil(" + v + ")";
                        default -> "math.floor(" + v + " + 0.5)";
                    };
                    e.line("local " + e.out("result") + " = " + expr);
                })
                .build(),
            NodeKind.builder(FORMAT, "Format Text", "Text")
                .doc("Fills {name} placeholders of the template; {{ and }} are literal braces.")
                .prop(PropSpec.required("template", PropSpec.Kind.TEMPLATE, "text with {name} placeholders"))
                .ports((ctx, n) -> {
                    NodePorts.Builder b = NodePorts.builder();
                    for (String p : placeholders(KindContext.str(n, "template"))) {
                        b.in(PortSpec.optional(p, PortType.ANY, "placeholder {" + p + "}"));
                    }
                    return b.out(out("text", PortType.STRING, "")).build();
                })
                .check((ctx, n, out) -> {
                    String problem = templateProblem(KindContext.str(n, "template"));
                    if (problem != null) {
                        out.error(Code.INVALID_PROP, "", problem);
                    }
                })
                .pure(ValueKinds::format)
                .build(),
            NodeKind.builder("ui:to-text", "To Text", "Text")
                .doc("Any value as text (integral numbers without a decimal point).")
                .ports(NodePorts.builder().in(PortSpec.optional("value", PortType.ANY, "")).out(out("text", PortType.STRING, "")).build())
                .pure(e -> e.line("local " + e.out("text") + " = " + Helpers.text(e) + "(" + e.in("value") + ")"))
                .build(),
            NodeKind.builder("ui:object.get", "Get Field", "Data")
                .doc("A field of an object (an action result, a signal argument); nil when absent.")
                .prop(PropSpec.required("field", PropSpec.Kind.IDENT, "field name"))
                .ports(NodePorts.builder().in(PortSpec.optional("object", PortType.OBJECT, ""))
                    .out(out("value", PortType.ANY, "")).build())
                .pure(e -> {
                    String o = e.local("obj");
                    e.line("local " + o + " = " + e.in("object"));
                    e.line("local " + e.out("value") + " = " + o + " and " + LuaText.index(o, e.prop("field")));
                })
                .build(),
            NodeKind.builder("ui:data.read", "Read Data", "Data")
                .doc("Host data at an absolute path, with its state (ready, loading, missing, failed).")
                .prop(PropSpec.required("path", PropSpec.Kind.DATA_PATH, "absolute host data path"))
                .ports(NodePorts.builder().out(out("value", PortType.ANY, "")).out(out("state", PortType.STRING, "")).build())
                .pure(e -> e.line("local " + e.out("value") + ", " + e.out("state") + " = ui.read(" + e.quote(e.prop("path")) + ")"))
                .build(),
            NodeKind.builder(VAR_GET, "Get Variable", "State")
                .prop(PropSpec.required("variable", PropSpec.Kind.VARIABLE, "graph variable"))
                .ports((ctx, n) -> NodePorts.builder().out(out("value", Ports.variableType(ctx, n), "")).build())
                .pure(e -> e.line("local " + e.out("value") + " = " + e.var(e.prop("variable"))))
                .build(),
            NodeKind.builder(VAR_SET, "Set Variable", "State")
                .prop(PropSpec.required("variable", PropSpec.Kind.VARIABLE, "graph variable"))
                .ports((ctx, n) -> Ports.statement(PortSpec.optional("value", Ports.variableType(ctx, n), "new value")))
                .statement(e -> e.line(e.var(e.prop("variable")) + " = " + e.in("value")))
                .build(),
            NodeKind.builder(VAR_INCREMENT, "Increment Variable", "State")
                .doc("Adds 'by' (default 1) to a numeric variable.")
                .prop(PropSpec.required("variable", PropSpec.Kind.VARIABLE, "an int or number variable"))
                .ports((ctx, n) -> {
                    PortType t = Ports.variableType(ctx, n);
                    return Ports.statement(in("by", t == PortType.ANY ? PortType.NUMBER : t, 1, "amount"));
                })
                .check((ctx, n, out) -> {
                    var v = ctx.variable(KindContext.str(n, "variable"));
                    if (v != null && !Ports.numeric(v.type())) {
                        out.error(Code.INVALID_PROP, "", "variable " + v.name() + " is " + v.type().wire() + ", not int or number");
                    }
                })
                .statement(e -> {
                    String v = e.var(e.prop("variable"));
                    e.line(v + " = (" + v + " or 0) + " + e.in("by"));
                })
                .build());
    }

    private static NodeKind binaryBool(String id, String title, String op) {
        return NodeKind.builder(id, title, "Logic")
            .ports(NodePorts.builder().in(in("a", PortType.BOOL, false, "")).in(in("b", PortType.BOOL, false, ""))
                .out(out("result", PortType.BOOL, "")).build())
            .pure(e -> e.line("local " + e.out("result") + " = (" + e.in("a") + " " + op + " " + e.in("b") + ") == true"))
            .build();
    }

    private static String compareOp(String op) {
        return switch (op == null ? "eq" : op) {
            case "ne" -> "~=";
            case "lt" -> "<";
            case "le" -> "<=";
            case "gt" -> ">";
            case "ge" -> ">=";
            default -> "==";
        };
    }

    private static String math(Emit e) {
        String a = e.in("a");
        String b = e.in("b");
        boolean integer = "int".equals(e.prop("type"));
        return switch (String.valueOf(e.prop("op"))) {
            case "sub" -> a + " - " + b;
            case "mul" -> a + " * " + b;
            case "div" -> a + (integer ? " // " : " / ") + b;
            case "mod" -> a + " % " + b;
            case "min" -> "math.min(" + a + ", " + b + ")";
            case "max" -> "math.max(" + a + ", " + b + ")";
            case "pow" -> a + " ^ " + b;
            default -> a + " + " + b;
        };
    }

    // ── format templates ────────────────────────────────────────────────────

    /** Placeholder names of a template, in first-use order. */
    static List<String> placeholders(String template) {
        Set<String> out = new LinkedHashSet<>();
        for (Object part : parse(template)) {
            if (part instanceof Hole h) {
                out.add(h.name);
            }
        }
        return new ArrayList<>(out);
    }

    private record Hole(String name) {
    }

    /** Literal strings and {@link Hole}s; null when malformed. */
    private static List<Object> parse(String t) {
        List<Object> parts = new ArrayList<>();
        if (t == null) {
            return parts;
        }
        StringBuilder lit = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{' && i + 1 < t.length() && t.charAt(i + 1) == '{') {
                lit.append('{');
                i++;
            } else if (c == '}' && i + 1 < t.length() && t.charAt(i + 1) == '}') {
                lit.append('}');
                i++;
            } else if (c == '{') {
                int end = t.indexOf('}', i);
                String name = end < 0 ? "" : t.substring(i + 1, end);
                if (!Ports.IDENT.matcher(name).matches()) {
                    return null;
                }
                if (!lit.isEmpty()) {
                    parts.add(lit.toString());
                    lit.setLength(0);
                }
                parts.add(new Hole(name));
                i = end;
            } else if (c == '}') {
                return null;
            } else {
                lit.append(c);
            }
        }
        if (!lit.isEmpty()) {
            parts.add(lit.toString());
        }
        return parts;
    }

    static String templateProblem(String t) {
        if (t == null) {
            return null;
        }
        return parse(t) == null ? "malformed template: placeholders are {name}; write {{ and }} for braces" : null;
    }

    private static void format(Emit.Pure e) {
        List<Object> parts = parse(e.prop("template"));
        StringBuilder sb = new StringBuilder();
        if (parts == null || parts.isEmpty()) {
            sb.append("\"\"");
        } else {
            for (Object p : parts) {
                if (!sb.isEmpty()) {
                    sb.append(" .. ");
                }
                sb.append(p instanceof Hole h ? Helpers.text(e) + "(" + e.in(h.name) + ")" : e.quote((String) p));
            }
        }
        e.line("local " + e.out("text") + " = " + sb);
    }
}
