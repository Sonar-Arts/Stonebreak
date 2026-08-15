package com.openmason.main.systems.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Back-compat for the {@code .omp} 1.2 scene reference.
 *
 * <p>The point of these is that upgrading users are not disturbed: a project file written
 * before scenes existed must load cleanly, report no scene, and round-trip without
 * growing a scene node it never had.
 */
class OMPSceneReferenceTest {

    @TempDir
    Path tempDir;

    private static OMPFormat.Document minimal(OMPFormat.SceneReference scene) {
        return new OMPFormat.Document(
                OMPFormat.FORMAT_VERSION, "Proj", "2026-01-01T00:00:00", "2026-01-02T00:00:00",
                new OMPFormat.CameraState("ARCBALL", 10, 20, 30, 45),
                new OMPFormat.ViewportState(0, 0, true, true, false, false, true, false, 0.25f),
                OMPFormat.TransformData.editorOnly(true),
                new OMPFormat.ModelReference("BLOCK_MODEL", "m", "default", null, null, "OMO_FILE", "m.omo"),
                new OMPFormat.UIState(true, true, true),
                null, scene);
    }

    private OMPFormat.Document roundTrip(OMPFormat.Document doc) {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(doc, file.toString()));
        OMPFormat.Document loaded = new OMPDeserializer().load(file.toString());
        assertNotNull(loaded);
        return loaded;
    }

    @Test
    @DisplayName("a scene reference round-trips")
    void sceneReferenceRoundTrips() {
        OMPFormat.Document loaded = roundTrip(minimal(
                new OMPFormat.SceneReference("Scenes/Town.omsc", "SCENE_VIEWER")));

        assertNotNull(loaded.scene());
        assertEquals("Scenes/Town.omsc", loaded.scene().sceneFilePath());
        assertEquals("SCENE_VIEWER", loaded.scene().activeCenterTab());
    }

    @Test
    @DisplayName("a project with no scene emits no scene node at all")
    void sceneLessProjectEmitsNoNode() throws IOException {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(minimal(null), file.toString()));

        String json = Files.readString(file, StandardCharsets.UTF_8);

        assertFalse(json.contains("\"scene\""), "the JSON must be unchanged for scene-less projects");
        assertNull(new OMPDeserializer().load(file.toString()).scene());
    }

    @Test
    @DisplayName("a pre-1.2 project file loads with no scene and defaults to the model editor")
    void legacyProjectLoadsWithoutScene() throws IOException {
        // Exactly what a 1.1 file looks like: no "scene" key anywhere.
        Path file = tempDir.resolve("legacy.omp");
        Files.writeString(file, """
                {
                  "version": "1.1",
                  "projectName": "Legacy",
                  "camera": { "mode": "ARCBALL", "distance": 8.0, "pitch": 15.0, "yaw": 40.0, "fov": 45.0 },
                  "ui": { "showModelBrowser": true, "showPropertyPanel": true, "showToolbar": true }
                }
                """, StandardCharsets.UTF_8);

        OMPFormat.Document loaded = new OMPDeserializer().load(file.toString());

        assertNotNull(loaded);
        assertEquals("Legacy", loaded.projectName());
        assertNull(loaded.scene(), "no scene node means no scene");
    }

    @Test
    @DisplayName("a blank active tab defaults to the model editor, so upgrades do not move the user")
    void blankTabDefaultsToModelEditor() {
        assertEquals("MODEL_EDITOR", new OMPFormat.SceneReference("Scenes/a.omsc", null).activeCenterTab());
        assertEquals("MODEL_EDITOR", new OMPFormat.SceneReference("Scenes/a.omsc", "  ").activeCenterTab());
    }

    @Test
    @DisplayName("the pre-1.2 constructors still work")
    void legacyConstructorsStillCompile() {
        OMPFormat.Document nineArg = new OMPFormat.Document(
                "1.1", "P", null, null, null, null, null, null, null);
        assertNull(nineArg.scene());

        OMPFormat.Document tenArg = new OMPFormat.Document(
                "1.1", "P", null, null, null, null, null, null, null, null);
        assertNull(tenArg.scene());
    }
}
