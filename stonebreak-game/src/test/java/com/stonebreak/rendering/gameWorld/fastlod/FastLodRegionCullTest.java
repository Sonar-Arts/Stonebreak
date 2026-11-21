package com.stonebreak.rendering.gameWorld.fastlod;

import com.stonebreak.rendering.gameWorld.ChunkFrustumCuller;
import com.stonebreak.world.fastlod.FootprintSet;
import com.stonebreak.world.operations.WorldConfiguration;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FastLOD render pass's 2026-11-20 shortcuts never change what draws: a region classified OUTSIDE holds no visible
 * node box, INSIDE holds no invisible one; the footprint stamp changes on every mutation.
 */
class FastLodRegionCullTest {

    @Test
    void regionClassesAgreeWithThePerNodeBoxTest() {
        Random r = new Random(3);
        for (int view = 0; view < 40; view++) {
            ChunkFrustumCuller culler = new ChunkFrustumCuller();
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(50 + r.nextInt(50)), 16f / 9f, 0.1f, 6000f);
            Matrix4f viewM = new Matrix4f().rotateX((float) Math.toRadians(-40 + r.nextInt(60)))
                .rotateY((float) Math.toRadians(r.nextInt(360))).translate(0, -(60 + r.nextInt(200)), 0);
            culler.update(proj, viewM);
            FastLodRenderPass.RegionCull cull = new FastLodRenderPass.RegionCull();
            cull.begin(culler, 0, 0);
            int outside = 0;
            for (int i = 0; i < 4000; i++) {
                int cx = r.nextInt(300) - 150, cz = r.nextInt(300) - 150;
                float minY = WorldConfiguration.MIN_Y + r.nextInt(600), maxY = minY + 1 + r.nextInt(300);
                float x0 = cx * 16f, z0 = cz * 16f;
                boolean visible = culler.isBoxVisible(x0, minY, z0, x0 + 16, Math.min(maxY, WorldConfiguration.MAX_Y), z0 + 16);
                int c = cull.classify(cx, cz);
                if (c == FastLodRenderPass.RegionCull.OUTSIDE) {
                    outside++;
                    assertTrue(!visible, "a visible node in an OUTSIDE region at chunk " + cx + "," + cz);
                } else if (c == FastLodRenderPass.RegionCull.INSIDE) {
                    assertTrue(visible, "an invisible node in an INSIDE region at chunk " + cx + "," + cz);
                }
            }
            assertTrue(outside > 0, "some region of a 300-chunk square is outside every frustum tried");
        }
    }

    @Test
    void theFootprintStampChangesOnEveryMutationAndDiffersBetweenSets() {
        FootprintSet a = new FootprintSet(), b = new FootprintSet();
        assertNotEquals(a.stamp(), b.stamp());
        long s0 = a.stamp();
        a.add(0, 1, 2);
        long s1 = a.stamp();
        assertNotEquals(s0, s1);
        a.add(0, 1, 2);   // already present: content unchanged (a new stamp would only cost a recheck)
        a.remove(0, 1, 2);
        assertNotEquals(s1, a.stamp());
        long s2 = a.stamp();
        a.clear();
        assertNotEquals(s2, a.stamp());
        assertEquals(a.stamp(), a.stamp());
    }
}
