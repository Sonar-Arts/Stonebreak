package com.openmason.main.systems.rendering.model.io.omo;

import java.util.List;
import java.util.Objects;

/**
 * Open Mason Object (.OMO) file format specification.
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with basic geometry dimensions</li>
 *   <li>1.1 - Added custom mesh data support for subdivision (vertices, indices, UVs, face mapping)</li>
 *   <li>1.2 - Added per-face texture persistence (face mappings, material entries, material PNGs)</li>
 *   <li>1.3 - Added model part entries for multi-part models (part transforms, mesh ranges)</li>
 * </ul>
 */
public final class OMOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.3";

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
     * Per-face UV mapping entry for serialization (v1.2+).
     *
     * @param faceId           face identifier
     * @param materialId       material this face uses
     * @param u0               UV region left
     * @param v0               UV region top
     * @param u1               UV region right
     * @param v1               UV region bottom
     * @param uvRotationDegrees rotation in degrees (0, 90, 180, 270)
     */
    public record FaceMappingEntry(int faceId, int materialId,
                                   float u0, float v0, float u1, float v1,
                                   int uvRotationDegrees) {}

    /**
     * Material definition entry for serialization (v1.2+).
     *
     * @param materialId  unique material identifier
     * @param name        display name
     * @param textureFile filename of the material's PNG inside the ZIP (e.g. "material_1.png")
     * @param renderLayer render layer name (OPAQUE, CUTOUT, TRANSLUCENT)
     * @param emissive    whether the material is emissive
     * @param tintColor   RGBA tint color
     */
    public record MaterialEntry(int materialId, String name, String textureFile,
                                String renderLayer, boolean emissive, int tintColor) {}

    /**
     * Aggregated per-face texture data for serialization (v1.2+).
     *
     * @param mappings  face-to-material mappings
     * @param materials material definitions (excluding the default material)
     */
    public record FaceTextureData(List<FaceMappingEntry> mappings,
                                  List<MaterialEntry> materials) {}

    /**
     * Model part entry for multi-part models (v1.3+).
     *
     * <p>Describes a single named part within a model, including its local transform,
     * mesh range in the combined buffer, and visibility/lock state. When the parts
     * list is null or empty, the entire mesh is treated as one implicit "Root" part
     * (backward compatible with pre-1.3 files).
     *
     * @param id          Unique part identifier (UUID string)
     * @param name        User-facing display name
     * @param originX     Transform pivot X
     * @param originY     Transform pivot Y
     * @param originZ     Transform pivot Z
     * @param posX        Translation offset X
     * @param posY        Translation offset Y
     * @param posZ        Translation offset Z
     * @param rotX        Euler rotation X (degrees)
     * @param rotY        Euler rotation Y (degrees)
     * @param rotZ        Euler rotation Z (degrees)
     * @param scaleX      Scale factor X
     * @param scaleY      Scale factor Y
     * @param scaleZ      Scale factor Z
     * @param vertexStart First vertex index in combined buffer
     * @param vertexCount Number of vertices this part owns
     * @param indexStart  First index position in combined index buffer
     * @param indexCount  Number of indices this part owns
     * @param faceStart   First face ID this part owns
     * @param faceCount   Number of faces this part owns
     * @param visible     Whether this part is rendered
     * @param locked      Whether this part is protected from editing
     */
    public record PartEntry(
            String id, String name,
            float originX, float originY, float originZ,
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float scaleX, float scaleY, float scaleZ,
            int vertexStart, int vertexCount,
            int indexStart, int indexCount,
            int faceStart, int faceCount,
            boolean visible, boolean locked
    ) {}

    /**
     * Custom mesh data for subdivided/edited models (v2.0+).
     *
     * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current texture assignment uses raw
     * texCoords array with simple uvMode string. Future versions will support:
     * <ul>
     *   <li>Per-face texture atlas coordinate specification</li>
     *   <li>Texture transformation (rotation, scale, offset) per face</li>
     *   <li>Texture wrapping and tiling modes</li>
     *   <li>Support for arbitrary geometry (not cube-locked)</li>
     * </ul>
     *
     * <p>When present, this overrides the standard cube generation.
     * Contains all data needed to reconstruct the exact mesh state.
     *
     * @param vertices Vertex positions (x,y,z interleaved), null for standard cube
     * @param texCoords Texture coordinates (u,v interleaved), null for standard cube - LEGACY: Will be replaced with per-face texture mapping
     * @param indices Triangle indices, null for non-indexed geometry
     * @param triangleToFaceId Face mapping for triangles (preserves original face ID through subdivision)
     * @param uvMode UV mapping mode ("FLAT" or "CUBE_NET") - LEGACY: Cube-specific, will be replaced with flexible texture assignment
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
     * Extended document structure (v1.1+) with optional mesh data and face textures.
     *
     * <p>Extends the base Document to include custom mesh data for
     * subdivided or edited models, and per-face texture data (v1.2+).
     *
     * @param version Format version
     * @param objectName Model name
     * @param modelType Model type identifier
     * @param geometry Base geometry dimensions (used for standard cubes or as reference)
     * @param textureFile Embedded texture filename
     * @param mesh Custom mesh data (null for standard cube, present for edited models)
     * @param faceTextures Per-face texture data (null for pre-1.2 files)
     */
    public record ExtendedDocument(
            String version,
            String objectName,
            String modelType,
            GeometryData geometry,
            String textureFile,
            MeshData mesh,
            FaceTextureData faceTextures
    ) {
        public ExtendedDocument {
            Objects.requireNonNull(version, "version cannot be null");
            Objects.requireNonNull(objectName, "objectName cannot be null");
            Objects.requireNonNull(modelType, "modelType cannot be null");
            Objects.requireNonNull(geometry, "geometry cannot be null");
            Objects.requireNonNull(textureFile, "textureFile cannot be null");
            // mesh can be null (standard cube)
            // faceTextures can be null (pre-1.2 files)
        }

        /**
         * Backward-compatible constructor for pre-1.2 code paths.
         */
        public ExtendedDocument(String version, String objectName, String modelType,
                                GeometryData geometry, String textureFile, MeshData mesh) {
            this(version, objectName, modelType, geometry, textureFile, mesh, null);
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
         * Check if this document has per-face texture data.
         *
         * @return true if face texture data is present
         */
        public boolean hasFaceTextures() {
            return faceTextures != null
                    && faceTextures.mappings() != null
                    && !faceTextures.mappings().isEmpty();
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
                    Objects.equals(mesh, that.mesh) &&
                    Objects.equals(faceTextures, that.faceTextures);
        }
    }
}
