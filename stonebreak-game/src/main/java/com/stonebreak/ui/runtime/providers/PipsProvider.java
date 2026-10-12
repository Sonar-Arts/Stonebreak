package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.style.StyleValues;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * {@value #ID}: a row of vital pips from the element's top-left: the character sheet's HP hearts and MP
 * diamonds ({@link #drawHeart}/{@link #drawDiamond}). Params: {@code shape} heart|diamond,
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
                drawHeart(canvas, x, rect.y(), size, c);
            } else {
                drawDiamond(canvas, x, rect.y(), size, c);
            }
        }
    }

    /** The vitals heart: two circles and a triangle. */
    public static void drawHeart(Canvas canvas, float x, float y, float s, int color) {
        try (Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
            float r = s * 0.27f;
            float lx = x + r;
            float rx = x + s - r;
            float ly = y + r;

            canvas.drawCircle(lx, ly, r, fill);
            canvas.drawCircle(rx, ly, r, fill);

            try (PathBuilder triBuilder = new PathBuilder()) {
                triBuilder.moveTo(x, ly);
                triBuilder.lineTo(x + s / 2f, y + s);
                triBuilder.lineTo(x + s, ly);
                triBuilder.closePath();
                try (Path tri = triBuilder.build()) {
                    canvas.drawPath(tri, fill);
                }
            }
        }
    }

    /** The mana diamond. */
    public static void drawDiamond(Canvas canvas, float x, float y, float s, int color) {
        float cx = x + s / 2f;
        float cy = y + s / 2f;
        try (PathBuilder builder = new PathBuilder();
             Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
            builder.moveTo(cx, y);
            builder.lineTo(x + s, cy);
            builder.lineTo(cx, y + s);
            builder.lineTo(x, cy);
            builder.closePath();
            try (Path path = builder.build()) {
                canvas.drawPath(path, fill);
            }
        }
    }

    private static double num(UiValue.Obj p, String k, double fallback) {
        return p.get(k) instanceof UiValue.Num n ? n.value() : fallback;
    }
}
