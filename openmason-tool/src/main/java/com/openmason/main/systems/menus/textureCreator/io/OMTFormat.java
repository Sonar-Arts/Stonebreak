package com.openmason.main.systems.menus.textureCreator.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data model for Open Mason Texture (.OMT) file format.
 */
public class OMTFormat {

    // File format constants
    public static final String FILE_EXTENSION = ".omt";
    public static final String FORMAT_VERSION = "1.0";
    public static final String MANIFEST_FILENAME = "manifest.json";
    public static final String LAYER_FILENAME_PREFIX = "layer_";
    public static final String LAYER_FILENAME_SUFFIX = ".png";
    public static final String MATERIAL_FILENAME_PREFIX = "material_";
    public static final String MATERIAL_FILENAME_SUFFIX = ".png";

    /**
     * Complete .OMT file data structure.
     */
    public record Document(String version, CanvasSize canvasSize, List<LayerInfo> layers, int activeLayerIndex,
                           List<FaceMappingInfo> faceMappings, List<MaterialInfo> materials) {

        public Document(String version, CanvasSize canvasSize, List<LayerInfo> layers, int activeLayerIndex,
                        List<FaceMappingInfo> faceMappings, List<MaterialInfo> materials) {
            if (version == null || version.trim().isEmpty()) {
                throw new IllegalArgumentException("Version cannot be null or empty");
            }
            if (canvasSize == null) {
                throw new IllegalArgumentException("Canvas size cannot be null");
            }
            if (layers == null || layers.isEmpty()) {
                throw new IllegalArgumentException("Layers list cannot be null or empty");
            }
            if (activeLayerIndex < 0 || activeLayerIndex >= layers.size()) {
                throw new IllegalArgumentException("Active layer index out of bounds: " + activeLayerIndex);
            }

            this.version = version;
            this.canvasSize = canvasSize;
            this.layers = new ArrayList<>(layers);
            this.activeLayerIndex = activeLayerIndex;
            this.faceMappings = faceMappings != null ? new ArrayList<>(faceMappings) : new ArrayList<>();
            this.materials = materials != null ? new ArrayList<>(materials) : new ArrayList<>();
        }

        /**
         * Backward-compatible constructor — no face mappings or materials.
         */
        public Document(String version, CanvasSize canvasSize, List<LayerInfo> layers, int activeLayerIndex) {
            this(version, canvasSize, layers, activeLayerIndex, List.of(), List.of());
        }

        @Override
        public List<LayerInfo> layers() {
            return Collections.unmodifiableList(layers);
        }

        @Override
        public List<FaceMappingInfo> faceMappings() {
            return Collections.unmodifiableList(faceMappings);
        }

        @Override
        public List<MaterialInfo> materials() {
            return Collections.unmodifiableList(materials);
        }

        @Override
        public String toString() {
            return String.format("OMTDocument{version=%s, canvasSize=%s, layers=%d, activeLayer=%d, faceMappings=%d, materials=%d}",
                    version, canvasSize, layers.size(), activeLayerIndex, faceMappings.size(), materials.size());
        }
    }

    /**
         * Canvas dimensions.
         */
        public record CanvasSize(int width, int height) {
        public CanvasSize {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Canvas dimensions must be positive: " + width + "x" + height);
            }
        }

            @Override
            public String toString() {
                return width + "x" + height;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof CanvasSize other)) return false;
                return width == other.width && height == other.height;
            }

    }

    /**
         * Layer metadata and reference to pixel data file.
         */
        public record LayerInfo(String name, boolean visible, float opacity, String dataFile) {
        public LayerInfo {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Layer name cannot be null or empty");
            }
            if (opacity < 0.0f || opacity > 1.0f) {
                throw new IllegalArgumentException("Opacity must be between 0.0 and 1.0: " + opacity);
            }
            if (dataFile == null || dataFile.trim().isEmpty()) {
                throw new IllegalArgumentException("Data file cannot be null or empty");
            }

        }

            @Override
            public String toString() {
                return String.format("LayerInfo{name=%s, visible=%b, opacity=%.2f, dataFile=%s}",
                        name, visible, opacity, dataFile);
            }
        }

    /**
     * Per-face UV mapping entry for the .OMT file format.
     *
     * @param faceId     Stable face identifier from the mesh topology
     * @param materialId Material this face is assigned to
     * @param u0         Left edge of UV region (0..1)
     * @param v0         Top edge of UV region (0..1)
     * @param u1         Right edge of UV region (0..1)
     * @param v1         Bottom edge of UV region (0..1)
     * @param uvRotation Rotation in degrees (0, 90, 180, or 270)
     */
    public record FaceMappingInfo(int faceId, int materialId,
                                  float u0, float v0, float u1, float v1,
                                  int uvRotation) {
        public FaceMappingInfo {
            if (uvRotation != 0 && uvRotation != 90 && uvRotation != 180 && uvRotation != 270) {
                throw new IllegalArgumentException("uvRotation must be 0, 90, 180, or 270: " + uvRotation);
            }
        }
    }

    /**
     * Material definition entry for the .OMT file format.
     *
     * @param materialId Unique material identifier (0 = default)
     * @param name       Human-readable display name
     * @param renderLayer Render layer as string ("OPAQUE", "CUTOUT", or "TRANSLUCENT")
     * @param textureFile Filename of the material's texture PNG inside the ZIP (e.g., "material_0.png")
     */
    public record MaterialInfo(int materialId, String name, String renderLayer, String textureFile) {
        public MaterialInfo {
            if (materialId < 0) {
                throw new IllegalArgumentException("materialId must be non-negative: " + materialId);
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be null or blank");
            }
            if (renderLayer == null || renderLayer.isBlank()) {
                throw new IllegalArgumentException("renderLayer must not be null or blank");
            }
            if (textureFile == null || textureFile.isBlank()) {
                throw new IllegalArgumentException("textureFile must not be null or blank");
            }
        }
    }

    /**
     * Generate layer filename for given index.
     *
     * @param layerIndex layer index (0-based)
     * @return filename like "layer_0.png"
     */
    public static String generateLayerFilename(int layerIndex) {
        if (layerIndex < 0) {
            throw new IllegalArgumentException("Layer index must be non-negative: " + layerIndex);
        }
        return LAYER_FILENAME_PREFIX + layerIndex + LAYER_FILENAME_SUFFIX;
    }

    /**
     * Generate material texture filename for given index.
     *
     * @param materialIndex material index (0-based)
     * @return filename like "material_0.png"
     */
    public static String generateMaterialFilename(int materialIndex) {
        if (materialIndex < 0) {
            throw new IllegalArgumentException("Material index must be non-negative: " + materialIndex);
        }
        return MATERIAL_FILENAME_PREFIX + materialIndex + MATERIAL_FILENAME_SUFFIX;
    }

    /**
     * Validate file extension.
     *
     * @param filename filename to validate
     * @return true if filename ends with .omt (case-insensitive)
     */
    public static boolean hasValidExtension(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return true;
        }
        return !filename.toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * Ensure filename has .omt extension.
     *
     * @param filename input filename
     * @return filename with .omt extension
     */
    public static String ensureExtension(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        if (hasValidExtension(filename)) {
            return filename + FILE_EXTENSION;
        }
        return filename;
    }
}
