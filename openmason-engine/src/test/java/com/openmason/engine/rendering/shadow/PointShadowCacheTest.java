package com.openmason.engine.rendering.shadow;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PointShadowCacheTest {
    @Test
    void reorderedSourcesCannotSampleAnotherLightsCachedDepth() {
        PointShadowCache cache = new PointShadowCache(2);
        Vector3f left = new Vector3f(-2, 3, -2);
        Vector3f right = new Vector3f(2, 3, -2);
        cache.rendered(0, left);
        cache.rendered(1, right);
        // Camera movement changes nearest-source order while both torches stay stationary.
        assertTrue(cache.needsRefresh(0, right));
        assertTrue(cache.needsRefresh(1, left));
        cache.rendered(0, right);
        cache.rendered(1, left);
        assertFalse(cache.needsRefresh(0, right));
        assertFalse(cache.needsRefresh(1, left));
        cache.retain(1);
        assertTrue(cache.needsRefresh(1, left), "a removed source must not leave reusable shadow depth");
    }

    @Test
    void unchangedPositionsReuseDepthButNewAndMovedLightsRefreshImmediately() {
        PointShadowCache cache = new PointShadowCache(16);
        Vector3f p = new Vector3f(3, 4, 5);
        for (int i = 0; i < 16; i++) {
            assertTrue(cache.needsRefresh(i, p));
            cache.rendered(i, p);
            assertFalse(cache.needsRefresh(i, p));
            assertTrue(cache.needsRefresh(i, new Vector3f(p).add(.01f, 0, 0)));
        }
    }

    @Test
    void removedSlotsAndWorldChangesCannotReuseOldDepth() {
        PointShadowCache cache = new PointShadowCache(4);
        Vector3f p = new Vector3f();
        cache.rendered(2, p);
        assertFalse(cache.needsRefresh(2, p));
        cache.retain(2);
        assertTrue(cache.needsRefresh(2, p));
        cache.rendered(2, p);
        cache.invalidate();
        assertTrue(cache.needsRefresh(2, p));
    }
}
