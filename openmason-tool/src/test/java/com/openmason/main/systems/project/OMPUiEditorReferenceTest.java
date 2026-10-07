package com.openmason.main.systems.project;

import com.openmason.main.systems.layout.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The .omp UI Editor node (#293; 1.4 adds detached + settings): round trip, and older projects untouched. */
class OMPUiEditorReferenceTest {

    @TempDir
    Path tempDir;

    private static OMPFormat.Document doc(OMPFormat.UiEditorReference ui) {
        return new OMPFormat.Document(OMPFormat.FORMAT_VERSION, "Proj", null, null,
                new OMPFormat.CameraState("ARCBALL", 10, 20, 30, 45),
                new OMPFormat.ViewportState(0, 0, true, true, false, false, true, false, 0.25f),
                OMPFormat.TransformData.editorOnly(true),
                new OMPFormat.ModelReference("BLOCK_MODEL", "m", "default", null, null, "NONE", null),
                new OMPFormat.UIState(true, true, true), null,
                new OMPFormat.SceneReference(null, "MODEL_EDITOR"), ui);
    }

    private OMPFormat.Document roundTrip(OMPFormat.Document d) throws Exception {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(d, file.toString()));
        return new OMPDeserializer().load(file.toString());
    }

    @Test
    void uiEditorNodeRoundTrips() throws Exception {
        OMPFormat.Document loaded = roundTrip(doc(new OMPFormat.UiEditorReference("UI",
                List.of("UI/stonebreak/ui/screens/pause.omui", "UI/stonebreak/ui/components/button.omui"),
                "UI/stonebreak/ui/screens/pause.omui")));
        assertNotNull(loaded.uiEditor());
        assertEquals(Workspace.UI, Workspace.resolve(loaded.uiEditor().workspace()));
        assertEquals(2, loaded.uiEditor().documents().size());
        assertEquals("UI/stonebreak/ui/screens/pause.omui", loaded.uiEditor().activeDocument());
        assertEquals("MODEL_EDITOR", loaded.scene().activeCenterTab(), "the scene node is unaffected");
    }

    @Test
    void detachedTabsAndCanvasSettingsRoundTrip() throws Exception {
        OMPFormat.Document loaded = roundTrip(doc(new OMPFormat.UiEditorReference("MODELING",
                List.of("UI/a.omui"), "UI/a.omui", List.of("UI"), java.util.Map.of("snapGrid", "true", "gridStep", "16"))));
        assertEquals(List.of("UI"), loaded.uiEditor().detached());
        assertEquals("16", loaded.uiEditor().settings().get("gridStep"));
        assertEquals("true", loaded.uiEditor().settings().get("snapGrid"));
    }

    @Test
    void aVersion13NodeReadsAsDockedWithDefaultSettings() throws Exception {
        Path file = tempDir.resolve("v13.omp");
        Files.writeString(file, "{\"version\":\"1.3\",\"projectName\":\"P\",\"uiEditor\":"
                + "{\"workspace\":\"UI\",\"documents\":[\"UI/a.omui\"]}}", StandardCharsets.UTF_8);
        OMPFormat.UiEditorReference ui = new OMPDeserializer().load(file.toString()).uiEditor();
        assertTrue(ui.detached().isEmpty());
        assertTrue(ui.settings().isEmpty());
        assertEquals(new OMPFormat.UiEditorReference("UI", List.of("UI/a.omui"), null), ui);
        // a docked node with no settings writes neither key, so a 1.3 reader sees its old shape
        Path again = tempDir.resolve("again13.omp");
        assertTrue(new OMPSerializer().save(doc(ui), again.toString()));
        String text = Files.readString(again);
        assertFalse(text.contains("detached") || text.contains("settings"), text);
    }

    @Test
    void olderProjectsHaveNoNodeAndOpenInModeling() throws Exception {
        Path file = tempDir.resolve("old.omp");
        Files.writeString(file, "{\"version\":\"1.2\",\"projectName\":\"Old\",\"scene\":{\"activeCenterTab\":\"SCENE_VIEWER\"}}",
                StandardCharsets.UTF_8);
        OMPFormat.Document loaded = new OMPDeserializer().load(file.toString());
        assertNotNull(loaded);
        assertNull(loaded.uiEditor());
        assertEquals("SCENE_VIEWER", loaded.scene().activeCenterTab());
        assertEquals(Workspace.MODELING, Workspace.resolve(null));
        // re-saving without a supplier writes no node: the file only grows what it had
        Path again = tempDir.resolve("again.omp");
        assertTrue(new OMPSerializer().save(doc(null), again.toString()));
        assertFalse(Files.readString(again).contains("uiEditor"));
    }

    @Test
    void unknownWorkspaceValuesFallBackToModeling() {
        assertEquals(Workspace.MODELING, Workspace.resolve("SOMETHING_NEW"));
        assertEquals(Workspace.UI, Workspace.resolve("ui"));
        assertEquals("MODELING", new OMPFormat.UiEditorReference(null, null, null).workspace());
    }
}
