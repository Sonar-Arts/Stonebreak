package com.openmason.main.systems.project;

/**
 * Open Mason Project (.OMP) file format specification.
 * Plain JSON format (not ZIP) storing session state and model references.
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with camera, viewport, transform, model reference, and UI state</li>
 * </ul>
 */
public final class OMPFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.0";

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
            UIState ui
    ) {
        public Document {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Version cannot be null or blank");
            }
            if (projectName == null) {
                projectName = "Untitled Project";
            }
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
            boolean wireframeMode,
            boolean showVertices,
            boolean showGizmo,
            boolean gridSnappingEnabled,
            float gridSnappingIncrement
    ) {}

    /**
     * Transform state for the model.
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
    ) {}

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
}
