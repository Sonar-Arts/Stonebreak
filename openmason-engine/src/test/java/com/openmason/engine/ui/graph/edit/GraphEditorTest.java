package com.openmason.engine.ui.graph.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphPort;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.graph.GraphCompiler;
import com.openmason.engine.ui.graph.GraphDerived;
import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless graph editor (#291): undo/redo of every edit, linking rules, copy/paste with id
 * remapping, comments and groups apart from semantics, renames, palette and pickers, and the
 * save/export/import round trip with its derived Lua.
 */
class GraphEditorTest {

    static final String CODE = """
        local M = {}
        --- Adds two numbers.
        ---@param a integer
        ---@param b integer
        ---@return integer sum
        function M.add(a, b) return a + b end
        return M
        """;

    static OmuiArchive doc() {
        OmuiArchive d = screen("t:ui/edit", box("root").kids(node("go", "Button").name("go"),
            label("out", "-").name("out")));
        UiDocument doc = d.document();
        return d.withDocument(new UiDocument(doc.root(), doc.styleSheets(), "main", doc.component(), doc.unknown()))
            .withScript("main", CODE);
    }

    static GraphEditor editor() {
        return GraphEditor.open(doc(), "behaviors", UiDocumentSource.EMPTY);
    }

    /** click(go) -> set-text(out) with a literal. */
    static GraphEditor clickWritesText() {
        GraphEditor e = editor();
        String click = e.addNode("", "ui:event.click", Map.of("target", UiValue.of("go")), 0, 0);
        String set = e.addNode("", "ui:element.set-text", Map.of("target", UiValue.of("out")), 300, 0);
        assertNull(e.connect("", click, "then", set, "exec"));
        e.setInput("", set, "text", UiValue.of("hi"));
        return e;
    }

    @Test
    void everyEditUndoesAndRedoesExactly() {
        GraphEditor e = editor();
        UiGraph empty = e.graph();
        String click = e.addNode("", "ui:event.click", Map.of("target", UiValue.of("go")), 10, 20);
        assertEquals("click", click);
        String set = e.addNode("", "ui:element.set-text", Map.of("target", UiValue.of("out")), 300, 20);
        e.connect("", click, "then", set, "exec");
        e.setInput("", set, "text", UiValue.of("hi"));
        UiGraph full = e.graph();
        assertEquals("Set text", e.undoLabel());
        assertTrue(e.undo() && e.undo() && e.undo() && e.undo());
        assertEquals(empty, e.graph());
        assertFalse(e.undo());
        while (e.redo()) {
            // replay
        }
        assertEquals(full, e.graph());
        assertTrue(e.diagnostics().isEmpty(), e.diagnostics().toString());
    }

    @Test
    void linksFollowPortRules() {
        GraphEditor e = clickWritesText();
        String fmt = e.addNode("", "ui:format", Map.of("template", UiValue.of("n={n}")), 0, 200);
        String cmp = e.addNode("", "ui:compare", Map.of(), 0, 300);
        assertEquals("exec pins only link to exec pins", e.canConnect("", "click", "then", "set_text", "text"));
        assertNull(e.canConnect("", cmp, "result", "set_text", "text"), "a bool renders into a text input");
        String branch = e.addNode("", "ui:flow.branch", Map.of(), 300, 300);
        assertEquals("string does not convert to bool", e.canConnect("", fmt, "text", branch, "condition"));
        assertEquals("connect an output to an input", e.canConnect("", fmt, "n", "set_text", "text"));
        assertEquals("a node cannot link to itself", e.canConnect("", fmt, "text", fmt, "n"));
        // Either drag direction links output -> input, and a link replaces the input's literal.
        assertNull(e.connect("", "set_text", "text", fmt, "text"));
        GraphEdge link = e.edges("").stream().filter(x -> x.toPort().equals("text")).findFirst().orElseThrow();
        assertEquals(fmt, link.fromNode());
        assertFalse(e.node("", "set_text").inputs().containsKey("text"), "the literal gave way to the link");
        // A second link into the same data input replaces the first.
        String other = e.addNode("", "ui:to-text", Map.of(), 0, 400);
        e.connect("", other, "text", "set_text", "text");
        assertEquals(1, e.edges("").stream().filter(x -> x.toNode().equals("set_text") && x.toPort().equals("text")).count());
        // An exec output keeps one link too.
        String log = e.addNode("", "ui:log", Map.of(), 600, 0);
        e.connect("", "click", "then", log, "exec");
        assertEquals(List.of(log), e.edges("").stream().filter(x -> x.fromNode().equals("click")).map(GraphEdge::toNode)
            .toList());
    }

    @Test
    void dragsMergeIntoOneUndoStep() {
        GraphEditor e = clickWritesText();
        e.moveNodes("", Set.of("click"), 5, 0, "d1");
        e.moveNodes("", Set.of("click"), 5, 0, "d1");
        e.moveNodes("", Set.of("click"), 5, 0, "d1");
        assertEquals(15, e.node("", "click").x());
        e.undo();
        assertEquals(0, e.node("", "click").x(), "one drag, one step");
        e.redo();
        e.endDrag();
        e.moveNodes("", Set.of("click"), 5, 0, "d1");
        e.undo();
        assertEquals(15, e.node("", "click").x(), "after endDrag the same token starts a new step");
    }

    @Test
    void copyPasteRemapsIdsAndKeepsInnerLinksOnly() {
        GraphEditor e = clickWritesText();
        String clip = e.copy("", List.of("click", "set_text"));
        List<String> pasted = e.paste("", clip, 1000, 1000);
        assertEquals(List.of("click_2", "set_text_2"), pasted);
        assertEquals(1000, e.node("", "click_2").x());
        assertTrue(e.edges("").stream().anyMatch(x -> x.fromNode().equals("click_2") && x.toNode().equals("set_text_2")));
        // Only the inner link travels.
        String alone = e.copy("", List.of("set_text"));
        List<String> one = e.paste("", alone, 0, 600);
        assertTrue(e.edges("").stream().noneMatch(x -> x.toNode().equals(one.getFirst())));
        assertEquals(List.of(), e.paste("", "not a clip", 0, 0));
        assertEquals(List.of(), e.paste("", "{\"id\":\"other\",\"nodes\":[]}", 0, 0));
        // Duplicate = copy + paste, offset.
        List<String> dup = e.duplicate("", List.of("click"));
        assertEquals(40, e.node("", dup.getFirst()).x());
        // Pasting into a function body works the same way.
        e.addFunction("helper", List.of(new GraphPort("exec", "exec", Map.of())), List.of(new GraphPort("then", "exec",
            Map.of())));
        List<String> inFn = e.paste("helper", e.copy("", List.of("set_text")), 0, 100);
        assertNotNull(e.node("helper", inFn.getFirst()));
    }

    @Test
    void removingNodesDropsTheirLinksAndGroupMemberships() {
        GraphEditor e = clickWritesText();
        e.group("", List.of("click", "set_text"), "writer");
        e.removeNodes("", List.of("set_text"));
        assertTrue(e.edges("").isEmpty());
        assertEquals(List.of("click"), e.layout().groups().getFirst().nodes());
        e.undo();
        assertEquals(1, e.edges("").size());
        assertEquals(List.of("click", "set_text"), e.layout().groups().getFirst().nodes());
    }

    @Test
    void commentsAndGroupsLiveApartFromSemantics() {
        GraphEditor e = clickWritesText();
        String sha = GraphCompiler.sourceSha256(e.graph());
        String c = e.addComment("", -20, -20, 700, 200, "click handling");
        e.group("", List.of("click"), "events");
        assertEquals(sha, GraphCompiler.sourceSha256(e.graph()), "annotations never touch the graph file");
        assertTrue(e.document().editor().containsKey(GraphLayout.entry("behaviors")));
        // Moving a comment carries the nodes inside its frame.
        e.moveComment(c, 100, 0, "m");
        assertEquals(100, e.node("", "click").x());
        assertEquals(400, e.node("", "set_text").x());
        assertEquals(80, e.layout().comments().getFirst().x());
        e.undo();
        assertEquals(0, e.node("", "click").x());
        e.removeComment(c);
        assertEquals(1, e.layout().groups().size());
        assertEquals(List.of("events"), e.groupsOf("", "click").stream().map(GraphLayout.Group::title).toList());
    }

    @Test
    void renamesFollowReferences() {
        GraphEditor e = editor();
        e.addVariable("score", ValueType.INT, UiValue.of(0));
        String get = e.addNode("", "ui:variable.get", Map.of("variable", UiValue.of("score")), 0, 0);
        e.renameVariable("score", "points");
        assertEquals(UiValue.of("points"), e.node("", get).props().get("variable"));
        e.addFunction("twice", List.of(new GraphPort("x", "number", Map.of())), List.of(new GraphPort("y", "number",
            Map.of())));
        String call = e.addNode("", "ui:function.call", Map.of("function", UiValue.of("twice")), 0, 100);
        e.addComment("twice", 0, 0, 100, 100, "inside");
        e.renameFunction("twice", "double");
        assertEquals(UiValue.of("double"), e.node("", call).props().get("function"));
        assertEquals("double", e.layout().comments().getFirst().function());
        assertNotNull(e.node("double", "entry"));
    }

    @Test
    void diagnosticsFollowEdits() {
        GraphEditor e = clickWritesText();
        assertTrue(e.diagnostics().isEmpty());
        e.setProp("", "set_text", "target", UiValue.of("nowhere"));
        GraphDiagnostic d = e.diagnostics().getFirst();
        assertEquals(GraphDiagnostic.Code.MISSING_ELEMENT, d.code());
        assertEquals(1, e.diagnostics("", "set_text").size());
        e.undo();
        assertTrue(e.diagnostics().isEmpty());
        // A signature change leaves a broken link the editor reports at the call.
        e.addFunction("f", List.of(new GraphPort("exec", "exec", Map.of()), new GraphPort("v", "string", Map.of())),
            List.of(new GraphPort("then", "exec", Map.of())));
        String call = e.addNode("", "ui:function.call", Map.of("function", UiValue.of("f")), 0, 300);
        String txt = e.addNode("", "ui:to-text", Map.of(), 0, 400);
        assertNull(e.connect("", txt, "text", call, "v"));
        e.setSignature("f", List.of(new GraphPort("exec", "exec", Map.of())), List.of(new GraphPort("then", "exec",
            Map.of())));
        assertTrue(e.diagnostics().stream().anyMatch(x -> x.code() == GraphDiagnostic.Code.BROKEN_LINK
            && x.node().equals(call)), e.diagnostics().toString());
    }

    @Test
    void paletteAndPickersOfferWhatTheDocumentHas() {
        GraphEditor e = editor();
        PaletteEntry add = e.palette("", "add").stream().filter(p -> p.kind().equals("lua:call")).findFirst()
            .orElseThrow();
        assertEquals(Map.of("function", UiValue.of("add")), add.props());
        String n = e.addNode("", add, 0, 0);
        assertEquals("add", n);
        assertTrue(e.ports("", n).input("a") != null && e.ports("", n).output("sum") != null);
        assertEquals("Branch", e.palette("", "bran").getFirst().title());
        assertTrue(e.palette("", "").stream().noneMatch(p -> p.kind().equals("ui:function.entry")),
            "body nodes stay in function bodies");
        String click = e.addNode("", "ui:event.click", Map.of(), 0, 100);
        assertEquals(List.of("root", "go", "out"), e.options("", click, "target"));
        e.setProp("", click, "target", UiValue.of("go"));
        assertEquals(List.of("go"), e.elementTargets("", click));
        assertEquals(1, e.nodesTargeting("go").size());
    }

    @Test
    void saveExportAndImportKeepSemanticsLayoutAndReproducibleLua() throws Exception {
        GraphEditor e = clickWritesText();
        e.addComment("", 0, 0, 400, 100, "note");
        assertTrue(e.dirty());
        byte[] saved = OmuiWriter.write(e.document());
        e.markSaved();
        assertFalse(e.dirty());
        OmuiArchive back = OmuiReader.read(saved).archive();
        assertEquals(e.graph(), back.graphs().get("behaviors"));
        GraphEditor reopened = GraphEditor.open(back, "behaviors", UiDocumentSource.EMPTY);
        assertEquals(e.layout(), reopened.layout());
        assertFalse(reopened.dirty());

        var derived = GraphDerived.compileAll(back, UiDocumentSource.EMPTY);
        SbuiArchive sbui = SbuiExporter.export(back, new SbuiExporter.Options(null, Map.of(), false, Map.of(), derived))
            .archive();
        SbuiArchive read = SbuiReader.read(SbuiWriter.write(sbui), SbuiReader.Options.RUNTIME).archive();
        OmuiArchive imported = SbuiImporter.importEditable(read).document();
        assertEquals(e.graph(), imported.graphs().get("behaviors"));
        assertEquals(e.layout(), GraphEditor.open(imported, "behaviors", UiDocumentSource.EMPTY).layout());
        // The shipped Lua reproduces from the source hash and the compiler version.
        String shipped = new String(read.derived().get("derived/graphs/behaviors.lua").toArray(), StandardCharsets.UTF_8);
        assertEquals(shipped, GraphCompiler.compile(imported.graphs().get("behaviors"),
            com.openmason.engine.ui.graph.DocumentEnvironment.of(imported, UiDocumentSource.EMPTY), false).lua());
        assertEquals(GraphCompiler.sourceSha256(imported.graphs().get("behaviors")),
            read.manifest().derived().getFirst().sourceSha256());
    }

    @Test
    void convertToScriptAddsAnEditableModuleAndUndoes() {
        GraphEditor e = clickWritesText();
        assertTrue(e.convertToScript("behaviors_script").isEmpty());
        String module = e.document().scripts().get("behaviors_script");
        assertNotNull(module);
        assertTrue(module.contains("function G.bind(module)") && module.contains("-- node set_text"), module);
        assertFalse(module.contains("-- @node"));
        assertNotNull(e.graph(), "the graph stays");
        e.undo();
        assertNull(e.document().scripts().get("behaviors_script"));
        e.setProp("", "set_text", "target", UiValue.of("nowhere"));
        assertNotEquals(List.of(), e.convertToScript("broken"), "a graph with errors does not convert");
    }

    @Test
    void arrangeLaysEventsOutInBandsByExecDepth() {
        GraphEditor e = clickWritesText();
        String fmt = e.addNode("", "ui:format", Map.of("template", UiValue.of("x{n}")), 900, 900);
        e.connect("", fmt, "text", "set_text", "text");
        String open = e.addNode("", "ui:event.open", Map.of(), 500, -500);
        String log = e.addNode("", "ui:log", Map.of(), 0, 0);
        e.connect("", open, "then", log, "exec");
        e.arrange("");
        assertEquals(0, e.node("", "click").x());
        assertEquals(GraphEditor.ARRANGE_COLUMN, e.node("", "set_text").x(), "one column per exec step");
        assertEquals(e.node("", "click").y(), e.node("", "set_text").y());
        assertTrue(e.node("", fmt).y() > e.node("", "set_text").y(), "a pure input sits below its reader");
        assertTrue(e.node("", open).y() > e.node("", fmt).y(), "the next event gets its own band");
        assertEquals("Arrange", e.undoLabel());
        assertTrue(e.diagnostics().isEmpty());
    }

    @Test
    void oneHistorySpansTheDocumentsGraphs() {
        GraphEditor e = clickWritesText();
        e.markSaved();
        e.switchGraph("extra");
        assertEquals("New graph extra", e.undoLabel());
        assertTrue(e.dirty(), "a new graph is a change");
        e.addNode("", "ui:event.open", Map.of(), 0, 0);
        assertEquals(List.of("behaviors", "extra"), e.graphIds());
        e.switchGraph("behaviors");
        e.removeNodes("", List.of("set_text"));
        e.undo();
        assertEquals("behaviors", e.graphId());
        assertNotNull(e.node("", "set_text"));
        e.undo();
        assertEquals("extra", e.graphId(), "undo returns to the graph the step was made in");
        assertTrue(e.nodes("").isEmpty());
        e.undo();
        assertEquals("behaviors", e.graphId(), "undoing the creation leaves the removed graph");
        assertFalse(e.document().graphs().containsKey("extra"));
        assertFalse(e.dirty(), "back at the saved document");
        e.redo();
        e.redo();
        assertEquals("extra", e.graphId());
        assertEquals(1, e.nodes("").size());
    }
}
