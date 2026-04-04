package com.openmason.main.systems.project;

import java.util.List;

/**
 * Open Mason Project (.OMP) file format specification.
 * Plain JSON format (not ZIP) storing session state and model references.
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with camera, viewport, transform, model reference, and UI state</li>
 *   <li>1.1 - Added model parts list for multi-part models (part transforms, visibility, lock)</li>
 * </ul>
 */
public final class OMPFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.1";

    /** File extension for OMP files */
    public static final String FILE_EXTENSION = ".omp";

    /** Private constructor - utility class */
    private OMPFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures a file path has the .omp extension.
     */
    public static String ensureExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return filePath;
        }

        String trimmed = filePath.trim();
        if (!trimmed.toLowerCase().endsWith(FILE_EXTENSION)) {
            return trimmed + FILE_EXTENSION;
        }
        return trimmed;
    }

    /**
     * Checks if a file path has the .omp extension.
     *
     * @param filePath the file path to check
     * @return true if the path ends with .omp
     */
    public static boolean hasOMPExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * Top-level OMP document containing all session state.
     */
    public record Document(
            String version,
            String projectName,
            String createdAt,
            String lastSavedAt,
            CameraState camera,
            ViewportState viewport,
            TransformData transform,
            ModelReference model,
            UIState ui,
            List<PartData> parts
    ) {
        public Document {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Version cannot be null or blank");
            }
            if (projectName == null) {
                projectName = "Untitled Project";
            }
        }

        /**
         * Backward-compatible constructor for pre-1.1 code paths (no parts).
         */
        public Document(String version, String projectName, String createdAt, String lastSavedAt,
                         CameraState camera, ViewportState viewport, TransformData transform,
                         ModelReference model, UIState ui) {
            this(version, projectName, createdAt, lastSavedAt, camera, viewport, transform, model, ui, null);
        }
    }

    /**
     * Camera position and orientation state.
     */
    public record CameraState(
            String mode,
            float distance,
            float pitch,
            float yaw,
            float fov
    ) {
        public CameraState {
            if (mode == null || mode.isBlank()) {
                mode = "ARCBALL";
            }
        }
    }

    /**
     * Viewport display settings.
     */
    public record ViewportState(
            int viewModeIndex,
            int renderModeIndex,
            boolean gridVisible,
            boolean axesVisible,
            boolean unrenderedMode,
            boolean showVertices,
            boolean showGizmo,
            boolean gridSnappingEnabled,
            float gridSnappingIncrement
    ) {}

    /**
     * Transform state for the model.
     *
     * <p>Position, rotation, and scale are part of the .OMO model data, not the
     * project session. Only editor-specific transform settings (gizmo toggle)
     * are persisted here. Legacy fields are kept for backward-compatible
     * deserialization but are ignored on restore.
     */
    public record TransformData(
            float positionX,
            float positionY,
            float positionZ,
            float rotationX,
            float rotationY,
            float rotationZ,
            float scaleX,
            float scaleY,
            float scaleZ,
            boolean gizmoEnabled
    ) {
        /**
         * Create a TransformData that only stores editor settings (no model positioning).
         */
        public static TransformData editorOnly(boolean gizmoEnabled) {
            return new TransformData(0, 0, 0, 0, 0, 0, 1, 1, 1, gizmoEnabled);
        }
    }

    /**
     * Reference to the model loaded in the project.
     * Model file paths are stored relative to the .OMP file for portability.
     */
    public record ModelReference(
            String renderingMode,
            String modelName,
            String textureVariant,
            String selectedBlockType,
            String selectedItemType,
            String modelSource,
            String modelFilePath
    ) {
        public ModelReference {
            if (renderingMode == null || renderingMode.isBlank()) {
                renderingMode = "BLOCK_MODEL";
            }
            if (modelSource == null || modelSource.isBlank()) {
                modelSource = "NONE";
            }
        }
    }

    /**
     * UI panel visibility state.
     */
    public record UIState(
            boolean showModelBrowser,
            boolean showPropertyPanel,
            boolean showToolbar
    ) {}

    /**
     * Model part data for multi-part models (v1.1+).
     * Stores the part's identity, local transform, and state.
     * Geometry is NOT stored here — it lives in the .OMO file.
     * The OMP file only tracks which parts exist and their transforms.
     *
     * @param id       Unique part identifier (UUID string)
     * @param name     User-facing display name
     * @param originX  Transform pivot X
     * @param originY  Transform pivot Y
     * @param originZ  Transform pivot Z
     * @param posX     Translation offset X
     * @param posY     Translation offset Y
     * @param posZ     Translation offset Z
     * @param rotX     Euler rotation X (degrees)
     * @param rotY     Euler rotation Y (degrees)
     * @param rotZ     Euler rotation Z (degrees)
     * @param scaleX   Scale factor X
     * @param scaleY   Scale factor Y
     * @param scaleZ   Scale factor Z
     * @param visible  Whether this part is rendered
     * @param locked   Whether this part is protected from editing
     */
    public record PartData(
            String id,
            String name,
            float originX, float originY, float originZ,
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float scaleX, float scaleY, float scaleZ,
            boolean visible,
            boolean locked
    ) {
        public PartData {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Part ID cannot be null or blank");
            }
            if (name == null || name.isBlank()) {
                name = "Unnamed Part";
            }
        }
    }
}
