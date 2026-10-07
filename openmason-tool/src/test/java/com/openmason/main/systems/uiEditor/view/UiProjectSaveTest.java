package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Save Project saves the UI documents and the UI editor's state with them. */
class UiProjectSaveTest {

    @TempDir
    Path project;

    private String workspaceJson(Path file) throws Exception {
        return new String(OmuiReader.read(file).archive().editor().get("editor/workspace.json").toArray(),
            StandardCharsets.UTF_8);
    }

    @Test
    void projectSaveWritesEditedDocumentsAndTheViewOfCleanOnes() throws Exception {
        UiEditorWorkspace w = new UiEditorWorkspace(() -> project);
        UiEditorDocument doc = w.service().create(UiDocumentTemplates.MENU_SCREEN, "test:ui/screens/a", "A");
        assertTrue(w.saveAllInPlace().isEmpty(), "a new document saves at its convention path");
        assertFalse(doc.isDirty());

        DocumentViewState v = w.context().view(doc);
        v.frameWidth = 1280;
        v.frameHeight = 720;
        v.transform.set(2f, 40f, 50f);
        assertFalse(doc.isDirty(), "zoom and frame size are view state, not edits");
        assertTrue(w.service().editorStampsChanged(doc));

        assertTrue(w.saveAllInPlace().isEmpty());
        String json = workspaceJson(doc.file());
        assertTrue(json.contains("1280") && json.contains("720"), json);
        assertFalse(w.service().editorStampsChanged(doc), "the file now holds the view");

        UiEditorWorkspace reopened = new UiEditorWorkspace(() -> project);
        UiEditorDocument back = reopened.service().open(doc.file()).document();
        DocumentViewState rv = reopened.context().view(back);
        assertEquals(1280, rv.frameWidth);
        assertEquals(2f, rv.transform.zoom(), 1e-3);
    }

    @Test
    void untouchedDocumentsAreNotRewritten() throws Exception {
        UiEditorWorkspace w = new UiEditorWorkspace(() -> project);
        UiEditorDocument doc = w.service().create(UiDocumentTemplates.BUTTON_COMPONENT, "test:ui/components/b", "B");
        assertTrue(w.saveAllInPlace().isEmpty());
        FileTime old = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(doc.file(), old);
        assertTrue(w.saveAllInPlace().isEmpty());
        assertEquals(old, Files.getLastModifiedTime(doc.file()), "nothing changed, so nothing is written");
    }

    @Test
    void canvasSettingsRoundTripThroughTheProjectNode() {
        UiEditorWorkspace w = new UiEditorWorkspace(() -> project);
        w.context().snapGrid = true;
        w.context().gridStep = 16;
        w.context().showRulers = false;
        w.context().gpuPath = false;
        Map<String, String> recorded = w.sessionSettings();

        UiEditorWorkspace next = new UiEditorWorkspace(() -> project);
        next.restoreSettings(recorded);
        assertTrue(next.context().snapGrid);
        assertEquals(16, next.context().gridStep);
        assertFalse(next.context().showRulers);
        assertFalse(next.context().gpuPath);
        assertEquals(recorded, next.sessionSettings());

        next.restoreSettings(Map.of("gridStep", "lots", "snapGrid", "maybe", "renderer", "vulkan"));
        assertEquals(16, next.context().gridStep, "unreadable values keep the current ones");
        assertTrue(next.context().snapGrid);
        assertFalse(next.context().gpuPath);
    }
}
