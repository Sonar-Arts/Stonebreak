package com.openmason.ui.components.textureCreator.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data model for Open Mason Texture (.OMT) file format.
 *
 * The .OMT format is a ZIP-based container similar to OpenRaster (.ora):
 * - manifest.json: Metadata about canvas, layers, and active layer
 * - layer_N.png: PNG image for each layer's pixel data
 *
 * Follows SOLID principles:
 * - Single Responsibility: Represents .OMT file structure only
 * - Immutable data model for thread safety
 *
 * @author Open Mason Team
 */
public class OMTFormat {

    // File format constants
    public static final String FILE_EXTENSION = ".omt";
    public static final String FORMAT_VERSION = "1.0";
    public static final String MANIFEST_FILENAME = "manifest.json";
    public static final String LAYER_FILENAME_PREFIX = "layer_";
    public static final String LAYER_FILENAME_SUFFIX = ".png";

    /**
     * Complete .OMT file data structure.
     */
    public static class Document {
        private final String version;
        private final CanvasSize canvasSize;
        private final List<LayerInfo> layers;
        private final int activeLayerIndex;

        public Document(String version, CanvasSize canvasSize, List<LayerInfo> layers, int activeLayerIndex) {
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
            this.layers = new ArrayList<>(layers); // Defensive copy
            this.activeLayerIndex = activeLayerIndex;
        }

        public String getVersion() {
            return version;
        }

        public CanvasSize getCanvasSize() {
            return canvasSize;
        }

        public List<LayerInfo> getLayers() {
            return Collections.unmodifiableList(layers);
        }

        public int getActiveLayerIndex() {
            return activeLayerIndex;
        }

        @Override
        public String toString() {
            return String.format("OMTDocument{version=%s, canvasSize=%s, layers=%d, activeLayer=%d}",
                    version, canvasSize, layers.size(), activeLayerIndex);
        }
    }

    /**
     * Canvas dimensions.
     */
    public static class CanvasSize {
        private final int width;
        private final int height;

        public CanvasSize(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Canvas dimensions must be positive: " + width + "x" + height);
            }
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CanvasSize)) return false;
            CanvasSize other = (CanvasSize) obj;
            return width == other.width && height == other.height;
        }

        @Override
        public int hashCode() {
            return 31 * width + height;
        }
    }

    /**
     * Layer metadata and reference to pixel data file.
     */
    public static class LayerInfo {
        private final String name;
        private final boolean visible;
        private final float opacity;
        private final String dataFile;

        public LayerInfo(String name, boolean visible, float opacity, String dataFile) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Layer name cannot be null or empty");
            }
            if (opacity < 0.0f || opacity > 1.0f) {
                throw new IllegalArgumentException("Opacity must be between 0.0 and 1.0: " + opacity);
            }
            if (dataFile == null || dataFile.trim().isEmpty()) {
                throw new IllegalArgumentException("Data file cannot be null or empty");
            }

            this.name = name;
            this.visible = visible;
            this.opacity = opacity;
            this.dataFile = dataFile;
        }

        public String getName() {
            return name;
        }

        public boolean isVisible() {
            return visible;
        }

        public float getOpacity() {
            return opacity;
        }

        public String getDataFile() {
            return dataFile;
        }

        @Override
        public String toString() {
            return String.format("LayerInfo{name=%s, visible=%b, opacity=%.2f, dataFile=%s}",
                    name, visible, opacity, dataFile);
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
     * Validate file extension.
     *
     * @param filename filename to validate
     * @return true if filename ends with .omt (case-insensitive)
     */
    public static boolean hasValidExtension(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        return filename.toLowerCase().endsWith(FILE_EXTENSION);
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
        if (!hasValidExtension(filename)) {
            return filename + FILE_EXTENSION;
        }
        return filename;
    }
}
