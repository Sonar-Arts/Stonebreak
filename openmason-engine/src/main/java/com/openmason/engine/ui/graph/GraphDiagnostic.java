package com.openmason.engine.ui.graph;

import java.util.Objects;

/**
 * A graph problem found before compiling (#291), located at a node (and port) of the event
 * graph or of one function, so the editor can select it and the runtime can name it.
 *
 * @param function {@code ""} for the event graph, else the function id
 * @param node     node id, {@code ""} for a graph-level problem
 * @param port     port name, {@code ""} when the node itself is the problem
 */
public record GraphDiagnostic(Severity severity, Code code, String graph, String function, String node, String port,
                              String message) {

    public enum Severity { INFO, WARNING, ERROR }

    public enum Code {
        /** The node kind is not in the registry. */
        UNKNOWN_KIND,
        /** The node was saved by a newer node-kind version than this compiler knows. */
        NEWER_KIND_VERSION,
        /** A required property is missing or has the wrong shape. */
        INVALID_PROP,
        /** A data input that needs a value has no link, literal or default. */
        REQUIRED_INPUT,
        /** A literal does not fit its port. */
        INVALID_LITERAL,
        /** A link names a port the node does not have (any more). */
        BROKEN_LINK,
        /** A link joins ports whose types do not connect (or exec to data). */
        TYPE_MISMATCH,
        /** A data input or an exec output has more than one link. */
        DUPLICATE_LINK,
        /** A link runs from an input or into an output. */
        WRONG_DIRECTION,
        /** A target element does not exist in the document. */
        MISSING_ELEMENT,
        /** The target is not a component instance, or the signal is not declared. */
        UNKNOWN_SIGNAL,
        UNKNOWN_VARIABLE,
        UNKNOWN_FUNCTION,
        UNKNOWN_LUA_FUNCTION,
        UNKNOWN_CLIP,
        /** A data cycle among pure nodes, an exec loop without a wait, or a recursive function. */
        SYNC_CYCLE,
        /** A wait or an awaiting call where nothing may wait (update, close, pure functions). */
        LATENT_IN_SYNC,
        /** A value read from a node that does not run before this one in the same event. */
        OUT_OF_SCOPE,
        /** An exec node no event reaches. */
        UNREACHABLE,
        /** A function body without its entry or return node, or with several. */
        FUNCTION_SHAPE,
        /** Two converters (pure one-in-one-out functions) or two graphs claim one name. */
        DUPLICATE_NAME
    }

    public GraphDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        graph = graph == null ? "" : graph;
        function = function == null ? "" : function;
        node = node == null ? "" : node;
        port = port == null ? "" : port;
        Objects.requireNonNull(message, "message");
    }

    /** {@code behaviors#count}, {@code behaviors#fn:log_click/print}, {@code behaviors#count.value}. */
    public String location() {
        StringBuilder sb = new StringBuilder(graph);
        if (!function.isEmpty() || !node.isEmpty()) {
            sb.append('#');
        }
        if (!function.isEmpty()) {
            sb.append("fn:").append(function);
            if (!node.isEmpty()) {
                sb.append('/');
            }
        }
        sb.append(node);
        if (!port.isEmpty()) {
            sb.append('.').append(port);
        }
        return sb.toString();
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " " + code + " " + location() + ": " + message;
    }
}
