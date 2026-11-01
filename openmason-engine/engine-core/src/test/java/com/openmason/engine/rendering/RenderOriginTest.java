package com.openmason.engine.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render-space rebase: that it is algebraically a no-op, and that it buys
 * back the float32 precision absolute world coordinates throw away.
 *
 * <p>No GL here — {@link RenderOrigin#update} only touches buffers when meshes
 * are registered, and none are in a unit test.
 */
class RenderOriginTest {

    /** A camera 100k blocks out, where float32 spacing is ~0.0078 blocks. */
    private static final float FAR_X = 100_000f;
    private static final float FAR_Z = 100_000f;

    @AfterEach
    void resetOrigin() {
        RenderOrigin.reset();
    }

    /** Snapping lands on the grid cell containing the camera, including negatives. */
    @Test
    void snapsToTheGridCellContainingTheCamera() {
        RenderOrigin.update(100_050f, -10f);
        assertEquals(100_032f, RenderOrigin.x(), "floor(100050/64)*64");
        assertEquals(-64f, RenderOrigin.z(), "negatives floor downward, never toward zero");

        // Inside the same cell nothing moves — which is the point of snapping:
        // a stepping origin costs a shimmer in anything keyed to world space.
        assertTrue(RenderOrigin.update(100_050f, -10f) == false);
        assertTrue(RenderOrigin.update(100_095f, -63f) == false);
        assertTrue(RenderOrigin.update(100_096f, -63f), "crossing a cell edge steps");
    }

    /** Y is never rebased — world height is bounded, so the vertical axis is already exact. */
    @Test
    void leavesYAlone() {
        RenderOrigin.update(FAR_X, FAR_Z);
        Vector3f out = RenderOrigin.toRender(new Vector3f(FAR_X, 91.5f, FAR_Z), new Vector3f());
        assertEquals(91.5f, out.y);
    }

    /** World → render → world is exact, so no Java-side consumer loses anything. */
    @Test
    void worldRoundTripIsExact() {
        RenderOrigin.update(FAR_X, FAR_Z);
        Vector3f world = new Vector3f(FAR_X + 13.25f, 64f, FAR_Z - 7.5f);
        Vector3f back = RenderOrigin.toWorld(
                RenderOrigin.toRender(world, new Vector3f()), new Vector3f());
        assertEquals(world.x, back.x);
        assertEquals(world.y, back.y);
        assertEquals(world.z, back.z);
    }

    /**
     * The algebra: {@code (M · T(+origin)) · (p - origin)} is the same transform
     * as {@code M · p}. Both rebase helpers are that identity, in each direction.
     */
    @Test
    void rebasingAMatrixIsANoOpOnTheResult() {
        RenderOrigin.update(FAR_X, FAR_Z);
        Matrix4f worldConsumer = new Matrix4f()
                .perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f)
                .lookAt(new Vector3f(FAR_X, 70f, FAR_Z), new Vector3f(FAR_X + 1f, 70f, FAR_Z), new Vector3f(0, 1, 0));

        Vector3f world = new Vector3f(FAR_X + 5f, 68f, FAR_Z + 2f);
        Vector3f render = RenderOrigin.toRender(world, new Vector3f());

        Vector3f viaWorld = worldConsumer.transformPosition(new Vector3f(world));
        Vector3f viaRender = RenderOrigin
                .acceptRenderSpace(new Matrix4f(worldConsumer))
                .transformPosition(new Vector3f(render));
        assertEquals(viaWorld.x, viaRender.x, 1e-3f);
        assertEquals(viaWorld.y, viaRender.y, 1e-3f);
        assertEquals(viaWorld.z, viaRender.z, 1e-3f);

        // And back again, for consumers that cannot be rebased caller-side
        // (the GPU cull pass, whose AABBs are uploaded in world coordinates).
        Vector3f viaRoundTrip = RenderOrigin
                .acceptWorldSpace(RenderOrigin.acceptRenderSpace(new Matrix4f(worldConsumer)))
                .transformPosition(new Vector3f(world));
        assertEquals(viaWorld.x, viaRoundTrip.x, 1e-3f);
        assertEquals(viaWorld.z, viaRoundTrip.z, 1e-3f);
    }

    /**
     * The terrain half of the bug. World coordinates in float32 sit on a lattice
     * whose spacing grows with magnitude — 1/128 block at 100k. Voxel meshes
     * happen to survive that (their stored offsets are 1/32-block, a multiple of
     * the lattice), but the camera position is an arbitrary fraction and does
     * not: the view transform snaps the whole scene to the lattice every frame,
     * which is the jitter and the z-fighting.
     */
    @Test
    void arbitraryFractionsSurviveTheRebaseAndNotAbsoluteCoordinates() {
        double exact = 100_000.3;
        RenderOrigin.update((float) exact, 0f);

        float absolute = (float) exact;
        double absoluteError = Math.abs(absolute - exact);

        float rebased = (float) (exact - RenderOrigin.x());
        double rebasedError = Math.abs(rebased - (exact - RenderOrigin.x()));

        assertTrue(absoluteError > 3e-3,
                "expected ~1/128 block of quantization at 100k, got " + absoluteError);
        assertTrue(rebasedError < 1e-6,
                "expected the rebase to recover sub-micron precision, got " + rebasedError);
        assertTrue(rebasedError * 1000 < absoluteError,
                "expected at least a 1000x improvement, got "
                        + (absoluteError / Math.max(rebasedError, Double.MIN_VALUE)) + "x");
    }

    /**
     * The mob half of the bug, which bit ~10x closer in. SBE models carry no
     * normals, so the fragment stage recovers one by differentiating the
     * interpolated position. A derivative divides by the per-pixel delta, so it
     * amplifies coordinate error by its reciprocal — which is why mobs went to
     * static thousands of blocks before terrain visibly moved.
     *
     * <p>What the shader actually computes is {@code modelTranslate + localVertex},
     * and it is that sum, at the magnitude of the translate, that rounds. Two
     * adjacent pixels across a mob differ by a few thousandths of a block; the
     * finer the delta (a smaller mob, a more distant one, a grazing face), the
     * more completely absolute coordinates destroy it.
     */
    @Test
    void perPixelDerivativeSurvivesTheRebase() {
        float mobX = 10_000f;
        RenderOrigin.update(mobX, 0f);
        float localTranslate = mobX - RenderOrigin.x();

        // delta -> worst tolerated relative error on the absolute path. At 10k the
        // coordinate lattice is ~0.00098 blocks, so a 0.0006-block delta cannot be
        // represented at all and the reconstructed normal is pure noise.
        float[][] cases = {{0.004f, 0.02f}, {0.0012f, 0.15f}, {0.0006f, 0.60f}};
        for (float[] c : cases) {
            float delta = c[0];
            float absoluteDerivative = (mobX + delta) - (mobX + 0f);
            float renderDerivative = (localTranslate + delta) - (localTranslate + 0f);

            float absoluteError = Math.abs(absoluteDerivative - delta) / delta;
            float renderError = Math.abs(renderDerivative - delta) / delta;

            assertTrue(absoluteError > c[1],
                    "delta " + delta + ": expected absolute coordinates to mangle the derivative"
                            + " by more than " + c[1] + ", got " + absoluteError);
            assertTrue(renderError < 0.01f,
                    "delta " + delta + ": expected the rebase to preserve the derivative,"
                            + " got " + renderError);
        }
    }
}
