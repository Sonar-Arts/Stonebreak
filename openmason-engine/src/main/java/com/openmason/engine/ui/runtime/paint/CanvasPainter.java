package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiCanvasCommands;
import com.openmason.engine.ui.runtime.UiRect;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Paints a {@code Canvas} element's draw commands (#292), read in place from the native buffer
 * its script filled. Command layout (floats; mirrored in the {@code ui} prelude):
 *
 * <pre>
 *  2 rect    x y w h rgb a           7 number  value x y size rgb a decimals
 *  3 circle  cx cy r rgb a           8 clip    x y w h
 *  4 line    x0 y0 x1 y1 width rgb a 9 unclip
 *  5 sprite  tex x y w h u0 v0 u1 v1 a   (u1 &lt; 0: the whole texture)
 *  6 text    str x y size rgb a      10 translate dx dy   11 reset-transform
 * </pre>
 *
 * Coordinates are logical px from the canvas's top-left; {@code rgb} is 0xRRGGBB and {@code a}
 * in [0, 1]. Text {@code y} is the baseline. Everything is clipped to the element. A truncated or
 * unknown command ends the frame's drawing (the script's bug, never a crash). Paints, fonts and
 * textures are reused across frames.
 */
final class CanvasPainter {

    static final int RECT = 2;
    static final int CIRCLE = 3;
    static final int LINE = 4;
    static final int SPRITE = 5;
    static final int TEXT = 6;
    static final int NUMBER = 7;
    static final int CLIP = 8;
    static final int UNCLIP = 9;
    static final int TRANSLATE = 10;
    static final int RESET = 11;

    private final UiPaintHost host;
    private final MasonryContentMeasurer text;
    // Created on the first canvas paint, so documents without a Canvas never touch Skija here.
    private Paint fill;
    private Paint stroke;
    private Paint sprite;
    private final Map<String, MTexture> textures = new HashMap<>();

    CanvasPainter(UiPaintHost host, MasonryContentMeasurer text) {
        this.host = host;
        this.text = text;
    }

    void paint(Canvas canvas, UiCanvasCommands cmds, UiRect r, float scale) {
        if (cmds == null) {
            return;
        }
        if (fill == null) {
            fill = new Paint().setAntiAlias(true);
            stroke = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE);
            sprite = new Paint();
        }
        int n = cmds.size();
        int base = canvas.save();
        canvas.clipRect(Rect.makeXYWH(r.x(), r.y(), r.width(), r.height()));
        float ox = r.x();
        float oy = r.y();
        float tx = 0;
        float ty = 0;
        int i = 0;
        loop:
        while (i < n) {
            int op = (int) cmds.get(i);
            switch (op) {
                case RECT -> {
                    if (i + 7 > n) {
                        break loop;
                    }
                    float x = ox + (cmds.get(i + 1) + tx) * scale;
                    float y = oy + (cmds.get(i + 2) + ty) * scale;
                    fill.setColor(argb(cmds.get(i + 5), cmds.get(i + 6)));
                    canvas.drawRect(Rect.makeXYWH(x, y, cmds.get(i + 3) * scale, cmds.get(i + 4) * scale), fill);
                    i += 7;
                }
                case CIRCLE -> {
                    if (i + 6 > n) {
                        break loop;
                    }
                    fill.setColor(argb(cmds.get(i + 4), cmds.get(i + 5)));
                    canvas.drawCircle(ox + (cmds.get(i + 1) + tx) * scale, oy + (cmds.get(i + 2) + ty) * scale,
                        cmds.get(i + 3) * scale, fill);
                    i += 6;
                }
                case LINE -> {
                    if (i + 8 > n) {
                        break loop;
                    }
                    stroke.setColor(argb(cmds.get(i + 6), cmds.get(i + 7)));
                    stroke.setStrokeWidth(cmds.get(i + 5) * scale);
                    canvas.drawLine(ox + (cmds.get(i + 1) + tx) * scale, oy + (cmds.get(i + 2) + ty) * scale,
                        ox + (cmds.get(i + 3) + tx) * scale, oy + (cmds.get(i + 4) + ty) * scale, stroke);
                    i += 8;
                }
                case SPRITE -> {
                    if (i + 11 > n) {
                        break loop;
                    }
                    Image img = image(cmds.texture((int) cmds.get(i + 1)));
                    if (img != null) {
                        float u1 = cmds.get(i + 8);
                        float v1 = cmds.get(i + 9);
                        Rect src = u1 < 0 || v1 < 0 ? Rect.makeWH(img.getWidth(), img.getHeight())
                            : Rect.makeLTRB(cmds.get(i + 6), cmds.get(i + 7), u1, v1);
                        sprite.setAlphaf(Math.clamp(cmds.get(i + 10), 0f, 1f));
                        canvas.drawImageRect(img, src, Rect.makeXYWH(ox + (cmds.get(i + 2) + tx) * scale,
                                oy + (cmds.get(i + 3) + ty) * scale, cmds.get(i + 4) * scale, cmds.get(i + 5) * scale),
                            SamplingMode.DEFAULT, sprite, true);
                    }
                    i += 11;
                }
                case TEXT, NUMBER -> {
                    int len = op == TEXT ? 7 : 8;
                    if (i + len > n) {
                        break loop;
                    }
                    String s = op == TEXT ? cmds.string((int) cmds.get(i + 1))
                        : number(cmds.get(i + 1), (int) cmds.get(i + 7));
                    Font font = text == null ? null : text.fontAt(cmds.get(i + 4), scale);
                    if (s != null && font != null) {
                        fill.setColor(argb(cmds.get(i + 5), cmds.get(i + 6)));
                        canvas.drawString(s, ox + (cmds.get(i + 2) + tx) * scale, oy + (cmds.get(i + 3) + ty) * scale,
                            font, fill);
                    }
                    i += len;
                }
                case CLIP -> {
                    if (i + 5 > n) {
                        break loop;
                    }
                    canvas.save();
                    canvas.clipRect(Rect.makeXYWH(ox + (cmds.get(i + 1) + tx) * scale,
                        oy + (cmds.get(i + 2) + ty) * scale, cmds.get(i + 3) * scale, cmds.get(i + 4) * scale));
                    i += 5;
                }
                case UNCLIP -> {
                    if (canvas.getSaveCount() > base + 1) {
                        canvas.restore();
                    }
                    i += 1;
                }
                case TRANSLATE -> {
                    if (i + 3 > n) {
                        break loop;
                    }
                    tx += cmds.get(i + 1);
                    ty += cmds.get(i + 2);
                    i += 3;
                }
                case RESET -> {
                    tx = 0;
                    ty = 0;
                    i += 1;
                }
                default -> {
                    break loop;
                }
            }
        }
        canvas.restoreToCount(base);
    }

    private Image image(String ref) {
        if (ref == null) {
            return null;
        }
        MTexture t = textures.get(ref);
        if (t == null && !textures.containsKey(ref)) {
            t = host.texture(ref);
            textures.put(ref, t);
        }
        return t == null ? null : t.image();
    }

    private static int argb(float rgb, float alpha) {
        int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255f);
        return a << 24 | ((int) rgb & 0xFFFFFF);
    }

    private static String number(float value, int decimals) {
        if (decimals <= 0) {
            return Long.toString(Math.round((double) value));
        }
        return String.format(Locale.ROOT, "%." + Math.min(decimals, 6) + "f", value);
    }
}
