package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MScreenFx;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.style.StyleValues;
import io.github.humbleui.skija.Canvas;

import java.util.Map;

/**
 * {@value #ID}: an elliptical screen-edge vignette over the element's rect (engine
 * {@link MScreenFx#vignette}: nothing inside the inner radius, a smooth rise to the corners, never more
 * opaque than {@link MScreenFx#VIGNETTE_MAX_ALPHA}). SBUI styles have no gradients, so the HUD document
 * places a full-screen {@code DrawProvider} and binds {@code params.intensity} to the {@code hurt}
 * root of {@code stonebreak:hud} 2 — the damage flash and low-health pulse.
 *
 * <p>Intensity 0..1 (times the cap; 0 paints nothing) comes from {@code params.intensity}, else
 * {@code params.vignette} — so {@code params} can be bound straight to the {@code hurt} record. The look is
 * authored in style: custom properties {@code --vignette-color} (default a deep red; its alpha is ignored)
 * and {@code --vignette-inner} (the clear inner radius, 0 = centre, 1 = edge midpoints; default 0.45);
 * {@code params.color} / {@code params.inner} work too. Element {@code opacity} scales it as usual.
 * Pure Skia, so the editor preview draws it too.
 */
public final class ScreenVignetteProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:screen-vignette";
    public static final int VERSION = 1;

    static final int DEFAULT_COLOR = 0xFFB0101A;
    static final float DEFAULT_INNER = 0.45f;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        if (!(element.prop("params") instanceof UiValue.Obj p)) {
            return;
        }
        float intensity = (float) num(p, "intensity", num(p, "vignette", 0));
        if (!(intensity > 0.002f) || !(rect.width() > 0f) || !(rect.height() > 0f)) {
            return;
        }
        Map<String, UiValue> customs = element.computedStyle().customs();
        int color = StyleValues.color(customs.getOrDefault("--vignette-color", p.get("color")), DEFAULT_COLOR);
        float inner = customs.get("--vignette-inner") instanceof UiValue.Num n && Double.isFinite(n.value())
            ? (float) n.value() : (float) num(p, "inner", DEFAULT_INNER);
        float peak = Math.min(1f, intensity) * MScreenFx.VIGNETTE_MAX_ALPHA;
        canvas.save();
        try {
            canvas.translate(rect.x(), rect.y());
            MScreenFx.vignette(canvas, rect.width(), rect.height(), color, peak, inner);
        } finally {
            canvas.restore();
        }
    }

    private static double num(UiValue.Obj p, String k, double fallback) {
        return p.get(k) instanceof UiValue.Num n && Double.isFinite(n.value()) ? n.value() : fallback;
    }
}
