package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How much generation work {@link FastLodManager#updateRing} is allowed to have
 * outstanding at once.
 *
 * <p>The ring wants thousands of nodes — 4056 at the default render distance 8 / LOD range
 * 24, and 21025 at the maximum the settings allow. Handing all of them to the worker pool in
 * one burst puts them in a FIFO that takes seconds to drain, and the pool has no way to know
 * that the node the player is walking toward, queued three seconds later, matters more than
 * the far-ring node in front of it. That is what "the LOD takes a while to sharpen" looks
 * like from the inside.
 *
 * <p>The fix is not a priority queue but a shallow one: {@code updateRing} runs every frame
 * and re-sorts its candidates nearest-first, so bounding the queue makes the pool re-pick
 * from the current player position constantly and near work start almost immediately. These
 * tests pin the bound and, more importantly, that bounding it does not lose any work.
 */
class FastLodSchedulingTest {

    /** inner=4, range=8 → ring is Chebyshev 3..12, far more nodes than the queue bound. */
    private static final int INNER = 4;
    private static final int RANGE = 8;
    private static final int WORKERS = 1;
    /** FastLodManager: max(MIN_IN_FLIGHT, IN_FLIGHT_PER_WORKER * workers). */
    private static final int EXPECTED_CAP = 64;

    private ManualExecutor executor;
    private FastLodManager manager;

    private static int ringNodeCount() {
        int outer = INNER + RANGE;
        int n = 0;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                if (FastLodBandPolicy.levelFor(Math.max(Math.abs(dx), Math.abs(dz)), INNER, RANGE) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    @BeforeEach
    void setUp() {
        WorldConfiguration config = new WorldConfiguration(INNER, WORKERS, RANGE, true);
        TerrainGenerationSystem terrain = mock(TerrainGenerationSystem.class);
        when(terrain.getFinalTerrainHeightAt(anyInt(), anyInt())).thenReturn(336);
        when(terrain.getSurfaceBlockAt(anyInt(), anyInt())).thenReturn(BlockType.GRASS);
        when(terrain.getTreeAt(anyInt(), anyInt())).thenReturn(null);
        org.mockito.Mockito.doAnswer(inv -> {
            int count = inv.getArgument(2);
            int[] outHeights = inv.getArgument(4);
            BlockType[] outSurface = inv.getArgument(6);
            for (int i = 0; i < count * count; i++) {
                outHeights[i] = 336;
                if (outSurface != null) outSurface[i] = BlockType.GRASS;
            }
            return null;
        }).when(terrain).sampleColumns(anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());

        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(7);

        executor = new ManualExecutor();
        manager = new FastLodManager(config, terrain, textures, null, executor,
                mesh -> mock(MmsRenderableHandle.class),
                TimeUnit.SECONDS.toNanos(10));
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void oneTickNeverQueuesMoreThanTheInFlightCap() {
        assertTrue(ringNodeCount() > EXPECTED_CAP * 4,
                "test is only meaningful if the ring dwarfs the cap; got " + ringNodeCount());

        manager.updateRing(0, 0);

        assertEquals(EXPECTED_CAP, executor.queued(),
                "the whole ring must not be dumped into the worker queue at once");
    }

    @Test
    void queueRefillsEachTickUntilTheRingIsComplete() {
        int ring = ringNodeCount();
        int ticks = 0;
        while (manager.visibleHandles().size() < ring && ticks < ring) {
            manager.updateRing(0, 0);
            assertTrue(executor.queued() <= EXPECTED_CAP,
                    "queue depth stayed bounded across refills");
            executor.runAll();
            drain();
            ticks++;
        }

        assertEquals(ring, manager.visibleHandles().size(),
                "bounding the queue must not lose nodes — the ring still completes");
        assertTrue(ticks <= Math.ceil(ring / (double) EXPECTED_CAP) + 1,
                "and it completes in the expected number of refills, got " + ticks);
    }

    /**
     * The point of the bound: work scheduled after the player moves does not sit behind a
     * queue built for where they used to be. With the ring capped, a tick from a new
     * position re-picks its candidates nearest-first from that position.
     */
    @Test
    void workIsRepickedFromTheCurrentPlayerPositionEachTick() {
        manager.updateRing(0, 0);
        executor.runAll();
        drain();

        // Walk far enough that the ring is largely new ground.
        manager.updateRing(40, 40);
        assertTrue(executor.queued() <= EXPECTED_CAP);
        executor.runAll();
        drain();

        int nearNewPlayer = 0;
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            int d = Math.max(Math.abs(e.key.chunkX() - 40), Math.abs(e.key.chunkZ() - 40));
            if (d <= INNER + 1) nearNewPlayer++;
        }
        assertTrue(nearNewPlayer > 0,
                "the first tick after moving must spend its budget on nodes near the player");
    }

    private void drain() {
        for (int i = 0; i <= EXPECTED_CAP + 8; i++) {
            manager.applyGLUpdates();
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        int queued() { return queue.size(); }

        @Override public void execute(Runnable command) { queue.add(command); }

        void runAll() {
            Runnable r;
            while ((r = queue.poll()) != null) r.run();
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
