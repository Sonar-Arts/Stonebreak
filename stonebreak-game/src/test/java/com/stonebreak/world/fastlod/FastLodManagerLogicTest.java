package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.diffusion.DiffusionTerrainGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Headless manager bookkeeping tests via the injectable executor/uploader
 * seam: residency, supersede atomicity on band transitions, upload-time
 * re-validation against the current ring, cooperative job cancellation, and
 * full eviction. No GL — uploads return Mockito handles.
 *
 * <p>Config: renderDistance=1, lodRange=5, quality LOW → ring covers Chebyshev 1..6
 * (168 columns: d≤5 → L0 incl. preload/handover, d=6 → L1 at 96 blocks). {@code applyGLUpdates} may
 * stop early on its 3 ms wall-clock budget under load, so tests drain it in a
 * loop ({@link #drainGLUpdates()}) — each call is guaranteed ≥1 item of
 * progress, never a single call.
 */
class FastLodManagerLogicTest {

    private static final int INNER = 1;
    private static final int RANGE = 5;
    private static final int RING_NODES = 168;  // 13x13 minus the player column
    /** The ring's one L0→L1 band edge (LOW: L1 from 96 blocks = 6 chunks). */
    private static final int EDGE = 6;

    private WorldConfiguration config;
    private DiffusionTerrainGenerator terrain;
    private ManualExecutor executor;
    private FastLodManager manager;

    private final AtomicBoolean terrainFails = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
    private final List<MmsRenderableHandle> createdHandles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 32 build threads → in-flight cap 256 ≥ RING_NODES, so one tick fills the ring.
        config = new WorldConfiguration(INNER, 32, RANGE, true);
        config.setLodQuality(FastLodQuality.LOW);
        terrain = mock(DiffusionTerrainGenerator.class);
        when(terrain.getFinalTerrainHeightAt(anyInt(), anyInt())).thenAnswer(inv -> {
            if (terrainFails.get()) throw new RuntimeException("simulated terrain failure");
            // Above SEA_LEVEL (320), so nodes are dry land: one terrain handle
            // each, no extra water-sheet mesh to skew the handle counts.
            return 336;
        });
        when(terrain.getSurfaceBlockAt(anyInt(), anyInt())).thenReturn(BlockType.GRASS);
        when(terrain.getTreeAt(anyInt(), anyInt())).thenReturn(null);
        // The sampler feeds off the batched probe; mirror it onto the per-point
        // stubs above so height-call counting (cooperative-cancellation test)
        // and the simulated terrain failure keep working.
        org.mockito.Mockito.doAnswer(inv -> {
            int x0 = inv.getArgument(0);
            int z0 = inv.getArgument(1);
            int count = inv.getArgument(2);
            int stride = inv.getArgument(3);
            int[] outHeights = inv.getArgument(4);
            BlockType[] outSurface = inv.getArgument(6);
            for (int ix = 0; ix < count; ix++) {
                for (int iz = 0; iz < count; iz++) {
                    int idx = ix * count + iz;
                    int wx = x0 + ix * stride, wz = z0 + iz * stride;
                    outHeights[idx] = terrain.getFinalTerrainHeightAt(wx, wz);
                    if (outSurface != null) {
                        outSurface[idx] = terrain.getSurfaceBlockAt(wx, wz);
                    }
                }
            }
            return null;
        }).when(terrain).sampleColumns(anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
        // Uncarved coarse levels (below ULTRA) use the raw probe: same flat terrain.
        org.mockito.Mockito.doAnswer(inv -> {
            int count = inv.getArgument(2);
            int[] outHeights = inv.getArgument(4);
            BlockType[] outSurface = inv.getArgument(6);
            for (int i = 0; i < count * count; i++) {
                outHeights[i] = 80;
                if (outSurface != null) outSurface[i] = BlockType.GRASS;
            }
            return null;
        }).when(terrain).sampleRawColumns(anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any());

        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(7);

        executor = new ManualExecutor();
        FastLodManager.Uploader uploader = mesh -> {
            MmsRenderableHandle handle = mock(MmsRenderableHandle.class);
            createdHandles.add(handle);
            return handle;
        };
        // Generous upload budget: this test measures bookkeeping (which handles exist,
        // in what order), not real GPU upload time — the mocked uploader returns
        // instantly, so the production 3ms budget only measures cold-JIT noise here,
        // not anything the test cares about.
        manager = new FastLodManager(config, terrain, textures, null, executor, uploader,
                java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
        manager.clock = now::get;
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private void tick(int playerCx, int playerCz) {
        manager.updateRing(playerCx, playerCz);
        executor.runAll();
        drainGLUpdates();
    }

    /**
     * applyGLUpdates caps work at 48 uploads OR a 3 ms wall-clock budget per
     * call; under a loaded machine the budget can bind first. Each call still
     * makes ≥1 item of progress (the deadline check sits at the loop bottom),
     * so a bounded loop drains deterministically.
     */
    private void drainGLUpdates() {
        for (int i = 0; i <= RING_NODES + 8; i++) {
            manager.applyGLUpdates();
        }
    }

    private MmsRenderableHandle handleFor(FastLodKey key) {
        FastLodManager.Entry e = entryFor(key);
        return e != null ? e.handle : null;
    }

    private FastLodManager.Entry entryFor(FastLodKey key) {
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            if (e.key.equals(key)) return e;
        }
        return null;
    }

    /**
     * The carve mode is part of a node's identity: switching the preset across
     * ULTRA must replace the coarse nodes (L3 here) with ones sampled the other
     * way, through the normal supersede path, and leave the carved-everywhere
     * levels alone.
     */
    @Test
    void qualitySwitchResamplesCoarseNodesInTheOtherCarveMode() {
        // inner=1, range=24: LOW puts L3 at 384 blocks = d 24..25. Huge in-flight cap so
        // every tick schedules up to MAX_SCHEDULES_PER_TICK.
        WorldConfiguration cfg = new WorldConfiguration(1, 512, 24, true);
        cfg.setLodQuality(FastLodQuality.LOW);
        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(7);
        ManualExecutor exec = new ManualExecutor();
        MmsRenderableHandle shared = mock(MmsRenderableHandle.class);
        FastLodManager m = new FastLodManager(cfg, terrain, textures, null, exec, mesh -> shared);
        try {
            settle(m, exec);
            long rawL3 = m.visibleHandles().stream()
                .filter(e -> e.key.level() == FastLodLevel.L3 && !e.key.carved()).count();
            assertTrue(rawL3 > 0, "LOW samples L3 uncarved");
            int l1 = (int) m.visibleHandles().stream().filter(e -> e.key.level() == FastLodLevel.L1).count();

            assertTrue(m.visibleHandles().stream().noneMatch(e -> e.key.coarseTrees()),
                "LOW draws trees at L0 only");

            cfg.setLodQuality(FastLodQuality.ULTRA);
            settle(m, exec);
            // ULTRA moves the bands outward too, so compare carve modes, not counts.
            assertTrue(m.visibleHandles().stream().allMatch(e -> e.key.carved()),
                "ULTRA replaced every uncarved node");
            assertTrue(m.visibleHandles().stream().allMatch(e -> e.key.trees()),
                "ULTRA draws trees on every level of this ring (L0..L2)");

            cfg.setLodQuality(FastLodQuality.LOW);
            settle(m, exec);
            assertEquals(rawL3, m.visibleHandles().stream()
                .filter(e -> e.key.level() == FastLodLevel.L3 && !e.key.carved()).count());
            assertEquals(l1, m.visibleHandles().stream().filter(e -> e.key.level() == FastLodLevel.L1).count());
            assertTrue(m.visibleHandles().stream().allMatch(e -> e.key.carved() || e.key.level().cellSize() > 4));
        } finally {
            m.shutdown();
        }
    }

    private static void settle(FastLodManager m, ManualExecutor exec) {
        for (int i = 0; i < 200; i++) {
            m.updateRing(0, 0);
            exec.runAll();
            for (int k = 0; k < 200; k++) m.applyGLUpdates();
        }
    }

    @Test
    void ringBecomesResidentAndSecondTickIsIdempotent() {
        tick(0, 0);
        assertEquals(RING_NODES, manager.visibleHandles().size());
        assertEquals(RING_NODES, createdHandles.size());

        // Every entry carries the mesh bounds for frustum culling: tops at the
        // flat terrain height, minY at 0 from the node-border foundation walls.
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            assertEquals(0f, e.minY, 1e-4f);
            assertEquals(336f, e.maxY, 1e-4f);
        }

        tick(0, 0);
        assertEquals(RING_NODES, manager.visibleHandles().size());
        assertEquals(RING_NODES, createdHandles.size(), "stable ring schedules nothing new");
    }

    @Test
    void bandTransitionRetiresOldHandleOnlyAfterReplacementUploads() {
        tick(0, 0);
        // Column (EDGE,0): d=6 from origin → L1; d=5 from (1,0) → L0.
        FastLodKey oldKey = FastLodKey.of(FastLodLevel.L1, EDGE, 0);
        MmsRenderableHandle oldHandle = handleFor(oldKey);
        assertNotNull(oldHandle);

        manager.updateRing(1, 0);
        // Jobs queued but not run — the old node must keep rendering (no gap).
        assertNotNull(handleFor(oldKey), "old level survives until replacement uploads");

        executor.runAll();
        assertNotNull(handleFor(oldKey), "generation done but not uploaded — still no retire");

        drainGLUpdates();
        assertNull(handleFor(oldKey), "retired atomically with the replacement upload");
        assertNotNull(handleFor(FastLodKey.of(FastLodLevel.L0, EDGE, 0)));
        verify(oldHandle).close();
    }

    @Test
    void bandTransitionReplacementInheritsCrossfadeState() {
        tick(0, 0);
        FastLodManager.Entry old = entryFor(FastLodKey.of(FastLodLevel.L1, EDGE, 0));
        assertNotNull(old);
        // Simulate the render pass having partially faded this node.
        old.fade = 0.37f;
        old.nativeCovered = true;

        tick(1, 0);   // column (EDGE,0) transitions L1 → L0 via the supersede path
        FastLodManager.Entry replacement = entryFor(FastLodKey.of(FastLodLevel.L0, EDGE, 0));
        assertNotNull(replacement);
        assertEquals(0.37f, replacement.fade, 1e-6f,
                "level swap must not restart the crossfade");
        assertTrue(replacement.nativeCovered);
    }

    /**
     * Walking inward used to punch a hole. The band policy stops wanting a node once the
     * player is past it by more than PRELOAD_RING, and the eviction pass deleted it on
     * that alone — including when the node was the only thing drawing its column because
     * the detail chunk had not meshed yet. The hole lasted until the mesh landed, which is
     * exactly the case (moving faster than chunks load) where it lasts longest.
     */
    @Test
    void nodeCoveringAHoleSurvivesEvictionUntilTheDetailChunkDraws() {
        tick(0, 0);
        // Column (1,0): d=1 from origin → in the ring. d=0 from (1,0) → wanted == null.
        FastLodKey key = FastLodKey.of(FastLodLevel.L0, 1, 0);
        FastLodManager.Entry e = entryFor(key);
        assertNotNull(e);

        // Render-pass state for "this node is the only thing drawing this column".
        e.fade = 1f;
        e.nativeCovered = false;

        tick(1, 0);
        assertNotNull(entryFor(key), "must not delete the node that is covering the gap");

        // The detail chunk lands; the render pass latches nativeCovered on the first
        // frame it actually draws. Now the node is redundant and goes.
        entryFor(key).nativeCovered = true;
        tick(1, 0);
        assertNull(entryFor(key), "retired once the detail chunk covers the column");
    }

    /** A node that has never drawn anything (fresh upload, fade 0) is still evicted at once. */
    @Test
    void neverRenderedNodeIsEvictedImmediately() {
        tick(0, 0);
        FastLodKey key = FastLodKey.of(FastLodLevel.L0, 1, 0);
        assertNotNull(entryFor(key));
        assertEquals(0f, entryFor(key).fade, 1e-6f);

        tick(1, 0);
        assertNull(entryFor(key), "nothing to protect — normal eviction");
    }

    @Test
    void freshUploadsStartFullyFadedOut() {
        tick(0, 0);
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            assertEquals(0f, e.fade, 1e-6f, "new nodes dissolve in from zero");
        }
    }

    @Test
    void finishedMeshesForAbandonedRingAreDroppedWithoutUpload() {
        // Generate everything for the origin ring, then teleport before any
        // upload happens: applyGLUpdates must re-validate each finished mesh
        // against the CURRENT ring and drop all of them — uploading would just
        // churn GPU handles that the next tick evicts.
        manager.updateRing(0, 0);
        executor.runAll();

        manager.updateRing(100, 100);
        // Run only the upload drain — the new ring's jobs stay queued so any
        // upload we see must come from the stale origin meshes.
        drainGLUpdates();
        assertEquals(0, createdHandles.size(), "no stale mesh may reach the uploader");
        assertTrue(manager.visibleHandles().isEmpty());
    }

    @Test
    void cancelledJobsSkipTerrainSamplingEntirely() {
        manager.updateRing(0, 0);      // queues 168 jobs, none run yet
        manager.updateRing(100, 100);  // cancels all of them, queues 168 new ones
        executor.runAll();
        drainGLUpdates();

        assertEquals(RING_NODES, manager.visibleHandles().size());
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            int d = Math.max(Math.abs(e.key.chunkX() - 100), Math.abs(e.key.chunkZ() - 100));
            assertTrue(d >= 1 && d <= INNER + RANGE, "resident node outside the current ring");
        }

        // Cooperative cancellation: the 168 stale jobs must exit before touching
        // the terrain system, so total height samples equal exactly one ring.
        int expectedHeightCalls = 0;
        for (int dx = -(INNER + RANGE); dx <= INNER + RANGE; dx++) {
            for (int dz = -(INNER + RANGE); dz <= INNER + RANGE; dz++) {
                FastLodLevel level = FastLodBandPolicy.levelFor(
                        Math.max(Math.abs(dx), Math.abs(dz)), INNER, RANGE, FastLodQuality.LOW);
                if (level != null) expectedHeightCalls += level.stride() * level.stride();
            }
        }
        verify(terrain, times(expectedHeightCalls)).getFinalTerrainHeightAt(anyInt(), anyInt());
    }

    @Test
    void generateFailureLeavesOldNodeIntactAndRecovers() {
        tick(0, 0);
        FastLodKey oldKey = FastLodKey.of(FastLodLevel.L1, EDGE, 0);
        MmsRenderableHandle oldHandle = handleFor(oldKey);
        assertNotNull(oldHandle);

        terrainFails.set(true);
        tick(1, 0);   // every replacement job fails
        assertNotNull(handleFor(oldKey), "failed replacement must never retire the live node");

        terrainFails.set(false);
        now.addAndGet(FastLodManager.RETRY_BACKOFF_NANOS);
        tick(1, 0);   // reschedules the failed keys once their backoff has run out
        assertNull(handleFor(oldKey));
        assertNotNull(handleFor(FastLodKey.of(FastLodLevel.L0, EDGE, 0)));
        verify(oldHandle).close();
    }

    /**
     * A model-backed sampler fails for as long as its service is down. Retrying on the very next
     * tick re-submitted the whole ring every frame; a failed node now waits out its backoff, and
     * the wait doubles while it keeps failing.
     */
    @Test
    void failedNodesWaitOutTheirBackoffBeforeRetrying() {
        terrainFails.set(true);
        tick(0, 0);
        assertTrue(manager.visibleHandles().isEmpty());

        manager.updateRing(0, 0);
        assertEquals(0, executor.queued(), "a failed node must not be resubmitted before its backoff");

        now.addAndGet(FastLodManager.RETRY_BACKOFF_NANOS);
        manager.updateRing(0, 0);
        assertEquals(RING_NODES, executor.queued(), "every node retries once the backoff has run out");
        executor.runAll();   // fails again: the next wait is twice as long

        now.addAndGet(FastLodManager.RETRY_BACKOFF_NANOS);
        manager.updateRing(0, 0);
        assertEquals(0, executor.queued(), "the second wait is longer than the first");

        terrainFails.set(false);
        now.addAndGet(FastLodManager.RETRY_BACKOFF_NANOS);
        tick(0, 0);
        assertEquals(RING_NODES, manager.visibleHandles().size());
    }

    @Test
    void disablingLodEvictsAndClosesEverything() {
        tick(0, 0);
        assertEquals(RING_NODES, createdHandles.size());

        config.setLodEnabled(false);
        tick(0, 0);
        assertTrue(manager.visibleHandles().isEmpty());
        for (MmsRenderableHandle handle : createdHandles) {
            verify(handle).close();
        }
    }

    /** Runs submitted tasks only when the test says so — deterministic ordering. */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            queue.add(command);
        }

        int queued() {
            return queue.size();
        }

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
