package com.openmason.main.systems.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Turning asset paths into project-relative references and back.
 *
 * <p>Extracted from {@code ProjectService} so the scene layer anchors paths exactly the
 * way the project file does — two copies of the relativize-or-fall-back-to-absolute rule
 * would eventually disagree about what a stored path means.
 */
public final class ProjectPaths {

    private static final Logger logger = LoggerFactory.getLogger(ProjectPaths.class);

    private ProjectPaths() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Make {@code file} relative to {@code root} for storage.
     *
     * <p>Falls back to the absolute path when relativizing is impossible (a different
     * Windows drive, or an asset outside the project). A stored absolute path still opens
     * on the machine that wrote it, which beats refusing to save.
     */
    public static String relativize(Path root, String file) {
        if (file == null || file.isBlank() || root == null) {
            return file;
        }
        try {
            return root.toAbsolutePath().relativize(Path.of(file).toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            logger.debug("Cannot relativize '{}' against '{}', storing absolute: {}", file, root, e.getMessage());
            return file;
        }
    }

    /** Resolve a stored reference against {@code root}; absolute references pass through. */
    public static String resolve(Path root, String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        Path path = Path.of(stored);
        if (path.isAbsolute()) {
            return path.toString();
        }
        if (root == null) {
            return stored;
        }
        return root.resolve(path).toAbsolutePath().toString();
    }
}
