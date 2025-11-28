package com.openmason.ui.textureCreator.tools.selection;

/**
 * Marker interface for selection preview data.
 * Different selection strategies provide different preview types.
 *
 * SOLID: Interface Segregation - allows different preview representations
 * YAGNI: Simple marker interface, subclasses define specific data
 *
 * @author Open Mason Team
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
     *
     * @return The preview type
     */
    PreviewType getType();
}
