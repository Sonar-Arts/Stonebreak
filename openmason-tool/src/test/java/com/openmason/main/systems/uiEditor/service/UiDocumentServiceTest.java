package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.document.UiTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Document lifecycle of the UI editor: create, save, reopen, SBUI copies, import, recovery (#293). */
class UiDocumentServiceTest {

    static final Path FIXTURES = Path.of("../openmason-engine/src/test/resources/ui/omui");

    @TempDir
    Path project;
    @TempDir
    Path recoveryDir;

    private UiDocumentService service;

    @BeforeEach
    void setUp() {
        service = new UiDocumentService(new UiProjectContext(() -> project), new UiRecoveryService(recoveryDir));
        service.setWorkspaceStamp(d -> UiBytes.utf8("{\"zoom\":1}"));
    }

    @Test
    void newDocumentsSaveAtTheirConventionPathAndReopenIdentically() throws Exception {
        UiEditorDocument doc = service.create(UiDocumentTemplates.MENU_SCREEN, "test:ui/screens/main_menu", "Main Menu");
        assertTrue(doc.isDirty(), "an unsaved new document is dirty");
        assertNull(doc.file());
        assertTrue(doc.execute(NodeCommands.setProp(List.of("title"), "text", UiValue.of("Stonebreak"))));

        assertNull(service.save(doc));
        Path expected = project.resolve("UI/test/ui/screens/main_menu.omui");
        assertEquals(expected, doc.file());
        assertFalse(doc.isDirty());
        OmuiArchive back = OmuiReader.read(expected).archive();
        assertEquals(doc.archive(), back, "what is on disk is exactly the document");
        assertNotNull(back.editor().get("editor/workspace.json"), "editor view state is stamped");

        UiDocumentService other = new UiDocumentService(new UiProjectContext(() -> project),
            new UiRecoveryService(recoveryDir));
        UiEditorDocument reopened = other.open(expected).document();
        assertEquals(doc.archive().document(), reopened.archive().document());
        assertSame(reopened, other.open(expected).document(), "opening an open file activates it");
        assertEquals(1, other.documents().size());
    }

    @Test
    void sbuiOpensAsAnEditableCopyThatNeverWritesTheExport() throws Exception {
        Path sbui = project.resolve("pause_menu.sbui");
        Files.copy(FIXTURES.resolve("pause_menu.sbui"), sbui);
        byte[] before = Files.readAllBytes(sbui);
        UiEditorDocument copy = service.open(sbui).document();
        assertEquals(UiEditorDocument.Origin.SBUI_COPY, copy.origin());
        assertNull(copy.file());
        assertTrue(copy.isDirty());
        assertNotNull(copy.archive().dependencies().find("stonebreak:ui/components/stone_button"));
        assertNull(service.save(copy));
        assertArrayEquals(before, Files.readAllBytes(sbui));
        assertTrue(copy.file().toString().endsWith(".omui"));
    }

    @Test
    void importIntoProjectWritesCollectedAssetsAndOpensTheDocument() throws Exception {
        Path sbui = project.resolve("export.sbui");
        Files.copy(FIXTURES.resolve("pause_menu.sbui"), sbui);
        UiDocumentService.OpenResult r = service.importIntoProject(sbui);
        assertNull(r.error(), r.error());
        assertNotNull(r.document().file());
        assertTrue(Files.isRegularFile(r.document().file()));
    }

    @Test
    void missingDependenciesAndTheirNodesSurviveSave() throws Exception {
        Path file = project.resolve("UI/stonebreak/ui/screens/pause_menu.omui");
        Files.createDirectories(file.getParent());
        Files.copy(FIXTURES.resolve("pause_menu.omui"), file);
        UiEditorDocument doc = service.open(file).document();
        OmuiArchive original = doc.archive();
        assertTrue(doc.execute(NodeCommands.setStyle(List.of("panel"), "width", UiValue.of(600))));
        assertNull(service.save(doc));
        OmuiArchive back = OmuiReader.read(file).archive();
        assertEquals(original.dependencies(), back.dependencies(), "unresolvable shared rows are kept, not dropped");
        assertEquals(UiTree.ids(original.document().root()), UiTree.ids(back.document().root()));
        assertEquals(original.scripts(), back.scripts());
        assertEquals(original.graphs(), back.graphs());
    }

    @Test
    void recoveryKeepsWorkAndRestoresAsOneUndoStep() throws Exception {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/r", "R");
        assertNull(service.save(doc));
        Files.setLastModifiedTime(doc.file(), java.nio.file.attribute.FileTime.fromMillis(1000));
        assertTrue(doc.execute(NodeCommands.create("Label", NodeLocation.endOf("root"), null)));
        assertTrue(service.recovery().write(doc));
        OmuiArchive edited = doc.archive();

        UiDocumentService after = new UiDocumentService(new UiProjectContext(() -> project),
            new UiRecoveryService(recoveryDir));
        UiDocumentService.OpenResult r = after.open(doc.file());
        assertNotNull(r.recovery(), "a recovery newer than the file is offered");
        assertTrue(after.restore(r.document(), r.recovery()));
        assertEquals(edited.document(), r.document().archive().document());
        assertTrue(r.document().isDirty());
        r.document().undo();
        assertEquals(0, r.document().archive().document().root().children().size(), "undo returns to the file");
        assertTrue(after.recovery().slots().isEmpty(), "the slot is consumed");

        // untitled documents recover too
        UiEditorDocument untitled = after.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/u", "U");
        assertTrue(after.recovery().write(untitled));
        UiRecoveryService.Slot slot = after.recovery().slots().getFirst();
        assertNull(slot.file());
        UiEditorDocument restored = after.openRecovered(slot).document();
        assertEquals(UiEditorDocument.Origin.RECOVERED, restored.origin());
        assertTrue(restored.isDirty());
    }

    @Test
    void closeDiscardsTheRecoverySlot() {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/c", "C");
        assertTrue(service.recovery().write(doc));
        assertEquals(1, service.recovery().slots().size());
        service.close(doc);
        assertTrue(service.recovery().slots().isEmpty());
        assertTrue(service.documents().isEmpty());
    }

    @Test
    void aCrashedSessionsSlotSurvivesTheReopenedDocument() throws Exception {
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/k", "K");
        assertNull(service.save(doc));
        Files.setLastModifiedTime(doc.file(), java.nio.file.attribute.FileTime.fromMillis(1000));
        assertTrue(doc.execute(NodeCommands.create("Label", NodeLocation.endOf("root"), null)));
        assertTrue(service.recovery().write(doc)); // the session "crashes" here

        UiDocumentService next = new UiDocumentService(new UiProjectContext(() -> project), new UiRecoveryService(recoveryDir));
        UiDocumentService.OpenResult r = next.open(doc.file());
        assertNotNull(r.recovery());
        assertTrue(r.document().execute(NodeCommands.create("Box", NodeLocation.endOf("root"), null)));
        assertTrue(next.recovery().write(r.document())); // its own autosave must not overwrite the crash slot
        next.close(r.document());                          // nor may closing it delete the slot
        assertTrue(Files.exists(r.recovery().archive()), "the crash slot is kept until Restore or Discard");
        assertEquals("label", OmuiReader.read(r.recovery().archive()).archive().document().root().children()
            .getFirst().id());
    }
}
