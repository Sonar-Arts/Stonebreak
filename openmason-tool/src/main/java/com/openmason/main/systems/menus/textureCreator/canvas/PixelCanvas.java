package com.openmason.main.systems.menus.textureCreator.canvas;

import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Core pixel canvas data structure.
 */
public class PixelCanvas {

    private final int width;
    private final int height;
    private final int[] pixels; // RGBA packed as int
    private long modificationVersion; // Incremented on each modification for cache invalidation
    private SelectionRegion activeSelection; // Active selection region (null if no selection) - legacy
    private SelectionManager selectionManager; // Optional centralized selection manager
    private boolean bypassSelectionConstraint = false; // Temporarily bypass selection constraint for special operations
    private CanvasShapeMask shapeMask; // Active shape mask (null = all pixels editable)
    private final List<CanvasChangeListener> changeListeners = new ArrayList<>();

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
        this.modificationVersion = 0L;
        this.activeSelection = null; // No selection by default

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
     * Get modification version for cache invalidation.
     * Increments whenever canvas content changes.
     *
     * @return modification version number
     */
    public long getModificationVersion() {
        return modificationVersion;
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
     * If a selection is active, only pixels within the selection bounds can be modified.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @param color pixel color (RGBA packed as int)
     */
    public void setPixel(int x, int y, int color) {
        if (!isValidCoordinate(x, y)) {
            return; // Silently ignore out-of-bounds
        }

        // Shape mask constraint: if a mask is active, reject pixels outside it
        if (shapeMask != null && !shapeMask.isEditable(x, y)) {
            return; // Pixel is outside editable region - ignore modification
        }

        // Selection constraint: if selection is active, only allow modifications within selection
        // (unless bypass is enabled for special operations like move tool)
        if (!bypassSelectionConstraint) {
            SelectionRegion selection = getActiveSelection();
            if (selection != null && !selection.isEmpty()) {
                if (!selection.contains(x, y)) {
                    return; // Pixel is outside selection - ignore modification
                }
            }
        }

        int index = y * width + x;
        pixels[index] = color;
        modificationVersion++;
        notifyChangeListeners(x, y, 1, 1);
    }

    /**
     * Fill entire canvas with a color.
     *
     * @param color fill color (RGBA packed as int)
     */
    public void fill(int color) {
        Arrays.fill(pixels, color);
        modificationVersion++;
        notifyChangeListeners(0, 0, width, height);
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
        modificationVersion++;
        notifyChangeListeners(0, 0, width, height);
    }

    /**
     * Create a resized copy of this canvas using nearest-neighbor sampling.
     * Returns this canvas unchanged if the dimensions already match.
     *
     * @param targetWidth  desired width in pixels
     * @param targetHeight desired height in pixels
     * @return new canvas with rescaled pixel data, or this canvas if dimensions match
     */
    public PixelCanvas resized(int targetWidth, int targetHeight) {
        if (targetWidth == width && targetHeight == height) {
            return this;
        }

        PixelCanvas result = new PixelCanvas(targetWidth, targetHeight);
        float xRatio = (float) width / targetWidth;
        float yRatio = (float) height / targetHeight;

        for (int y = 0; y < targetHeight; y++) {
            int srcY = Math.min((int) (y * yRatio), height - 1);
            for (int x = 0; x < targetWidth; x++) {
                int srcX = Math.min((int) (x * xRatio), width - 1);
                result.pixels[y * targetWidth + x] = pixels[srcY * width + srcX];
            }
        }
        return result;
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

    /**
     * Sets the SelectionManager for this canvas.
     * When set, the canvas will use the SelectionManager's selection instead of its own.
     * @param selectionManager The SelectionManager instance (can be null)
     */
    public void setSelectionManager(SelectionManager selectionManager) {
        this.selectionManager = selectionManager;
    }

    /**
     * Get the active selection region.
     * If SelectionManager is set, returns its selection; otherwise returns local selection.
     *
     * @return The active selection, or null if no selection is active
     */
    public SelectionRegion getActiveSelection() {
        if (selectionManager != null) {
            return selectionManager.getActiveSelection();
        }
        return activeSelection;
    }

    /**
     * Clear the active selection.
     * If SelectionManager is set, clears it through the manager; otherwise clears locally.
     */
    public void clearSelection() {
        if (selectionManager != null) {
            selectionManager.clearSelection();
        } else {
            this.activeSelection = null;
        }
    }

    /**
     * Check if a selection is currently active.
     * If SelectionManager is set, checks its selection; otherwise checks local selection.
     *
     * @return true if a selection is active, false otherwise
     */
    public boolean hasActiveSelection() {
        if (selectionManager != null) {
            return selectionManager.hasActiveSelection();
        }
        return activeSelection != null && !activeSelection.isEmpty();
    }

    /**
     * Temporarily bypass the selection constraint.
     * This allows special operations (like move tool) to write pixels outside the selection bounds.
     * IMPORTANT: Must be re-enabled after the operation completes.
     *
     * @param bypass true to bypass selection constraint, false to enforce it
     */
    public void setBypassSelectionConstraint(boolean bypass) {
        this.bypassSelectionConstraint = bypass;
    }

    /**
     * Set the active shape mask defining which pixels are editable.
     * When set, only pixels where {@link CanvasShapeMask#isEditable} returns true
     * can be modified via {@link #setPixel}.
     *
     * @param mask the shape mask, or null to allow editing all pixels
     */
    public void setShapeMask(CanvasShapeMask mask) {
        this.shapeMask = mask;
    }

    /**
     * Get the active shape mask.
     *
     * @return the current mask, or null if no mask is active
     */
    public CanvasShapeMask getShapeMask() {
        return shapeMask;
    }

    /**
     * Check if a pixel coordinate is currently editable, considering
     * shape mask and bounds constraints.
     *
     * <p>Tools can use this to query editability before attempting writes
     * (e.g., flood-fill boundary detection, preview rendering).
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return true if the pixel can be modified
     */
    public boolean isEditablePixel(int x, int y) {
        if (!isValidCoordinate(x, y)) {
            return false;
        }
        if (shapeMask != null && !shapeMask.isEditable(x, y)) {
            return false;
        }
        return true;
    }

    /**
     * Get the shape mask coverage for a pixel coordinate.
     *
     * <p>Returns a value between 0.0 and 1.0 indicating how much of the
     * pixel is inside the active shape mask. Tools combine this with their
     * own coverage (brush edge, line distance, etc.) for smooth blending.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return mask coverage in [0.0, 1.0], or 1.0 if no mask is active
     */
    public float getMaskCoverage(int x, int y) {
        if (!isValidCoordinate(x, y)) {
            return 0.0f;
        }
        if (shapeMask == null) {
            return 1.0f;
        }
        return shapeMask.getCoverage(x, y);
    }

    // =========================================================================
    // CHANGE LISTENERS
    // =========================================================================

    /**
     * Register a listener to be notified when canvas pixels change.
     *
     * @param listener the listener to add
     */
    public void addChangeListener(CanvasChangeListener listener) {
        if (listener != null && !changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    /**
     * Remove a previously registered change listener.
     *
     * @param listener the listener to remove
     */
    public void removeChangeListener(CanvasChangeListener listener) {
        changeListeners.remove(listener);
    }

    /**
     * Notify all registered change listeners about a modified region.
     *
     * @param dirtyX      left edge of the dirty region
     * @param dirtyY      top edge of the dirty region
     * @param dirtyWidth  width of the dirty region
     * @param dirtyHeight height of the dirty region
     */
    private void notifyChangeListeners(int dirtyX, int dirtyY, int dirtyWidth, int dirtyHeight) {
        if (changeListeners.isEmpty()) {
            return;
        }
        List<CanvasChangeListener> copy = new ArrayList<>(changeListeners);
        for (CanvasChangeListener listener : copy) {
            listener.onCanvasChanged(this, dirtyX, dirtyY, dirtyWidth, dirtyHeight);
        }
    }

    /**
     * Notify all change listeners that the entire canvas is dirty.
     * Used when entering face-region mode to force a full GPU upload
     * of the canvas contents to the new target texture.
     */
    public void notifyFullCanvasDirty() {
        notifyChangeListeners(0, 0, width, height);
    }

    /**
     * Extract a sub-region of the canvas as RGBA bytes for partial GPU upload.
     * Converts from ABGR int to RGBA byte format, matching the layout expected
     * by {@code glTexSubImage2D}.
     *
     * @param regionX left edge of the region
     * @param regionY top edge of the region
     * @param regionW width of the region
     * @param regionH height of the region
     * @return byte array in RGBA format for the requested sub-region
     */
    public byte[] getPixelsAsRGBABytes(int regionX, int regionY, int regionW, int regionH) {
        // Clamp to canvas bounds
        int x0 = Math.max(0, regionX);
        int y0 = Math.max(0, regionY);
        int x1 = Math.min(width, regionX + regionW);
        int y1 = Math.min(height, regionY + regionH);
        int clampedW = x1 - x0;
        int clampedH = y1 - y0;

        if (clampedW <= 0 || clampedH <= 0) {
            return new byte[0];
        }

        byte[] bytes = new byte[clampedW * clampedH * 4];
        int byteIndex = 0;

        for (int row = y0; row < y1; row++) {
            for (int col = x0; col < x1; col++) {
                int pixel = pixels[row * width + col];
                int[] rgba = unpackRGBA(pixel);
                bytes[byteIndex++] = (byte) rgba[0]; // R
                bytes[byteIndex++] = (byte) rgba[1]; // G
                bytes[byteIndex++] = (byte) rgba[2]; // B
                bytes[byteIndex++] = (byte) rgba[3]; // A
            }
        }

        return bytes;
    }

}
