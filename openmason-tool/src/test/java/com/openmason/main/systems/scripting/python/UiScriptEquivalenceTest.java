package com.openmason.main.systems.scripting.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.ScriptExecutor.Language;
import com.openmason.main.systems.scripting.ScriptExecutor.RunOptions;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptResult;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptSource;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import com.openmason.main.systems.scripting.live.LiveUiScriptTarget;
import com.openmason.main.systems.uiEditor.automation.UiAutomation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.ops.UiOpBatch;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.view.UiEditorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code om.ui} scripts and {@code ui_ops} batches produce identical documents for the same
 * ops (#324): both go through one validator and one executor, and a script's edits land as one
 * undo step only when the script succeeds.
 */
class UiScriptEquivalenceTest {

    private static final Path PAUSE = Path.of("../openmason-engine/src/test/resources/ui/omui/pause_menu.omui");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static PythonScriptEngine engine;

    @TempDir
    Path tmp;

    private final List<UiEditorContext> contexts = new ArrayList<>();

    @BeforeAll
    static void boot() {
        engine = PythonScriptEngine.ifAvailable();
        assertNotNull(engine, "GraalPy must be on the test classpath");
    }

    @AfterEach
    void close() {
        contexts.forEach(UiEditorContext::close);
    }

    private UiAutomation editorWithPause() throws Exception {
        UiProjectContext project = new UiProjectContext(() -> tmp);
        UiEditorContext ctx = new UiEditorContext(new UiDocumentService(project,
            new UiRecoveryService(tmp.resolve("rec" + contexts.size()))));
        contexts.add(ctx);
        UiAutomation ui = new UiAutomation(ctx, null);
        ui.open(PAUSE);
        return ui;
    }

    private ScriptResult runPython(UiAutomation ui, String source) {
        return new ScriptExecutor(MAPPER, engine).run(new HeadlessModelDocument(), null,
            new LiveUiScriptTarget(ui, MAPPER), new ScriptSource(Language.PYTHON, "script.py", source),
            new RunOptions(false, false, 30_000));
    }

    private OmuiArchive before;

    @BeforeEach
    void original() throws Exception {
        before = OmuiReader.read(PAUSE).archive();
    }

    @Test
    void scriptAndBatchBuildTheSameDocument() throws Exception {
        UiAutomation viaPython = editorWithPause();
        ScriptResult r = runPython(viaPython, """
            import om
            om.ui.label("Agent: build")
            p = om.ui.create("Box", name="extra", style={"width": 120, "height": 40.0},
                             classes=["x"], before="resume")
            t = om.ui.create("Label", parent=p, props={"text": "Hi"},
                             bindings=[{"target": "prop:text", "path": "session.name", "mode": "once"}])
            om.ui.set_prop("quit/label", "text", "Leave")
            om.ui.set_classes("quit/button", add=["big"])
            b = om.ui.add_instance("stonebreak:ui/components/stone_button", parent="panel",
                                   params={"label": "More"})
            om.ui.set_style(b.inner("button"), {"width": 300})
            d = om.ui.duplicate(p)
            om.ui.set_style(d, {"left": 10, "width": None})
            for i in range(3):
                om.ui.create("Label", parent=d, props={"text": "row %d" % i})
            om.ui.add_sheet("hud")
            om.ui.add_rule("hud", "#extra", {"color": "#FFFFFF"})
            om.ui.set_token("pause", "--accent", "#123456")
            om.ui.move("title", after="resume")
            om.ui.rename("statistics", "stats")
            print(len(om.ui.pending()["ops"]))
            """);
        assertTrue(r.ok(), () -> String.valueOf(r.error()));
        assertEquals("16\n", r.stdout());
        assertNotNull(r.ui());
        assertEquals(true, r.ui().get("changed"));

        UiAutomation viaOps = editorWithPause();
        UiEditorDocument target = viaOps.document(null);
        viaOps.apply(target, UiOpBatch.parse(MAPPER.readTree("""
            {"label":"Agent: build","ops":[
              {"op":"create","type":"Box","name":"extra","style":{"width":120,"height":40},
               "classes":["x"],"before":"resume","as":"p"},
              {"op":"create","type":"Label","parent":"$p","props":{"text":"Hi"},
               "bindings":[{"target":"prop:text","path":"session.name","mode":"once"}]},
              {"op":"set_prop","keys":"quit/label","prop":"text","value":"Leave"},
              {"op":"set_classes","keys":"quit/button","add":["big"]},
              {"op":"add_instance","component":"stonebreak:ui/components/stone_button","parent":"panel",
               "params":{"label":"More"},"as":"b"},
              {"op":"set_style","keys":"$b/button","style":{"width":300}},
              {"op":"duplicate","keys":"$p","as":"d"},
              {"op":"set_style","keys":"$d","style":{"left":10,"width":null}},
              {"op":"create","type":"Label","parent":"$d","props":{"text":"row 0"}},
              {"op":"create","type":"Label","parent":"$d","props":{"text":"row 1"}},
              {"op":"create","type":"Label","parent":"$d","props":{"text":"row 2"}},
              {"op":"add_sheet","id":"hud"},
              {"op":"add_rule","sheet":"hud","selector":"#extra","style":{"color":"#FFFFFF"}},
              {"op":"set_token","sheet":"pause","name":"--accent","value":"#123456"},
              {"op":"move","keys":"title","after":"resume"},
              {"op":"rename","key":"statistics","name":"stats"}
            ]}""")));

        UiEditorDocument scripted = viaPython.document(null);
        assertEquals(target.archive(), scripted.archive(), "identical documents for the same ops");
        assertEquals(List.of("Agent: build"), scripted.history().undoLabels(), "the whole script is one undo step");
        assertTrue(scripted.undo());
        assertEquals(before.document(), scripted.archive().document());
    }

    @Test
    void aFailingScriptAppliesNothing() throws Exception {
        UiAutomation ui = editorWithPause();
        UiEditorDocument d = ui.document(null);
        OmuiArchive start = d.archive();
        ScriptResult r = runPython(ui, """
            import om
            om.ui.create("Label", parent="panel")
            raise ValueError("late failure")
            """);
        assertFalse(r.ok());
        assertSame(start, d.archive());
        assertFalse(d.history().canUndo());

        ScriptResult bad = runPython(ui, """
            import om
            om.ui.create("Label", parent="panel")
            om.ui.set_style("$nope", {"width": 1})
            """);
        assertFalse(bad.ok());
        assertEquals(3, bad.error().line(), "a bad om.ui call fails at its own line");
        assertTrue(bad.error().error().contains("$nope"), bad.error().error());
        assertSame(start, d.archive());

        ScriptResult runtime = runPython(ui, """
            import om
            om.ui.create("Label", parent="panel")
            om.ui.set_prop("missing", "text", "x")
            """);
        assertFalse(runtime.ok(), "an op that fails when applied fails the run");
        assertTrue(runtime.error().error().contains("op 1 (set_prop)"), runtime.error().error());
        assertSame(start, d.archive());
    }

    @Test
    void omUiNeedsTheLiveEditor() {
        ScriptResult r = new ScriptExecutor(MAPPER, engine).run(new HeadlessModelDocument(),
            new ScriptSource(Language.PYTHON, "script.py", "import om\nom.ui.create('Label')\n"),
            new RunOptions(false, false, 30_000));
        assertFalse(r.ok());
        assertTrue(r.error().error().contains("live Open Mason UI Editor"), r.error().error());
        assertNull(r.ui());
    }
}
