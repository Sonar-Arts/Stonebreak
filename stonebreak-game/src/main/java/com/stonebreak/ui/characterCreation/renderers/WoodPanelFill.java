package com.stonebreak.ui.characterCreation.renderers;

import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * The character creation left panel's wood: the recipe screen's {@code Elm_UI.sbt} repeated at 4x from the
 * panel's corner through a shader, clipped to the panel's rounded rect (anti-aliased), under a dark tint so
 * the preview and labels stay legible. Nothing without the texture. Painted by the
 * {@code stonebreak:wood-panel} draw provider (#301).
 */
public final class WoodPanelFill {

    public static final String RESOURCE = "/ui/recipeScreen/Elm_UI.sbt";
    private static final int TINT = 0xAA000000;

    private static Shader shader;

    private WoodPanelFill() {
    }

    public static void paint(Canvas canvas, float x, float y, float w, float h) {
        Shader s = shader();
        if (s == null) {
            return;
        }
        int save = canvas.save();
        canvas.clipRRect(RRect.makeXYWH(x, y, w, h, MStyle.PANEL_RADIUS), true);
        try (Paint p = new Paint().setShader(s)) {
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(4f, 4f);
            canvas.drawRect(Rect.makeXYWH(0, 0, w / 4f, h / 4f), p);
            canvas.restore();
        }
        try (Paint tint = new Paint().setColor(TINT)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), tint);
        }
        canvas.restoreToCount(save);
    }

    private static synchronized Shader shader() {
        if (shader == null) {
            // Registry-cached and shared with the recipe screen.
            MTexture planks = MTextureRegistry.get(RESOURCE);
            if (planks == null || planks.image() == null) {
                return null;
            }
            shader = planks.image().makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
        }
        return shader;
    }
}
