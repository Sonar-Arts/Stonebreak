package com.stonebreak.fx;

import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.ParticleCollider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game's FX runtime (Stonebreak#436): playing by id, conventions, anchoring, collision, clearing. */
class FxRuntimeTest {

    private FxRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = FxFixtures.runtime();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void playsByObjectIdOrBareIdAndUnknownIdsPlayNothing() {
        FxInstance a = runtime.play("stonebreak:block_debris", 0, 70, 0);
        FxInstance b = runtime.play("block_debris", 0, 70, 0);
        assertNotNull(a);
        assertNotNull(b);
        assertNull(runtime.play("stonebreak:nope", 0, 70, 0));
        assertFalse(runtime.fire("nope", 0, 0, 0));
        a.release();
        b.release();
        FxFixtures.step(runtime, 2);
        assertTrue(runtime.liveParticles() > 0, "debris bursts");
    }

    @Test
    void densityStartsEveryInstanceAndThinsTheBurst() {
        runtime.setDensity(1f);
        runtime.fire("stonebreak:block_debris", 0, 70, 0);
        FxFixtures.step(runtime, 1);
        int full = runtime.liveParticles();
        runtime.clear();
        FxFixtures.step(runtime, 1);
        assertEquals(0, runtime.liveParticles());

        runtime.setDensity(0.25f);
        FxInstance inst = runtime.play("stonebreak:block_debris", 0, 70, 0);
        assertEquals(0.25f, inst.param(FxRuntime.PARAM_DENSITY));
        inst.release();
        FxFixtures.step(runtime, 1);
        int thin = runtime.liveParticles();
        assertTrue(thin > 0 && thin < full * 0.5, "density 0.25 thins " + full + " -> " + thin);

        runtime.setDensity(7f);
        assertEquals(1f, runtime.density(), "clamped");
    }

    @Test
    void particlesKeepWorldPositionsWhenTheAnchorMoves() {
        runtime.fire("test:probe", 1000.5, 70.25, -2000.75);
        runtime.update(1f / 60f, 1000, 70, -2000, 1024, 0, -1984);
        assertEquals(1, runtime.liveParticles());
        assertEquals(1000.5, runtime.particles().worldX(0), 1e-3);
        runtime.update(1f / 60f, 1000, 70, -2000, 960, 0, -2048);
        assertEquals(1000.5, runtime.particles().worldX(0), 1e-3);
        assertEquals(70.25, runtime.particles().worldY(0), 1e-3);
        assertEquals(-2000.75, runtime.particles().worldZ(0), 1e-3);
    }

    @Test
    void debrisRestsOnPartialBlocksTheColliderReports() {
        // A half-height slab everywhere under y=64: debris lands at 64.5, not on the cell floor.
        ParticleCollider slab = new ParticleCollider() {
            @Override public boolean isSolid(int x, int y, int z) { return y <= 64; }
            @Override public float solidHeight(int x, int y, int z) { return y < 64 ? 1f : y == 64 ? 0.5f : 0f; }
        };
        runtime.setCollider(slab);
        runtime.fire("stonebreak:block_debris", 0.5, 66, 0.5);
        // 0.9 s: the chunks have fallen and landed, and most still live (they last 0.7-1.5 s).
        for (int i = 0; i < 54; i++) {
            runtime.update(1f / 60f, 0, 66, 0, 0, 0, 0);
        }
        int n = runtime.liveParticles();
        assertTrue(n > 0, "debris still alive");
        for (int i = 0; i < n; i++) {
            assertTrue(runtime.particles().worldY(i) >= 64.45, "rests on the slab: " + runtime.particles().worldY(i));
        }
    }

    @Test
    void clearEndsEverythingButHandlesStayUsable() {
        FxInstance ambient = runtime.play("stonebreak:torch_flame", 0, 70, 0);
        FxFixtures.step(runtime, 30);
        assertTrue(runtime.liveParticles() > 0);
        runtime.clear();
        FxFixtures.step(runtime, 1);
        assertEquals(0, runtime.liveParticles());
        assertFalse(ambient.isAlive());
        ambient.release();
    }
}
