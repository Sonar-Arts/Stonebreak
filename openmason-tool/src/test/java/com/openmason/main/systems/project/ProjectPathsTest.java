package com.openmason.main.systems.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path anchoring shared by the project and scene layers.
 *
 * <p>This is what makes a scene transposable: a model stored as {@code "Well.omo"}
 * resolves against whichever project is currently open, not the one that saved it.
 */
class ProjectPathsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a file inside the project becomes a bare relative reference")
    void relativizesInsideProject() {
        Path root = tempDir.resolve("MyProject");
        String stored = ProjectPaths.relativize(root, root.resolve("Well.omo").toString());

        assertEquals("Well.omo", stored);
    }

    @Test
    @DisplayName("a relative reference resolves against the open project's root")
    void resolvesAgainstRoot() {
        Path root = tempDir.resolve("MyProject");
        String resolved = ProjectPaths.resolve(root, "Well.omo");

        assertEquals(root.resolve("Well.omo").toAbsolutePath().toString(), resolved);
    }

    @Test
    @DisplayName("the same stored reference resolves into a different project — this is transposition")
    void sameReferenceResolvesPerProject() {
        Path projectA = tempDir.resolve("A");
        Path projectB = tempDir.resolve("B");

        String stored = ProjectPaths.relativize(projectA, projectA.resolve("Well.omo").toString());

        assertTrue(ProjectPaths.resolve(projectB, stored).startsWith(projectB.toString()),
                "a scene carried into project B must look for its models in B");
    }

    @Test
    @DisplayName("an absolute reference passes through resolution untouched")
    void absoluteReferencePassesThrough() {
        Path outside = tempDir.resolve("elsewhere").resolve("Shared.omo").toAbsolutePath();

        assertEquals(outside.toString(), ProjectPaths.resolve(tempDir.resolve("Proj"), outside.toString()));
    }

    @Test
    @DisplayName("a file outside the project relativizes to a parent-relative path, still resolvable")
    void outsideProjectStillRoundTrips() {
        Path root = tempDir.resolve("MyProject");
        Path outside = tempDir.resolve("Shared.omo");

        String stored = ProjectPaths.relativize(root, outside.toString());
        String resolved = ProjectPaths.resolve(root, stored);

        assertEquals(outside.toAbsolutePath().normalize(),
                Path.of(resolved).normalize(), "the round trip must land back on the same file");
    }

    @Test
    @DisplayName("null and blank inputs are handled rather than throwing")
    void nullAndBlankInputs() {
        assertNull(ProjectPaths.resolve(tempDir, null));
        assertNull(ProjectPaths.resolve(tempDir, "  "));
        assertNull(ProjectPaths.relativize(tempDir, null));
        assertEquals("Well.omo", ProjectPaths.resolve(null, "Well.omo"), "no root: pass the reference through");
    }

    @Test
    @DisplayName("ensureScaffold creates the Scenes folder and is safe to repeat")
    void scaffoldIsIdempotent() {
        Path root = tempDir.resolve("MyProject");
        root.toFile().mkdirs();

        ProjectLayout.ensureScaffold(root);
        ProjectLayout.ensureScaffold(root);

        assertTrue(Files.isDirectory(ProjectLayout.scenesDir(root)));
    }

    @Test
    @DisplayName("the project root is the folder holding the .omp")
    void projectRootIsOmpParent() {
        Path omp = tempDir.resolve("MyProject").resolve("MyProject.omp");

        assertEquals(tempDir.resolve("MyProject").toAbsolutePath(), ProjectLayout.projectRoot(omp.toString()));
        assertEquals(tempDir.resolve("MyProject").resolve("Scenes").toAbsolutePath(),
                ProjectLayout.scenesDirFor(omp.toString()));
        assertNull(ProjectLayout.projectRoot(null));
    }
}
