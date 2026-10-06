package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiValue;

/**
 * What a node kind uses to write its Lua (#291). The compiler owns naming, indentation, source
 * markers and the evaluation of inputs; a kind only states what its node does. Every value a
 * kind interpolates goes through {@link #in}, {@link #lit} or {@link #quote}, so generated code
 * never splices raw document text.
 */
public interface Emit {

    UiGraph.GraphNode node();

    KindContext context();

    /** A string property, or its spec default, or null. */
    String prop(String name);

    boolean bool(String name, boolean fallback);

    /** Lua expression for a data input: a link's value, the node's literal, the default, or {@code nil}. */
    String in(String port);

    /** Whether a data input has a link or a literal (an optional input may be left out). */
    boolean has(String port);

    /** A Lua literal for a value. */
    String lit(UiValue value);

    /** A quoted Lua string. */
    String quote(String text);

    /** {@code ui.get("path")} for the element named by an element property. */
    String element(String prop);

    /** The Lua lvalue of a graph variable. */
    String var(String variable);

    /** The Lua name of a graph function. */
    String function(String id);

    /** One statement line at the current indentation. */
    void line(String lua);

    /** A fresh local name with a readable hint. */
    String local(String hint);

    /** The Lua name of a chunk-level helper ({@link Helpers}), emitted once when first used. */
    String helper(String name);

    /** For pure nodes: the temporary holding one output (define it with {@code local}). */
    interface Pure extends Emit {
        String out(String port);
    }

    /** For statement nodes. */
    interface Statement extends Emit {

        /** Assigns an output that a later node reads; no-op when nothing reads it. */
        void set(String port, String luaExpr);

        /** Whether anything reads this data output. */
        boolean used(String port);

        /** Opens a block: {@code if x then}, {@code for ... do}. */
        void open(String lua);

        /** {@code else} / {@code elseif ... then} between blocks. */
        void mid(String lua);

        /** Closes a block: {@code end}. */
        void close(String lua);

        /**
         * Writes the chain that follows an exec output here. A kind that calls this (branch,
         * sequence, loop, multi-exit call) owns all of its continuations; the transfer is always
         * the last statement of its block. Kinds that don't let the compiler continue {@code then}.
         */
        void cont(String execPort);

        /** Whether an exec output has a link. */
        boolean connected(String execPort);

        /**
         * Writes {@code code} with the exec output's chain wrapped in a local function, so a
         * transfer inside it returns to this point (sequence outputs, loop bodies). Returns the
         * function's name; null when the output has no link.
         */
        String contFunction(String execPort);
    }
}
