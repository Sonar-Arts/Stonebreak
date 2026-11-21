package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxHost;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxLibrary;
import com.stonebreak.fx.FxParams;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.fx.FxFixtures;
import com.stonebreak.fx.TrailFx;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.mobs.entities.FireBolt;
import com.stonebreak.player.combat.illusionist.MirroredDeceitAbility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FX that replaced {@code rendering/effects} (Stonebreak#441), as shipped: what the game plays exists and behaves
 * like the old emitters did — a trail streams while it follows and dies out once ended (or on its own), the bursts
 * are one-shot and fade, a ripple is one flat ring living as long as the old one. CPU backend, no GL.
 */
class GameplayFxContentTest {

    private static final Path SHIPPED = Path.of("src/main/resources/sbfx");
    private static final String RIPPLE_FX = "stonebreak:water_ripple";

    private FxRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        List<SbfxDocument> docs = new ArrayList<>();
        for (String file : GameplayFxContent.all().keySet()) {
            docs.add(SbfxCodec.read(SHIPPED.resolve(file)));
        }
        runtime = new FxRuntime(new FxLibrary(docs), 8192, FxHost.NONE);
        Fx.overrideEnabled(true);
        Fx.install(runtime);
    }

    @AfterEach
    void tearDown() {
        Fx.uninstall(runtime);
        Fx.overrideEnabled(null);
        runtime.close();
    }

    @Test
    void shipsEveryEffectTheGamePlays() {
        for (String id : List.of(FireBolt.TRAIL_FX, FireBolt.IMPACT_FX, MirroredDeceitAbility.SMOKE_FX, RIPPLE_FX)) {
            assertNotNull(runtime.library().find(id), id + " is shipped");
        }
    }

    @Test
    void shippedFilesAreTheGeneratedContent() throws Exception {
        for (Map.Entry<String, SbfxDocument> e : GameplayFxContent.all().entrySet()) {
            assertEquals(e.getValue(), SbfxCodec.read(SHIPPED.resolve(e.getKey())),
                    e.getKey() + " was regenerated (GameplayFxContentGeneratorTest) after its source changed");
        }
    }

    @Test
    void theTrailAimsItsFlamesThroughADirectionParameter() {
        FxSystem trail = runtime.library().find(FireBolt.TRAIL_FX);
        FxParameter direction = FxParams.find(trail, TrailFx.PARAM_DIRECTION);
        assertNotNull(direction);
        assertEquals(FxParameter.Type.VECTOR, direction.type());
        assertTrue(trail.bindings().stream().anyMatch(b -> b.parameter().equals(TrailFx.PARAM_DIRECTION)
                && b.target() == FxBinding.Target.DIRECTION));
        assertFalse(trail.endless(), "a trail whose bolt vanished unnoticed still ends");
    }

    @Test
    void aTrailStreamsWhileItFollowsAndDiesOutOnceEnded() {
        TrailFx trail = new TrailFx(FireBolt.TRAIL_FX);
        float x = 0f;
        for (int f = 0; f < 30; f++) { // half a second at 25 blocks/s, like a bolt
            x += 25f / 60f;
            trail.follow(x, 64, 0, -1, 0, 0);
            FxFixtures.step(runtime, 1);
        }
        assertTrue(trail.isActive());
        int streaming = runtime.liveParticles();
        assertTrue(streaming > 40, "about the old 150/s trail is alive: " + streaming);

        trail.end();
        assertFalse(trail.isActive());
        FxFixtures.step(runtime, 60);
        assertEquals(0, runtime.liveParticles(), "no flame outlives the old 0.8 s");
        assertEquals(0, runtime.instanceCount(), "the handle went back to the pool");
    }

    @Test
    void aForgottenTrailStopsByItself() {
        TrailFx trail = new TrailFx(FireBolt.TRAIL_FX);
        trail.follow(0, 64, 0, 0, 0, 1);
        for (int f = 0; f < (int) ((GameplayFxContent.TRAIL_MAX_SECONDS + 1f) * 60f); f++) {
            FxFixtures.step(runtime, 1);
        }
        assertEquals(0, runtime.liveParticles(), "its emitters ran out");
    }

    @Test
    void aRuntimeSwapStartsTheTrailAfresh() {
        TrailFx trail = new TrailFx(FireBolt.TRAIL_FX);
        trail.follow(0, 64, 0, 0, 0, 1);
        FxRuntime next = new FxRuntime(runtime.library(), 8192, FxHost.NONE);
        try {
            Fx.install(next);
            trail.follow(1, 64, 0, 0, 0, 1);
            assertEquals(1, next.instanceCount(), "playing in the new runtime");
            trail.end();
            for (int f = 0; f < 60; f++) {
                next.update(1f / 60f, 0, 64, 0, 0, 0, 0);
            }
            assertEquals(0, next.instanceCount(), "ended in the runtime it plays in");
        } finally {
            Fx.install(runtime);
            next.close();
        }
    }

    @Test
    void theImpactBurstAndTheSmokeAreOneShotsThatFade() {
        for (String id : List.of(FireBolt.IMPACT_FX, MirroredDeceitAbility.SMOKE_FX)) {
            assertFalse(runtime.library().find(id).endless(), id);
            Fx.fire(id, 0, 64, 0);
            FxFixtures.step(runtime, 12); // the burst smoke is delayed 0.1 s
            assertTrue(runtime.liveParticles() >= 18, id + " bursts like the old emitter");
            FxFixtures.step(runtime, 150);
            assertEquals(0, runtime.liveParticles(), id + " fades within 2.5 s");
        }
    }

    @Test
    void aRippleIsOneFlatRingThatLivesAsLongAsTheOldOne() {
        FxSystem ripple = runtime.library().find(RIPPLE_FX);
        ParticleEffect ring = ripple.emitters().getFirst().effect();
        assertEquals(ParticleEffect.Orientation.FLAT, ring.orientation());
        assertEquals(ParticleEffect.Sprite.TEXTURE, ring.sprite());
        assertEquals(GameplayFxContent.RIPPLE_LIFE, ring.lifetime().max(), 1e-4f);

        Fx.fire(RIPPLE_FX, 0, 64, 0);
        FxFixtures.step(runtime, 1);
        assertEquals(1, runtime.liveParticles());
        FxFixtures.step(runtime, (int) (GameplayFxContent.RIPPLE_LIFE * 60f) + 2);
        assertEquals(0, runtime.liveParticles());
    }
}
