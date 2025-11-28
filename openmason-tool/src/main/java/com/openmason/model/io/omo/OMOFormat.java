package com.openmason.model.io.omo;

import java.util.Objects;

/**
 * Open Mason Object (.OMO) file format specification.
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
         *
         * <p>Note: Texture format (cube net vs flat) is auto-detected
         * from the embedded .OMT file dimensions at load time.
         */
        public record Document(String version, String objectName, String modelType, GeometryData geometry,
                               String textureFile) {
            public Document(String version, String objectName, String modelType,
                            GeometryData geometry, String textureFile) {
                this.version = Objects.requireNonNull(version, "version cannot be null");
                this.objectName = Objects.requireNonNull(objectName, "objectName cannot be null");
                this.modelType = Objects.requireNonNull(modelType, "modelType cannot be null");
                this.geometry = Objects.requireNonNull(geometry, "geometry cannot be null");
                this.textureFile = Objects.requireNonNull(textureFile, "textureFile cannot be null");
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Document document)) return false;
                return Objects.equals(version, document.version) &&
                        Objects.equals(objectName, document.objectName) &&
                        Objects.equals(modelType, document.modelType) &&
                        Objects.equals(geometry, document.geometry) &&
                        Objects.equals(textureFile, document.textureFile);
            }

    }

    /**
         * Geometry data structure for manifest.
         *
         * <p>Stores dimensional and positional information for the model.
         */
        public record GeometryData(int width, int height, int depth, Position position) {
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

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof GeometryData that)) return false;
                return width == that.width &&
                        height == that.height &&
                        depth == that.depth &&
                        Objects.equals(position, that.position);
            }

    }

    /**
         * 3D position data structure.
         */
        public record Position(double x, double y, double z) {

        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Position position)) return false;
                return Double.compare(position.x, x) == 0 &&
                        Double.compare(position.y, y) == 0 &&
                        Double.compare(position.z, z) == 0;
            }

    }
}
