package com.openmason.ui.textureCreator.io;

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

    /**
         * Complete .OMT file data structure.
         */
        public record Document(String version, CanvasSize canvasSize, List<LayerInfo> layers, int activeLayerIndex) {
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

            @Override
            public List<LayerInfo> layers() {
                return Collections.unmodifiableList(layers);
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
