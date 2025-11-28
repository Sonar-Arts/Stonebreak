package com.openmason.ui.textureCreator.tools.selection;

import com.openmason.ui.textureCreator.selection.MaskSelectionRegion;

import java.util.Set;

/**
 * Preview data for pixel-based selection strategies (like selection brush).
 * Holds a set of encoded pixel coordinates that represent the in-progress selection.
 *
 * SOLID: Single Responsibility - holds pixel preview data only
 * KISS: Simple data holder with no complex logic
 *
 * @author Open Mason Team
 */
public class PixelsPreview implements SelectionPreview {

    private final Set<Long> encodedPixels;

    /**
     * Creates a pixels preview from a set of encoded pixel coordinates.
     *
     * @param encodedPixels Set of encoded pixel coordinates (x,y pairs encoded as longs)
     */
    public PixelsPreview(Set<Long> encodedPixels) {
        this.encodedPixels = encodedPixels;
    }

    @Override
    public PreviewType getType() {
        return PreviewType.PIXELS;
    }

    /**
     * Gets the encoded pixel coordinates.
     *
     * @return Set of encoded pixels
     */
    public Set<Long> getEncodedPixels() {
        return encodedPixels;
    }

    /**
     * Checks if this preview is empty.
     *
     * @return true if no pixels in preview
     */
    public boolean isEmpty() {
        return encodedPixels == null || encodedPixels.isEmpty();
    }

    /**
     * Decodes an X coordinate from an encoded pixel.
     *
     * @param encoded Encoded pixel coordinate
     * @return X coordinate
     */
    public static int decodeX(long encoded) {
        return MaskSelectionRegion.decodeX(encoded);
    }

    /**
     * Decodes a Y coordinate from an encoded pixel.
     *
     * @param encoded Encoded pixel coordinate
     * @return Y coordinate
     */
    public static int decodeY(long encoded) {
        return MaskSelectionRegion.decodeY(encoded);
    }
}
