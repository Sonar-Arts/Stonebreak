package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Debugging compiled graphs in the preview (#291): debug builds call {@code dbg(node)} before
 * every statement and {@code dbgv(node, port, value)} for every value they produce. This records
 * the hits (active-node highlighting), the last value of every port (watches) and pauses a
 * task at a breakpoint until {@link #resume}. Release builds (the game, derived caches) carry
 * no trace calls at all.
 *
 * <p>Node locations are source-map keys: {@code count}, {@code fn:double/ret}. A breakpoint
 * only pauses inside a task (handlers, open events); in {@code update}, {@code on_close} and
 * converters it is recorded as a hit and the code runs on. Closing, reloading and leaving the
 * world cancel paused tasks like any other await.
 */
public final class GraphDebugger {

    /** The last time a node ran. */
    public record Hit(String graph, String node, double time, long count) {
    }

    /** A task waiting at a breakpoint. */
    public record Paused(long token, String graph, String node, String context) {
    }

    private record Waiting(Paused paused, ScriptContext ctx) {
    }

    private final UiScriptRuntime rt;
    private final Set<String> breakpoints = new LinkedHashSet<>();
    private final Map<String, Hit> hits = new LinkedHashMap<>();
    private final Map<String, UiValue> values = new LinkedHashMap<>();
    private final Map<Long, Waiting> waiting = new LinkedHashMap<>();
    private final List<String> trace = new ArrayList<>();
    private int traceLimit = 200;

    GraphDebugger(UiScriptRuntime rt) {
        this.rt = rt;
    }

    private static String key(String graph, String node) {
        return graph + "#" + node;
    }

    public void setBreakpoint(String graph, String node, boolean on) {
        if (on) {
            breakpoints.add(key(graph, node));
        } else {
            breakpoints.remove(key(graph, node));
        }
    }

    public boolean hasBreakpoint(String graph, String node) {
        return breakpoints.contains(key(graph, node));
    }

    public Set<String> breakpoints() {
        return Collections.unmodifiableSet(breakpoints);
    }

    /** Last hit per {@code graph#node}. */
    public Map<String, Hit> hits() {
        return Collections.unmodifiableMap(hits);
    }

    public Hit hit(String graph, String node) {
        return hits.get(key(graph, node));
    }

    /** Last value per {@code graph#node.port}. */
    public Map<String, UiValue> values() {
        return Collections.unmodifiableMap(values);
    }

    public UiValue value(String graph, String node, String port) {
        return values.get(key(graph, node) + "." + port);
    }

    /** Recent hits as {@code graph#node}, oldest first (bounded). */
    public List<String> trace() {
        return List.copyOf(trace);
    }

    public void setTraceLimit(int limit) {
        traceLimit = Math.max(1, limit);
    }

    public List<Paused> paused() {
        return waiting.values().stream().map(Waiting::paused).toList();
    }

    /** Continues one paused task at the next frame. */
    public boolean resume(long token) {
        Waiting w = waiting.remove(token);
        if (w == null) {
            return false;
        }
        rt.settleDebug(w.ctx, token);
        return true;
    }

    public void resumeAll() {
        for (Long token : List.copyOf(waiting.keySet())) {
            resume(token);
        }
    }

    public void clearHistory() {
        hits.clear();
        values.clear();
        trace.clear();
    }

    /** A trace call: records the hit; returns a token to await when a breakpoint pauses this task. */
    Long onHit(ScriptContext ctx, String graph, String node, boolean inTask) {
        String k = key(graph, node);
        Hit old = hits.get(k);
        hits.put(k, new Hit(graph, node, rt.time(), old == null ? 1 : old.count() + 1));
        trace.add(k);
        while (trace.size() > traceLimit) {
            trace.removeFirst();
        }
        if (!inTask || !breakpoints.contains(k)) {
            return null;
        }
        long token = rt.token();
        waiting.put(token, new Waiting(new Paused(token, graph, node, ctx.toString()), ctx));
        rt.log(ctx, "info", "paused at breakpoint " + k);
        return token;
    }

    void onValue(String graph, String node, String port, UiValue value) {
        values.put(key(graph, node) + "." + port, value == null ? UiValue.NULL : value);
    }

    /** Paused tasks of a cancelled context (close, reload, world change) are gone. */
    void cancel(ScriptContext ctx) {
        waiting.values().removeIf(w -> ctx == null || w.ctx == ctx);
    }
}
