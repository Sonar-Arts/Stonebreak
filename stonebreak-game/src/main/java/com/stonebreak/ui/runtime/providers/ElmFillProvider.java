package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.ui.recipeScreen.renderers.ElmFill;
import io.github.humbleui.skija.Canvas;

/**
 * {@value #ID}: the recipe book's elm-wood pane filling the element ({@link ElmFill}, #301): the texture
 * tiled at 4x from the element's top-left and the dark overlay. Pure Skia.
 */
public final class ElmFillProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:elm-fill";
    public static final int VERSION = 1;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        ElmFill.paint(canvas, rect.x(), rect.y(), rect.width(), rect.height());
    }
}
