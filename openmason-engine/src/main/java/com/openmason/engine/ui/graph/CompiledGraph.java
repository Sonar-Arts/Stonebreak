package com.openmason.engine.ui.graph;

import java.util.List;
import java.util.Objects;

/**
 * The result of compiling one graph (#291).
 *
 * @param lua             the generated chunk, or null when the graph has errors
 * @param sourceSha256    SHA-256 of the graph's canonical entry bytes (the derived-cache key)
 * @param compilerVersion {@link GraphCompiler#VERSION} that produced {@code lua}
 * @param debug           true when the chunk calls the trace hooks (preview debugging)
 * @param diagnostics     everything the checks found, errors first never hidden
 * @param sourceMap       line → node, from the markers in {@code lua} (null without code)
 */
public record CompiledGraph(String graphId, String lua, String sourceSha256, String compilerVersion, boolean debug,
                            List<GraphDiagnostic> diagnostics, SourceMap sourceMap) {

    public CompiledGraph {
        Objects.requireNonNull(graphId, "graphId");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean ok() {
        return lua != null;
    }

    public List<GraphDiagnostic> errors() {
        return diagnostics.stream().filter(GraphDiagnostic::isError).toList();
    }

    /** The chunk name errors and tracebacks carry: {@code <graph id>.graph.lua}. */
    public String chunkName() {
        return chunkName(graphId);
    }

    public static String chunkName(String graphId) {
        return graphId + GraphCompiler.CHUNK_SUFFIX;
    }
}
