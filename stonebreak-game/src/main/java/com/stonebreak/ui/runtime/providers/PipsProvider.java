package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.style.StyleValues;
import com.stonebreak.ui.characterScreen.CharacterRenderCoordinator;
import io.github.humbleui.skija.Canvas;

/**
 * {@value #ID}: a row of vital pips from the element's top-left, as the character sheet draws its HP hearts and MP
 * diamonds ({@link CharacterRenderCoordinator#drawHeart}/{@code drawDiamond}). Params: {@code shape} heart|diamond,
 * {@code count}, {@code filled} (pips with index &lt; filled take {@code color}, the rest {@code dim}), {@code size}
 * and {@code gap} in logical px (times the UI scale). Pure Skia (#301).
 */
public final class PipsProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:pips";
    public static final int VERSION = 1;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        if (!(element.prop("params") instanceof UiValue.Obj p)) {
            return;
        }
        boolean heart = !(p.get("shape") instanceof UiValue.Str s) || !"diamond".equals(s.value());
        int count = (int) num(p, "count", 0);
        float filled = (float) num(p, "filled", 0);
        float size = (float) num(p, "size", 13) * scale;
        float step = size + (float) num(p, "gap", 2) * scale;
        int color = StyleValues.color(p.get("color"), 0xFFCC2222);
        int dim = StyleValues.color(p.get("dim"), 0xFF551111);
        for (int i = 0; i < count; i++) {
            float x = rect.x() + i * step;
            int c = i < filled ? color : dim;
            if (heart) {
                CharacterRenderCoordinator.drawHeart(canvas, x, rect.y(), size, c);
            } else {
                CharacterRenderCoordinator.drawDiamond(canvas, x, rect.y(), size, c);
            }
        }
    }

    private static double num(UiValue.Obj p, String k, double fallback) {
        return p.get(k) instanceof UiValue.Num n ? n.value() : fallback;
    }
}
