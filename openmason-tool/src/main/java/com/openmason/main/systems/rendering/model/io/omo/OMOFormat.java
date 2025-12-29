package com.openmason.main.systems.rendering.model.io.omo;

import java.util.Objects;

/**
 * Open Mason Object (.OMO) file format specification.
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with basic geometry dimensions</li>
 *   <li>1.1 - Added custom mesh data support for subdivision (vertices, indices, UVs, face mapping)</li>
 * </ul>
 */
public final class OMOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.1";

    /** Minimum supported format version for reading */
    public static final String MIN_SUPPORTED_VERSION = "1.0";

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

    /**
     * Custom mesh data for subdivided/edited models (v2.0+).
     *
     * <p>When present, this overrides the standard cube generation.
     * Contains all data needed to reconstruct the exact mesh state.
     *
     * @param vertices Vertex positions (x,y,z interleaved), null for standard cube
     * @param texCoords Texture coordinates (u,v interleaved), null for standard cube
     * @param indices Triangle indices, null for non-indexed geometry
     * @param triangleToFaceId Face mapping for triangles (preserves original face ID through subdivision)
     * @param uvMode UV mapping mode ("FLAT" or "CUBE_NET")
     */
    public record MeshData(
            float[] vertices,
            float[] texCoords,
            int[] indices,
            int[] triangleToFaceId,
            String uvMode
    ) {
        /**
         * Check if this mesh data represents custom geometry (not a standard cube).
         *
         * @return true if custom mesh data is present
         */
        public boolean hasCustomGeometry() {
            return vertices != null && vertices.length > 0;
        }

        /**
         * Get the vertex count.
         *
         * @return number of vertices, or 0 if none
         */
        public int getVertexCount() {
            return vertices != null ? vertices.length / 3 : 0;
        }

        /**
         * Get the triangle count.
         *
         * @return number of triangles, or 0 if none
         */
        public int getTriangleCount() {
            return indices != null ? indices.length / 3 : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MeshData meshData)) return false;
            return java.util.Arrays.equals(vertices, meshData.vertices) &&
                    java.util.Arrays.equals(texCoords, meshData.texCoords) &&
                    java.util.Arrays.equals(indices, meshData.indices) &&
                    java.util.Arrays.equals(triangleToFaceId, meshData.triangleToFaceId) &&
                    Objects.equals(uvMode, meshData.uvMode);
        }

        @Override
        public int hashCode() {
            int result = java.util.Arrays.hashCode(vertices);
            result = 31 * result + java.util.Arrays.hashCode(texCoords);
            result = 31 * result + java.util.Arrays.hashCode(indices);
            result = 31 * result + java.util.Arrays.hashCode(triangleToFaceId);
            result = 31 * result + Objects.hashCode(uvMode);
            return result;
        }
    }

    /**
     * Extended document structure (v1.1+) with optional mesh data.
     *
     * <p>Extends the base Document to include custom mesh data for
     * subdivided or edited models.
     *
     * @param version Format version
     * @param objectName Model name
     * @param modelType Model type identifier
     * @param geometry Base geometry dimensions (used for standard cubes or as reference)
     * @param textureFile Embedded texture filename
     * @param mesh Custom mesh data (null for standard cube, present for edited models)
     */
    public record ExtendedDocument(
            String version,
            String objectName,
            String modelType,
            GeometryData geometry,
            String textureFile,
            MeshData mesh
    ) {
        public ExtendedDocument {
            Objects.requireNonNull(version, "version cannot be null");
            Objects.requireNonNull(objectName, "objectName cannot be null");
            Objects.requireNonNull(modelType, "modelType cannot be null");
            Objects.requireNonNull(geometry, "geometry cannot be null");
            Objects.requireNonNull(textureFile, "textureFile cannot be null");
            // mesh can be null (standard cube)
        }

        /**
         * Check if this document has custom mesh data.
         *
         * @return true if custom mesh data is present
         */
        public boolean hasCustomMesh() {
            return mesh != null && mesh.hasCustomGeometry();
        }

        /**
         * Convert to base Document for backward compatibility checks.
         *
         * @return Document with same base fields
         */
        public Document toBaseDocument() {
            return new Document(version, objectName, modelType, geometry, textureFile);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ExtendedDocument that)) return false;
            return Objects.equals(version, that.version) &&
                    Objects.equals(objectName, that.objectName) &&
                    Objects.equals(modelType, that.modelType) &&
                    Objects.equals(geometry, that.geometry) &&
                    Objects.equals(textureFile, that.textureFile) &&
                    Objects.equals(mesh, that.mesh);
        }
    }
}
