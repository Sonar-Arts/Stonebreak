package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.ui.characterCreation.renderers.WoodPanelFill;
import io.github.humbleui.skija.Canvas;

/**
 * {@value #ID}: the character creation panel's tinted elm wood filling the element ({@link WoodPanelFill}:
 * repeated at 4x from its corner, clipped to the stone panel's rounded rect). Pure Skia (#301).
 */
public final class WoodPanelProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:wood-panel";
    public static final int VERSION = 1;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        WoodPanelFill.paint(canvas, rect.x(), rect.y(), rect.width(), rect.height());
    }
}
