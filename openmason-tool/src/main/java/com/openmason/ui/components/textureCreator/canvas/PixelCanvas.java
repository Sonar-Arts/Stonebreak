package com.openmason.ui.components.textureCreator.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Core pixel canvas data structure.
 *
 * Stores pixel data as a simple RGBA integer array.
 * Follows KISS principle - straightforward data structure with clear operations.
 *
 * Pixel format: 0xAABBGGRR (alpha, blue, green, red)
 * - Alpha: bits 24-31
 * - Blue:  bits 16-23
 * - Green: bits 8-15
 * - Red:   bits 0-7
 *
 * @author Open Mason Team
 */
public class PixelCanvas {

    private static final Logger logger = LoggerFactory.getLogger(PixelCanvas.class);

    private final int width;
    private final int height;
    private final int[] pixels; // RGBA packed as int

    /**
     * Create new pixel canvas with specified dimensions.
     *
     * @param width canvas width in pixels
     * @param height canvas height in pixels
     */
    public PixelCanvas(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Canvas dimensions must be positive: " + width + "x" + height);
        }

        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];

        // Initialize to transparent
        Arrays.fill(pixels, 0x00000000);
    }

    /**
     * Get canvas width.
     * @return width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get canvas height.
     * @return height in pixels
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get pixel data array.
     * @return pixel array (RGBA packed as int)
     */
    public int[] getPixels() {
        return pixels;
    }

    /**
     * Get pixel color at coordinates.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return pixel color (RGBA packed as int)
     */
    public int getPixel(int x, int y) {
        if (!isValidCoordinate(x, y)) {
            return 0x00000000; // Return transparent for out-of-bounds
        }

        int index = y * width + x;
        return pixels[index];
    }

    /**
     * Set pixel color at coordinates.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @param color pixel color (RGBA packed as int)
     */
    public void setPixel(int x, int y, int color) {
        if (!isValidCoordinate(x, y)) {
            return; // Silently ignore out-of-bounds
        }

        int index = y * width + x;
        pixels[index] = color;
    }

    /**
     * Fill entire canvas with a color.
     *
     * @param color fill color (RGBA packed as int)
     */
    public void fill(int color) {
        Arrays.fill(pixels, color);
    }

    /**
     * Clear canvas to transparent.
     */
    public void clear() {
        fill(0x00000000);
    }

    /**
     * Copy pixel data from another canvas.
     *
     * @param source source canvas to copy from
     */
    public void copyFrom(PixelCanvas source) {
        if (source.width != this.width || source.height != this.height) {
            throw new IllegalArgumentException("Canvas dimensions must match: " +
                this.width + "x" + this.height + " vs " + source.width + "x" + source.height);
        }

        System.arraycopy(source.pixels, 0, this.pixels, 0, pixels.length);
    }

    /**
     * Create a copy of this canvas.
     *
     * @return new canvas with copied pixel data
     */
    public PixelCanvas copy() {
        PixelCanvas copy = new PixelCanvas(width, height);
        copy.copyFrom(this);
        return copy;
    }

    /**
     * Check if coordinates are within canvas bounds.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return true if coordinates are valid
     */
    public boolean isValidCoordinate(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    /**
     * Extract RGBA components from packed color.
     *
     * @param color packed RGBA color
     * @return array [r, g, b, a] with values 0-255
     */
    public static int[] unpackRGBA(int color) {
        int r = color & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = (color >> 16) & 0xFF;
        int a = (color >> 24) & 0xFF;
        return new int[]{r, g, b, a};
    }

    /**
     * Pack RGBA components into a single int.
     *
     * @param r red component (0-255)
     * @param g green component (0-255)
     * @param b blue component (0-255)
     * @param a alpha component (0-255)
     * @return packed RGBA color
     */
    public static int packRGBA(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Blend two colors using alpha compositing (source over destination).
     *
     * @param src source color (RGBA packed)
     * @param dst destination color (RGBA packed)
     * @return blended color (RGBA packed)
     */
    public static int blendColors(int src, int dst) {
        int[] srcRGBA = unpackRGBA(src);
        int[] dstRGBA = unpackRGBA(dst);

        float srcAlpha = srcRGBA[3] / 255.0f;
        float dstAlpha = dstRGBA[3] / 255.0f;
        float outAlpha = srcAlpha + dstAlpha * (1.0f - srcAlpha);

        if (outAlpha == 0.0f) {
            return 0x00000000; // Fully transparent
        }

        int outR = (int) ((srcRGBA[0] * srcAlpha + dstRGBA[0] * dstAlpha * (1.0f - srcAlpha)) / outAlpha);
        int outG = (int) ((srcRGBA[1] * srcAlpha + dstRGBA[1] * dstAlpha * (1.0f - srcAlpha)) / outAlpha);
        int outB = (int) ((srcRGBA[2] * srcAlpha + dstRGBA[2] * dstAlpha * (1.0f - srcAlpha)) / outAlpha);
        int outA = (int) (outAlpha * 255.0f);

        return packRGBA(outR, outG, outB, outA);
    }

    /**
     * Get pixel data as RGBA byte array for OpenGL texture upload.
     * Converts from ABGR int to RGBA byte format.
     *
     * @return byte array in RGBA format
     */
    public byte[] getPixelsAsRGBABytes() {
        byte[] bytes = new byte[width * height * 4];
        int byteIndex = 0;

        for (int pixel : pixels) {
            int[] rgba = unpackRGBA(pixel);
            bytes[byteIndex++] = (byte) rgba[0]; // R
            bytes[byteIndex++] = (byte) rgba[1]; // G
            bytes[byteIndex++] = (byte) rgba[2]; // B
            bytes[byteIndex++] = (byte) rgba[3]; // A
        }

        return bytes;
    }
}
