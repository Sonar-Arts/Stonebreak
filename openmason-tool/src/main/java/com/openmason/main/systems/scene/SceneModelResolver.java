package com.openmason.main.systems.scene;

import com.openmason.engine.format.omsc.OMSCFormat;
import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.main.systems.project.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Decides where each of a scene's models actually comes from.
 *
 * <p>An {@code .omsc} stores every model twice, so opening one is a resolution problem
 * rather than a load:
 *
 * <ol>
 *   <li><b>Referenced path first.</b> If the project has the file, use it — that is what
 *       makes edits to a shared model show up in every scene that places it. If its bytes
 *       have drifted from the embedded copy, the file still wins; the drift is reported,
 *       not corrected.</li>
 *   <li><b>Embedded copy as fallback</b> when the file is absent (or there is no project
 *       root at all). This is the normal path for a scene carried into another project,
 *       and it is what makes a scene transposable rather than merely portable.</li>
 *   <li><b>Missing</b> only when neither resolves — reachable from a hand-edited or
 *       truncated archive. The instance is kept and drawn as a placeholder rather than
 *       dropped, so re-saving never silently deletes the user's arrangement.</li>
 * </ol>
 */
public class SceneModelResolver {

    private static final Logger logger = LoggerFactory.getLogger(SceneModelResolver.class);

    private final ModelCache modelCache;

    public SceneModelResolver(ModelCache modelCache) {
        this.modelCache = java.util.Objects.requireNonNull(modelCache, "modelCache");
    }

    /**
     * Resolve one model reference.
     *
     * @param ref          the manifest entry
     * @param embedded     bytes carried in the archive, or null
     * @param projectRoot  the open project's root, or null when no project is open
     */
    public SceneModelRef resolve(OMSCFormat.ModelRef ref, byte[] embedded, Path projectRoot) {
        String sourceName = ref.sourceName() != null ? ref.sourceName() : ref.modelId();

        // 1. Referenced path.
        String resolved = ProjectPaths.resolve(projectRoot, ref.path());
        if (resolved != null) {
            Path candidate = Path.of(resolved);
            if (Files.isRegularFile(candidate)) {
                try {
                    ModelHandle handle = modelCache.acquire(candidate);
                    ResolutionStatus status = matchesEmbedded(candidate, ref.checksum())
                            ? ResolutionStatus.REFERENCED
                            : ResolutionStatus.REFERENCED_MODIFIED;
                    if (status == ResolutionStatus.REFERENCED_MODIFIED) {
                        logger.info("Model '{}' has changed since the scene was saved; using the file on disk",
                                sourceName);
                    }
                    return new SceneModelRef(ref.modelId(), sourceName, candidate,
                            ref.path(), embedded, handle, status);
                } catch (IOException e) {
                    logger.warn("Referenced model {} failed to load, falling back to the embedded copy: {}",
                            candidate, e.getMessage());
                }
            }
        }

        // 2. Embedded fallback.
        if (embedded != null && embedded.length > 0) {
            try {
                ModelHandle handle = modelCache.acquireBytes(
                        "embedded:" + ref.modelId(), embedded, sourceName);
                return new SceneModelRef(ref.modelId(), sourceName, null,
                        ref.path(), embedded, handle, ResolutionStatus.EMBEDDED_FALLBACK);
            } catch (IOException e) {
                logger.warn("Embedded copy of '{}' failed to load: {}", sourceName, e.getMessage());
            }
        }

        // 3. Missing.
        logger.warn("Model '{}' could not be resolved from a path or an embedded copy", sourceName);
        return new SceneModelRef(ref.modelId(), sourceName, null,
                ref.path(), embedded, null, ResolutionStatus.MISSING);
    }

    /**
     * Write a model's embedded copy into the project and re-point its reference at it.
     *
     * <p>Naming: reuse the destination when a file of the same name already holds the
     * same bytes (re-importing the same scene twice should not litter the folder), and
     * disambiguate with a numeric suffix only when the name is taken by <em>different</em>
     * content.
     */
    public boolean importToProject(SceneModelRef ref, Path projectRoot) {
        if (projectRoot == null || ref.embeddedBytes() == null || ref.embeddedBytes().length == 0) {
            return false;
        }

        String fileName = ref.sourceName() != null ? ref.sourceName() : ref.sessionId() + ".omo";
        Path destination = uniqueDestination(projectRoot, fileName, ref.embeddedBytes());

        try {
            if (!Files.exists(destination)) {
                Path temp = Files.createTempFile(projectRoot, "import_", ".tmp");
                Files.write(temp, ref.embeddedBytes());
                Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ref.setSourcePath(destination);
            ref.setRelativePath(ProjectPaths.relativize(projectRoot, destination.toString()));
            ref.setStatus(ResolutionStatus.REFERENCED);
            logger.info("Imported '{}' into the project as {}", ref.sourceName(), destination.getFileName());
            return true;
        } catch (IOException e) {
            logger.error("Failed to import '{}' into {}: {}", ref.sourceName(), projectRoot, e.getMessage());
            return false;
        }
    }

    private Path uniqueDestination(Path projectRoot, String fileName, byte[] bytes) {
        Path direct = projectRoot.resolve(fileName);
        if (!Files.exists(direct) || sameContent(direct, bytes)) {
            return direct;
        }

        String stem = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int n = 1; n < 1000; n++) {
            Path candidate = projectRoot.resolve(stem + "_" + n + extension);
            if (!Files.exists(candidate) || sameContent(candidate, bytes)) {
                return candidate;
            }
        }
        return direct;
    }

    private boolean sameContent(Path file, byte[] bytes) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(file), bytes);
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the on-disk file still matches the checksum recorded in the scene. */
    private boolean matchesEmbedded(Path file, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            return true; // nothing to compare against
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(OMSCFormat.CHECKSUM_ALGORITHM);
            String actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
            return actual.equalsIgnoreCase(expectedChecksum);
        } catch (IOException | NoSuchAlgorithmException e) {
            return true; // cannot tell — assume unchanged rather than crying wolf
        }
    }
}
