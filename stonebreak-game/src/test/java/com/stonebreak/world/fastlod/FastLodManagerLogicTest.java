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
 * <p>Config: renderDistance=1, lodRange=2 → ring covers Chebyshev 1..3
 * (48 columns: d≤2 → L0 incl. preload, d=3 → L1), small enough that a single
 * {@code applyGLUpdates} call (48-upload cap) drains everything.
 */
class FastLodManagerLogicTest {

    private static final int INNER = 1;
    private static final int RANGE = 2;
    private static final int RING_NODES = 48;   // 7x7 minus the player column

    private WorldConfiguration config;
    private TerrainGenerationSystem terrain;
    private ManualExecutor executor;
    private FastLodManager manager;

    private final AtomicBoolean terrainFails = new AtomicBoolean(false);
    private final List<MmsRenderableHandle> createdHandles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = new WorldConfiguration(INNER, 1, RANGE, true);
        terrain = mock(TerrainGenerationSystem.class);
        when(terrain.getFinalTerrainHeightAt(anyInt(), anyInt())).thenAnswer(inv -> {
            if (terrainFails.get()) throw new RuntimeException("simulated terrain failure");
            return 80;
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
            BlockType[] outSurface = inv.getArgument(5);
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
        }).when(terrain).sampleColumns(anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any());

        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(7);

        executor = new ManualExecutor();
        FastLodManager.Uploader uploader = mesh -> {
            MmsRenderableHandle handle = mock(MmsRenderableHandle.class);
            createdHandles.add(handle);
            return handle;
        };
        manager = new FastLodManager(config, terrain, textures, null, executor, uploader);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private void tick(int playerCx, int playerCz) {
        manager.updateRing(playerCx, playerCz);
        executor.runAll();
        manager.applyGLUpdates();
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

    @Test
    void ringBecomesResidentAndSecondTickIsIdempotent() {
        tick(0, 0);
        assertEquals(RING_NODES, manager.visibleHandles().size());
        assertEquals(RING_NODES, createdHandles.size());

        // Every entry carries the mesh bounds for frustum culling: tops at the
        // flat terrain height, minY at 0 from the node-border foundation walls.
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            assertEquals(0f, e.minY, 1e-4f);
            assertEquals(80f, e.maxY, 1e-4f);
        }

        tick(0, 0);
        assertEquals(RING_NODES, manager.visibleHandles().size());
        assertEquals(RING_NODES, createdHandles.size(), "stable ring schedules nothing new");
    }

    @Test
    void bandTransitionRetiresOldHandleOnlyAfterReplacementUploads() {
        tick(0, 0);
        // Column (3,0): d=3 from origin → L1; d=2 from (1,0) → L0.
        FastLodKey oldKey = FastLodKey.of(FastLodLevel.L1, 3, 0);
        MmsRenderableHandle oldHandle = handleFor(oldKey);
        assertNotNull(oldHandle);

        manager.updateRing(1, 0);
        // Jobs queued but not run — the old node must keep rendering (no gap).
        assertNotNull(handleFor(oldKey), "old level survives until replacement uploads");

        executor.runAll();
        assertNotNull(handleFor(oldKey), "generation done but not uploaded — still no retire");

        manager.applyGLUpdates();
        assertNull(handleFor(oldKey), "retired atomically with the replacement upload");
        assertNotNull(handleFor(FastLodKey.of(FastLodLevel.L0, 3, 0)));
        verify(oldHandle).close();
    }

    @Test
    void bandTransitionReplacementInheritsCrossfadeState() {
        tick(0, 0);
        FastLodManager.Entry old = entryFor(FastLodKey.of(FastLodLevel.L1, 3, 0));
        assertNotNull(old);
        // Simulate the render pass having partially faded this node.
        old.fade = 0.37f;
        old.nativeCovered = true;

        tick(1, 0);   // column (3,0) transitions L1 → L0 via the supersede path
        FastLodManager.Entry replacement = entryFor(FastLodKey.of(FastLodLevel.L0, 3, 0));
        assertNotNull(replacement);
        assertEquals(0.37f, replacement.fade, 1e-6f,
                "level swap must not restart the crossfade");
        assertTrue(replacement.nativeCovered);
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
        manager.applyGLUpdates();
        assertEquals(0, createdHandles.size(), "no stale mesh may reach the uploader");
        assertTrue(manager.visibleHandles().isEmpty());
    }

    @Test
    void cancelledJobsSkipTerrainSamplingEntirely() {
        manager.updateRing(0, 0);      // queues 48 jobs, none run yet
        manager.updateRing(100, 100);  // cancels all of them, queues 48 new ones
        executor.runAll();
        manager.applyGLUpdates();

        assertEquals(RING_NODES, manager.visibleHandles().size());
        for (FastLodManager.Entry e : manager.visibleHandles()) {
            int d = Math.max(Math.abs(e.key.chunkX() - 100), Math.abs(e.key.chunkZ() - 100));
            assertTrue(d >= 1 && d <= INNER + RANGE, "resident node outside the current ring");
        }

        // Cooperative cancellation: the 48 stale jobs must exit before touching
        // the terrain system, so total height samples equal exactly one ring.
        int expectedHeightCalls = 0;
        for (int dx = -(INNER + RANGE); dx <= INNER + RANGE; dx++) {
            for (int dz = -(INNER + RANGE); dz <= INNER + RANGE; dz++) {
                FastLodLevel level = FastLodBandPolicy.levelFor(
                        Math.max(Math.abs(dx), Math.abs(dz)), INNER, RANGE);
                if (level != null) expectedHeightCalls += level.stride() * level.stride();
            }
        }
        verify(terrain, times(expectedHeightCalls)).getFinalTerrainHeightAt(anyInt(), anyInt());
    }

    @Test
    void generateFailureLeavesOldNodeIntactAndRecovers() {
        tick(0, 0);
        FastLodKey oldKey = FastLodKey.of(FastLodLevel.L1, 3, 0);
        MmsRenderableHandle oldHandle = handleFor(oldKey);
        assertNotNull(oldHandle);

        terrainFails.set(true);
        tick(1, 0);   // every replacement job fails
        assertNotNull(handleFor(oldKey), "failed replacement must never retire the live node");

        terrainFails.set(false);
        tick(1, 0);   // reschedules the failed keys
        assertNull(handleFor(oldKey));
        assertNotNull(handleFor(FastLodKey.of(FastLodLevel.L0, 3, 0)));
        verify(oldHandle).close();
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
