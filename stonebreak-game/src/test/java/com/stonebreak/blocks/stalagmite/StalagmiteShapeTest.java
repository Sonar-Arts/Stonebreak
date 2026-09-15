package com.stonebreak.blocks.stalagmite;

import com.stonebreak.blocks.stairs.StairState.Facing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Model-measured stalagmite boxes, and that they turn and flip the way the renderer's stamps do. */
class StalagmiteShapeTest {

    private static final float EPS = 1e-4f;

    @Test
    void twoStackedBoxesSliceIntoOneBoxPerTier() {
        float[][] tris = concat(boxTriangles(-0.4f, -0.5f, -0.4f, 0.4f, 0f, 0.4f),
                boxTriangles(-0.1f, 0f, -0.1f, 0.1f, 0.5f, 0.1f));
        List<float[]> boxes = StalagmiteShape.slice(tris);
        assertEquals(2, boxes.size());
        assertArrayEquals(new float[]{-0.4f, -0.5f, -0.4f, 0.4f, 0f, 0.4f}, boxes.get(0), EPS);
        assertArrayEquals(new float[]{-0.1f, 0f, -0.1f, 0.1f, 0.5f, 0.1f}, boxes.get(1), EPS);
    }

    @Test
    void hangingMirrorsAboutTheCellCentreAndFacingTurnsLikeTheStamp() {
        float[] box = {0.1f, -0.5f, 0.2f, 0.3f, 1.5f, 0.4f};
        float[] hung = StalagmiteShape.place(box, new StalagmiteState(2, true, Facing.SOUTH));
        assertArrayEquals(new float[]{0.1f, -1.5f, 0.2f, 0.3f, 0.5f, 0.4f}, hung, EPS);
        // One quarter turn (EAST) maps +Z onto +X, as SBOStampRotator does: (x, z) -> (z, -x).
        float[] east = StalagmiteShape.place(box, new StalagmiteState(2, false, Facing.EAST));
        assertArrayEquals(new float[]{0.2f, -0.5f, -0.3f, 0.4f, 1.5f, -0.1f}, east, EPS);
    }

    @Test
    void realModelsFollowTheirTiersAndTaper() {
        for (int size = 1; size <= 3; size++) {
            List<float[]> boxes = StalagmiteShape.placedBoxes(new StalagmiteState(size, false, Facing.SOUTH));
            assertTrue(boxes.size() > 2, "size " + size + " should be several tier boxes, got " + boxes.size());
            float bottom = Float.MAX_VALUE, top = -Float.MAX_VALUE;
            for (float[] b : boxes) {
                bottom = Math.min(bottom, b[1]);
                top = Math.max(top, b[4]);
            }
            assertEquals(-0.5f, bottom, 0.01f, "size " + size + " stands on its cell floor");
            assertEquals(size - 0.5f, top, 0.01f, "size " + size + " reaches its full height");
            float[] base = boxes.get(0);
            float[] tip = boxes.get(boxes.size() - 1);
            assertTrue(tip[3] - tip[0] < base[3] - base[0], "size " + size + " tapers to a tip");
            assertTrue(base[3] - base[0] < 1f, "size " + size + " is narrower than a block");
        }
    }

    private static float[][] boxTriangles(float x0, float y0, float z0, float x1, float y1, float z1) {
        // Bounds are all slice() reads: four sides spanning the height, plus both caps.
        return new float[][]{
                {x0, y0, z0, x1, y1, z0}, {x0, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z1}, {x1, y0, z0, x1, y1, z1},
                {x0, y0, z0, x1, y0, z1}, {x0, y1, z0, x1, y1, z1},
        };
    }

    private static float[][] concat(float[][] a, float[][] b) {
        float[][] out = new float[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
