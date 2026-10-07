package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a graph's generated Lua depends on besides the graph itself (#291): the signatures of
 * the Lua functions it calls and the contracts of the signals it handles or emits. Their
 * SHA-256 is written into the chunk header ({@code -- inputs sha256 ...}), so a derived cache
 * built against other signatures is recognised as stale and compiled again, even when the
 * graph file and the compiler version did not change.
 */
public final class GraphInputs {

    private static final Pattern HEADER = Pattern.compile("(?m)^-- inputs sha256 ([0-9a-f]{64})$");

    private GraphInputs() {
    }

    /** SHA-256 over the resolved Lua signatures and signal contracts the graph's nodes use. */
    public static String sha256(UiGraph graph, GraphEnvironment env) {
        List<String> lines = new ArrayList<>();
        collect("", graph.nodes(), env, lines);
        for (UiGraph.GraphFunction f : graph.functions()) {
            collect(f.id(), f.nodes(), env, lines);
        }
        return UiBytes.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** The inputs hash recorded in a generated chunk, or null for a chunk without one. */
    public static String recorded(String lua) {
        Matcher m = HEADER.matcher(lua == null ? "" : lua);
        return m.find() ? m.group(1) : null;
    }

    static String headerLine(String sha) {
        return "-- inputs sha256 " + sha;
    }

    private static void collect(String body, List<GraphNode> nodes, GraphEnvironment env, List<String> out) {
        for (GraphNode n : nodes) {
            String where = body + "/" + n.id() + " ";
            switch (n.kind()) {
                case CallKinds.LUA_CALL -> {
                    String module = KindContext.str(n, "module");
                    LuaFunction f = env.luaFunction(module == null ? "" : module, KindContext.str(n, "function"));
                    out.add(where + (f == null ? "missing" : signature(f)));
                }
                case EventKinds.SIGNAL -> {
                    GraphEnvironment.ElementInfo el = env.element(KindContext.str(n, "target"));
                    out.add(where + event(el == null ? null : Ports.signal(el.contract(), KindContext.str(n, "signal"))));
                }
                case HostKinds.EMIT -> out.add(where + event(Ports.signal(env.ownContract(), KindContext.str(n, "signal"))));
                default -> {
                }
            }
        }
    }

    private static String signature(LuaFunction f) {
        StringBuilder sb = new StringBuilder(f.module()).append('.').append(f.name()).append(f.global() ? " global" : "")
            .append(f.async() ? " async" : "").append(" (");
        f.params().forEach(p -> sb.append(p.name()).append(':').append(p.type().wire()).append(p.optional() ? "?" : "")
            .append(','));
        sb.append(") -> (");
        f.returns().forEach(p -> sb.append(p.name()).append(':').append(p.type().wire()).append(','));
        return sb.append(')').toString();
    }

    private static String event(UiDocument.EventDef e) {
        if (e == null) {
            return "missing";
        }
        StringBuilder sb = new StringBuilder(e.name()).append(" (");
        e.args().forEach(p -> sb.append(p.name()).append(':').append(p.type().wire()).append(','));
        return sb.append(')').toString();
    }
}
