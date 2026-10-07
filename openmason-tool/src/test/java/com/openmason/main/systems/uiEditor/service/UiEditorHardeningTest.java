package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-safety fixes of the #282 hardening pass in the UI editor's service layer: this session's
 * own autosave never blocks a save, a dropped document stays recoverable, recovery survives an
 * unsaveable edit, a file changed on disk is not silently overwritten, null editor stamps
 * remove their entry, and a graph-window save merges instead of reverting newer Lua.
 */
class UiEditorHardeningTest {

    @TempDir
    Path project;
    @TempDir
    Path recoveryDir;

    private UiDocumentService service;

    @BeforeEach
    void setUp() {
        service = new UiDocumentService(new UiProjectContext(() -> project), new UiRecoveryService(recoveryDir));
    }

    private UiEditorDocument savedScreen(String id) {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, id, "S");
        assertNull(service.save(doc));
        return doc;
    }

    private void addLabel(UiEditorDocument doc) {
        assertTrue(doc.execute(NodeCommands.create("Label", NodeLocation.endOf("root"), null)), doc.lastMessage());
    }

    @Test
    void theSessionsOwnAutosaveNeverCountsAsANewerRecoveryCopy() throws Exception {
        UiEditorDocument doc = savedScreen("test:ui/screens/own");
        Files.setLastModifiedTime(doc.file(), java.nio.file.attribute.FileTime.fromMillis(1000));
        addLabel(doc);
        assertTrue(service.recovery().write(doc), "the 20 s autosave");
        assertNull(service.recovery().newerThan(doc.file()), "our own slot is the open document's state");
        assertNull(service.save(doc), "so saving is never refused because of it");

        // another session (a crash) still counts
        addLabel(doc);
        assertTrue(service.recovery().write(doc));
        Files.setLastModifiedTime(doc.file(), java.nio.file.attribute.FileTime.fromMillis(1000));
        UiRecoveryService other = new UiRecoveryService(recoveryDir);
        assertNotNull(other.newerThan(doc.file()), "a slot left by another session is offered");
    }

    @Test
    void closingWithUnsavedChangesKeepsThemInRecoveryForTheAuthor() throws Exception {
        UiEditorDocument doc = savedScreen("test:ui/screens/kept");
        Files.setLastModifiedTime(doc.file(), java.nio.file.attribute.FileTime.fromMillis(1000));
        addLabel(doc);
        OmuiArchive edited = doc.archive();
        assertTrue(service.closeKeepingRecovery(doc));
        assertTrue(service.documents().isEmpty());

        UiDocumentService.OpenResult reopened = service.open(doc.file());
        assertNotNull(reopened.recovery(), "the dropped changes are offered on reopen");
        assertTrue(service.restore(reopened.document(), reopened.recovery()));
        assertEquals(edited.document(), reopened.document().archive().document());
    }

    @Test
    void recoveryFallsBackToTheNewestSaveableStateAfterABrokenEdit() throws Exception {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/broken", "B");
        addLabel(doc);
        OmuiArchive good = doc.archive();
        UiManifest m = good.manifest();
        // an edit the writer refuses (a malformed document id)
        assertTrue(doc.execute(UiCommand.of("Break", ctx -> ctx.setDoc(ctx.doc().withManifest(new UiManifest(
            m.schemaVersion(), "Not An Id", m.kind(), m.displayName(), m.uiApi(), m.layoutSemantics(), m.requires(),
            m.hostApis(), m.providers(), m.unknown()))))));

        assertTrue(service.recovery().write(doc), "a slot is still written");
        UiRecoveryService.Slot slot = service.recovery().slots().getFirst();
        assertEquals(good, service.recovery().read(slot), "holding the last state that writes");
        String meta = Files.readString(slot.meta());
        assertTrue(meta.contains("\"skippedEdits\" : 1"), meta);
        assertTrue(doc.lastMessage().contains("cannot be saved"), doc.lastMessage());
    }

    @Test
    void aFileChangedOnDiskIsNotSilentlyOverwritten() throws Exception {
        UiEditorDocument doc = savedScreen("test:ui/screens/disk");
        Path file = doc.file();
        // someone else rewrites the file (a git pull, another editor)
        UiEditorDocument theirs = service.create(UiDocumentTemplates.MENU_SCREEN, "test:ui/screens/other", "O");
        Files.write(file, com.openmason.engine.format.omui.OmuiWriter.write(theirs.archive()));
        addLabel(doc);

        String err = service.save(doc);
        assertNotNull(err, "an in-place save refuses");
        assertTrue(err.contains("changed on disk"), err);
        assertTrue(doc.isDirty());
        assertNull(service.saveAs(doc, file), "Save As over it is the explicit way to replace it");
        assertEquals(doc.archive(), OmuiReader.read(file).archive());
        addLabel(doc);
        assertNull(service.save(doc), "after our own write the fingerprint is ours again");
    }

    @Test
    void reopeningThroughASymlinkActivatesTheOpenDocument() throws Exception {
        UiEditorDocument doc = savedScreen("test:ui/screens/link");
        Path link = project.resolve("alias.omui");
        try {
            Files.createSymbolicLink(link, doc.file());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlinks on this file system
        }
        assertEquals(doc, service.open(link).document(), "one document per real file, never two that clobber");
        assertEquals(1, service.documents().size());
    }

    @Test
    void aNullEditorStampRemovesItsEntry() throws Exception {
        UiEditorDocument doc = savedScreen("test:ui/screens/stamps");
        java.util.Map<String, UiBytes> stamp = new java.util.HashMap<>();
        stamp.put("editor/timeline.json", UiBytes.utf8("{\"zoom\":2}"));
        service.setEditorStamps(d -> stamp);
        addLabel(doc);
        assertNull(service.save(doc));
        assertNotNull(OmuiReader.read(doc.file()).archive().editor().get("editor/timeline.json"));

        stamp.put("editor/timeline.json", null); // the view state went away
        addLabel(doc);
        assertNull(service.save(doc));
        assertNull(OmuiReader.read(doc.file()).archive().editor().get("editor/timeline.json"));
    }

    // ── graph editor saves (three-way merge) ───────────────────────────────

    private static UiGraph graph(String id) {
        return new UiGraph(id, List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    @Test
    void aGraphSaveKeepsLuaEditedMeanwhileAndTakesOnlyItsOwnChanges() {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/g", "G");
        assertTrue(doc.execute(DocumentCommands.createCodeBehind("main", "-- v1")));
        OmuiArchive base = doc.archive(); // the graph window opens here

        // meanwhile the Script panel (or an agent's set_script) edits the code-behind
        assertTrue(doc.execute(DocumentCommands.setScript("main", "-- v2 from the script panel")));

        // the graph window's copy: a new graph plus a module "Convert to script" added
        OmuiArchive edited = base.withGraph(graph("behaviors")).withScript("converted", "-- from graph");
        assertTrue(doc.execute(DocumentCommands.replaceGraphs(edited, base)), doc.lastMessage());

        OmuiArchive after = doc.archive();
        assertEquals("-- v2 from the script panel", after.scripts().get("main"), "newer Lua survives");
        assertEquals("-- from graph", after.scripts().get("converted"));
        assertTrue(after.graphs().containsKey("behaviors"));
        assertEquals("main", after.document().codeBehind());
    }

    @Test
    void aGraphSaveThatWouldRevertLuaBothSidesChangedIsRefused() {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/c", "C");
        assertTrue(doc.execute(DocumentCommands.createCodeBehind("main", "-- v1")));
        OmuiArchive base = doc.archive();
        assertTrue(doc.execute(DocumentCommands.setScript("main", "-- edited here")));
        OmuiArchive before = doc.archive();

        OmuiArchive edited = base.withScript("main", "-- edited in the graph window");
        assertFalse(doc.execute(DocumentCommands.replaceGraphs(edited, base)));
        assertTrue(doc.lastMessage().contains("main"), doc.lastMessage());
        assertEquals(before, doc.archive(), "nothing was taken");
    }

    @Test
    void aGraphRemovedInTheWindowIsRemovedAndOneAddedElsewhereStays() {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/r", "R");
        assertTrue(doc.execute(UiCommand.of("graphs", ctx -> ctx.setDoc(ctx.doc().withGraph(graph("a"))))));
        OmuiArchive base = doc.archive();
        // an agent's put_graph adds another graph meanwhile
        assertTrue(doc.execute(UiCommand.of("agent", ctx -> ctx.setDoc(ctx.doc().withGraph(graph("b"))))));
        OmuiArchive edited = new OmuiArchive(base.manifest(), base.document(), base.styles(), Map.of(),
            base.animations(), base.stateMachines(), base.scripts(), base.dependencies(), base.assets(), base.editor(),
            base.extraEntries());
        assertTrue(doc.execute(DocumentCommands.replaceGraphs(edited, base)), doc.lastMessage());
        assertEquals(java.util.Set.of("b"), doc.archive().graphs().keySet());
    }

    @Test
    void recentStatesListTheStateBeforeEachStepNewestFirst() {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/h", "H");
        OmuiArchive s0 = doc.archive();
        addLabel(doc);
        OmuiArchive s1 = doc.archive();
        assertTrue(doc.execute(NodeCommands.setProp(List.of(doc.primary()), "text", UiValue.of("x"))));
        assertEquals(List.of(s1, s0), doc.history().recentStates());
    }
}
