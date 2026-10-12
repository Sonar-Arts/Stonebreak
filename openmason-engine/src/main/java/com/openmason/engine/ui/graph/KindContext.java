package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiValue;

import java.util.List;

/**
 * What a node kind sees while resolving ports and checking a node: the environment, the graph,
 * the body the node is in (the event graph, or one function) and its other graphs' functions.
 *
 * @param function the function whose body holds the node, or null for the event graph
 */
public record KindContext(GraphEnvironment env, UiGraph graph, UiGraph.GraphFunction function) {

    public UiGraph.GraphVariable variable(String name) {
        for (UiGraph.GraphVariable v : graph.variables()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return null;
    }

    public UiGraph.GraphFunction function(String id) {
        for (UiGraph.GraphFunction f : graph.functions()) {
            if (f.id().equals(id)) {
                return f;
            }
        }
        return null;
    }

    /** A string property of a node, or null when absent or not a string. */
    public static String str(UiGraph.GraphNode node, String prop) {
        return node.props().get(prop) instanceof UiValue.Str s ? s.value() : null;
    }

    public static boolean bool(UiGraph.GraphNode node, String prop, boolean fallback) {
        return node.props().get(prop) instanceof UiValue.Bool b ? b.value() : fallback;
    }

    /** A list-of-strings property ({@link PropSpec.Kind#NAMES}); bad entries are skipped. */
    public static List<String> names(UiGraph.GraphNode node, String prop) {
        if (!(node.props().get(prop) instanceof UiValue.Arr a)) {
            return List.of();
        }
        return a.items().stream().filter(v -> v instanceof UiValue.Str).map(v -> ((UiValue.Str) v).value()).toList();
    }
}
