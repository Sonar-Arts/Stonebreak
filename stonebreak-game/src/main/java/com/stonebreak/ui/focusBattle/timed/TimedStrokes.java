package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * The battle-specific line work of the timed-input overlays: reticle notches, burst rays, parry
 * brackets, the block shield, frost shards. The UI library draws circles, frames and text; open
 * polylines and free polygons are this HUD's own shapes, so they live here.
 *
 * <p>These marks float over the 3D scene with no frame behind them, and the scene is both bright
 * snow and a near-black Archon. One rule keeps them readable on either: <b>every bright stroke sits
 * on a wider {@link MStyle#OUTLINE_DARK} under-stroke</b>, the same outline colour the library's
 * {@link MPainter#drawTextOutlined} puts under words.
 */
final class TimedStrokes {

    private TimedStrokes() {}

    /** How much wider than its stroke the dark under-stroke is. */
    static float underGrowth(float scale) {
        return Math.max(3f, 3f * scale);
    }

    /** A circle outline on its dark under-stroke; {@code alpha} fades both. */
    static void haloCircle(Canvas canvas, float cx, float cy, float radius, float width, int color, float alpha,
                           float scale) {
        if (alpha <= 0f) return;
        MPainter.strokeCircle(canvas, cx, cy, radius, MColor.fade(MStyle.OUTLINE_DARK, alpha), width + underGrowth(scale));
        MPainter.strokeCircle(canvas, cx, cy, radius, MColor.fade(color, alpha), width);
    }

    /** The annulus between two radii, as one wide stroke. */
    static void annulus(Canvas canvas, float cx, float cy, float inner, float outer, int color) {
        float lo = Math.max(0f, Math.min(inner, outer)), hi = Math.max(inner, outer);
        MPainter.strokeCircle(canvas, cx, cy, (lo + hi) / 2f, color, hi - lo);
    }

    /** A straight segment on its dark under-stroke. */
    static void haloLine(Canvas canvas, float x0, float y0, float x1, float y1, float width, int color, float alpha,
                         float scale) {
        haloPolyline(canvas, new float[]{x0, y0, x1, y1}, width, color, alpha, scale);
    }

    /** Open polyline through {@code xy} = {x0,y0,x1,y1,…} on its dark under-stroke. */
    static void haloPolyline(Canvas canvas, float[] xy, float width, int color, float alpha, float scale) {
        if (canvas == null || xy == null || xy.length < 4 || alpha <= 0f) return;
        try (Path path = path(xy, false)) {
            stroke(canvas, path, width + underGrowth(scale), MColor.fade(MStyle.OUTLINE_DARK, alpha));
            stroke(canvas, path, width, MColor.fade(color, alpha));
        }
    }

    /** Closed filled polygon through {@code xy}. */
    static void fillPolygon(Canvas canvas, float[] xy, int color) {
        if (canvas == null || xy == null || xy.length < 6 || (color & 0xFF000000) == 0) return;
        try (Path path = path(xy, true); Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawPath(path, p);
        }
    }

    /** Closed polygon outline on its dark under-stroke. */
    static void haloPolygon(Canvas canvas, float[] xy, float width, int color, float alpha, float scale) {
        if (canvas == null || xy == null || xy.length < 6 || alpha <= 0f) return;
        try (Path path = path(xy, true)) {
            stroke(canvas, path, width + underGrowth(scale), MColor.fade(MStyle.OUTLINE_DARK, alpha));
            stroke(canvas, path, width, MColor.fade(color, alpha));
        }
    }

    /** Closed polygon outline, no under-stroke (for rims that already sit on their own dark fill). */
    static void strokePolygon(Canvas canvas, float[] xy, float width, int color) {
        if (canvas == null || xy == null || xy.length < 6) return;
        try (Path path = path(xy, true)) {
            stroke(canvas, path, width, color);
        }
    }

    private static Path path(float[] xy, boolean closed) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) pb.lineTo(xy[i], xy[i + 1]);
            if (closed) pb.closePath();
            return pb.build();
        }
    }

    private static void stroke(Canvas canvas, Path path, float width, int color) {
        if (width <= 0f || (color & 0xFF000000) == 0) return;
        try (Paint p = new Paint().setColor(color).setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(width).setStrokeCap(PaintStrokeCap.SQUARE)) {
            canvas.drawPath(path, p);
        }
    }
}
