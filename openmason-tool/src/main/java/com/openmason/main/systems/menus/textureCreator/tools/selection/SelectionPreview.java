package com.openmason.main.systems.menus.textureCreator.tools.selection;

/**
 * Marker interface for selection preview data.
 */
public interface SelectionPreview {

    /**
     * Preview type for identification.
     */
    enum PreviewType {
        RECTANGLE,  // Rectangular bounds preview
        PIXELS      // Pixel-by-pixel preview
    }

    /**
     * Gets the type of this preview.
     */
    PreviewType getType();
}
