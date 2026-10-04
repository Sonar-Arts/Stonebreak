package com.openmason.engine.format.omui;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code graphs/<id>.graph.json}: a behavior graph, the canonical source that #291 compiles
 * to Lua. The format stores structure only; node-kind semantics, port typing and
 * synchronous-cycle rules belong to the compiler. Ordering is canonical: nodes by id, edges by
 * endpoint, variables by name, functions by id.
 *
 * @param variables graph-local variables
 * @param nodes     event-graph nodes
 * @param edges     event-graph connections
 * @param functions reusable functions, each with its own node set
 */
public record UiGraph(String id, List<GraphVariable> variables, List<GraphNode> nodes, List<GraphEdge> edges,
                      List<GraphFunction> functions, Map<String, UiValue> unknown) {

    public UiGraph {
        Objects.requireNonNull(id, "id");
        variables = Canon.sortedBy(variables, GraphVariable::name);
        nodes = Canon.sortedBy(nodes, GraphNode::id);
        edges = Canon.sortedBy(edges, GraphEdge.ORDER);
        functions = Canon.sortedBy(functions, GraphFunction::id);
        unknown = Canon.unknown(unknown);
    }

    /**
     * @param kind        namespaced node kind ({@code ui:event.click}, {@code lua:call})
     * @param kindVersion node-kind version, independent of the schema
     * @param x           editor canvas position (part of the source; affects no semantics)
     * @param inputs      literal values of unconnected input ports
     * @param props       kind-specific settings (called function, variable name, ...)
     */
    public record GraphNode(String id, String kind, int kindVersion, double x, double y,
                            Map<String, UiValue> inputs, Map<String, UiValue> props, Map<String, UiValue> unknown) {
        public GraphNode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            x = Canon.num(x);
            y = Canon.num(y);
            inputs = Canon.values(inputs);
            props = Canon.values(props);
            unknown = Canon.unknown(unknown);
        }
    }

    /** A connection from an output port to an input port (exec or data). */
    public record GraphEdge(String fromNode, String fromPort, String toNode, String toPort,
                            Map<String, UiValue> unknown) {
        static final Comparator<GraphEdge> ORDER = Comparator
                .comparing(GraphEdge::fromNode, UiValue.KEY_ORDER)
                .thenComparing(GraphEdge::fromPort, UiValue.KEY_ORDER)
                .thenComparing(GraphEdge::toNode, UiValue.KEY_ORDER)
                .thenComparing(GraphEdge::toPort, UiValue.KEY_ORDER);

        public GraphEdge {
            Objects.requireNonNull(fromNode, "fromNode");
            Objects.requireNonNull(fromPort, "fromPort");
            Objects.requireNonNull(toNode, "toNode");
            Objects.requireNonNull(toPort, "toPort");
            unknown = Canon.unknown(unknown);
        }
    }

    public record GraphVariable(String name, ValueType type, UiValue defaultValue, Map<String, UiValue> unknown) {
        public GraphVariable {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            defaultValue = Canon.value(defaultValue);
            unknown = Canon.unknown(unknown);
        }
    }

    /** A function signature port; {@code type} is a {@link ValueType} wire name or {@code exec}. */
    public record GraphPort(String name, String type, Map<String, UiValue> unknown) {
        public static final String EXEC = "exec";

        public GraphPort {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            unknown = Canon.unknown(unknown);
        }
    }

    /** Inputs and outputs keep declaration order (they are a signature). */
    public record GraphFunction(String id, List<GraphPort> inputs, List<GraphPort> outputs,
                                List<GraphNode> nodes, List<GraphEdge> edges, Map<String, UiValue> unknown) {
        public GraphFunction {
            Objects.requireNonNull(id, "id");
            inputs = Canon.list(inputs);
            outputs = Canon.list(outputs);
            nodes = Canon.sortedBy(nodes, GraphNode::id);
            edges = Canon.sortedBy(edges, GraphEdge.ORDER);
            unknown = Canon.unknown(unknown);
        }
    }
}
