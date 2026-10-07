package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.ui.graph.CompiledGraph;
import com.openmason.engine.ui.graph.DocumentEnvironment;
import com.openmason.engine.ui.graph.GraphBuilder;
import com.openmason.engine.ui.graph.GraphCompiler;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shipped graph cache (#291) runs as is while its keys match: graph source hash, compiler
 * version, and the Lua signatures and signal contracts recorded in the chunk. Built against
 * other signatures, it is compiled again.
 */
class GraphDerivedRuntimeTest {

    static OmuiArchive doc(String code) {
        return ScriptRig.withCode(screen("t:ui/cache", box("root").style("width", 400).style("height", 300).kids(
            node("go", "Button").name("go").style("width", 100).style("height", 30), label("out", "-").name("out"))),
            code).withGraph(new GraphBuilder("g")
                .node("click", "ui:event.click", "target", "go")
                .node("call", "lua:call", "function", "word")
                .node("show", "ui:element.set-text", "target", "out")
                .link("click.then", "call.exec").link("call.then", "show.exec").link("call.text", "show.text")
                .build());
    }

    static final String ONE = """
        local M = {}
        ---@return string text
        function M.word() return "fresh" end
        return M
        """;

    /** Same function, another signature: a new optional parameter. */
    static final String TWO = """
        local M = {}
        ---@param suffix string?
        ---@return string text
        function M.word(suffix) return "fresh" .. (suffix or "") end
        return M
        """;

    /** A source serving {@code lua} as the shipped cache of graph g. */
    static UiDocumentSource shipping(OmuiArchive built, String lua) {
        return new UiDocumentSource() {
            @Override
            public OmuiArchive component(String id) {
                return null;
            }

            @Override
            public UiStyleSheet styleSheet(String id) {
                return null;
            }

            @Override
            public DerivedLua derivedGraph(String documentId, String graphId) {
                return new DerivedLua(lua, GraphCompiler.sourceSha256(built.graphs().get("g")), GraphCompiler.COMPILER,
                    GraphCompiler.VERSION);
            }
        };
    }

    static String shipped(OmuiArchive d) {
        CompiledGraph c = GraphCompiler.compile(d.graphs().get("g"), DocumentEnvironment.of(d, UiDocumentSource.EMPTY),
            false);
        // Marks the cache so the test can tell it ran instead of a fresh compile.
        return c.lua().replace("local G, F, H = {}, {}, {}", "local G, F, H = {}, {}, {}\nui.log(\"from cache\")");
    }

    /** First-party hosts opt in; the derived chunk itself cannot be verified without compiling again. */
    static final UiScriptOptions TRUSTED = UiScriptOptions.DEFAULTS.withTrustedDerivedGraphs(true);

    @Test
    void anUntrustedHostNeverRunsShippedGraphLua() {
        OmuiArchive d = doc(ONE);
        // A pack could ship Lua whose recorded hashes name an innocent graph: by default it compiles.
        try (ScriptRig rig = new ScriptRig(d, shipping(d, shipped(d)), null, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertTrue(rig.log().stream().noneMatch(l -> l.equals("from cache")), rig.log().toString());
            rig.click("go");
            assertEquals("fresh", rig.text("out"));
        }
    }

    @Test
    void aCurrentCacheRunsAsShipped() {
        OmuiArchive d = doc(ONE);
        try (ScriptRig rig = new ScriptRig(d, shipping(d, shipped(d)), null, TRUSTED,
            UiScriptServices.NONE)) {
            assertTrue(rig.log().contains("from cache"), rig.log().toString());
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            rig.click("go");
            assertEquals("fresh", rig.text("out"));
        }
    }

    @Test
    void aCacheBuiltAgainstOtherLuaSignaturesIsCompiledAgain() {
        OmuiArchive built = doc(ONE);
        OmuiArchive now = doc(TWO);
        try (ScriptRig rig = new ScriptRig(now, shipping(built, shipped(built)), null, TRUSTED,
            UiScriptServices.NONE)) {
            assertTrue(rig.log().stream().noneMatch(l -> l.equals("from cache")), rig.log().toString());
            assertTrue(rig.rt.diagnostics().stream().anyMatch(x -> x.message().contains("other Lua signatures")),
                rig.rt.diagnostics().toString());
            rig.click("go");
            assertEquals("fresh", rig.text("out"));
        }
    }
}
