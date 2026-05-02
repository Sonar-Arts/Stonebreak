package com.openmason.engine.format.omt;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory representation of an Open Mason Texture (.OMT) archive.
 *
 * <p>Holds the canvas dimensions and the raw PNG bytes of each layer in
 * draw order (bottom-most first). Image decoding and compositing are left
 * to callers — the engine ships the bytes; downstream modules (with their
 * own image library, e.g. Skija or STB) decide how to render them.
 *
 * <p>This type is immutable. Layer lists handed in are defensively copied.
 */
public final class OMTArchive {

    /**
     * Canvas dimensions in pixels.
     */
    public record CanvasSize(int width, int height) {
        public CanvasSize {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "Canvas dimensions must be positive: " + width + "x" + height);
            }
        }
    }

    /**
     * A single layer in the OMT, with its metadata and decoded-but-still-encoded
     * PNG payload. PNG bytes are exactly the on-disk file — callers decode.
     *
     * @param name      display name from the manifest
     * @param visible   layer visibility flag
     * @param opacity   layer opacity in {@code [0, 1]}
     * @param pngBytes  raw PNG file bytes for this layer
     */
    public record Layer(String name, boolean visible, float opacity, byte[] pngBytes) {
        public Layer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pngBytes, "pngBytes");
            if (opacity < 0f || opacity > 1f) {
                throw new IllegalArgumentException("opacity must be in [0,1]: " + opacity);
            }
        }
    }

    private final CanvasSize canvasSize;
    private final List<Layer> layers;
    private final int activeLayerIndex;

    public OMTArchive(CanvasSize canvasSize, List<Layer> layers, int activeLayerIndex) {
        this.canvasSize = Objects.requireNonNull(canvasSize, "canvasSize");
        Objects.requireNonNull(layers, "layers");
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("OMT archive must contain at least one layer");
        }
        if (activeLayerIndex < 0 || activeLayerIndex >= layers.size()) {
            throw new IllegalArgumentException(
                    "activeLayerIndex out of range: " + activeLayerIndex + " / " + layers.size());
        }
        this.layers = List.copyOf(layers);
        this.activeLayerIndex = activeLayerIndex;
    }

    public CanvasSize canvasSize() {
        return canvasSize;
    }

    public List<Layer> layers() {
        return Collections.unmodifiableList(layers);
    }

    public int activeLayerIndex() {
        return activeLayerIndex;
    }
}
