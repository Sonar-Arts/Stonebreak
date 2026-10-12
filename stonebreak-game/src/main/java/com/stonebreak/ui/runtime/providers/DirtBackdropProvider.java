package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.io.InputStream;

/**
 * {@value #ID}: the menus' dirt backdrop (multiplayer, settings, world select, the main menu's dirt
 * mode, #299): a dark base under the {@code Dirt.png} texture tiled with nearest sampling at
 * {@value #TILE_SCALE}x, anchored at the window origin, in device pixels at every UI scale. The legacy
 * screens draw it with {@link #paint} too, so they cannot drift; each screen's own tint over it is
 * an ordinary document box. Params: {@code tileScale} (default 4). Pure Skia: no GL, also in the
 * editor preview.
 */
public final class DirtBackdropProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:dirt-backdrop";
    public static final int VERSION = 1;
    public static final float TILE_SCALE = 4f;
    public static final int BASE = 0xFF2C2C2C;

    private static Image dirt;
    private static Shader shader;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        UiValue.Obj p = element.prop("params") instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY;
        float tile = p.get("tileScale") instanceof UiValue.Num n && n.value() > 0 ? (float) n.value() : TILE_SCALE;
        paint(canvas, rect.x(), rect.y(), rect.width(), rect.height(), tile);
    }

    /** The backdrop over {@code (x, y, w, h)}: base colour, then the dirt tiles (anchored at the origin). */
    public static void paint(Canvas canvas, float x, float y, float w, float h, float tileScale) {
        try (Paint p = new Paint().setColor(BASE)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
        Shader s = shader();
        if (s == null) {
            return;
        }
        try (Paint p = new Paint().setShader(s)) {
            canvas.save();
            canvas.scale(tileScale, tileScale);
            canvas.drawRect(Rect.makeXYWH(x / tileScale, y / tileScale, w / tileScale, h / tileScale), p);
            canvas.restore();
        }
    }

    /** The tiled dirt shader, decoded once from the game resources (null when missing). */
    static synchronized Shader shader() {
        if (shader == null) {
            Image img = image();
            if (img != null) {
                shader = img.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
            }
        }
        return shader;
    }

    /** {@code /ui/mainMenu/Dirt.png}, as the game backend loads it. */
    public static synchronized Image image() {
        if (dirt == null) {
            try (InputStream in = DirtBackdropProvider.class.getResourceAsStream("/ui/mainMenu/Dirt.png")) {
                if (in != null) {
                    dirt = Image.makeFromEncoded(in.readAllBytes());
                }
            } catch (java.io.IOException e) {
                org.slf4j.LoggerFactory.getLogger(DirtBackdropProvider.class).warn("[ui] Dirt.png unreadable", e);
            }
        }
        return dirt;
    }
}
