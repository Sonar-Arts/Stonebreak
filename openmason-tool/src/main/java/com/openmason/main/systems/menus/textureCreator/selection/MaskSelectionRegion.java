package com.openmason.main.systems.menus.textureCreator.selection;

import java.awt.Rectangle;
import java.util.Collection;
import java.util.Objects;

/**
 * Selection region backed by a per-pixel mask. Supports arbitrary shapes and
 */
public final class MaskSelectionRegion implements SelectionRegion {

    private static final MaskSelectionRegion EMPTY =
            new MaskSelectionRegion(new Rectangle(), new boolean[0], 0);

    private final Rectangle bounds;
    private final boolean[] mask;
    private final int width;
    private final int height;
    private final int pixelCount;

    private MaskSelectionRegion(Rectangle bounds, boolean[] mask, int pixelCount) {
        this.bounds = new Rectangle(bounds);
        this.width = Math.max(bounds.width, 0);
        this.height = Math.max(bounds.height, 0);
        this.mask = mask;
        this.pixelCount = pixelCount;
    }

    /**
    * Creates an empty mask selection region.
    */
    public static MaskSelectionRegion empty() {
        return EMPTY;
    }

    /**
     * Builds a mask selection from a collection of encoded pixel positions.
     */
    public static MaskSelectionRegion fromEncodedPixels(Collection<Long> encodedPixels) {
        if (encodedPixels == null || encodedPixels.isEmpty()) {
            return EMPTY;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (long packed : encodedPixels) {
            int x = decodeX(packed);
            int y = decodeY(packed);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        boolean[] mask = new boolean[width * height];
        int pixelCount = 0;

        for (long packed : encodedPixels) {
            int x = decodeX(packed);
            int y = decodeY(packed);
            int localX = x - minX;
            int localY = y - minY;
            int index = localY * width + localX;
            if (!mask[index]) {
                mask[index] = true;
                pixelCount++;
            }
        }

        Rectangle bounds = new Rectangle(minX, minY, width, height);
        return new MaskSelectionRegion(bounds, mask, pixelCount);
    }

    @Override
    public boolean contains(int x, int y) {
        if (!bounds.contains(x, y) || mask.length == 0) {
            return false;
        }
        int localX = x - bounds.x;
        int localY = y - bounds.y;
        int index = localY * width + localX;
        return mask[index];
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(bounds);
    }

    @Override
    public SelectionType getType() {
        return SelectionType.FREEFORM;
    }

    @Override
    public boolean isEmpty() {
        return pixelCount == 0;
    }

    @Override
    public SelectionRegion translate(int dx, int dy) {
        if (pixelCount == 0) {
            return EMPTY;
        }
        Rectangle translated = new Rectangle(bounds);
        translated.translate(dx, dy);
        // Mask is immutable; safe to share between translated instances.
        return new MaskSelectionRegion(translated, mask, pixelCount);
    }

    public static long encode(int x, int y) {
        return (long) x & 0xffffffffL | ((long) y << 32);
    }

    public static int decodeX(long packed) {
        return (int) (packed & 0xffffffffL);
    }

    public static int decodeY(long packed) {
        return (int) (packed >>> 32);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaskSelectionRegion)) return false;
        MaskSelectionRegion that = (MaskSelectionRegion) o;
        return pixelCount == that.pixelCount &&
                bounds.equals(that.bounds) &&
                java.util.Arrays.equals(mask, that.mask);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(bounds, pixelCount);
        result = 31 * result + java.util.Arrays.hashCode(mask);
        return result;
    }

    @Override
    public String toString() {
        return "MaskSelectionRegion{" +
                "bounds=" + bounds +
                ", pixelCount=" + pixelCount +
                '}';
    }
}
