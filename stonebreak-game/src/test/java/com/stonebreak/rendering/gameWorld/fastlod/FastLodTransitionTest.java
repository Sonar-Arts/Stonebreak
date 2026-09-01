package com.stonebreak.rendering.gameWorld.fastlod;

import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the LOD↔native crossfade state machine
 * ({@link FastLodRenderPass#updateFade}). Covers the four transitions the
 * render pass performs: dissolve-out under a live native chunk, hole-cover
 * inside the ring edge, snap-to-solid when a column leaves the native disk,
 * and dissolve-in for fresh far-ring nodes.
 */
class FastLodTransitionTest {

    private static final int INNER = 8;

    private static FastLodManager.Entry entry() {
        return new FastLodManager.Entry(FastLodKey.of(FastLodLevel.L0, 0, 0), null, 60f, 90f);
    }

    @Test
    void nodeUnderLiveNativeChunkDissolvesOutOverTime() {
        FastLodManager.Entry e = entry();
        e.fade = 1f;

        float afterOneStep = FastLodRenderPass.updateFade(e, INNER, INNER, true, 0.1f);
        assertTrue(afterOneStep < 1f && afterOneStep > 0f,
                "fade must decrease gradually, not cut to zero");
        assertTrue(e.nativeCovered, "covered flag set while the native mesh is live");

        // Enough accumulated time fades it fully out and it stays out.
        for (int i = 0; i < 20; i++) {
            FastLodRenderPass.updateFade(e, INNER, INNER, true, 0.1f);
        }
        assertEquals(0f, e.fade, 1e-6f);
    }

    @Test
    void nodeInsideRingEdgeWithoutNativeMeshCoversTheHole() {
        FastLodManager.Entry e = entry();   // fresh upload: fade starts at 0

        float f = FastLodRenderPass.updateFade(e, INNER, INNER, false, 0.1f);
        assertTrue(f > 0f, "must start dissolving in to cover the missing chunk");
        assertFalse(e.nativeCovered);

        for (int i = 0; i < 20; i++) {
            FastLodRenderPass.updateFade(e, INNER, INNER, false, 0.1f);
        }
        assertEquals(1f, e.fade, 1e-6f, "fully covers the hole until the mesh lands");
    }

    /**
     * The void this system exists to prevent, in the one direction the old rule missed.
     * A node that has fully dissolved under a live detail chunk draws nothing; if that
     * chunk stops drawing while the column is still inside the native disk — it unloaded,
     * or dropped MESH_GPU_UPLOADED to rebuild in place — the node has to come straight
     * back. Fading in from zero at FADE_IN_PER_SEC leaves the column at under half
     * opacity for ~130 ms and under 90% for ~250 ms, which reads as a hole in the terrain.
     */
    @Test
    void losingTheNativeMeshInsideTheDiskSnapsBackToSolid() {
        FastLodManager.Entry e = entry();
        e.fade = 1f;
        for (int i = 0; i < 40; i++) {
            FastLodRenderPass.updateFade(e, INNER, INNER, true, 1f / 60f);
        }
        assertEquals(0f, e.fade, 1e-6f, "precondition: fully dissolved under the detail chunk");
        assertTrue(e.nativeCovered);

        float f = FastLodRenderPass.updateFade(e, INNER, INNER, false, 1f / 60f);

        assertEquals(1f, f, 1e-6f,
                "nothing draws this column now — it must not fade in from zero");
        assertFalse(e.nativeCovered, "flag consumed by the snap");
    }

    /** The snap is one-shot: once consumed, a still-missing chunk leaves the node solid. */
    @Test
    void snappedBackNodeStaysSolidWhileTheChunkIsStillMissing() {
        FastLodManager.Entry e = entry();
        e.fade = 0f;
        e.nativeCovered = true;

        FastLodRenderPass.updateFade(e, INNER, INNER, false, 1f / 60f);
        for (int i = 0; i < 30; i++) {
            FastLodRenderPass.updateFade(e, INNER, INNER, false, 1f / 60f);
        }
        assertEquals(1f, e.fade, 1e-6f);

        // And when the chunk comes back it dissolves out again, no pop.
        float f = FastLodRenderPass.updateFade(e, INNER, INNER, true, 1f / 60f);
        assertTrue(f < 1f && f > 0f, "returns to a gradual dissolve-out");
        assertTrue(e.nativeCovered);
    }

    @Test
    void leavingTheNativeDiskSnapsACoveredNodeSolid() {
        FastLodManager.Entry e = entry();
        e.fade = 0f;
        e.nativeCovered = true;   // was fully hidden under a native chunk

        float f = FastLodRenderPass.updateFade(e, INNER + 1, INNER, false, 0.016f);
        assertEquals(1f, f, 1e-6f,
                "the native chunk no longer draws this column — fading in would flash a gap");
        assertFalse(e.nativeCovered, "flag consumed by the snap");
    }

    @Test
    void freshFarRingNodeDissolvesInInsteadOfPopping() {
        FastLodManager.Entry e = entry();   // fade 0, never covered

        float f = FastLodRenderPass.updateFade(e, INNER + 5, INNER, false, 0.05f);
        assertTrue(f > 0f && f < 1f, "gradual dissolve-in, no snap");

        for (int i = 0; i < 20; i++) {
            FastLodRenderPass.updateFade(e, INNER + 5, INNER, false, 0.1f);
        }
        assertEquals(1f, e.fade, 1e-6f);
    }

    @Test
    void reappearingNativeMeshReversesAnInProgressFadeIn() {
        FastLodManager.Entry e = entry();
        e.fade = 0.5f;   // mid hole-cover fade-in

        FastLodRenderPass.updateFade(e, INNER, INNER, true, 0.1f);
        assertTrue(e.fade < 0.5f, "native mesh landing mid-fade reverses direction smoothly");
        assertTrue(e.nativeCovered);
    }
}
