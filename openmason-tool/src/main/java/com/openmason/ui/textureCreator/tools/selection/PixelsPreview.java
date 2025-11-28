package com.openmason.ui.textureCreator.tools.selection;

import java.util.Set;

/**
 * Preview data for pixel-based selection strategies (like selection brush).
 */
public record PixelsPreview(Set<Long> encodedPixels) implements SelectionPreview {

    @Override
    public PreviewType getType() {
        return PreviewType.PIXELS;
    }

    public boolean isEmpty() {
        return encodedPixels == null || encodedPixels.isEmpty();
    }

}
