package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.ui.runtime.UiRect;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each piece of a sprite lands (#294). Pure geometry, so the rules below are unit-tested
 * without a canvas:
 *
 * <ul>
 *   <li><b>Device scale.</b> {@code kx}/{@code ky} are device pixels per source pixel: the
 *       sprite's logical size × UI scale ÷ its source size.</li>
 *   <li><b>Pixel-art snapping</b> ({@code snap}, used with nearest sampling): corner scales round
 *       to a whole number of device pixels per source pixel (at least 1), so every source pixel of
 *       a corner covers the same number of device pixels at fractional DPI (1.25×, 1.5×); every
 *       patch edge lands on a whole device pixel, so neighbouring patches never leave a seam or
 *       overlap. Without snapping (linear sampling) geometry is exact.</li>
 *   <li><b>Below the minimum size</b> (the element is narrower than left + right corners, or
 *       shorter than top + bottom): all four corners shrink by one common factor until they fit,
 *       keeping their aspect (CSS border-image rule); edges and the centre get no space. Nothing
 *       ever overlaps or turns negative.</li>
 *   <li><b>Edges</b> stretch along their length, or tile at the corner scale; across their depth
 *       they always match the corners. The <b>centre</b> stretches, tiles at the corner scale, or
 *       is hidden.</li>
 *   <li><b>Integer</b> mode draws the largest whole multiple of the source size that fits (at
 *       least 1) and places it by the sprite's pivot; <b>tile</b> repeats from the top-left at the
 *       device scale.</li>
 * </ul>
 */
public final class SpriteSlices {

    private SpriteSlices() {
    }

    /**
     * One draw: source pixels {@code (sx, sy, sw, sh)} of the texture into a device rect. A tiled
     * axis repeats the source at {@code tileKx}/{@code tileKy} device px per source px from the
     * patch origin instead of stretching to the patch.
     */
    public record Patch(int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh,
                        boolean tileX, boolean tileY, float tileKx, float tileKy) {
        public boolean tiled() {
            return tileX || tileY;
        }
    }

    /**
     * @param srcX     region x in the texture (the current animation frame)
     * @param kx       device px per source px along x (logical width × scale ÷ source width)
     * @param pivotX   placement anchor for {@link ScaleMode#INTEGER}
     * @param slice    insets; ignored unless {@code mode} is {@link ScaleMode#NINE_SLICE}
     */
    public static List<Patch> layout(int srcX, int srcY, int srcW, int srcH, Slice slice, Fill edges, Fill center,
                                     ScaleMode mode, double pivotX, double pivotY, UiRect dst, float kx, float ky,
                                     boolean snap) {
        List<Patch> out = new ArrayList<>(9);
        if (srcW <= 0 || srcH <= 0 || dst.width() <= 0 || dst.height() <= 0) {
            return out;
        }
        switch (mode) {
            case INTEGER -> {
                float k = (float) Math.max(1, Math.floor(Math.min(dst.width() / srcW, dst.height() / srcH)));
                float w = srcW * k;
                float h = srcH * k;
                float x = Math.round(dst.x() + (dst.width() - w) * (float) pivotX);
                float y = Math.round(dst.y() + (dst.height() - h) * (float) pivotY);
                out.add(new Patch(srcX, srcY, srcW, srcH, x, y, w, h, false, false, 0, 0));
            }
            case TILE -> {
                float tkx = snap ? Math.max(1, Math.round(kx)) : kx;
                float tky = snap ? Math.max(1, Math.round(ky)) : ky;
                out.add(new Patch(srcX, srcY, srcW, srcH, edge(dst.x(), snap), edge(dst.y(), snap),
                        edge(dst.right(), snap) - edge(dst.x(), snap), edge(dst.bottom(), snap) - edge(dst.y(), snap),
                        true, true, tkx, tky));
            }
            case NINE_SLICE -> nineSlice(out, srcX, srcY, srcW, srcH, slice, edges, center, dst, kx, ky, snap);
            default -> out.add(new Patch(srcX, srcY, srcW, srcH, edge(dst.x(), snap), edge(dst.y(), snap),
                    edge(dst.right(), snap) - edge(dst.x(), snap), edge(dst.bottom(), snap) - edge(dst.y(), snap),
                    false, false, 0, 0));
        }
        return out;
    }

    private static void nineSlice(List<Patch> out, int srcX, int srcY, int srcW, int srcH, Slice s, Fill edges,
                                  Fill center, UiRect dst, float kx, float ky, boolean snap) {
        float cx = snap ? Math.max(1, Math.round(kx)) : kx;
        float cy = snap ? Math.max(1, Math.round(ky)) : ky;
        float l = s.left() * cx;
        float r = s.right() * cx;
        float t = s.top() * cy;
        float b = s.bottom() * cy;
        float fit = 1f;
        if (l + r > dst.width()) {
            fit = Math.min(fit, dst.width() / (l + r));
        }
        if (t + b > dst.height()) {
            fit = Math.min(fit, dst.height() / (t + b));
        }
        l *= fit;
        r *= fit;
        t *= fit;
        b *= fit;
        float tileKx = cx * fit;
        float tileKy = cy * fit;
        // column and row edges in device px; snapped edges keep patches abutting
        float x0 = edge(dst.x(), snap);
        float x3 = edge(dst.right(), snap);
        float x1 = Math.min(edge(dst.x() + l, snap), x3);
        float x2 = Math.max(edge(dst.right() - r, snap), x1);
        float y0 = edge(dst.y(), snap);
        float y3 = edge(dst.bottom(), snap);
        float y1 = Math.min(edge(dst.y() + t, snap), y3);
        float y2 = Math.max(edge(dst.bottom() - b, snap), y1);
        int[] sxs = {srcX, srcX + s.left(), srcX + srcW - s.right(), srcX + srcW};
        int[] sys = {srcY, srcY + s.top(), srcY + srcH - s.bottom(), srcY + srcH};
        float[] dxs = {x0, x1, x2, x3};
        float[] dys = {y0, y1, y2, y3};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int sw = sxs[col + 1] - sxs[col];
                int sh = sys[row + 1] - sys[row];
                float dw = dxs[col + 1] - dxs[col];
                float dh = dys[row + 1] - dys[row];
                if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
                    continue;
                }
                boolean middleCol = col == 1;
                boolean middleRow = row == 1;
                boolean tileX = false;
                boolean tileY = false;
                if (middleCol && middleRow) {
                    if (center == Fill.HIDDEN) {
                        continue;
                    }
                    tileX = tileY = center == Fill.TILE;
                } else if (middleCol) {
                    tileX = edges == Fill.TILE;
                } else if (middleRow) {
                    tileY = edges == Fill.TILE;
                }
                out.add(new Patch(sxs[col], sys[row], sw, sh, dxs[col], dys[row], dw, dh, tileX, tileY,
                        tileX ? tileKx : dw / sw, tileY ? tileKy : dh / sh));
            }
        }
    }

    private static float edge(float v, boolean snap) {
        return snap ? Math.round(v) : v;
    }
}
