package com.openmason.model.io.omo;

import java.util.Objects;

/**
 * Open Mason Object (.OMO) file format specification.
 *
 * <p>The .OMO format is a ZIP-based container that stores editable 3D models
 * for Open Mason. The format includes model geometry, metadata, and embedded
 * textures.
 *
 * <p>File Structure:
 * <pre>
 * model.omo (ZIP archive)
 * ├── manifest.json    - Model metadata and geometry definition
 * └── texture.omt      - Embedded texture file (Open Mason Texture format)
 * </pre>
 *
 * <p>The manifest.json contains:
 * <ul>
 *   <li>version: Format version (currently "1.0")</li>
 *   <li>objectName: Display name of the model</li>
 *   <li>modelType: Type identifier (e.g., "SINGLE_CUBE")</li>
 *   <li>geometry: Spatial and dimensional properties</li>
 *   <li>textureFile: Reference to embedded .OMT texture</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - defines format specification only</li>
 *   <li>DRY: Reuses .OMT format for texture storage</li>
 *   <li>KISS: Simple ZIP container with JSON manifest</li>
 *   <li>YAGNI: Only essential fields for current functionality</li>
 * </ul>
 *
 * @since 1.0
 */
public final class OMOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.0";

    /** File extension for OMO files */
    public static final String FILE_EXTENSION = ".omo";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Default texture filename in ZIP archive */
    public static final String DEFAULT_TEXTURE_FILENAME = "texture.omt";

    /** Private constructor - utility class */
    private OMOFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures a file path has the .omo extension.
     *
     * @param filePath the file path
     * @return the file path with .omo extension
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
     * Checks if a file path has the .omo extension.
     *
     * @param filePath the file path to check
     * @return true if the path ends with .omo
     */
    public static boolean hasOMOExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * Complete .OMO document structure.
     *
     * <p>Represents the entire contents of an .OMO file including
     * manifest data and texture reference.
     */
    public static class Document {
        private final String version;
        private final String objectName;
        private final String modelType;
        private final GeometryData geometry;
        private final String textureFile;

        public Document(String version, String objectName, String modelType,
                       GeometryData geometry, String textureFile) {
            this.version = Objects.requireNonNull(version, "version cannot be null");
            this.objectName = Objects.requireNonNull(objectName, "objectName cannot be null");
            this.modelType = Objects.requireNonNull(modelType, "modelType cannot be null");
            this.geometry = Objects.requireNonNull(geometry, "geometry cannot be null");
            this.textureFile = Objects.requireNonNull(textureFile, "textureFile cannot be null");
        }

        public String getVersion() { return version; }
        public String getObjectName() { return objectName; }
        public String getModelType() { return modelType; }
        public GeometryData getGeometry() { return geometry; }
        public String getTextureFile() { return textureFile; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Document)) return false;
            Document document = (Document) o;
            return Objects.equals(version, document.version) &&
                   Objects.equals(objectName, document.objectName) &&
                   Objects.equals(modelType, document.modelType) &&
                   Objects.equals(geometry, document.geometry) &&
                   Objects.equals(textureFile, document.textureFile);
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, objectName, modelType, geometry, textureFile);
        }
    }

    /**
     * Geometry data structure for manifest.
     *
     * <p>Stores dimensional and positional information for the model.
     */
    public static class GeometryData {
        private final int width;
        private final int height;
        private final int depth;
        private final Position position;

        public GeometryData(int width, int height, int depth, Position position) {
            if (width <= 0 || height <= 0 || depth <= 0) {
                throw new IllegalArgumentException(
                    String.format("Dimensions must be positive: %dx%dx%d", width, height, depth)
                );
            }
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.position = Objects.requireNonNull(position, "position cannot be null");
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getDepth() { return depth; }
        public Position getPosition() { return position; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GeometryData)) return false;
            GeometryData that = (GeometryData) o;
            return width == that.width &&
                   height == that.height &&
                   depth == that.depth &&
                   Objects.equals(position, that.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(width, height, depth, position);
        }
    }

    /**
     * 3D position data structure.
     */
    public static class Position {
        private final double x;
        private final double y;
        private final double z;

        public Position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Position)) return false;
            Position position = (Position) o;
            return Double.compare(position.x, x) == 0 &&
                   Double.compare(position.y, y) == 0 &&
                   Double.compare(position.z, z) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }
}
