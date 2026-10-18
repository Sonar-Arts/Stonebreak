package com.openmason.main.systems.project;

import com.openmason.api.target.GameTarget;
import com.openmason.api.target.ResourceRoot;
import com.openmason.main.systems.target.GameTargets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMP 1.5 records the project's game target (#380): round trip, "none" for 1.5 files without the
 * node, and the migration of older files to the target that claims legacy projects.
 */
@Tag("regression")
class OMPGameTargetTest {

    @TempDir
    Path tempDir;

    private static final GameTarget LEGACY = GameTarget.builder("oldgame", "Old Game")
            .resources(ResourceRoot.at(Path.of("."))).legacyDefault(true).build();
    private static final GameTarget OTHER = GameTarget.builder("newgame", "New Game")
            .resources(ResourceRoot.at(Path.of("."))).build();

    private static OMPFormat.Document doc(OMPFormat.TargetReference target) {
        return new OMPFormat.Document(OMPFormat.FORMAT_VERSION, "Proj", null, null, null, null, null, null, null,
                null, null, null, target);
    }

    private Path write(String name, String json) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private OMPFormat.Document load(Path file) {
        return new OMPDeserializer().load(file.toString());
    }

    /** A service that skips the viewport/model restore, which needs a live UI. */
    private static ProjectService headless(GameTargets targets) {
        return new ProjectService(targets) {
            @Override
            public void restoreState(OMPFormat.Document document,
                                     com.openmason.main.systems.ViewportController viewport,
                                     com.openmason.main.systems.stateHandling.ModelState modelState,
                                     com.openmason.main.systems.stateHandling.UIVisibilityState uiState,
                                     com.openmason.main.systems.services.ModelOperationService modelOperations) {
            }
        };
    }

    @Test
    void targetRoundTrips() throws Exception {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(doc(OMPFormat.TargetReference.of("newgame")), file.toString()));
        assertTrue(Files.readString(file).contains("\"target\" : \"newgame\""), Files.readString(file));
        assertEquals(OMPFormat.TargetReference.of("newgame"), load(file).target());
    }

    @Test
    void noTargetWritesNoNodeAndReadsBackAsNone() throws Exception {
        Path file = tempDir.resolve("none.omp");
        assertTrue(new OMPSerializer().save(doc(OMPFormat.TargetReference.NONE), file.toString()));
        assertFalse(Files.readString(file).contains("\"target\""));
        assertEquals(OMPFormat.TargetReference.NONE, load(file).target());
    }

    @Test
    void preTargetFilesAreLegacyAndTargetNullIsNone() throws Exception {
        assertEquals(OMPFormat.TargetReference.LEGACY,
                load(write("v14.omp", "{\"version\":\"1.4\",\"projectName\":\"P\"}")).target());
        assertEquals(OMPFormat.TargetReference.LEGACY,
                load(write("v10.omp", "{\"version\":\"1.0\",\"projectName\":\"P\"}")).target());
        assertEquals(OMPFormat.TargetReference.NONE,
                load(write("v15.omp", "{\"version\":\"1.5\",\"projectName\":\"P\"}")).target());
        assertEquals(OMPFormat.TargetReference.NONE,
                load(write("null.omp", "{\"version\":\"1.5\",\"projectName\":\"P\",\"target\":null}")).target());
        // an old file that somehow names a target keeps it
        assertEquals(OMPFormat.TargetReference.of("newgame"),
                load(write("odd.omp", "{\"version\":\"1.4\",\"target\":\"newgame\"}")).target());
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(OMPFormat.isOlder("1.4", "1.5"));
        assertTrue(OMPFormat.isOlder("1", "1.5"));
        assertFalse(OMPFormat.isOlder("1.5", "1.5"));
        assertFalse(OMPFormat.isOlder("1.10", "1.5"));
        assertFalse(OMPFormat.isOlder("garbage", "1.5"));
    }

    @Test
    void openingAnOldProjectMigratesItToTheLegacyTargetAndSavingRecordsIt() throws Exception {
        Path file = write("old.omp", "{\"version\":\"1.4\",\"projectName\":\"Old\"}");
        GameTargets targets = new GameTargets(() -> List.of(OTHER, LEGACY));
        ProjectService service = headless(targets);

        assertTrue(service.openProject(file.toString(), null, null, null, null));
        assertEquals("oldgame", service.getTargetId());
        assertEquals("oldgame", targets.active().orElseThrow().id());
        assertFalse(service.hasUnsavedChanges(), "the migration is not an edit");

        assertEquals(OMPFormat.TargetReference.of("oldgame"), service.targetReference(), "the next save records it");
    }

    @Test
    void oldProjectWithNoLegacyTargetOpensWithoutOne() throws Exception {
        Path file = write("old.omp", "{\"version\":\"1.4\",\"projectName\":\"Old\"}");
        GameTargets targets = new GameTargets(() -> List.of(OTHER));
        ProjectService service = headless(targets);

        assertTrue(service.openProject(file.toString(), null, null, null, null));
        assertNull(service.getTargetId());
        assertTrue(targets.active().isEmpty(), "a project without a target has none, even with targets installed");
    }

    @Test
    void aMissingTargetIsKeptAndReported() throws Exception {
        Path file = tempDir.resolve("gone.omp");
        assertTrue(new OMPSerializer().save(doc(OMPFormat.TargetReference.of("uninstalled")), file.toString()));
        GameTargets targets = new GameTargets(() -> List.of(OTHER));
        ProjectService service = headless(targets);

        assertTrue(service.openProject(file.toString(), null, null, null, null));
        assertEquals("uninstalled", service.getTargetId(), "kept, so re-saving does not drop the choice");
        assertTrue(targets.isProjectTargetMissing());
        assertTrue(targets.active().isEmpty());
    }

    @Test
    void switchingTargetIsAnEditAndClosingReturnsToTheDefault() throws Exception {
        Path file = tempDir.resolve("p.omp");
        assertTrue(new OMPSerializer().save(doc(OMPFormat.TargetReference.NONE), file.toString()));
        GameTargets targets = new GameTargets(() -> List.of(OTHER, LEGACY));
        ProjectService service = headless(targets);
        assertTrue(service.openProject(file.toString(), null, null, null, null));
        assertTrue(targets.active().isEmpty());

        service.setTargetId("newgame");
        assertTrue(service.hasUnsavedChanges());
        assertEquals("newgame", targets.active().orElseThrow().id());

        service.clearCurrentProject();
        assertNull(service.getTargetId());
        assertEquals("newgame", targets.active().orElseThrow().id(), "no project: the first registered target");
    }
}
