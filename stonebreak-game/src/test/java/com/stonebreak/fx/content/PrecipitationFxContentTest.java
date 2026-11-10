package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationFx;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped precipitation FX ({@code SB_Precipitation.sbfx}): what {@code PrecipitationFx} looks up exists, and a
 * drop that lands dies there (rain splashing) instead of lying on the ground until its lifetime ends. Runs on the
 * CPU backend (no GL here); the GPU backend compiles the same graph.
 */
class PrecipitationFxContentTest {

    private static final float DT = 1f / 60f;
    private static final int GROUND = 64;

    private static FxSystem shipped() throws Exception {
        SbfxDocument doc = SbfxCodec.read(Path.of("src/main/resources/sbfx", PrecipitationFxContent.FILE));
        assertEquals(SbfxDocument.objectIdFor(PrecipitationFx.SYSTEM), doc.objectId());
        return doc.system();
    }

    @Test
    void shipsTheEffectsTheDriverLooksUp() throws Exception {
        FxSystem system = shipped();
        ParticleEffect rain = system.emitter(PrecipitationFx.RAIN_EMITTER).effect();
        ParticleEffect snow = system.emitter(PrecipitationFx.SNOW_EMITTER).effect();
        assertEquals(PrecipitationFx.RAIN_EFFECT, rain.name());
        assertEquals(PrecipitationFx.SNOW_EFFECT, snow.name());
        assertEquals(ParticleEffect.Simulation.GPU, rain.simulation());
        assertTrue(rain.collides() && snow.collides(), "drops land");
        assertNotNull(rain.graph(), "the die-on-landing module");
    }

    @Test
    void rainDiesWhereItLandsAndSplashes() throws Exception {
        ParticleEffect rain = shipped().emitter(PrecipitationFx.RAIN_EMITTER).effect();
        ParticleEffect splash = rain.subEmitters().getFirst().effect();
        ParticleSystem system = groundedSystem();
        assertEquals(1, system.burst(rain, 0.5, GROUND + 6, 0.5, 0f, -1f, 0f, 1, 0xFFFFFFFF));

        // 6 blocks at ~16 blocks/s: lands after ~0.4 s, well inside its 1.25 s life.
        int landedAt = -1;
        boolean splashed = false;
        for (int f = 0; f < 60 && landedAt < 0; f++) {
            system.update(DT);
            splashed |= system.liveCount(splash) > 0;
            if (system.liveCount(rain) == 0) landedAt = f;
        }
        assertTrue(landedAt > 0, "the drop died");
        assertTrue(landedAt * DT < 0.6f, "it died on landing (" + landedAt * DT + " s), not of old age");
        assertTrue(splashed, "it splashed");
    }

    @Test
    void midAirDropsLiveTheirFullLife() throws Exception {
        ParticleEffect rain = shipped().emitter(PrecipitationFx.RAIN_EMITTER).effect();
        ParticleSystem system = groundedSystem();
        system.burst(rain, 0.5, GROUND + 200, 0.5, 0f, -1f, 0f, 1, 0xFFFFFFFF);
        int frames = (int) (0.9f * PrecipitationFx.RAIN_LIFE / DT);
        for (int f = 0; f < frames; f++) {
            system.update(DT);
        }
        assertEquals(1, system.liveCount(rain), "falling at full speed is not landing");
    }

    @Test
    void snowDiesWhereItLands() throws Exception {
        ParticleEffect snow = shipped().emitter(PrecipitationFx.SNOW_EMITTER).effect();
        ParticleSystem system = groundedSystem();
        system.burst(snow, 0.5, GROUND + 1.5, 0.5, 0f, -1f, 0f, 1, 0xFFFFFFFF);
        int diedAt = -1;
        for (int f = 0; f < 300 && diedAt < 0; f++) {
            system.update(DT);
            if (system.liveCount(snow) == 0) diedAt = f;
        }
        assertTrue(diedAt > 0 && diedAt * DT < 2.5f, "the flake melted on landing (" + diedAt * DT + " s)");
    }

    /** A CPU particle system over solid ground below y = {@value #GROUND}. */
    private static ParticleSystem groundedSystem() {
        ParticleSystem system = new ParticleSystem(1024, 7L);
        system.setCollider((x, y, z) -> y < GROUND);
        return system;
    }
}
