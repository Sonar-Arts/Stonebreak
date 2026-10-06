package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.GraphCodec;

import java.util.List;

/**
 * Compiles behavior graphs to readable, source-mapped Lua for the #292 runtime (#291). There is
 * no graph interpreter: a graph is a visual layer over Lua, sharing its sandbox, scheduler and
 * budgets.
 *
 * <p>The chunk is called with {@code (ui, script, dbg, dbgv)} in the environment of the
 * document's script context and returns its hooks ({@code on_open}, {@code update},
 * {@code on_close}). Output is deterministic: the same graph, environment and
 * {@link #VERSION} always give the same bytes, which is what the SBUI {@code derived/} cache is
 * keyed to.
 */
public final class GraphCompiler {

    /** Compiler id recorded with derived caches. */
    public static final String COMPILER = "omui-graphc";
    /** Bumped whenever generated code changes for the same graph; stale caches then regenerate. */
    public static final String VERSION = "1";
    public static final String CHUNK_SUFFIX = ".graph.lua";

    private GraphCompiler() {
    }

    /** Checks only: every diagnostic, no code. */
    public static List<GraphDiagnostic> validate(UiGraph graph, GraphEnvironment env) {
        return GraphPlan.build(graph, env).diagnostics;
    }

    /**
     * @param debug true adds the trace calls the preview's active-node highlighting, watches
     *              and breakpoints use; release code (game, derived caches) has none
     */
    public static CompiledGraph compile(UiGraph graph, GraphEnvironment env, boolean debug) {
        GraphPlan plan = GraphPlan.build(graph, env);
        String sha = sourceSha256(graph);
        if (plan.hasErrors()) {
            return new CompiledGraph(graph.id(), null, sha, VERSION, debug, sorted(plan.diagnostics), null);
        }
        String lua = new LuaGenerator(plan, debug, sha, GraphInputs.sha256(graph, env)).generate();
        return new CompiledGraph(graph.id(), lua, sha, VERSION, debug, sorted(plan.diagnostics),
            SourceMap.parse(graph.id(), lua));
    }

    /** SHA-256 of the graph's canonical {@code graphs/<id>.graph.json} bytes. */
    public static String sourceSha256(UiGraph graph) {
        return UiBytes.sha256(CanonicalJson.write(GraphCodec.write(graph)));
    }

    private static List<GraphDiagnostic> sorted(List<GraphDiagnostic> d) {
        return d.stream().sorted((a, b) -> b.severity().compareTo(a.severity())).toList();
    }
}
