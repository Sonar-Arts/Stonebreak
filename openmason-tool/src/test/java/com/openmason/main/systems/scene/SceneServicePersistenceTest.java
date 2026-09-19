package com.openmason.main.systems.scene;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omsc.OMSCFormat;
import com.openmason.engine.format.omsc.OMSCParseResult;
import com.openmason.engine.format.omsc.OMSCParser;
import com.openmason.engine.format.omsc.OMSCSerializer;
import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelSource;
import com.openmason.engine.rendering.viewer.scene.OmoModelLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * What a scene file must carry across open → save: the viewer's camera and display
 * toggles, the author/description metadata, and — the data-safety case — placements
 * whose model could not be loaded.
 */
class SceneServicePersistenceTest {

    @TempDir
    Path tempDir;

    private static final byte[] CUBE_BYTES = "fake-omo-cube".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BROKEN_BYTES = "fake-omo-broken".getBytes(StandardCharsets.UTF_8);

    /** Loads anything except a model whose display name says it is broken. */
    private static final class StubSource implements ModelSource {
        @Override
        public OmoModelLoader.Loaded load(Path path) throws IOException {
            if (path.getFileName().toString().startsWith("broken")) {
                throw new IOException("stub: cannot parse " + path);
            }
            return new OmoModelLoader.Loaded(null, path.toString(), new int[0]);
        }

        @Override
        public OmoModelLoader.Loaded load(byte[] omoBytes, String displayName) throws IOException {
            if (displayName.startsWith("broken")) {
                throw new IOException("stub: cannot parse " + displayName);
            }
            return new OmoModelLoader.Loaded(null, displayName, new int[0]);
        }
    }

    private static SceneService newService() {
        return new SceneService(new ModelCache(new StubSource(), handle -> { }));
    }

    private static OMSCFormat.ModelRef ref(String id, String sourceName) {
        return new OMSCFormat.ModelRef(id, sourceName, sourceName, OMSCFormat.modelEntryPath(id), "", 0);
    }

    private static OMSCFormat.InstanceEntry instance(String id, String name, String modelId, float x) {
        return new OMSCFormat.InstanceEntry(id, name, modelId,
                new OMOFormat.ModelTransform(x, 0, 0, 0, 0, 0, 1, 1, 1), true, false);
    }

    /** A scene with one loadable model and one whose embedded copy is corrupt. */
    private Path writeSceneWithBrokenModel() {
        OMSCFormat.Document doc = new OMSCFormat.Document("1.0", "Square", "chace", "two wells",
                "2026-09-16T10:00:00", "2026-09-16T10:00:00",
                List.of(ref("cube", "cube.omo"), ref("broken", "broken.omo")),
                List.of(instance("i1", "Cube", "cube", 1),
                        instance("i2", "Broken A", "broken", 2),
                        instance("i3", "Broken B", "broken", 3)),
                new OMSCFormat.CameraState("ARCBALL", 12.5f, 28f, 135f, 60f, 0f, 1f, 0f),
                new OMSCFormat.ViewportState(0, 0, false, true, true, false, true, true, 0.5f));
        Path out = tempDir.resolve("square.omsc");
        assertTrue(new OMSCSerializer().save(doc,
                Map.of("cube", CUBE_BYTES, "broken", BROKEN_BYTES), out.toString()));
        return out;
    }

    @Test
    @DisplayName("placements of a model that failed to load are kept as orphans and written back on save")
    void orphansSurviveOpenAndSave() throws IOException {
        Path file = writeSceneWithBrokenModel();
        SceneService service = newService();

        assertTrue(service.openScene(file.toString(), tempDir));
        SceneDocument doc = service.getDocument();
        assertEquals(1, doc.instances().size(), "only the loadable model is placed live");
        assertEquals(2, doc.orphanInstances().size(), "the broken model's placements are kept on paper");
        // Model ids in the file are content hashes, so find the broken one by its name.
        SceneModelRef broken = doc.models().stream()
                .filter(m -> "broken.omo".equals(m.sourceName())).findFirst().orElseThrow();
        assertEquals(ResolutionStatus.MISSING, broken.status());

        assertTrue(service.saveScene(tempDir));

        OMSCParseResult reparsed = new OMSCParser().parse(file);
        assertEquals(3, reparsed.instances().size(), "nothing was dropped by the save");
        assertEquals(2, reparsed.instances().stream()
                .filter(i -> i.name().startsWith("Broken")).count());
        assertEquals("chace", reparsed.manifest().author());
        assertEquals("two wells", reparsed.manifest().description());
    }

    @Test
    @DisplayName("camera and viewport state flow in on open and out on save through the bridge")
    void viewStateRoundTrips() throws IOException {
        Path file = writeSceneWithBrokenModel();
        SceneService service = newService();

        OMSCFormat.CameraState[] cameraSeen = new OMSCFormat.CameraState[1];
        OMSCFormat.ViewportState[] viewportSeen = new OMSCFormat.ViewportState[1];
        service.setViewStateBridge(
                () -> new OMSCFormat.CameraState("FIRST_PERSON", 3f, 10f, 20f, 70f, 4f, 5f, 6f),
                () -> new OMSCFormat.ViewportState(0, 0, true, false, false, false, true, false, 1f),
                c -> cameraSeen[0] = c,
                v -> viewportSeen[0] = v);

        assertTrue(service.openScene(file.toString(), tempDir));
        assertNotNull(cameraSeen[0], "the saved camera reached the viewer");
        assertEquals(135f, cameraSeen[0].yaw(), 1e-4);
        assertEquals(1f, cameraSeen[0].targetY(), 1e-4);
        assertNotNull(viewportSeen[0]);
        assertFalse(viewportSeen[0].gridVisible());
        assertTrue(viewportSeen[0].unrenderedMode());
        assertEquals(0.5f, viewportSeen[0].gridSnappingIncrement(), 1e-6);

        assertTrue(service.saveScene(tempDir));
        OMSCParseResult reparsed = new OMSCParser().parse(file);
        assertEquals("FIRST_PERSON", reparsed.manifest().camera().mode());
        assertEquals(70f, reparsed.manifest().camera().fov(), 1e-4);
        assertEquals(6f, reparsed.manifest().camera().targetZ(), 1e-4);
        assertTrue(reparsed.manifest().viewport().gridVisible());
        assertEquals(1f, reparsed.manifest().viewport().gridSnappingIncrement(), 1e-6);
    }

    @Test
    @DisplayName("a file without view state leaves the viewer alone")
    void missingViewStateIsNotApplied() throws IOException {
        OMSCFormat.Document doc = new OMSCFormat.Document("1.0", "Bare", null, null,
                "2026-09-16T10:00:00", "2026-09-16T10:00:00",
                List.of(ref("cube", "cube.omo")), List.of(instance("i1", "Cube", "cube", 0)),
                null, null);
        Path file = tempDir.resolve("bare.omsc");
        assertTrue(new OMSCSerializer().save(doc, Map.of("cube", CUBE_BYTES), file.toString()));

        SceneService service = newService();
        boolean[] touched = {false};
        service.setViewStateBridge(null, null, c -> touched[0] = true, v -> touched[0] = true);
        assertTrue(service.openScene(file.toString(), tempDir));
        assertFalse(touched[0]);
    }

    @Test
    @DisplayName("saveIntoProject writes an untitled scene into Scenes/ under its name, without clobbering")
    void saveIntoProjectNamesUntitledScenes() throws IOException {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);

        SceneService first = newService();
        first.newScene("Untitled Scene");
        ModelCache cache = new ModelCache(new StubSource(), handle -> { });
        ModelHandle handle = cache.acquireBytes("cube", CUBE_BYTES, "cube.omo");
        first.getDocument().registerModel(new SceneModelRef("cube", "cube.omo", null, "cube.omo",
                CUBE_BYTES, handle, ResolutionStatus.REFERENCED));
        first.placeInstance(first.getDocument().modelBySessionId("cube"), "Cube", 0, 0, 0);

        assertTrue(first.saveIntoProject(root));
        Path expected = root.resolve("Scenes").resolve("Untitled Scene.omsc");
        assertTrue(Files.exists(expected), "landed in Scenes/ under the scene name");
        assertEquals(expected.toString(), first.getCurrentScenePath());
        assertFalse(first.hasUnsavedChanges());

        SceneService second = newService();
        second.newScene("Untitled Scene");
        assertTrue(second.saveIntoProject(root));
        assertTrue(Files.exists(root.resolve("Scenes").resolve("Untitled Scene 2.omsc")),
                "a sibling with the same name is not overwritten");

        // Already-titled scenes go straight to their own file.
        assertTrue(first.saveIntoProject(root));
        assertEquals(expected.toString(), first.getCurrentScenePath());
    }

    @Test
    @DisplayName("saveIntoProject refuses an untitled scene when no project is open")
    void saveIntoProjectNeedsARoot() {
        SceneService service = newService();
        service.newScene("Loose");
        assertFalse(service.saveIntoProject(null));
        assertNull(service.getCurrentScenePath());
    }
}
