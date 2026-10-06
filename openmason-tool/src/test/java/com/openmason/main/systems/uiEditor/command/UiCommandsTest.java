package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiValidator;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.document.UiTree;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless command and undo/redo behaviour of the UI editor's document model (#293). */
class UiCommandsTest {

    static final Path PAUSE = Path.of("../openmason-engine/src/test/resources/ui/omui/pause_menu.omui");

    private UiEditorDocument doc;
    private OmuiArchive original;

    @BeforeEach
    void open() throws Exception {
        original = OmuiReader.read(PAUSE).archive();
        doc = new UiEditorDocument(original, PAUSE, UiEditorDocument.Origin.FILE, null);
    }

    private UiNode node(String id) {
        return UiTree.find(doc.archive().document().root(), id);
    }

    @Test
    void createDeleteUndoRedoRestoresTreeAndSelection() {
        assertFalse(doc.isDirty());
        assertTrue(doc.execute(NodeCommands.create("Label", NodeLocation.endOf("panel"), null)));
        String created = doc.primary();
        assertNotNull(node(created));
        assertEquals("label", created);
        assertTrue(doc.isDirty());

        doc.select(List.of("title", created));
        assertTrue(doc.execute(NodeCommands.delete(doc.selection())));
        assertNull(node("title"));
        assertNull(node(created));
        assertEquals(List.of("panel"), doc.selection());

        assertTrue(doc.undo());
        assertNotNull(node("title"));
        assertEquals(List.of("title", created), doc.selection());
        assertTrue(doc.undo());
        assertEquals(original.document(), doc.archive().document());
        assertFalse(doc.isDirty(), "undo back to the opened state is clean");

        assertTrue(doc.redo());
        assertTrue(doc.redo());
        assertNull(node("title"));
        assertTrue(doc.isDirty());
    }

    @Test
    void refusedCommandLeavesDocumentUntouched() {
        long rev = doc.revision();
        assertFalse(doc.execute(NodeCommands.delete(List.of("root"))));
        assertNotNull(doc.lastMessage());
        assertSame(original, doc.archive());
        assertEquals(rev, doc.revision());
        assertFalse(doc.history().canUndo());
    }

    @Test
    void compoundEditIsOneStep() {
        UiCommand wrapAndStyle = UiCommand.compound("Wrap and style", List.of(
            NodeCommands.wrap(List.of("resume", "statistics"), "Box"),
            NodeCommands.setStyle(List.of("box"), "flex-direction", UiValue.of("row"))));
        assertTrue(doc.execute(wrapAndStyle));
        UiNode box = node("box");
        assertEquals(List.of("resume", "statistics"), box.children().stream().map(UiNode::id).toList());
        assertEquals(UiValue.of("row"), box.style().get("flex-direction"));
        assertEquals(List.of("Wrap and style"), doc.history().undoLabels());
        assertTrue(doc.undo());
        assertEquals(original.document(), doc.archive().document());
    }

    @Test
    void failingStepOfCompoundAbortsEverything() {
        UiCommand bad = UiCommand.compound("Bad", List.of(
            NodeCommands.setStyle(List.of("title"), "color", UiValue.of("#FF0000")),
            NodeCommands.delete(List.of("root"))));
        assertFalse(doc.execute(bad));
        assertSame(original, doc.archive());
    }

    @Test
    void moveKeepsIdentityAndReferences() {
        List<UiNode.UiBinding> resyncBindings = node("resync").bindings();
        assertTrue(doc.execute(NodeCommands.move(List.of("quit"), NodeLocation.childAt("root", 0))));
        NodeLocation at = UiTree.locate(doc.archive().document().root(), "quit");
        assertEquals("root", at.parentId());
        assertEquals(0, at.index());
        assertEquals("quit_icon", node("quit").instance().slots().get("icon").getFirst().id(), "slot content moves along");
        assertEquals(resyncBindings, node("resync").bindings());
        assertTrue(doc.undo());
        assertEquals("panel", UiTree.locate(doc.archive().document().root(), "quit").parentId());
    }

    @Test
    void moveWithinSameListAdjustsIndex() {
        List<String> before = node("panel").children().stream().map(UiNode::id).toList();
        // drop "title" (index 0) just before "quit" (index 4): it lands at index 3
        int quit = before.indexOf("quit");
        assertTrue(doc.execute(NodeCommands.move(List.of("title"), NodeLocation.childAt("panel", quit))));
        List<String> after = node("panel").children().stream().map(UiNode::id).toList();
        assertEquals(after.indexOf("quit") - 1, after.indexOf("title"));
    }

    @Test
    void moveIntoOwnDescendantIsRefused() {
        assertFalse(doc.execute(NodeCommands.move(List.of("panel"), NodeLocation.endOf("title"))));
        assertFalse(doc.execute(NodeCommands.move(List.of("panel"), NodeLocation.endOf("panel"))));
    }

    @Test
    void duplicateRemapsIdsAndNamesInsideSubtree() {
        assertTrue(doc.execute(NodeCommands.duplicate(List.of("quit"))));
        String copy = doc.primary();
        assertNotEquals("quit", copy);
        UiNode c = node(copy);
        assertNotEquals("quit", c.name());
        assertNotEquals("quit_icon", c.instance().slots().get("icon").getFirst().id(), "slot content gets fresh ids");
        Set<String> ids = new HashSet<>();
        for (String id : UiTree.ids(doc.archive().document().root())) {
            assertTrue(ids.add(id), "duplicate id " + id);
        }
        assertEquals(c.instance().overrides(), node("quit").instance().overrides(), "overrides are copied verbatim");
        assertTrue(OmuiValidator.validate(doc.archive()).stream().noneMatch(UiDiagnostic::isError));
    }

    @Test
    void consecutiveStyleEditsMergeUntilInteractionEnds() {
        doc.execute(NodeCommands.setStyle(List.of("panel"), "width", UiValue.of(500)));
        doc.execute(NodeCommands.setStyle(List.of("panel"), "width", UiValue.of(510)));
        doc.execute(NodeCommands.setStyle(List.of("panel"), "width", UiValue.of(520)));
        assertEquals(1, doc.history().undoLabels().size());
        doc.endInteraction();
        doc.execute(NodeCommands.setStyle(List.of("panel"), "width", UiValue.of(530)));
        assertEquals(2, doc.history().undoLabels().size());
        doc.undo();
        assertEquals(UiValue.of(520), node("panel").style().get("width"));
        doc.undo();
        assertEquals(original.document(), doc.archive().document());
    }

    @Test
    void dirtyFollowsSavePoint() {
        doc.execute(NodeCommands.setProp(List.of("title"), "text", UiValue.of("Paused")));
        doc.savedTo(PAUSE, doc.archive());
        assertFalse(doc.isDirty());
        doc.execute(NodeCommands.setProp(List.of("title"), "text", UiValue.of("Paused!")));
        assertTrue(doc.isDirty(), "a mergeable edit after a save starts a new step");
        doc.undo();
        assertFalse(doc.isDirty());
        doc.undo();
        assertTrue(doc.isDirty(), "undo past the save point is dirty");
        doc.redo();
        assertFalse(doc.isDirty());
    }

    @Test
    void featuresAreDeclaredAutomatically() {
        assertFalse(doc.archive().manifest().requires().contains(UiFeatures.SCROLL));
        doc.execute(NodeCommands.create("ScrollView", NodeLocation.endOf("panel"), null));
        assertTrue(doc.archive().manifest().requires().contains(UiFeatures.SCROLL));
        doc.execute(NodeCommands.setProp(List.of("title"), "tooltip", UiValue.of("hi")));
        assertTrue(doc.archive().manifest().requires().contains(UiFeatures.INPUT));
        doc.undo();
        doc.undo();
        assertEquals(original.manifest(), doc.archive().manifest());
    }

    @Test
    void renameRewritesNameSelectorsButNotIdentity() {
        assertTrue(doc.execute(NodeCommands.rename("panel", "main_panel")));
        assertEquals("main_panel", node("panel").name());
        String selector = doc.archive().styles().get("pause").rules().get(1).selector();
        assertTrue(selector.contains("#main_panel"), selector);
        assertFalse(doc.execute(NodeCommands.rename("title", "main_panel")), "names stay unique");
        assertFalse(doc.execute(NodeCommands.rename("title", "bad name")));
        doc.undo();
        assertEquals(original.styles(), doc.archive().styles());
    }

    @Test
    void overridesEditAndResetComponentInternals() {
        assertTrue(doc.execute(OverrideCommands.setStyle("resume/button", Map.of("width", UiValue.of(300)), "w")));
        UiNode.InstanceOverride o = node("resume").instance().overrides().getFirst();
        assertEquals("button", o.target());
        assertEquals(UiValue.of(300), o.style().get("width"));
        assertTrue(doc.execute(OverrideCommands.setParam("resume", "label", UiValue.of("Continue"))));
        assertEquals(UiValue.of("Continue"), node("resume").instance().params().get("label"));
        assertTrue(doc.execute(OverrideCommands.reset("resume/button")));
        assertTrue(node("resume").instance().overrides().isEmpty());
        assertFalse(doc.execute(OverrideCommands.reset("title/inner")), "title is not an instance");
    }

    @Test
    void clipboardCarriesDependenciesAcrossDocuments() {
        OmuiArchive payload = UiClipboard.copy(doc.archive(), List.of("quit", "title"));
        assertNotNull(payload);
        OmuiArchive viaText = UiClipboard.fromText(UiClipboard.toText(payload));
        assertEquals(payload, viaText, "text form round-trips");
        assertTrue(payload.dependencies().entries().stream()
            .anyMatch(d -> d.id().equals("stonebreak:ui/components/stone_button")));

        UiEditorDocument target = new UiEditorDocument(
            UiDocumentTemplates.BLANK_SCREEN.create("test:ui/blank", "Blank"), null, UiEditorDocument.Origin.NEW, null);
        assertTrue(target.execute(UiClipboard.paste(viaText, NodeLocation.endOf("root"))));
        UiNode root = target.archive().document().root();
        assertEquals(2, root.children().size());
        assertNotNull(target.archive().dependencies().find("stonebreak:ui/components/stone_button"));
        String embeddedEntry = target.archive().dependencies().find("stonebreak:ui/components/stone_button").entry();
        assertTrue(target.archive().assets().containsKey(embeddedEntry), "embedded component bytes travel along");
        assertTrue(OmuiValidator.validate(target.archive()).stream().noneMatch(UiDiagnostic::isError),
            () -> OmuiValidator.validate(target.archive()).toString());

        // pasting into the source again remaps ids and names
        assertTrue(doc.execute(UiClipboard.paste(viaText, NodeLocation.endOf("panel"))));
        Set<String> ids = new HashSet<>();
        UiTree.ids(doc.archive().document().root()).forEach(id -> assertTrue(ids.add(id)));
        assertEquals(UiTree.names(doc.archive().document().root()).size(),
            new HashSet<>(UiTree.names(doc.archive().document().root())).size());
    }

    @Test
    void styleRuleCommandsValidateSelectors() {
        assertTrue(doc.execute(DocumentCommands.addRule("pause", "Button.primary:hover", Map.of())));
        int last = doc.archive().styles().get("pause").rules().size() - 1;
        assertTrue(doc.execute(DocumentCommands.setRuleDeclaration("pause", last, "opacity", UiValue.of(0.5))));
        assertFalse(doc.execute(DocumentCommands.addRule("pause", "Button..x", Map.of())));
        assertFalse(doc.execute(DocumentCommands.setRuleDeclaration("shared-sheet", 0, "opacity", UiValue.of(1))));
        assertTrue(doc.execute(DocumentCommands.moveRule("pause", last, -1)));
        assertEquals("Button.primary:hover", doc.archive().styles().get("pause").rules().get(last - 1).selector());
    }

    @Test
    void reorderChangesPaintOrderOnly() {
        List<String> before = node("panel").children().stream().map(UiNode::id).toList();
        doc.execute(NodeCommands.reorderToEnd("title", true));
        List<String> after = node("panel").children().stream().map(UiNode::id).toList();
        assertEquals("title", after.getLast());
        assertEquals(new HashSet<>(before), new HashSet<>(after));
    }

    @Test
    void undoingPastDroppedStepsStaysDirty() {
        for (int i = 0; i <= UiHistory.MAX_STEPS; i++) {
            doc.execute(NodeCommands.setProp(List.of("title"), "text", UiValue.of("v" + i)));
            doc.endInteraction();
        }
        while (doc.undo()) {
            // undo everything the history still holds
        }
        assertTrue(doc.isDirty(), "the oldest edit fell off the history and is still applied");
        doc.savedTo(PAUSE, doc.archive());
        assertFalse(doc.isDirty());
    }

    @Test
    void movingNothingIsRefused() {
        assertFalse(doc.execute(NodeCommands.move(List.of("quit/button"), NodeLocation.endOf("panel"))));
        assertSame(original, doc.archive());
    }
}
