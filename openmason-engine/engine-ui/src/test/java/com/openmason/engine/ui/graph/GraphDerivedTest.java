package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiManifest;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Graph Lua as SBUI derived caches (#291): one per graph, keyed to its source; invalid graphs block the export. */
class GraphDerivedTest {

    static OmuiArchive twoGraphs() {
        return GraphSamples.screenDoc().withGraph(GraphSamples.counter().graph()).withGraph(GraphSamples.flow().graph());
    }

    @Test
    void oneReleaseChunkPerGraph() throws UiFormatException {
        OmuiArchive doc = twoGraphs();
        List<SbuiExporter.DerivedInput> out = GraphDerived.compileAll(doc, UiDocumentSource.EMPTY);
        assertEquals(2, out.size(), out.toString());
        GraphEnvironment env = DocumentEnvironment.of(doc, UiDocumentSource.EMPTY);
        for (UiGraph g : doc.graphs().values()) {
            SbuiExporter.DerivedInput in = out.stream().filter(d -> d.source().equals("graph:" + g.id())).findFirst()
                .orElseThrow(() -> new AssertionError("no derived input for " + g.id() + " in " + out));
            assertEquals(SbuiFormat.GRAPH_SOURCE + g.id(), in.source());
            assertEquals("graphs/" + g.id() + ".lua", in.name());
            assertEquals(GraphDerived.name(g.id()), in.name());
            assertEquals(SbuiManifest.DerivedKind.GRAPH_LUA, in.kind());
            assertEquals(GraphCompiler.COMPILER, in.compiler());
            assertEquals("omui-graphc", in.compiler());
            assertEquals(GraphCompiler.VERSION, in.compilerVersion());
            String lua = new String(in.bytes().toArray(), StandardCharsets.UTF_8);
            assertEquals(GraphCompiler.compile(g, env, false).lua(), lua, "the release build, byte for byte");
            assertTrue(lua.contains("source sha256 " + GraphCompiler.sourceSha256(g)), lua);
        }
    }

    @Test
    void theExportKeysEachCacheToItsGraphsSourceHash() throws UiFormatException {
        OmuiArchive doc = twoGraphs();
        SbuiExporter.Options options = new SbuiExporter.Options(null, Map.of(), false, Map.of(),
            GraphDerived.compileAll(doc, UiDocumentSource.EMPTY));
        SbuiManifest manifest = SbuiExporter.export(doc, options).archive().manifest();
        assertEquals(2, manifest.derived().size(), manifest.derived().toString());
        for (UiGraph g : doc.graphs().values()) {
            SbuiManifest.DerivedEntry e = manifest.derived().stream()
                .filter(x -> x.source().equals(SbuiFormat.GRAPH_SOURCE + g.id())).findFirst().orElseThrow();
            assertEquals(GraphCompiler.sourceSha256(g), e.sourceSha256(), g.id());
            assertEquals(GraphCompiler.VERSION, e.compilerVersion());
        }
    }

    @Test
    void aDocumentWithoutGraphsHasNoCaches() throws UiFormatException {
        assertEquals(List.of(), GraphDerived.compileAll(GraphSamples.screenDoc(), UiDocumentSource.EMPTY));
    }

    /** Errors in the event graph, at function level and inside a function body. */
    static UiGraph broken() {
        return new GraphBuilder("broken")
            .node("click", "ui:event.click", "target", "resume")
            .node("log", "ui:log")
            .node("zz", "ui:nope")
            .link("click.then", "log.exec")
            .function("f", f -> f.in("x", "number").out("y", "number").node("ret", "ui:function.return", "=y", 1))
            .function("g", f -> f.in("x", "number").out("y", "number")
                .node("entry", "ui:function.entry").node("ret", "ui:function.return").node("weird", "ui:nope")
                .link("entry.x", "ret.y"))
            .build();
    }

    @Test
    void anInvalidGraphBlocksTheExportWithPointersToItsNodes() {
        OmuiArchive doc = GraphSamples.screenDoc().withGraph(GraphSamples.counter().graph()).withGraph(broken());
        UiFormatException ex = assertThrows(UiFormatException.class,
            () -> GraphDerived.compileAll(doc, UiDocumentSource.EMPTY));
        assertTrue(ex.has(UiDiagnostic.Code.GRAPH_INVALID));
        List<UiDiagnostic> errors = ex.diagnostics().stream().filter(UiDiagnostic::isError).toList();
        assertTrue(errors.stream().allMatch(d -> d.code() == UiDiagnostic.Code.GRAPH_INVALID), errors.toString());
        assertTrue(errors.stream().allMatch(d -> d.entry().equals("graphs/broken.graph.json")), errors.toString());
        assertEquals(OmuiFormat.graphEntry("broken"), "graphs/broken.graph.json");

        // Nodes are canonically sorted by id: click, log, zz / entry, ret, weird.
        assertEquals(List.of("/functions/0", "/functions/1/nodes/2", "/nodes/2"),
            errors.stream().map(UiDiagnostic::pointer).sorted().distinct().toList(), errors.toString());
        UiDiagnostic zz = errors.stream().filter(d -> d.pointer().equals("/nodes/2")).findFirst().orElseThrow();
        assertTrue(zz.message().startsWith("broken#zz: UNKNOWN_KIND: "), zz.message());
        UiDiagnostic weird = errors.stream().filter(d -> d.pointer().equals("/functions/1/nodes/2")).findFirst()
            .orElseThrow();
        assertTrue(weird.message().startsWith("broken#fn:g/weird: UNKNOWN_KIND: "), weird.message());
        UiDiagnostic shape = errors.stream().filter(d -> d.pointer().equals("/functions/0")).findFirst().orElseThrow();
        assertTrue(shape.message().startsWith("broken#fn:f: FUNCTION_SHAPE: "), shape.message());
    }

    @Test
    void warningsDoNotBlockTheExport() throws UiFormatException {
        UiGraph g = new GraphBuilder("warned")
            .node("click", "ui:event.click", "target", "resume")
            .node("orphan", "ui:log")
            .build();
        List<SbuiExporter.DerivedInput> out = GraphDerived.compileAll(GraphSamples.screenDoc().withGraph(g),
            UiDocumentSource.EMPTY);
        assertEquals(1, out.size());
    }

    @Test
    void pointersOfUnknownLocationsFallBackToTheirContainer() {
        UiGraph g = broken();
        GraphDiagnostic.Severity e = GraphDiagnostic.Severity.ERROR;
        GraphDiagnostic.Code c = GraphDiagnostic.Code.BROKEN_LINK;
        assertEquals("", GraphDerived.pointer(g, new GraphDiagnostic(e, c, "broken", "", "", "", "m")));
        assertEquals("", GraphDerived.pointer(g, new GraphDiagnostic(e, c, "broken", "", "missing", "", "m")));
        assertEquals("/functions/1", GraphDerived.pointer(g, new GraphDiagnostic(e, c, "broken", "g", "missing", "", "m")));
        assertEquals("", GraphDerived.pointer(g, new GraphDiagnostic(e, c, "broken", "nope", "", "", "m")));
        assertEquals("/nodes/1", GraphDerived.pointer(g, new GraphDiagnostic(e, c, "broken", "", "log", "exec", "m")));
    }
}
