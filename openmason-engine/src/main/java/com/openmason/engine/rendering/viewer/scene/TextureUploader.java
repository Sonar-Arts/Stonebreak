package com.openmason.engine.rendering.viewer.scene;

/**
 * Uploads a PNG to the GPU and returns its texture id.
 *
 * <p>Exists so {@link OmoModelLoader}'s parse-and-wire path can be exercised headlessly:
 * everything except this one call is pure data manipulation, so a test supplies a stub
 * that hands back incrementing ids and never touches OpenGL.
 */
@FunctionalInterface
public interface TextureUploader {

    /**
     * @param pngBytes encoded PNG
     * @return GL texture id, or 0 if the upload failed
     */
    int upload(byte[] pngBytes);

    /**
     * Upload already-decoded, tightly-packed RGBA pixels.
     *
     * <p>Needed because a {@code .omt} is a layer stack: its layers must be flattened
     * before upload, and re-encoding the result back to PNG just to decode it again would
     * be wasted work. Default throws so an implementation must opt in.
     */
    default int uploadRgba(int width, int height, byte[] rgba) {
        throw new UnsupportedOperationException("This uploader cannot upload raw RGBA");
    }

    /** Decode a PNG to tightly-packed RGBA, for compositing. Null if undecodable. */
    default com.openmason.engine.format.omt.OmtCompositor.PngDecoder.Decoded decode(byte[] pngBytes) {
        return null;
    }
}
