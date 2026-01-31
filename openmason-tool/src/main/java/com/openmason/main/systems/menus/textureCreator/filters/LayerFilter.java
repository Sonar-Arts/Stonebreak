package com.openmason.main.systems.menus.textureCreator.filters;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;

/**
 * Interface for filters that can be applied to layers.
 * Follows the Strategy pattern for extensible filter implementations.
 */
public interface LayerFilter {

    /**
     * @return Human-readable name of the filter
     */
    String getName();

    /**
     * @return Brief description of what the filter does
     */
    String getDescription();

    /**
     * Apply the filter to the given canvas.
     * If a selection is provided, only apply within the selection bounds.
     *
     * @param canvas The canvas to modify
     * @param selection Optional selection region (null = entire canvas)
     */
    void apply(PixelCanvas canvas, SelectionRegion selection);

}
