package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiGraph.GraphPort;
import com.openmason.engine.format.omui.UiGraph.GraphVariable;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.runtime.UiDocs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Terse graphs for tests: {@code new GraphBuilder("g").node("a", "ui:event.click", "target", "b")
 * .link("a.then", "c.exec").build()}. Values go through {@link UiDocs#value}. Not a test class.
 */
public final class GraphBuilder {

    private final String id;
    private final List<GraphVariable> vars = new ArrayList<>();
    private final Body main = new Body();
    private final List<GraphFunction> functions = new ArrayList<>();
    private double x;

    public GraphBuilder(String id) {
        this.id = id;
    }

    /** A function body under construction. */
    public static final class Body {
        final List<GraphNode> nodes = new ArrayList<>();
        final List<GraphEdge> edges = new ArrayList<>();
        final List<GraphPort> inputs = new ArrayList<>();
        final List<GraphPort> outputs = new ArrayList<>();
        private double x;

        /** {@code node(id, kind, "prop", value, ..., "=port", literal, ...)}: "=" prefixes input literals. */
        public Body node(String id, String kind, Object... kv) {
            Map<String, UiValue> props = new LinkedHashMap<>();
            Map<String, UiValue> inputs = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                String k = (String) kv[i];
                if (k.startsWith("=")) {
                    inputs.put(k.substring(1), UiDocs.value(kv[i + 1]));
                } else {
                    props.put(k, UiDocs.value(kv[i + 1]));
                }
            }
            nodes.add(new GraphNode(id, kind, 1, x, 0, inputs, props, Map.of()));
            x += 200;
            return this;
        }

        /** {@code link("a.then", "b.exec")}. */
        public Body link(String from, String to) {
            String[] f = from.split("\\.", 2);
            String[] t = to.split("\\.", 2);
            edges.add(new GraphEdge(f[0], f[1], t[0], t[1], Map.of()));
            return this;
        }

        public Body in(String name, String type) {
            inputs.add(new GraphPort(name, type, Map.of()));
            return this;
        }

        public Body out(String name, String type) {
            outputs.add(new GraphPort(name, type, Map.of()));
            return this;
        }
    }

    public GraphBuilder var(String name, ValueType type, Object def) {
        vars.add(new GraphVariable(name, type, def == null ? null : UiDocs.value(def), Map.of()));
        return this;
    }

    public GraphBuilder node(String id, String kind, Object... kv) {
        main.node(id, kind, kv);
        return this;
    }

    public GraphBuilder link(String from, String to) {
        main.link(from, to);
        return this;
    }

    public GraphBuilder function(String id, java.util.function.Consumer<Body> body) {
        Body b = new Body();
        body.accept(b);
        functions.add(new GraphFunction(id, b.inputs, b.outputs, b.nodes, b.edges, Map.of()));
        return this;
    }

    public UiGraph build() {
        return new UiGraph(id, vars, main.nodes, main.edges, functions, Map.of());
    }

    /** {@code [{name, type}, ...]} for PARAMS properties: {@code params("score", "int")}. */
    public static UiValue params(String... nameType) {
        List<UiValue> items = new ArrayList<>();
        for (int i = 0; i < nameType.length; i += 2) {
            Map<String, UiValue> m = new LinkedHashMap<>();
            m.put("name", UiValue.of(nameType[i]));
            m.put("type", UiValue.of(nameType[i + 1]));
            items.add(new UiValue.Obj(m));
        }
        return new UiValue.Arr(items);
    }

    public static UiValue names(String... names) {
        List<UiValue> items = new ArrayList<>();
        for (String n : names) {
            items.add(UiValue.of(n));
        }
        return new UiValue.Arr(items);
    }
}
