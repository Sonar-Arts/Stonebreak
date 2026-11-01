package com.openmason.engine.ui.theme;

import com.openmason.engine.ui.runtime.UiRect;
import io.github.humbleui.skija.Canvas;

/**
 * Paints one named {@code -sb-surface} into an element's box (#384): after the element's
 * background, before its widget look. Surfaces are registered in a {@link UiTheme}.
 */
@FunctionalInterface
public interface SurfacePainter {

    /**
     * @param rect  the element's device-pixel rect
     * @param scale device pixels per logical pixel (the house surfaces ignore it: their radii are
     *              device pixels, as the legacy painters drew them)
     */
    void paint(Canvas canvas, UiRect rect, float scale);
}
