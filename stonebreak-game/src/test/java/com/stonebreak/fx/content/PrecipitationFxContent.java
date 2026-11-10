package com.stonebreak.fx.content;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxNode;
import com.openmason.engine.rendering.particles.graph.FxOp;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationFx;

import java.util.List;

/**
 * Rain and snow as FX (the bootstrap source of {@code sbfx/SB_Precipitation.sbfx}, written by
 * {@link PrecipitationFxContentGeneratorTest}). The system is never played: {@link PrecipitationFx} spawns its
 * emitters' effects drop by drop under the raining clouds around the camera, so its emitters are event-only.
 *
 * <p>Both effects simulate on the GPU (thousands of drops in a storm) and collide with the ground; a drop that has
 * landed (stopped falling) dies on the next step ({@link #dieOnLanding}), so nothing lingers on the ground. Rain
 * splashes where it lands.
 */
public final class PrecipitationFxContent {

    public static final String FILE = "SB_Precipitation.sbfx";

    /** Droplets thrown up where a raindrop lands (near the camera only). */
    static ParticleEffect splash() {
        return ParticleEffect.builder("sb-rain-splash")
                .lifetime(0.12f, 0.24f).rate(0).point()
                .speed(0.9f, 1.8f).direction(0, 1, 0).spread(55)
                .gravity(0, -14f, 0).size(0.025f, 0.018f).stretched(0.035f)
                .colors(0xC8D8F0A0, 0xC8D8F060).fade(0f, 0.4f).additive(1f)
                .priority(ParticleEffect.Priority.AMBIENT).maxLive(8192).lodDistance(8f)
                .simulation(ParticleEffect.Simulation.GPU).build();
    }

    /** A raindrop: a thin streak along its fall, falling at terminal speed from where it is spawned. */
    static ParticleEffect rain() {
        return ParticleEffect.builder(PrecipitationFx.RAIN_EFFECT)
                .lifetime(PrecipitationFx.RAIN_LIFE, PrecipitationFx.RAIN_LIFE).rate(0).point()
                .speed(15f, 17f).direction(0, -1, 0).spread(1.5f)
                .size(0.028f, 0.028f).stretched(0.065f)
                .color(0xA8B8D452).colorJitter(0.15f).fade(0.06f, 0.12f).additive(1f)
                .collides(0f, 0f)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.COLLISION, splash(), 1))
                .priority(ParticleEffect.Priority.AMBIENT).maxLive(65536)
                .graph(dieOnLanding(0.5f))
                .simulation(ParticleEffect.Simulation.GPU).build();
    }

    /** A snowflake drifting down at a steady speed. */
    static ParticleEffect snow() {
        return ParticleEffect.builder(PrecipitationFx.SNOW_EFFECT)
                .lifetime(PrecipitationFx.SNOW_LIFE, PrecipitationFx.SNOW_LIFE).rate(0).point()
                .speed(1.3f, 1.7f).direction(0, -1, 0).spread(22f)
                .size(0.075f, 0.075f).color(0xF4F6FAE0).colorJitter(0.06f).fade(0.08f, 0.1f)
                .rotation(0, 360).spin(-90, 90)
                .collides(0f, 0f)
                .priority(ParticleEffect.Priority.AMBIENT).maxLive(32768)
                .graph(dieOnLanding(0.25f))
                .simulation(ParticleEffect.Simulation.GPU).build();
    }

    /**
     * PARTICLE_UPDATE with a module after {@code move}: a particle no longer falling faster than {@code minFall}
     * blocks/s (it landed: the collision zeroes a settled particle's velocity) ages out, so the built-in
     * {@code lifetime} retires it on the next step. Precipitation falls at a constant speed, so only a landing
     * slows it.
     */
    static FxGraph dieOnLanding(float minFall) {
        FxModule kill = FxModule.custom("die on landing",
                FxNode.read("v", "velocity"),
                FxNode.op("vy", FxOp.Y, "v"),
                FxNode.constant("minFall", minFall),
                FxNode.op("slow", FxOp.ADD, "vy", "minFall"),     // > 0 once it falls slower than minFall
                FxNode.constant("sharp", 1000f),
                FxNode.op("scaled", FxOp.MUL, "slow", "sharp"),
                FxNode.constant("zero", 0f),
                FxNode.constant("one", 1f),
                FxNode.op("landed", FxOp.CLAMP, "scaled", "zero", "one"),
                FxNode.constant("forever", 1.0e4f),
                FxNode.op("extra", FxOp.MUL, "landed", "forever"),
                FxNode.read("age", "age"),
                FxNode.op("aged", FxOp.ADD, "age", "extra"),
                FxNode.write("out", "age", "aged"));
        return FxGraph.of(List.of(), FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, kill, "spin"));
    }

    static FxSystem system() {
        return FxSystem.builder(PrecipitationFx.SYSTEM)
                .emitter(FxEmitter.builder(PrecipitationFx.RAIN_EMITTER, rain()).eventOnly().build())
                .emitter(FxEmitter.builder(PrecipitationFx.SNOW_EMITTER, snow()).eventOnly().build())
                .build();
    }

    public static SbfxDocument document() {
        return SbfxDocument.of(OmfxDocument.of(system()).withAuthor(FirstFxContent.AUTHOR));
    }

    private PrecipitationFxContent() {
    }
}
