package com.openmason.engine.format.omsc;

import com.openmason.engine.format.omo.OMOFormat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Open Mason Scene (.OMSC) file format specification.
 *
 * <p>An OMSC is a ZIP container describing a composition: a flat list of placed .OMO
 * model instances plus the scene's own camera and viewport state. It is an authoring
 * document, not a game-ready export — SBO/SBE remain the runtime payloads.
 *
 * <p>ZIP structure:
 * <ul>
 *   <li>{@code manifest.json} — scene metadata, model index, instance list, camera, viewport</li>
 *   <li>{@code models/&lt;modelId&gt;/model.omo} — one embedded OMO per DISTINCT referenced model</li>
 * </ul>
 *
 * <p>The payload is deliberately <em>hybrid</em>: every referenced model is recorded both
 * as a project-relative path AND embedded verbatim. The path wins on open, so edits to
 * the live .omo propagate into every scene that places it; the embedded copy is the
 * fallback that makes a scene renderable when carried into a different project, and the
 * source for "Import to project".
 *
 * <p>Version history:
 * <ul>
 *   <li>1.0 — Initial format. Flat list of OMO instances (id, name, model ref, TRS
 *       transform, visible/locked) plus scene camera and viewport state. Instances
 *       sharing a model share one embedded entry, deduplicated by the SHA-256 content
 *       hash of the OMO bytes. No lights, no grouping or hierarchy, no per-instance
 *       geometry overrides.</li>
 * </ul>
 */
public final class OMSCFormat {

    public static final String FORMAT_VERSION = "1.0";
    public static final String FILE_EXTENSION = ".omsc";
    public static final String MANIFEST_FILENAME = "manifest.json";
    public static final String MODELS_DIR_PREFIX = "models/";
    public static final String EMBEDDED_MODEL_FILENAME = "model.omo";
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    /** Length of the content-hash prefix used as a model id. */
    public static final int MODEL_ID_LENGTH = 16;

    private OMSCFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** ZIP entry path for an embedded model. */
    public static String modelEntryPath(String modelId) {
        return MODELS_DIR_PREFIX + modelId + "/" + EMBEDDED_MODEL_FILENAME;
    }

    public static String ensureExtension(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return filePath;
        }
        String trimmed = filePath.trim();
        return trimmed.toLowerCase().endsWith(FILE_EXTENSION) ? trimmed : trimmed + FILE_EXTENSION;
    }

    public static boolean hasOMSCExtension(String filePath) {
        return filePath != null && filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * One distinct model the scene places, recorded both by reference and by value.
     *
     * @param modelId    content-hash id; also the ZIP directory name
     * @param path       project-root-relative source path (e.g. {@code "Well.omo"}), or an
     *                   absolute path when the model lives outside the project; null when
     *                   the model exists only as an embedded copy
     * @param sourceName original file name, used when importing the embedded copy back
     *                   into a project
     * @param file       ZIP entry path of the embedded bytes
     * @param checksum   full SHA-256 hex of the embedded bytes
     * @param size       embedded byte length
     */
    public record ModelRef(String modelId, String path, String sourceName,
                           String file, String checksum, long size) {
        public ModelRef {
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId cannot be blank");
            }
            if (file == null || file.isBlank()) {
                throw new IllegalArgumentException("file cannot be blank");
            }
        }
    }

    /**
     * One placement of a model.
     *
     * @param transform null is normalized to {@link OMOFormat.ModelTransform#identity()},
     *                  which is how an omitted transform in the JSON reads back
     */
    public record InstanceEntry(String id, String name, String modelId,
                                OMOFormat.ModelTransform transform,
                                boolean visible, boolean locked) {
        public InstanceEntry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("instance id cannot be blank");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("instance modelId cannot be blank");
            }
            if (name == null || name.isBlank()) {
                name = "Instance";
            }
            if (transform == null) {
                transform = OMOFormat.ModelTransform.identity();
            }
        }
    }

    /** The scene's saved viewpoint. */
    public record CameraState(String mode, float distance, float pitch, float yaw, float fov,
                              float targetX, float targetY, float targetZ) {
        public CameraState {
            if (mode == null || mode.isBlank()) {
                mode = "ARCBALL";
            }
        }
    }

    /** The scene's saved display toggles. */
    public record ViewportState(int viewModeIndex, int renderModeIndex,
                                boolean gridVisible, boolean axesVisible, boolean unrenderedMode,
                                boolean showVertices, boolean showGizmo,
                                boolean gridSnappingEnabled, float gridSnappingIncrement) {
    }

    /**
     * A whole scene.
     *
     * <p>The compact constructor enforces the referential integrity the rest of the
     * pipeline assumes: unique model ids, unique instance ids, and every instance
     * pointing at a model that actually exists. Catching that here means a corrupt or
     * hand-edited file fails at parse rather than as a confusing NPE during rendering.
     */
    public record Document(String version, String sceneName, String author, String description,
                           String createdAt, String lastSavedAt,
                           List<ModelRef> models, List<InstanceEntry> instances,
                           CameraState camera, ViewportState viewport) {
        public Document {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("version cannot be blank");
            }
            if (sceneName == null || sceneName.isBlank()) {
                sceneName = "Untitled Scene";
            }
            models = models == null ? List.of() : List.copyOf(models);
            instances = instances == null ? List.of() : List.copyOf(instances);

            Set<String> modelIds = new HashSet<>();
            for (ModelRef ref : models) {
                if (!modelIds.add(ref.modelId())) {
                    throw new IllegalArgumentException("duplicate modelId: " + ref.modelId());
                }
            }
            Set<String> instanceIds = new HashSet<>();
            for (InstanceEntry instance : instances) {
                if (!instanceIds.add(instance.id())) {
                    throw new IllegalArgumentException("duplicate instance id: " + instance.id());
                }
                if (!modelIds.contains(instance.modelId())) {
                    throw new IllegalArgumentException(
                            "instance '" + instance.id() + "' references unknown modelId: " + instance.modelId());
                }
            }
        }

        public ModelRef modelById(String modelId) {
            for (ModelRef ref : models) {
                if (ref.modelId().equals(modelId)) {
                    return ref;
                }
            }
            return null;
        }

        public List<InstanceEntry> instancesOf(String modelId) {
            return instances.stream().filter(i -> i.modelId().equals(modelId)).toList();
        }

        public boolean hasInstances() {
            return !instances.isEmpty();
        }
    }
}
