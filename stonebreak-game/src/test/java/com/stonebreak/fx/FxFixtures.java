package com.stonebreak.fx;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxHost;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.stonebreak.fx.content.FirstFxContent;
import com.stonebreak.fx.sbfx.SbfxDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared FX test fixtures: the first content plus a "probe" FX whose one particle sits where it was played. */
public final class FxFixtures {

    /** One motionless particle per play, living 10 s: where it sits is where the FX was played. */
    public static final FxSystem PROBE = FxSystem.builder("probe")
            .emitter(FxEmitter.builder("dot", ParticleEffect.builder("probe-dot").point().rate(0)
                    .lifetime(10f, 10f).speed(0f, 0f).spread(0).gravity(0, 0, 0).drag(0f).build())
                    .burst(1).duration(0).build())
            .parameter(FxParameter.color(FxRuntime.PARAM_TINT, 0xFFFFFFFF))
            .parameter(FxParameter.scalar(FxRuntime.PARAM_INTENSITY, 1f))
            .parameter(FxParameter.scalar("amount", 1f))
            .parameter(FxParameter.scalar(FxRuntime.PARAM_DENSITY, 1f))
            .bind(FxBinding.of(FxRuntime.PARAM_TINT, FxBinding.Target.TINT))
            .build();

    /** An endless FX for ambient bindings. */
    public static final FxSystem GLOW = FxSystem.builder("glow")
            .emitter(FxEmitter.builder("specks", ParticleEffect.builder("glow-specks").point().rate(20)
                    .lifetime(0.5f, 0.5f).speed(0f, 0f).spread(0).gravity(0, 0, 0).build()).build())
            .parameter(FxParameter.scalar(FxRuntime.PARAM_DENSITY, 1f))
            .bind(FxBinding.of(FxRuntime.PARAM_DENSITY, FxBinding.Target.RATE))
            .build();

    private FxFixtures() {
    }

    /** The first content plus {@code test:probe} and {@code test:glow}. */
    public static FxLibrary library() {
        List<SbfxDocument> docs = new ArrayList<>(FirstFxContent.all().values());
        docs.add(new SbfxDocument("test:probe", OmfxDocument.of(PROBE)));
        docs.add(new SbfxDocument("test:glow", OmfxDocument.of(GLOW)));
        return new FxLibrary(docs);
    }

    public static FxRuntime runtime() {
        return new FxRuntime(library(), 8192, FxHost.NONE);
    }

    public static FxDef def(String event, String fx) {
        return FxDef.of(event, fx);
    }

    public static FxDef def(String event, String fx, float ox, float oy, float oz,
                            Map<String, FxParamValue> params, boolean tint) {
        return new FxDef(event, fx, ox, oy, oz, params, tint);
    }

    public static FxData data(FxDef... defs) {
        return new FxData(List.of(defs));
    }

    /** Steps {@code runtime} a few small frames. */
    public static void step(FxRuntime runtime, int frames) {
        for (int i = 0; i < frames; i++) {
            runtime.update(1f / 60f, 0, 64, 0, 0, 0, 0);
        }
    }
}
