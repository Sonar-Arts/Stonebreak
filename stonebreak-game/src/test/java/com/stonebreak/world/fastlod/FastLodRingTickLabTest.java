package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manual: per-frame CPU of {@link FastLodManager#updateRing} and heap per
 * resident node as the ring grows — what bounds the LOD range once node
 * generation is cheap. Gated on {@code -Dstonebreak.bench=true}. Terrain is
 * a flat stub so only the manager's bookkeeping is measured.
 */
class FastLodRingTickLabTest {

    @Test
    void lab() {
        assumeTrue(Boolean.getBoolean("stonebreak.bench"), "manual benchmark (-Dstonebreak.bench=true)");
        for (int range : new int[]{24, 48, 96, 128}) {
            measure(8, range);
        }
    }

    private static void measure(int inner, int range) {
        TerrainGenerationSystem terrain = mock(TerrainGenerationSystem.class, org.mockito.Mockito.withSettings().stubOnly());
        org.mockito.stubbing.Answer<Void> flat = inv -> {
            int count = inv.getArgument(2);
            int[] h = inv.getArgument(4);
            BlockType[] s = inv.getArgument(6);
            for (int i = 0; i < count * count; i++) {
                h[i] = 80;
                if (s != null) s[i] = BlockType.GRASS;
            }
            return null;
        };
        org.mockito.Mockito.doAnswer(flat)
            .when(terrain).sampleColumns(anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(flat)
            .when(terrain).sampleRawColumns(anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any());
        BlockTextureArray textures = mock(BlockTextureArray.class, org.mockito.Mockito.withSettings().stubOnly());
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(1);
        MmsRenderableHandle shared = mock(MmsRenderableHandle.class, org.mockito.Mockito.withSettings().stubOnly());
        ManualExecutor exec = new ManualExecutor();
        WorldConfiguration config = new WorldConfiguration(inner, 4, range, true);
        FastLodManager m = new FastLodManager(config, terrain, textures, null, exec, mesh -> shared);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        // Fill the whole ring.
        for (int guard = 0; guard < 100_000; guard++) {
            m.updateRing(0, 0);
            boolean ran = exec.runAll();
            for (int i = 0; i < 64; i++) m.applyGLUpdates();
            if (!ran && m.visibleHandles().size() > 0) break;
        }
        int resident = m.visibleHandles().size();
        System.gc();
        long after = rt.totalMemory() - rt.freeMemory();

        // Steady state: the player stands still.
        for (int i = 0; i < 50; i++) m.updateRing(0, 0);
        long t0 = System.nanoTime();
        int n = 200;
        for (int i = 0; i < n; i++) m.updateRing(0, 0);
        double still = (System.nanoTime() - t0) / 1e6 / n;

        // Walking: one chunk per tick (generation not run, only bookkeeping/scheduling).
        long t1 = System.nanoTime();
        int walk = 64;
        for (int i = 1; i <= walk; i++) m.updateRing(i, 0);
        double moving = (System.nanoTime() - t1) / 1e6 / walk;

        System.out.println(String.format(Locale.ROOT,
            "[lod-tick] R%d/L%d: resident=%d  updateRing still=%.3f ms  walking=%.3f ms  heap/node~%d B (mesh bytes excluded, shared mock handle)",
            inner, range, resident, still, moving, resident == 0 ? 0 : (after - before) / resident));
        m.shutdown();
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) { queue.add(command); }

        boolean runAll() {
            boolean any = false;
            Runnable r;
            while ((r = queue.poll()) != null) { r.run(); any = true; }
            return any;
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> rest = new ArrayList<>(queue);
            queue.clear();
            return rest;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
