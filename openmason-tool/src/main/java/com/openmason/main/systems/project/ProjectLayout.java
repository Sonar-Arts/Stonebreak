package com.openmason.main.systems.project;

import com.openmason.main.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Where things live inside a project folder.
 *
 * <p>Projects are otherwise flat — {@code .omo} and {@code .omt} sit beside the
 * {@code .omp}. Scenes get their own subfolder because they are compositions <em>of</em>
 * those assets rather than more of them, and mixing them into the same listing makes the
 * browser harder to scan. Existing assets do not move; there is no migration.
 */
public final class ProjectLayout {

    private static final Logger logger = LoggerFactory.getLogger(ProjectLayout.class);

    /** Subfolder holding a project's {@code .omsc} scenes. */
    public static final String SCENES_DIR = "Scenes";

    private ProjectLayout() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Path scenesDir(Path projectRoot) {
        return projectRoot == null ? null : projectRoot.resolve(SCENES_DIR);
    }

    /** Scenes folder for the project owning the given {@code .omp}, or null. */
    public static Path scenesDirFor(String ompFilePath) {
        Path root = projectRoot(ompFilePath);
        return root == null ? null : scenesDir(root);
    }

    /** The folder a {@code .omp} lives in, which is the project root. */
    public static Path projectRoot(String ompFilePath) {
        if (ompFilePath == null || ompFilePath.isBlank()) {
            return null;
        }
        return Path.of(ompFilePath).toAbsolutePath().getParent();
    }

    /**
     * Ensure the project's folder structure exists. Idempotent, so it is safe to call on
     * every open — that is how projects created before this existed gain a Scenes folder.
     */
    public static void ensureScaffold(Path projectRoot) {
        if (projectRoot == null) {
            return;
        }
        try {
            AppPaths.ensureDir(scenesDir(projectRoot));
        } catch (Exception e) {
            logger.warn("Could not create the Scenes folder under {}: {}", projectRoot, e.getMessage());
        }
    }
}
