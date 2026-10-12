package com.stonebreak.ui.recipeScreen.renderers;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

/**
 * The recipe book's elm-wood panes: the {@code Elm_UI.sbt} texture tiled at 4x (whole texels, crisp),
 * clipped to the pane, under a 40 % black overlay; a flat deep fill without the texture. Painted by the
 * {@code stonebreak:elm-fill} draw provider (#301).
 */
public final class ElmFill {

    public static final String RESOURCE = "/ui/recipeScreen/Elm_UI.sbt";
    private static final int TINT_OVERLAY = 0x66000000;
    private static final int TILE_SCALE = 4;

    private ElmFill() {
    }

    public static void paint(Canvas canvas, float x, float y, float w, float h) {
        MTexture tex = MTextureRegistry.get(RESOURCE);
        if (tex != null) {
            int tw = tex.width();
            int th = tex.height();
            if (tw > 0 && th > 0) {
                float tileW = tw * TILE_SCALE;
                float tileH = th * TILE_SCALE;
                int save = canvas.save();
                try {
                    canvas.clipRect(Rect.makeXYWH(x, y, w, h));
                    for (float py = y; py < y + h; py += tileH) {
                        for (float px = x; px < x + w; px += tileW) {
                            MPainter.drawImage(canvas, tex.image(), px, py, tileW, tileH);
                        }
                    }
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } else {
            try (Paint p = new Paint().setColor(MStyle.PANEL_FILL_DEEP)) {
                canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
            }
        }
        try (Paint p = new Paint().setColor(TINT_OVERLAY)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }
}
