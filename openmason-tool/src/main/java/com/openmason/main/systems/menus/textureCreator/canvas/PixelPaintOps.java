package com.openmason.main.systems.menus.textureCreator.canvas;

import java.util.ArrayDeque;

/**
 * Shared pixel-painting primitives (rect, Bresenham line, flood fill) over a
 * caller-supplied writer, so every painting surface — scripted face textures,
 * scripted canvas edits, the MCP one-shot tools — uses the same geometry and
 * only differs in how a write is applied (guards, undo recording, dirty
 * tracking).
 */
public final class PixelPaintOps {

    /** Applies one pixel write; returns 1 if the pixel actually changed, else 0. */
    @FunctionalInterface
    public interface PixelWriter {
        int write(int x, int y, int color);
    }

    /** Whether flood fill may traverse a pixel (bounds + guards). */
    @FunctionalInterface
    public interface PixelProbe {
        boolean test(int x, int y);
    }

    private PixelPaintOps() {
    }

    /**
     * Ellipse inscribed in the box (x,y,w,h) — filled, or a 1px ring (pixels
     * inside the ellipse with a 4-neighbour outside it). Returns pixels changed.
     */
    public static int ellipse(PixelWriter writer, int x, int y, int w, int h, int color, boolean filled) {
        if (w <= 0 || h <= 0) return 0;
        double cx = x + w / 2.0, cy = y + h / 2.0, rx = w / 2.0, ry = h / 2.0;
        int changed = 0;
        for (int py = y; py < y + h; py++) {
            for (int px = x; px < x + w; px++) {
                if (!insideEllipse(px, py, cx, cy, rx, ry)) continue;
                if (!filled
                        && insideEllipse(px + 1, py, cx, cy, rx, ry)
                        && insideEllipse(px - 1, py, cx, cy, rx, ry)
                        && insideEllipse(px, py + 1, cx, cy, rx, ry)
                        && insideEllipse(px, py - 1, cx, cy, rx, ry)) {
                    continue;
                }
                changed += writer.write(px, py, color);
            }
        }
        return changed;
    }

    private static boolean insideEllipse(int px, int py, double cx, double cy, double rx, double ry) {
        double nx = (px + 0.5 - cx) / rx, ny = (py + 0.5 - cy) / ry;
        return nx * nx + ny * ny <= 1.0;
    }

    /**
     * Auto-outline an opaque silhouette. {@code inside=false} grows a 1px border
     * into transparent 4-neighbours; {@code inside=true} recolours the
     * silhouette's own edge row. {@code fixedColor} null ⇒ each outline pixel is
     * {@link #outlineOf} its neighbour (darker, cooler, never flat black).
     * Reads from a snapshot ({@code src}, {@link PixelCanvas}-packed, row-major) so writes do
     * not feed back into the pass. Returns pixels changed.
     */
    public static int outline(PixelWriter writer, int[] src, int w, int h, boolean inside, Integer fixedColor) {
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int self = src[y * w + x];
                boolean opaque = alphaOf(self) != 0;
                if (inside) {
                    if (!opaque) continue;
                    boolean edge = false;
                    for (int[] o : n4) {
                        int nx = x + o[0], ny = y + o[1];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || alphaOf(src[ny * w + nx]) == 0) {
                            edge = true;
                            break;
                        }
                    }
                    if (edge) changed += writer.write(x, y, fixedColor != null ? fixedColor : outlineOf(self));
                } else {
                    if (opaque) continue;
                    int neighbour = 0;
                    for (int[] o : n4) {
                        int nx = x + o[0], ny = y + o[1];
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && alphaOf(src[ny * w + nx]) != 0) {
                            neighbour = src[ny * w + nx];
                            break;
                        }
                    }
                    if (neighbour != 0) {
                        changed += writer.write(x, y, fixedColor != null ? fixedColor : outlineOf(neighbour));
                    }
                }
            }
        }
        return changed;
    }

    /** Darker, cooler, never black — the pixel-art outline rule (packed RGBA in/out). */
    public static int outlineOf(int packed) {
        int[] c = PixelCanvas.unpackRGBA(packed);
        return PixelCanvas.packRGBA(clamp8(c[0] * 0.42 + 8), clamp8(c[1] * 0.38 + 6), clamp8(c[2] * 0.48 + 22), 255);
    }

    private static int alphaOf(int packed) {
        return (packed >>> 24) & 0xFF;
    }

    private static int clamp8(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Rectangle at (x,y) size w×h — filled, or a 1px outline. Returns pixels changed. */
    public static int rect(PixelWriter writer, int x, int y, int w, int h, int color, boolean filled) {
        if (w <= 0 || h <= 0) return 0;
        int x1 = x + w - 1;
        int y1 = y + h - 1;
        int changed = 0;
        for (int yy = y; yy <= y1; yy++) {
            for (int xx = x; xx <= x1; xx++) {
                if (!filled && xx != x && xx != x1 && yy != y && yy != y1) continue;
                changed += writer.write(xx, yy, color);
            }
        }
        return changed;
    }

    /** 1-pixel Bresenham line from (x0,y0) to (x1,y1). Returns pixels changed. */
    public static int line(PixelWriter writer, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int changed = 0;
        int x = x0, y = y0;
        while (true) {
            changed += writer.write(x, y, color);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return changed;
    }

    /**
     * 4-connected flood fill seeded at (sx,sy), replacing the seed's color.
     * Reads through {@code canvas}; traversal is gated by {@code traversable}
     * (bounds plus any guard — non-traversable pixels also block the fill,
     * matching the texture editor's semantics). Returns pixels changed.
     */
    public static int flood(PixelCanvas canvas, PixelWriter writer, PixelProbe traversable,
                            int sx, int sy, int fillColor) {
        if (!canvas.isValidCoordinate(sx, sy)) return 0;
        int target = canvas.getPixel(sx, sy);
        if (target == fillColor) return 0;

        int w = canvas.getWidth();
        int h = canvas.getHeight();
        boolean[][] visited = new boolean[w][h];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.offer(new int[]{sx, sy});
        int changed = 0;
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1];
            if (x < 0 || x >= w || y < 0 || y >= h || visited[x][y]) continue;
            visited[x][y] = true;
            if (!traversable.test(x, y)) continue;
            if (canvas.getPixel(x, y) != target) continue;
            changed += writer.write(x, y, fillColor);
            queue.offer(new int[]{x + 1, y});
            queue.offer(new int[]{x - 1, y});
            queue.offer(new int[]{x, y + 1});
            queue.offer(new int[]{x, y - 1});
        }
        return changed;
    }
}
