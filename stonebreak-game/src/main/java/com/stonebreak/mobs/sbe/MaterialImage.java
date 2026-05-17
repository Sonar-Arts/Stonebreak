package com.stonebreak.mobs.sbe;

/**
 * A decoded material texture: tightly-packed RGBA8 pixels ready for GPU upload.
 *
 * <p>Rows run top-to-bottom, in source PNG order — the texture is uploaded
 * exactly as authored, with the OMO's texture coordinates used verbatim. Held
 * as a plain {@code byte[]} so the loader stays free of native memory and
 * GL-thread requirements; the renderer wraps it in a direct buffer at upload.
 *
 * @param width  texture width in pixels
 * @param height texture height in pixels
 * @param rgba   {@code width * height * 4} bytes, RGBA8, top row first
 */
public record MaterialImage(int width, int height, byte[] rgba) {
}
