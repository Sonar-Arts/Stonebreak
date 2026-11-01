package com.openmason.engine.ui.theme;

import io.github.humbleui.skija.Canvas;

/**
 * Draws one named {@code -sb-symbol} into a box in a colour (#384): vector geometry, never a font
 * glyph. Symbols are registered in a {@link UiTheme}.
 */
@FunctionalInterface
public interface SymbolPainter {

    void draw(Canvas canvas, float x, float y, float w, float h, int color);

    /** The symbol over a 1 px offset pass in {@code shadow}: the house shadowed-label depth. */
    default void drawWithShadow(Canvas canvas, float x, float y, float w, float h, int color, int shadow) {
        draw(canvas, x + 1f, y + 1f, w, h, shadow);
        draw(canvas, x, y, w, h, color);
    }
}
