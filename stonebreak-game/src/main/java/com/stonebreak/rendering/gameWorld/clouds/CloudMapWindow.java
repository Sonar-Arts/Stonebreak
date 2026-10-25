package com.stonebreak.rendering.gameWorld.clouds;

import java.util.ArrayList;
import java.util.List;

/**
 * Bookkeeping for the toroidal cloud map: which cloud-space cells must be
 * (re)written when the window of {@code size}×{@code size} cells around the
 * camera moves, and how a cloud-space rectangle splits at the texture's wrap
 * seams. Pure arithmetic, so the GL side ({@link CloudMap}) stays trivial.
 */
final class CloudMapWindow {

    /** A rectangle of cells: cloud-space origin and size, or texel origin for {@link #wrap}. */
    record Rect(int x, int z, int w, int h) {
        int area() {
            return w * h;
        }
    }

    private CloudMapWindow() {}

    /**
     * Cells in the window at ({@code newX}, {@code newZ}) that the window at
     * ({@code oldX}, {@code oldZ}) did not cover. A jump past the whole map
     * returns the full new window.
     */
    static List<Rect> exposed(int oldX, int oldZ, int newX, int newZ, int size) {
        List<Rect> out = new ArrayList<>(2);
        int dx = newX - oldX;
        int dz = newZ - oldZ;
        if (Math.abs(dx) >= size || Math.abs(dz) >= size) {
            out.add(new Rect(newX, newZ, size, size));
            return out;
        }
        // Columns the old window never had, over the full new height.
        if (dx > 0) out.add(new Rect(oldX + size, newZ, dx, size));
        else if (dx < 0) out.add(new Rect(newX, newZ, -dx, size));
        // Rows the old window never had, over the columns both windows share.
        int sharedX = Math.max(oldX, newX);
        int sharedW = size - Math.abs(dx);
        if (dz > 0) out.add(new Rect(sharedX, oldZ + size, sharedW, dz));
        else if (dz < 0) out.add(new Rect(sharedX, newZ, sharedW, -dz));
        return out;
    }

    /**
     * Splits a cloud-space rectangle (at most {@code size} on a side) into the
     * pieces that are contiguous in texel space, each with its texel origin.
     * Returned pairs are (cloud-space rect, texel rect) of equal size.
     */
    static List<Rect[]> wrap(Rect cells, int size) {
        List<Rect[]> out = new ArrayList<>(4);
        int tx = Math.floorMod(cells.x(), size);
        int tz = Math.floorMod(cells.z(), size);
        int w1 = Math.min(cells.w(), size - tx);
        int h1 = Math.min(cells.h(), size - tz);
        int[][] xs = {{cells.x(), tx, w1}, {cells.x() + w1, 0, cells.w() - w1}};
        int[][] zs = {{cells.z(), tz, h1}, {cells.z() + h1, 0, cells.h() - h1}};
        for (int[] x : xs) {
            if (x[2] <= 0) continue;
            for (int[] z : zs) {
                if (z[2] <= 0) continue;
                out.add(new Rect[]{
                        new Rect(x[0], z[0], x[2], z[2]),
                        new Rect(x[1], z[1], x[2], z[2])});
            }
        }
        return out;
    }
}
