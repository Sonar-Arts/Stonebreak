package com.stonebreak.fx;

import com.openmason.engine.format.fx.FxData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.fx.bindings.AmbientFx;
import com.stonebreak.fx.bindings.AmbientSources;
import com.stonebreak.fx.bindings.AssetFx;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static com.stonebreak.fx.FxFixtures.data;
import static com.stonebreak.fx.FxFixtures.def;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The game wrapper keeps the engine's zero-allocation contract (Stonebreak#436): once warm, a frame of FX — the
 * runtime step, ambient FX following a moving selection, debris from asset bindings with tint and parameter
 * overrides, colliding against blocks — allocates nothing. Best of several windows, like the engine's
 * {@code ParticleAllocationTest} (a one-off JIT deopt can show up in one window).
 */
class FxRuntimeAllocationTest {

    private static final int WINDOWS = 5;
    private static final int FRAMES = 240;

    @Test
    void aWarmFrameAllocatesNothing() {
        FxRuntime runtime = FxFixtures.runtime();
        runtime.setCollider((x, y, z) -> y < 64);
        AmbientFx ambient = new AmbientFx();
        MovingSources sources = new MovingSources();
        FxData debris = data(def("break", "stonebreak:block_debris", 0, 0, 0,
                java.util.Map.of("amount", com.openmason.engine.format.fx.FxParamValue.scalar(0.5f)), true));
        // Torches run a pulse-aware FX, so the per-frame pulse feed is inside the measured frame.
        FxData torch = data(def("ambient", "stonebreak:torch_flame"), def("ambient", "test:pulse-glow"));
        Function<BlockType, FxData> bindings = b -> b == BlockType.TORCH_PLACED ? torch : debris;
        ToIntFunction<BlockType> tints = b -> 0x7A6A5AFF;

        long m0 = allocated();
        long meterCost = allocated() - m0; // reading the meter allocates on some JVMs
        long best = Long.MAX_VALUE;
        for (int w = 0; w < WINDOWS + 2; w++) { // two warm-up windows
            long before = allocated();
            for (int f = 0; f < FRAMES; f++) {
                frame(runtime, ambient, sources, bindings, tints, debris, f);
            }
            long bytes = allocated() - before - meterCost;
            if (w >= 2) {
                best = Math.min(best, bytes);
            }
        }
        runtime.close();
        assertEquals(0L, best, "bytes allocated by the best window of " + FRAMES + " frames");
    }

    private static void frame(FxRuntime runtime, AmbientFx ambient, MovingSources sources,
                              Function<BlockType, FxData> bindings, ToIntFunction<BlockType> tints,
                              FxData debris, int f) {
        sources.t = f;
        ambient.sync(runtime, sources, bindings, tints, 0, 64, 0);
        if (f % 10 == 0) {
            AssetFx.fire(runtime, debris, "break", (f % 7) - 3.5, 65.5, (f % 5) - 2.5, 0x7A6A5AFF, AssetFx.NORMAL);
        }
        runtime.update(1f / 60f, 0, 66, 0, 0, 0, 0);
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /** Four torches, one carried in a circle; a fifth source flickers in and out of the selection. */
    private static final class MovingSources implements AmbientSources {
        int t;

        @Override public int count() { return (t / 30) % 2 == 0 ? 5 : 4; }
        @Override public BlockType block(int i) { return BlockType.TORCH_PLACED; }
        @Override public long key(int i) { return i == 0 ? AmbientSources.holderKey(1) : AmbientSources.blockKey(i * 3, 65, 0); }

        @Override
        public Vector3f position(int i, Vector3f out) {
            if (i == 0) {
                double a = t * 0.05;
                return out.set((float) Math.cos(a) * 3f, 65.5f, (float) Math.sin(a) * 3f);
            }
            return out.set(i * 3 + 0.5f, 65.6f, 0.5f);
        }

        @Override public float pulse(int i) { return ((t + i * 17) % 72) / 72f; }
    }
}
