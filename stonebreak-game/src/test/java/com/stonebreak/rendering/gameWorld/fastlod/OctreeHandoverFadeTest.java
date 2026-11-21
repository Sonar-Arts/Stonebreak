package com.stonebreak.rendering.gameWorld.fastlod;

import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The octree-mode handover steps: FastLOD fallback nodes and octree nodes never leave a frame of hole. */
class OctreeHandoverFadeTest {

    private static FastLodManager.Entry entry() {
        return new FastLodManager.Entry(FastLodKey.of(FastLodLevel.L0, 0, 0), null, 0f, 64f);
    }

    @Test
    void fallbackNodeFadesInAndSnapsWhenDetailJustStopped() {
        FastLodManager.Entry e = entry();
        float f = FastLodRenderPass.updateFallbackFade(e, true, false, 0.1f);
        assertTrue(f > 0f && f < 1f, "fades in: " + f);
        FastLodManager.Entry s = entry();
        assertEquals(1f, FastLodRenderPass.updateFallbackFade(s, true, true, 0.0f));
    }

    @Test
    void uncoveredNodeHoldsItsOpacityThenStops() {
        FastLodManager.Entry e = entry();
        for (int i = 0; i < 10; i++) {
            FastLodRenderPass.updateFallbackFade(e, true, false, 0.1f);
        }
        assertEquals(1f, e.fade);
        float t = 0f;
        while (t < FastLodRenderPass.FALLBACK_HOLD_SECONDS - 0.05f) {
            assertEquals(1f, FastLodRenderPass.updateFallbackFade(e, false, false, 0.05f), "held at " + t);
            t += 0.05f;
        }
        for (int i = 0; i < 4; i++) {
            FastLodRenderPass.updateFallbackFade(e, false, false, 0.05f);
        }
        assertEquals(0f, e.fade);
        // Covered again: fades back in (no snap), from nothing.
        float back = FastLodRenderPass.updateFallbackFade(e, true, false, 0.1f);
        assertTrue(back > 0f && back < 1f);
    }

    @Test
    void neverDrawnNodeIsDroppedAtOnce() {
        FastLodManager.Entry e = entry();
        assertEquals(0f, FastLodRenderPass.updateFallbackFade(e, false, false, 0.016f));
    }

    @Test
    void octreeNodeFadesInAndAHiddenNodeIsHeldSolidThenFreed() {
        OctreeLodRenderer.Node n = new OctreeLodRenderer.Node(2, 1, -3, 4);
        assertFalse(OctreeLodRenderer.advance(n, 0.1f));
        assertTrue(n.fade > 0f && n.fade < 1f);
        for (int i = 0; i < 5; i++) {
            OctreeLodRenderer.advance(n, 0.1f);
        }
        assertEquals(1f, n.fade);
        n.hiding = true;
        n.hold = OctreeLodRenderer.HIDE_HOLD_SECONDS;
        int frames = 0;
        while (!OctreeLodRenderer.advance(n, 0.05f)) {
            assertEquals(1f, n.fade, "held solid");
            frames++;
        }
        assertTrue(frames * 0.05f >= OctreeLodRenderer.HIDE_HOLD_SECONDS - 1e-3f, "held " + frames + " frames");
    }

    @Test
    void nodeTagRoundTripsThroughTheShaderDecode() {
        for (int level = 0; level <= 16; level++) {
            float w = OctreeLodRenderer.nodeTag(level);
            assertTrue(w < -3.5f);
            assertEquals(level, (int) (-w - 4.0f + 0.5f));
        }
    }
}
