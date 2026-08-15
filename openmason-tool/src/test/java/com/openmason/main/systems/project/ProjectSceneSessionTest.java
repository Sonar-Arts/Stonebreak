package com.openmason.main.systems.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Projects re-open the scene that was open when they were saved.
 *
 * <p>These pin the {@code ProjectService} side of that contract: the scene node parsed
 * from the {@code .omp} is handed to the restore hook on open — including the null hand-off
 * for pre-1.2 files, which is how "do not disturb an upgrading user" is expressed.
 *
 * <p>Headless on purpose: {@code restoreState} needs the live viewport, so the test
 * service no-ops it — the live-object restore is not what is under test here, the scene
 * hand-off around it is.
 */
class ProjectSceneSessionTest {

    @TempDir
    Path tempDir;

    /** A document carrying nothing but identity and the scene node. */
    private static OMPFormat.Document sceneOnly(OMPFormat.SceneReference scene) {
        return new OMPFormat.Document(OMPFormat.FORMAT_VERSION, "Proj", null, null,
                null, null, null, null, null, null, scene);
    }

    /** A service that skips the viewport/model restore, which needs a live UI. */
    private static ProjectService headlessService() {
        return new ProjectService() {
            @Override
            public void restoreState(OMPFormat.Document document,
                                     com.openmason.main.systems.ViewportController viewport,
                                     com.openmason.main.systems.stateHandling.ModelState modelState,
                                     com.openmason.main.systems.stateHandling.UIVisibilityState uiState,
                                     com.openmason.main.systems.services.ModelOperationService modelOperations) {
                // Live-object restore is exercised by the running tool, not here.
            }
        };
    }

    @Test
    @DisplayName("opening a project hands its recorded scene to the restore hook")
    void openHandsSceneToRestoreHook() {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(
                sceneOnly(new OMPFormat.SceneReference("Scenes/Town.omsc", "SCENE_VIEWER")),
                file.toString()));

        ProjectService service = headlessService();
        AtomicReference<OMPFormat.SceneReference> restored = new AtomicReference<>();
        service.setSceneRestoreHook(restored::set);

        assertTrue(service.openProject(file.toString(), null, null, null, null));

        assertEquals("Scenes/Town.omsc", restored.get().sceneFilePath());
        assertEquals("SCENE_VIEWER", restored.get().activeCenterTab());
    }

    @Test
    @DisplayName("a pre-1.2 project fires the hook with null, meaning no recorded choice")
    void legacyProjectFiresHookWithNull() throws IOException {
        Path file = tempDir.resolve("legacy.omp");
        Files.writeString(file, """
                { "version": "1.1", "projectName": "Legacy" }
                """, StandardCharsets.UTF_8);

        ProjectService service = headlessService();
        AtomicBoolean invoked = new AtomicBoolean();
        AtomicReference<OMPFormat.SceneReference> restored = new AtomicReference<>();
        service.setSceneRestoreHook(ref -> {
            invoked.set(true);
            restored.set(ref);
        });

        assertTrue(service.openProject(file.toString(), null, null, null, null));

        assertTrue(invoked.get(), "the hook must run so the caller can decide to leave things alone");
        assertNull(restored.get());
    }

    @Test
    @DisplayName("a scene-less save still records the centre tab")
    void sceneLessSaveRecordsTab() {
        // What the app's supplier produces when no scene is open: null path, real tab.
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(
                sceneOnly(new OMPFormat.SceneReference(null, "MODEL_EDITOR")), file.toString()));

        OMPFormat.Document loaded = new OMPDeserializer().load(file.toString());

        assertNull(loaded.scene().sceneFilePath());
        assertEquals("MODEL_EDITOR", loaded.scene().activeCenterTab());
    }

    @Test
    @DisplayName("opening without a hook wired is safe")
    void openWithoutHookIsSafe() {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(
                sceneOnly(new OMPFormat.SceneReference("Scenes/a.omsc", null)), file.toString()));

        assertTrue(headlessService().openProject(file.toString(), null, null, null, null));
    }
}
