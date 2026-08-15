package com.openmason.engine.format.omt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flattens an {@link OMTArchive}'s visible layers into a single RGBA image.
 *
 * <p>A {@code .omt} is a layer stack, so taking any one layer's PNG is wrong: a texture
 * authored across several layers loses everything above the one picked, and a model whose
 * base layer is still empty comes out fully transparent — which renders black. Compositing
 * is what the texture editor has always done when it hands a texture to the viewport; this
 * is the same rule for hosts that only have the archive bytes.
 */
public final class OmtCompositor {

    private static final Logger logger = LoggerFactory.getLogger(OmtCompositor.class);

    /** A flattened image, as tightly-packed RGBA bytes. */
    public record Composited(int width, int height, byte[] rgba) {
    }

    private OmtCompositor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Composite every visible layer, bottom to top, with source-over alpha blending.
     *
     * @param archive        the parsed archive
     * @param decoder        decodes one layer's PNG into RGBA; supplied by the caller so
     *                       this stays free of any particular image library
     * @return the flattened image, or null when there is nothing visible to draw
     */
    public static Composited composite(OMTArchive archive, PngDecoder decoder) {
        if (archive == null || archive.layers().isEmpty()) {
            return null;
        }

        int width = archive.canvasSize().width();
        int height = archive.canvasSize().height();
        if (width <= 0 || height <= 0) {
            return null;
        }

        byte[] out = new byte[width * height * 4];
        boolean anyLayer = false;

        for (OMTArchive.Layer layer : archive.layers()) {
            if (!layer.visible() || layer.pngBytes() == null || layer.pngBytes().length == 0) {
                continue;
            }
            PngDecoder.Decoded decoded = decoder.decode(layer.pngBytes());
            if (decoded == null) {
                logger.warn("Could not decode layer '{}'; skipping", layer.name());
                continue;
            }
            if (decoded.width() != width || decoded.height() != height) {
                logger.warn("Layer '{}' is {}x{} but the canvas is {}x{}; skipping",
                        layer.name(), decoded.width(), decoded.height(), width, height);
                continue;
            }

            blend(out, decoded.rgba(), clamp01(layer.opacity()));
            anyLayer = true;
        }

        return anyLayer ? new Composited(width, height, out) : null;
    }

    /** Standard source-over compositing of {@code src} onto {@code dst}, in place. */
    private static void blend(byte[] dst, byte[] src, float layerOpacity) {
        for (int i = 0; i < dst.length; i += 4) {
            float srcA = ((src[i + 3] & 0xFF) / 255.0f) * layerOpacity;
            if (srcA <= 0.0f) {
                continue;
            }
            float dstA = (dst[i + 3] & 0xFF) / 255.0f;
            float outA = srcA + dstA * (1.0f - srcA);
            if (outA <= 0.0f) {
                continue;
            }

            for (int c = 0; c < 3; c++) {
                float srcC = (src[i + c] & 0xFF) / 255.0f;
                float dstC = (dst[i + c] & 0xFF) / 255.0f;
                float outC = (srcC * srcA + dstC * dstA * (1.0f - srcA)) / outA;
                dst[i + c] = (byte) Math.round(clamp01(outC) * 255.0f);
            }
            dst[i + 3] = (byte) Math.round(clamp01(outA) * 255.0f);
        }
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : Math.min(v, 1.0f);
    }

    /** Decodes a PNG to tightly-packed RGBA. */
    @FunctionalInterface
    public interface PngDecoder {

        record Decoded(int width, int height, byte[] rgba) {
        }

        /** @return the decoded image, or null if it could not be read */
        Decoded decode(byte[] pngBytes);
    }
}
