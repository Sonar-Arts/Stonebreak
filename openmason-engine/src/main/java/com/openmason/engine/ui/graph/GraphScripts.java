package com.openmason.engine.ui.graph;

/**
 * "Convert to script" (#291): copies a graph's generated Lua into an ordinary, editable module.
 * The graph stays canonical for its own code; the copy is a new module the author owns from then
 * on (nothing regenerates it). Its {@code -- @node} markers become plain {@code -- node}
 * comments, the chunk arguments become a {@code bind} function, and the hooks are called from
 * the code-behind:
 *
 * <pre>
 * local behaviors = require("behaviors")
 * function M.on_open(ui) behaviors.bind(M) behaviors.on_open() end
 * function M.update(dt) behaviors.update(dt) end   -- when the graph had update events
 * </pre>
 */
public final class GraphScripts {

    private GraphScripts() {
    }

    /**
     * @param compiled a release build ({@link CompiledGraph#ok()}); debug builds are refused
     * @throws IllegalArgumentException when the graph did not compile or is a debug build
     */
    public static String convert(CompiledGraph compiled) {
        if (!compiled.ok() || compiled.debug()) {
            throw new IllegalArgumentException("only a release build of a valid graph converts to a script");
        }
        StringBuilder out = new StringBuilder();
        out.append("-- Converted from graph ").append(compiled.graphId()).append(" (").append(GraphCompiler.COMPILER)
            .append(' ').append(compiled.compilerVersion()).append(", source sha256 ")
            .append(compiled.sourceSha256()).append(").\n");
        out.append("-- An ordinary module now: edit it freely; the graph no longer drives it. Call its hooks from\n");
        out.append("-- the code-behind: local g = require(\"<this module>\"); in on_open: g.bind(M) g.on_open().\n");
        boolean skipHeader = true;
        for (String line : compiled.lua().split("\n", -1)) {
            if (skipHeader && line.startsWith("--")) {
                continue; // the generated header ("never edit") no longer applies
            }
            skipHeader = false;
            String t = line.strip();
            if (t.equals("local ui, script, dbg, dbgv = ...")) {
                out.append("local script -- the code-behind module the graph's Lua calls used; set by G.bind\n");
                continue;
            }
            if (t.equals("return G")) {
                out.append("--- Hands this module the code-behind table its Lua calls go to.\n");
                out.append("function G.bind(module) script = module end\n\n");
            }
            if (t.startsWith(LuaWriter.MARKER)) {
                String rest = t.substring(LuaWriter.MARKER.length());
                if (rest.equals("-")) {
                    continue;
                }
                out.append(line, 0, line.indexOf('-')).append("-- node ").append(rest).append('\n');
                continue;
            }
            out.append(line).append('\n');
        }
        String s = out.toString();
        return s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s;
    }
}
