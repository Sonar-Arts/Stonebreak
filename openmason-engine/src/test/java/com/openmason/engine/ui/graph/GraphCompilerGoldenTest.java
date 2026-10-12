package com.openmason.engine.ui.graph;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.cenda.LuaState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the Lua the graph compiler (#291) writes for representative graphs against the goldens in
 * {@code src/test/resources/ui/graph/golden/}. Generated code is the derived-cache contract, so
 * any change is deliberate: regenerate with {@code -Dui.graph.write=true}, review the diff and
 * bump {@link GraphCompiler#VERSION} when the same graph now compiles differently.
 */
@Tag("regression")
class GraphCompilerGoldenTest {

    static final Path DIR = Path.of("src/test/resources/ui/graph/golden");
    static final boolean WRITE = Boolean.getBoolean("ui.graph.write");

    /** Golden file name → generated Lua: every sample in release, the counter also in debug. */
    static Map<String, String> generated() {
        Map<String, String> out = new LinkedHashMap<>();
        GraphSamples.all().forEach((name, s) -> out.put(name + ".lua", lua(s, false)));
        out.put("counter.debug.lua", lua(GraphSamples.counter(), true));
        return out;
    }

    static String lua(GraphSamples.Sample s, boolean debug) {
        CompiledGraph c = s.compile(debug);
        assertTrue(c.ok(), s.graph().id() + ": " + c.diagnostics());
        assertEquals(java.util.List.of(), c.diagnostics(), s.graph().id() + " compiles without warnings");
        return c.lua();
    }

    @Test
    void generatedLuaMatchesTheGoldens() throws IOException {
        Map<String, String> generated = generated();
        if (WRITE) {
            Files.createDirectories(DIR);
            for (var e : generated.entrySet()) {
                Files.writeString(DIR.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
        }
        for (var e : generated.entrySet()) {
            Path file = DIR.resolve(e.getKey());
            assertTrue(Files.exists(file), file + " is missing; generate it with -Dui.graph.write=true");
            assertEquals(Files.readString(file, StandardCharsets.UTF_8), e.getValue(),
                e.getKey() + " drifted; if intentional rerun with -Dui.graph.write=true and review the diff");
        }
    }

    @Test
    void compilingIsDeterministic() {
        GraphSamples.all().forEach((name, s) -> {
            for (boolean debug : new boolean[]{false, true}) {
                String first = s.compile(debug).lua();
                String again = GraphCompiler.compile(s.graph(), s.env(), debug).lua();
                assertEquals(first, again, name + " compiled twice");
            }
        });
        // A fresh environment and a fresh copy of the sample give the same bytes too.
        assertEquals(GraphSamples.flow().compile(false).lua(), GraphSamples.flow().compile(false).lua());
    }

    @Test
    void releaseBuildsHaveNoTraceCallsAndDebugBuildsDo() {
        GraphSamples.all().forEach((name, s) -> {
            CompiledGraph release = s.compile(false);
            CompiledGraph debug = s.compile(true);
            assertFalse(release.debug());
            assertTrue(debug.debug());
            assertFalse(release.lua().contains("dbg("), name);
            assertFalse(release.lua().contains("dbgv("), name);
            assertFalse(release.lua().contains("(debug build)"), name);
            assertTrue(debug.lua().contains("dbg("), name);
            assertTrue(debug.lua().contains("(debug build)"), name);
            assertEquals(release.sourceSha256(), debug.sourceSha256(), "the source hash ignores the build kind");
        });
        assertTrue(GraphSamples.counter().compile(true).lua().contains("dbgv("), "watched values in debug builds");
    }

    @Test
    void theHeaderNamesCompilerVersionAndSource() {
        GraphSamples.Sample s = GraphSamples.counter();
        CompiledGraph c = s.compile(false);
        assertEquals(GraphCompiler.VERSION, c.compilerVersion());
        assertEquals(GraphCompiler.sourceSha256(s.graph()), c.sourceSha256());
        assertEquals("counter.graph.lua", c.chunkName());
        assertTrue(c.lua().startsWith("-- " + GraphCompiler.COMPILER + " " + GraphCompiler.VERSION
            + ": graphs/counter.graph.json, source sha256 " + c.sourceSha256() + "\n"), c.lua());
    }

    @Test
    void everyChunkLoadsAndReturnsItsHooks() {
        assumeLua();
        Map<String, String> chunks = generated();
        GraphSamples.all().forEach((name, s) -> chunks.put(name + ".debug.lua", s.compile(true).lua()));
        try (LuaState lua = CendaLua.newState(16 << 20)) {
            for (var e : chunks.entrySet()) {
                int status = lua.run(loader(e.getValue()), e.getKey(), 0);
                assertEquals(LuaState.OK, status, e.getKey() + ": " + lua.lastError());
            }
        }
    }

    @Test
    void theLoadCheckRejectsBrokenChunks() {
        assumeLua();
        try (LuaState lua = CendaLua.newState(16 << 20)) {
            assertTrue(lua.run(loader("local x = = 1"), "syntax", 0) != LuaState.OK, "a syntax error fails");
            assertTrue(lua.run(loader("return 1"), "no-hooks", 0) != LuaState.OK, "a chunk without hooks fails");
            assertEquals(LuaState.OK, lua.run(loader("return { on_open = function() end }"), "minimal", 0),
                lua.lastError());
        }
    }

    /**
     * Lua that compiles {@code chunk} without running any handler: its top level runs against
     * stub arguments (variables, function definitions, converter registration) and must return
     * the hooks table.
     */
    static String loader(String chunk) {
        return """
            local f, err = load(%s, "=graph", "t")
            if not f then error(err, 0) end
            local stub = setmetatable({}, { __index = function() return function() end end })
            local G = f(stub, stub, function() end, function() end)
            assert(type(G) == "table", "the chunk returns a table")
            assert(type(G.on_open) == "function", "the chunk returns its on_open hook")
            """.formatted(LuaText.quote(chunk));
    }

    /**
     * UI scripting has no Java fallback (#292): without the native Lua host this fails, unless
     * {@code -Dui.script.allowMissingNative=true} turns it into a skip (as ScriptRig does).
     */
    static void assumeLua() {
        if (CendaLua.isAvailable()) {
            return;
        }
        String why = "the Cenda Lua host is unavailable (build it: openmason-engine/cenda/build-kernels.sh)";
        assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), why);
        throw new AssertionError(why);
    }
}
