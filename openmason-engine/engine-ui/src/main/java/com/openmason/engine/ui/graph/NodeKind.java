package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph.GraphNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * A versioned node kind (#291): its properties, how its ports resolve, the checks beyond port
 * typing, and the Lua it compiles to. Kinds are data plus small lambdas, registered in
 * {@link NodeKinds}; a node's role follows from its resolved ports:
 * <ul>
 *   <li><b>event</b> ({@link #event()} set): an entry point that starts a task;</li>
 *   <li><b>statement</b>: has an exec input; runs in order and may wait if {@link #latent} says so;</li>
 *   <li><b>pure</b>: no exec ports; evaluated where a statement reads it, once per statement.</li>
 * </ul>
 * Function entry and return nodes are statements the compiler treats specially.
 */
public final class NodeKind {

    /** Validation beyond port typing (props, targets); report through {@link Checks}. */
    @FunctionalInterface
    public interface Check {
        void check(KindContext ctx, GraphNode node, Checks out);
    }

    /** A sink for a node's problems. */
    public interface Checks {
        void error(GraphDiagnostic.Code code, String port, String message);

        void warning(GraphDiagnostic.Code code, String port, String message);
    }

    /** When an event's chain runs. */
    public enum Trigger {
        /** After the screen opens (handlers are attached first): a task. */
        OPEN,
        /** Before the screen closes: synchronous, may not wait. */
        CLOSE,
        /** Every frame: synchronous, may not wait. */
        UPDATE,
        /** A handler attached in {@code on_open} (UI events, signals, custom events, watches): a task. */
        HANDLER
    }

    /**
     * @param params   the handler's Lua parameter list ({@code ev}, {@code dt}, {@code value, state})
     * @param register for {@link Trigger#HANDLER}: the line that attaches the named handler
     * @param output   the Lua expression of an output port in terms of {@code params}
     */
    public record EventSpec(Trigger trigger, String params, BiFunction<Emit, String, String> register,
                            BiFunction<Emit, String, String> output) {
        public boolean mayWait() {
            return trigger == Trigger.OPEN || trigger == Trigger.HANDLER;
        }
    }

    private final String id;
    private final int version;
    private final String title;
    private final String category;
    private final String doc;
    private final List<PropSpec> props;
    private final BiFunction<KindContext, GraphNode, NodePorts> ports;
    private final EventSpec event;
    private final BiPredicate<KindContext, GraphNode> latent;
    private final Check check;
    private final Consumer<Emit.Statement> statement;
    private final boolean ownsFlow;
    private final Consumer<Emit.Pure> pure;

    private NodeKind(Builder b) {
        this.id = b.id;
        this.version = b.version;
        this.title = b.title;
        this.category = b.category;
        this.doc = b.doc;
        this.props = List.copyOf(b.props);
        this.ports = Objects.requireNonNull(b.ports, "ports of " + b.id);
        this.event = b.event;
        this.latent = b.latent;
        this.check = b.check;
        this.statement = b.statement;
        this.ownsFlow = b.ownsFlow;
        this.pure = b.pure;
    }

    public String id() {
        return id;
    }

    /** The node-kind version this compiler writes and understands ({@code kindVersion}). */
    public int version() {
        return version;
    }

    public String title() {
        return title;
    }

    public String category() {
        return category;
    }

    public String doc() {
        return doc;
    }

    public List<PropSpec> props() {
        return props;
    }

    public PropSpec prop(String name) {
        for (PropSpec p : props) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** The node's ports given its properties; never null. */
    public NodePorts ports(KindContext ctx, GraphNode node) {
        NodePorts p = ports.apply(ctx, node);
        return p == null ? NodePorts.NONE : p;
    }

    public EventSpec event() {
        return event;
    }

    public boolean isEvent() {
        return event != null;
    }

    /** Whether running the node may wait ({@code ui.await}); only allowed inside tasks. */
    public boolean latent(KindContext ctx, GraphNode node) {
        return latent != null && latent.test(ctx, node);
    }

    public void check(KindContext ctx, GraphNode node, Checks out) {
        if (check != null) {
            check.check(ctx, node, out);
        }
    }

    Consumer<Emit.Statement> statement() {
        return statement;
    }

    /** True when the statement writes its own continuations ({@link Emit.Statement#cont}). */
    boolean ownsFlow() {
        return ownsFlow;
    }

    Consumer<Emit.Pure> pure() {
        return pure;
    }

    @Override
    public String toString() {
        return id + "@" + version;
    }

    public static Builder builder(String id, String title, String category) {
        return new Builder(id, title, category);
    }

    public static final class Builder {
        private final String id;
        private final String title;
        private final String category;
        private int version = 1;
        private String doc = "";
        private final List<PropSpec> props = new ArrayList<>();
        private BiFunction<KindContext, GraphNode, NodePorts> ports;
        private EventSpec event;
        private BiPredicate<KindContext, GraphNode> latent;
        private Check check;
        private Consumer<Emit.Statement> statement;
        private boolean ownsFlow;
        private Consumer<Emit.Pure> pure;

        private Builder(String id, String title, String category) {
            this.id = id;
            this.title = title;
            this.category = category;
        }

        public Builder version(int v) {
            version = v;
            return this;
        }

        public Builder doc(String d) {
            doc = d;
            return this;
        }

        public Builder prop(PropSpec p) {
            props.add(p);
            return this;
        }

        /** Fixed ports. */
        public Builder ports(NodePorts p) {
            ports = (c, n) -> p;
            return this;
        }

        /** Ports that depend on the node's properties or the environment. */
        public Builder ports(BiFunction<KindContext, GraphNode, NodePorts> p) {
            ports = p;
            return this;
        }

        public Builder event(EventSpec e) {
            event = e;
            return this;
        }

        public Builder latent(BiPredicate<KindContext, GraphNode> l) {
            latent = l;
            return this;
        }

        public Builder latent() {
            latent = (c, n) -> true;
            return this;
        }

        public Builder check(Check c) {
            check = c;
            return this;
        }

        /** A statement that the compiler continues through {@code then}. */
        public Builder statement(Consumer<Emit.Statement> s) {
            statement = s;
            ownsFlow = false;
            return this;
        }

        /** A statement that writes its own continuations (branches, loops, sequences). */
        public Builder flow(Consumer<Emit.Statement> s) {
            statement = s;
            ownsFlow = true;
            return this;
        }

        public Builder pure(Consumer<Emit.Pure> p) {
            pure = p;
            return this;
        }

        public NodeKind build() {
            return new NodeKind(this);
        }
    }
}
