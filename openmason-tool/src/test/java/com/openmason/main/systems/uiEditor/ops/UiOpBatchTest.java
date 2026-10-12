package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.document.UiTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI op-batch validator and executor (#324) against {@code pause_menu.omui}: one undo step
 * per batch, failure atomicity, alias/id/name remapping, overrides, sheets and clips.
 */
class UiOpBatchTest {

    static final Path PAUSE = Path.of("../openmason-engine/src/test/resources/ui/omui/pause_menu.omui");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UiEditorDocument doc;
    private OmuiArchive original;

    @TempDir
    Path tmp;

    @BeforeEach
    void open() throws Exception {
        original = OmuiReader.read(PAUSE).archive();
        doc = new UiEditorDocument(original, PAUSE, UiEditorDocument.Origin.FILE, null);
    }

    private static UiOpBatch batch(String json) throws Exception {
        return UiOpBatch.parse(MAPPER.readTree(json));
    }

    private UiOpBatch run(String json) throws Exception {
        UiOpBatch b = batch(json);
        assertTrue(doc.execute(b.command(UiOpBatch.Components.NONE)), () -> doc.lastMessage());
        return b;
    }

    private UiNode node(String id) {
        return UiTree.find(doc.archive().document().root(), id);
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void validationRefusesTheWholeBatchBeforeAnythingRuns() {
        UiOpException unknown = assertThrows(UiOpException.class, () -> batch("""
            {"ops":[{"op":"create","type":"Label"},{"op":"set_porp","keys":"title","prop":"text","value":"x"}]}"""));
        assertEquals(1, unknown.opIndex());
        assertTrue(unknown.getMessage().contains("did you mean 'set_prop'"), unknown.getMessage());

        UiOpException field = assertThrows(UiOpException.class,
            () -> batch("[{\"op\":\"create\",\"type\":\"Label\",\"colour\":\"red\"}]"));
        assertEquals(0, field.opIndex());
        assertTrue(field.getMessage().contains("no field 'colour'"));

        UiOpException missing = assertThrows(UiOpException.class,
            () -> batch("[{\"op\":\"set_prop\",\"keys\":\"title\",\"prop\":\"text\"}]"));
        assertTrue(missing.getMessage().contains("needs 'value'"));

        UiOpException type = assertThrows(UiOpException.class,
            () -> batch("[{\"op\":\"reorder\",\"key\":\"title\",\"delta\":\"up\"}]"));
        assertTrue(type.getMessage().contains("must be an integer"));

        UiOpException unbound = assertThrows(UiOpException.class, () -> batch("""
            [{"op":"set_style","keys":"$later","style":{"width":10}},
             {"op":"create","type":"Box","as":"later"}]"""));
        assertEquals(0, unbound.opIndex(), "an alias must be bound by an EARLIER op");

        UiOpException widget = assertThrows(UiOpException.class,
            () -> batch("[{\"op\":\"create\",\"type\":\"Lable\"}]"));
        assertTrue(widget.getMessage().contains("unknown widget type"));
        assertThrows(UiOpException.class, () -> batch("[{\"op\":\"create\",\"type\":\"Instance\"}]"),
            "instances go through add_instance");
        assertThrows(UiOpException.class, () -> batch("{\"ops\":[]}"));
        assertThrows(UiOpException.class, () -> batch("[{\"op\":\"move\",\"keys\":\"title\",\"before\":\"quit\","
            + "\"parent\":\"panel\"}]"));
        assertEquals(original, doc.archive(), "validation never touches a document");
    }

    // ── undo granularity ────────────────────────────────────────────────────

    @Test
    void aBatchIsExactlyOneUndoStep() throws Exception {
        run("""
            {"label":"Agent: build","ops":[
              {"op":"create","parent":"panel","type":"Label","id":"subtitle","name":"subtitle",
               "props":{"text":"Paused"},"classes":["title"],"style":{"font-size":18},
               "bindings":[{"target":"prop:text","path":"session.name"}],"as":"sub"},
              {"op":"move","keys":"$sub","before":"resume"},
              {"op":"set_style","keys":["title","$sub"],"style":{"color":"#FFFFFF"}},
              {"op":"rename","key":"panel","name":"frame"},
              {"op":"set_display_name","name":"Pause (agent)"}
            ]}""");
        assertEquals(List.of("Agent: build"), doc.history().undoLabels(), "one step for five ops");
        UiNode sub = node("subtitle");
        assertEquals("Paused", ((UiValue.Str) sub.props().get("text")).value());
        assertEquals(UiValue.of(18), sub.style().get("font-size"));
        assertEquals(UiValue.of("#FFFFFF"), sub.style().get("color"));
        assertEquals("session.name", Nodes.binding(sub, "prop:text").path());
        assertEquals(1, UiTree.locate(doc.archive().document().root(), "subtitle").index(), "before resume");
        assertEquals("frame", node("panel").name());
        assertTrue(doc.archive().styles().get("pause").rules().stream()
            .anyMatch(r -> r.selector().startsWith("#frame > ")), "rename rewrote the #panel rule");

        assertTrue(doc.undo());
        assertEquals(original.document(), doc.archive().document(), "one undo restores everything");
        assertEquals(original.manifest(), doc.archive().manifest());
        assertFalse(doc.isDirty());
        assertTrue(doc.redo());
        assertNotNull(node("subtitle"));
    }

    @Test
    void aBatchNeverMergesWithTheAuthorsOpenInteraction() throws Exception {
        assertTrue(doc.execute(com.openmason.main.systems.uiEditor.command.NodeCommands.setStyle(List.of("panel"),
            "width", UiValue.of(500))));
        run("[{\"op\":\"set_style\",\"keys\":\"panel\",\"style\":{\"width\":510}}]");
        assertEquals(2, doc.history().undoLabels().size(), "the agent step stands alone in History");
    }

    // ── failure atomicity ───────────────────────────────────────────────────

    @Test
    void aFailingOpLeavesTheDocumentUntouchedAndNamesTheOp() throws Exception {
        long revision = doc.revision();
        UiOpBatch b = batch("""
            [{"op":"create","parent":"panel","type":"Label","as":"x"},
             {"op":"set_prop","keys":"$x","prop":"text","value":"ok"},
             {"op":"set_style","keys":"nope","style":{"width":1}},
             {"op":"delete","keys":"title"}]""");
        assertFalse(doc.execute(b.command(UiOpBatch.Components.NONE)));
        assertSame(original, doc.archive(), "the very same snapshot: nothing applied");
        assertEquals(revision, doc.revision());
        assertFalse(doc.history().canUndo());
        assertFalse(doc.isDirty());
        assertNotNull(b.failure());
        assertEquals(2, b.failure().opIndex());
        assertEquals("set_style", b.failure().op());
        assertTrue(b.failure().message().contains("nope"), b.failure().message());

        UiOpBatch internal = batch("[{\"op\":\"delete\",\"keys\":\"quit/label\"}]");
        assertFalse(doc.execute(internal.command(UiOpBatch.Components.NONE)));
        assertTrue(internal.failure().message().contains("reset_override"), "teaches the override path");
        UiOpBatch root = batch("[{\"op\":\"delete\",\"keys\":\"root\"}]");
        assertFalse(doc.execute(root.command(UiOpBatch.Components.NONE)));
        assertSame(original, doc.archive());
    }

    // ── id / name remapping ─────────────────────────────────────────────────

    @Test
    void duplicateRemapsIdsAndNamesAndAliasesFollowTheCopies() throws Exception {
        UiOpBatch b = run("""
            [{"op":"duplicate","keys":"panel","as":"copy"},
             {"op":"set_style","keys":"$copy","style":{"left":40,"position":"absolute"}},
             {"op":"set_prop","keys":"title_2","prop":"text","value":"Copy"}]""");
        String copy = b.aliases().get("copy").getFirst();
        assertEquals("panel_2", copy);
        UiNode p2 = node(copy);
        assertEquals("panel_2", p2.name(), "names stay unique so #panel rules do not leak onto the copy");
        assertEquals(UiValue.of(40), p2.style().get("left"));
        assertNotNull(node("title_2"));
        assertNotNull(node("quit_2"), "descendants remapped");
        assertNotNull(node("quit_icon_2"), "slot content remapped too");
        assertEquals(node("quit").instance().overrides(), node("quit_2").instance().overrides());
        assertEquals("panel", node("panel").name(), "the original keeps its name");
    }

    // ── overrides ───────────────────────────────────────────────────────────

    @Test
    void internalKeysEditInstanceOverrides() throws Exception {
        run("""
            [{"op":"set_prop","keys":["resume/label","quit/label"],"prop":"text","value":"Go"},
             {"op":"set_style","keys":"quit/label","style":{"color":"#FF0000"}},
             {"op":"set_classes","keys":"quit/button","add":["big"],"remove":["danger"]},
             {"op":"set_param","key":"statistics","param":"label","value":"Stats"},
             {"op":"reset_override","keys":"resume/label"}]""");
        UiNode quit = node("quit");
        UiNode.InstanceOverride label = Nodes.override(quit.instance(), "label");
        assertEquals(UiValue.of("Go"), label.props().get("text"));
        assertEquals(UiValue.of("#FF0000"), label.style().get("color"));
        UiNode.InstanceOverride button = Nodes.override(quit.instance(), "button");
        assertEquals(List.of("big"), button.addClasses(), "remove cancels the earlier add of danger");
        assertEquals(List.of("danger"), button.removeClasses());
        assertNull(Nodes.override(node("resume").instance(), "label"), "reset to source");
        assertEquals(UiValue.of("Stats"), node("statistics").instance().params().get("label"));
        assertEquals(1, doc.history().undoLabels().size());

        UiOpBatch replace = batch("[{\"op\":\"set_classes\",\"keys\":\"quit/button\",\"classes\":[\"x\"]}]");
        assertFalse(doc.execute(replace.command(UiOpBatch.Components.NONE)), "internals take add/remove only");
    }

    // ── document-level ops ──────────────────────────────────────────────────

    @Test
    void sheetsTokensScriptsAndClips() throws Exception {
        run("""
            [{"op":"add_sheet","id":"hud"},
             {"op":"add_rule","sheet":"hud","selector":"#panel","style":{"background-color":"#203040"}},
             {"op":"add_rule","sheet":"hud","selector":".title:hover","style":{"color":"red"}},
             {"op":"set_rule","sheet":"hud","rule":".title:hover","style":{"color":null,"opacity":0.5}},
             {"op":"move_rule","sheet":"hud","rule":1,"delta":-1},
             {"op":"set_token","sheet":"pause","name":"--accent","value":"#FFFFFF"},
             {"op":"set_script","id":"extra","source":"return {}"},
             {"op":"put_clip","clip":{"id":"pulse","duration":0.5,"loop":"loop","tracks":[
                {"target":"title","property":"opacity","keys":[{"time":0,"value":1},{"time":0.5,"value":0.4}]}]}},
             {"op":"remove_clip","id":"pulse"},
             {"op":"put_clip","clip":{"id":"pulse2","duration":1,"tracks":[]}}]""");
        UiStyleSheet hud = doc.archive().styles().get("hud");
        assertEquals(List.of(".title:hover", "#panel"), hud.rules().stream().map(UiStyleSheet.StyleRule::selector).toList());
        assertEquals(Map.of("opacity", UiValue.of(0.5)), hud.rules().getFirst().style());
        assertEquals("hud", doc.archive().document().styleSheets().getLast(), "attached last (highest precedence)");
        assertEquals(UiValue.of("#FFFFFF"), doc.archive().styles().get("pause").variables().get("--accent"));
        assertEquals("return {}", doc.archive().scripts().get("extra"));
        assertNull(doc.archive().animations().get("pulse"));
        assertNotNull(doc.archive().animations().get("pulse2"));

        UiOpBatch badSelector = batch("[{\"op\":\"add_rule\",\"sheet\":\"hud\",\"selector\":\"#a >> b\"}]");
        assertFalse(doc.execute(badSelector.command(UiOpBatch.Components.NONE)));
        assertTrue(badSelector.failure().message().startsWith("Selector"), badSelector.failure().message());
        UiOpBatch badClip = batch("[{\"op\":\"put_clip\",\"clip\":{\"id\":\"c\"}}]");
        assertFalse(doc.execute(badClip.command(UiOpBatch.Components.NONE)), "duration is required");
    }

    @Test
    void usedFeaturesAreDeclaredAndTheResultSaves() throws Exception {
        run("[{\"op\":\"create\",\"parent\":\"panel\",\"type\":\"ScrollView\",\"as\":\"s\"},"
            + "{\"op\":\"create\",\"parent\":\"$s\",\"type\":\"Label\",\"props\":{\"text\":\"row\"}}]");
        assertTrue(doc.archive().manifest().requires().contains("ui-scroll"), doc.archive().manifest().requires()
            .toString());
        Path out = tmp.resolve("pause.omui");
        OmuiWriter.save(doc.archive(), out);
        assertEquals(doc.archive(), OmuiReader.read(out).archive(), "the writer accepts what the ops built");
    }

    @Test
    void instancesResolveDependencyRowsOrTeach() throws Exception {
        UiOpBatch b = run("[{\"op\":\"add_instance\",\"parent\":\"panel\",\"component\":"
            + "\"stonebreak:ui/components/stone_button\",\"name\":\"extra\",\"params\":{\"label\":\"More\"},"
            + "\"as\":\"btn\"},{\"op\":\"set_prop\",\"keys\":\"$btn/label\",\"prop\":\"text\",\"value\":\"x\"}]");
        UiNode btn = node(b.aliases().get("btn").getFirst());
        assertEquals("stonebreak:ui/components/stone_button", btn.instance().component());
        assertEquals("extra", btn.name());
        assertEquals(UiValue.of("More"), btn.instance().params().get("label"));
        assertNotNull(Nodes.override(btn.instance(), "label"));

        UiOpBatch missing = batch("[{\"op\":\"add_instance\",\"component\":\"test:ui/none\"}]");
        assertFalse(doc.execute(missing.command(UiOpBatch.Components.NONE)));
        assertTrue(missing.failure().message().contains("No component"));
    }

    // ── review regressions ──────────────────────────────────────────────────

    @Test
    void editsTheWriterWouldRefuseFailAtTheirOp() throws Exception {
        UiOpBatch provider = batch("[{\"op\":\"create\",\"parent\":\"panel\",\"type\":\"Label\"},"
            + "{\"op\":\"create\",\"parent\":\"panel\",\"type\":\"stonebreak:Gauge\"}]");
        assertFalse(doc.execute(provider.command(UiOpBatch.Components.NONE)));
        assertEquals(1, provider.failure().opIndex());
        assertTrue(provider.failure().message().contains("would not save"), provider.failure().message());

        UiOpBatch path = batch("[{\"op\":\"bind\",\"key\":\"title\",\"target\":\"prop:text\",\"path\":\"a b\"}]");
        assertFalse(doc.execute(path.command(UiOpBatch.Components.NONE)));
        assertEquals(0, path.failure().opIndex());
        assertSame(original, doc.archive());
        assertFalse(doc.history().canUndo());

        UiOpBatch self = batch("[{\"op\":\"add_instance\",\"component\":\"stonebreak:ui/pause_menu\"}]");
        assertFalse(doc.execute(self.command(UiOpBatch.Components.NONE)));
        assertTrue(self.failure().message().contains("itself"));
    }

    @Test
    void overridesOfMissingInternalsAreRefused() throws Exception {
        UiOpBatch typo = batch("[{\"op\":\"set_prop\",\"keys\":\"quit/lable\",\"prop\":\"text\",\"value\":\"Go\"}]");
        assertFalse(doc.execute(typo.command(UiOpBatch.Components.NONE)));
        assertTrue(typo.failure().message().contains("has no element 'lable'"), typo.failure().message());
        UiOpBatch style = batch("[{\"op\":\"set_style\",\"keys\":\"title/x\",\"style\":{\"width\":1}}]");
        assertFalse(doc.execute(style.command(UiOpBatch.Components.NONE)), "title is not an instance");
        assertSame(original, doc.archive());
    }

    @Test
    void oversizedIntegersAreRefusedNotTruncated() {
        UiOpException e = assertThrows(UiOpException.class, () -> batch(
            "[{\"op\":\"create\",\"parent\":\"panel\",\"type\":\"Label\",\"index\":4294967296}]"));
        assertTrue(e.getMessage().contains("integer"));
    }

    @Test
    void rollbackRetractsAStepWithoutTouchingTheAuthorsRedo() throws Exception {
        assertTrue(doc.execute(com.openmason.main.systems.uiEditor.command.NodeCommands.setStyle(List.of("panel"),
            "width", UiValue.of(500))));
        assertTrue(doc.undo()); // the author's redo stack now holds "Set width"
        var cp = doc.checkpoint();
        run("[{\"op\":\"create\",\"parent\":\"panel\",\"type\":\"Label\"}]");
        assertFalse(doc.history().canRedo(), "executing cleared redo, as any edit does");
        assertTrue(doc.rollbackTo(cp));
        assertEquals(original.document(), doc.archive().document());
        assertFalse(doc.history().canUndo(), "no trace of the retracted step");
        assertEquals("Set width", doc.history().redoLabel(), "the author's redo is back");
        assertFalse(doc.isDirty());
        assertFalse(doc.rollbackTo(cp), "nothing left to retract");
    }

    // ── nested components, embedded (no project to fall back on) ────────────

    /** {@code host} with {@code comp} embedded and placed as instance {@code instanceId} under its root. */
    private static OmuiArchive embedInstance(OmuiArchive host, OmuiArchive comp, String instanceId) throws Exception {
        String id = comp.manifest().documentId();
        String entry = "assets/components/" + id.substring(id.lastIndexOf('/') + 1) + ".omui";
        com.openmason.engine.format.omui.UiBytes bytes = com.openmason.engine.format.omui.UiBytes.copyOf(
            OmuiWriter.write(comp));
        List<com.openmason.engine.format.omui.UiDependency> rows = new java.util.ArrayList<>(host.dependencies().entries());
        rows.add(com.openmason.engine.format.omui.UiDependency.embedded(id,
            com.openmason.engine.format.omui.UiDependency.Kind.COMPONENT, bytes, entry, null));
        host = host.withDependencies(new OmuiArchive.UiDependencies(rows, Map.of())).withAsset(entry, bytes);
        UiNode inst = Nodes.withInstance(Nodes.create(instanceId, UiNode.INSTANCE_TYPE),
            new UiNode.ComponentInstance(id, Map.of(), List.of(), Map.of(), Map.of()));
        var d = host.document();
        List<UiNode> kids = new java.util.ArrayList<>(d.root().children());
        kids.add(inst);
        host = host.withDocument(new com.openmason.engine.format.omui.UiDocument(d.root().withChildren(kids),
            d.styleSheets(), d.codeBehind(), d.component(), d.unknown()));
        return com.openmason.main.systems.uiEditor.command.UiHistory.withRequiredFeatures(host);
    }

    @Test
    void internalKeysReachThroughEmbeddedNestedComponents() throws Exception {
        OmuiArchive btn = com.openmason.main.systems.uiEditor.service.UiDocumentTemplates.BUTTON_COMPONENT
            .create("test:ui/components/btn", "Btn");
        OmuiArchive card = embedInstance(com.openmason.main.systems.uiEditor.service.UiDocumentTemplates
            .BLANK_COMPONENT.create("test:ui/components/card", "Card"), btn, "btn");
        OmuiArchive screen = embedInstance(com.openmason.main.systems.uiEditor.service.UiDocumentTemplates
            .BLANK_SCREEN.create("test:ui/screens/s", "S"), card, "card");
        doc = new UiEditorDocument(screen, null, UiEditorDocument.Origin.NEW, null);

        run("[{\"op\":\"set_prop\",\"keys\":\"card/btn/label\",\"prop\":\"text\",\"value\":\"Deep\"},"
            + "{\"op\":\"set_style\",\"keys\":\"card/btn/button\",\"style\":{\"width\":222}}]");
        UiNode.InstanceOverride o = Nodes.override(node("card").instance(), "btn/label");
        assertEquals(UiValue.of("Deep"), o.props().get("text"), "the override lives on the screen's instance");

        UiOpBatch typo = batch("[{\"op\":\"set_prop\",\"keys\":\"card/btn/lable\",\"prop\":\"text\",\"value\":1}]");
        assertFalse(doc.execute(typo.command(UiOpBatch.Components.NONE)));
        assertTrue(typo.failure().message().contains("test:ui/components/btn has no element 'lable'"),
            typo.failure().message());
        UiOpBatch mid = batch("[{\"op\":\"set_prop\",\"keys\":\"card/btm/label\",\"prop\":\"text\",\"value\":1}]");
        assertFalse(doc.execute(mid.command(UiOpBatch.Components.NONE)));
        assertTrue(mid.failure().message().contains("test:ui/components/card has no element 'btm'"));
        UiOpBatch through = batch("[{\"op\":\"set_prop\",\"keys\":\"card/root/label\",\"prop\":\"text\",\"value\":1}]");
        assertFalse(doc.execute(through.command(UiOpBatch.Components.NONE)));
        assertTrue(through.failure().message().contains("not a nested component instance"));
    }
}
