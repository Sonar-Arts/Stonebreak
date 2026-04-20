package com.stonebreak.world.lod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates distant-terrain LOD: schedules coarse chunk sampling on worker
 * threads, batches GPU uploads on the render thread, and evicts entries that
 * leave the LOD ring. One instance per {@link com.stonebreak.world.World}.
 *
 * Threading contract:
 *  - {@link #updateRing} runs on the chunk lifecycle tick (logic thread).
 *  - {@link #applyGLUpdates} and {@link #visibleHandles} run on the GL thread.
 *  - Worker threads only read the terrain system (deterministic) and build
 *    immutable {@link MmsMeshData}; no GL calls off the render thread.
 */
public final class LodManager {
    private static final int MAX_SCHEDULES_PER_TICK = 128;
    private static final int MAX_UPLOADS_PER_FRAME = 8;
    private static final int MAX_CLEANUPS_PER_FRAME = 16;

    private final WorldConfiguration config;
    private final LodChunkGenerator generator;
    private final LodMesher mesher;
    private final ExecutorService executor;

    private final Set<ChunkPosition> inFlight = ConcurrentHashMap.newKeySet();
    private final Queue<Ready> readyToUpload = new ConcurrentLinkedQueue<>();
    private final Map<ChunkPosition, Entry> handles = new ConcurrentHashMap<>();
    private final Queue<MmsRenderableHandle> cleanupQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean shutdown = false;

    public LodManager(WorldConfiguration config, TerrainGenerationSystem terrain, TextureAtlas atlas) {
        this.config = config;
        this.generator = new LodChunkGenerator(terrain);
        this.mesher = new LodMesher(atlas);
        this.executor = Executors.newFixedThreadPool(config.getChunkBuildThreads(),
            new ThreadFactory() {
                private int i = 0;
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Lod-Worker-" + i++);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });
    }

    /**
     * Logic-tick entry point. Given the player's chunk coordinates, schedules
     * generation of any LOD chunks that should exist but don't, and evicts
     * ones that have moved out of range. Idempotent; safe to call each tick.
     */
    public void updateRing(int playerCx, int playerCz) {
        if (shutdown) return;

        if (!config.isLodEnabled() || config.getLodRange() <= 0) {
            evictAll();
            return;
        }

        int inner = config.getRenderDistance();
        int outer = inner + config.getLodRange();

        // Evict anything outside the ring. Order is critical: remove from the
        // handles map FIRST, then enqueue for GPU cleanup — otherwise the render
        // thread's applyGLUpdates could close the handle while it is still
        // reachable via visibleHandles(), producing a use-after-dispose crash.
        for (Iterator<Map.Entry<ChunkPosition, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ChunkPosition, Entry> e = it.next();
            int d = chebyshev(e.getKey(), playerCx, playerCz);
            if (d <= inner || d > outer) {
                MmsRenderableHandle h = e.getValue().handle;
                it.remove();
                cleanupQueue.offer(h);
            }
        }
        inFlight.removeIf(pos -> {
            int d = chebyshev(pos, playerCx, playerCz);
            return d <= inner || d > outer;
        });

        // Schedule the nearest missing columns (bounded per tick so ticks stay cheap).
        List<ChunkPosition> missing = new ArrayList<>();
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d <= inner || d > outer) continue;
                int cx = playerCx + dx;
                int cz = playerCz + dz;
                ChunkPosition pos = new ChunkPosition(cx, cz);
                if (handles.containsKey(pos) || inFlight.contains(pos)) continue;
                missing.add(pos);
            }
        }

        if (missing.isEmpty()) return;

        missing.sort((a, b) -> Integer.compare(
            chebyshev(a, playerCx, playerCz),
            chebyshev(b, playerCx, playerCz)));

        int toSchedule = Math.min(missing.size(), MAX_SCHEDULES_PER_TICK);
        for (int i = 0; i < toSchedule; i++) {
            ChunkPosition pos = missing.get(i);
            if (!inFlight.add(pos)) continue;
            executor.submit(() -> runGenerate(pos));
        }
    }

    /**
     * GL-thread entry point. Uploads a bounded number of ready meshes and frees
     * a bounded number of orphaned GPU handles. Call once per frame before
     * {@link #visibleHandles}.
     */
    public void applyGLUpdates() {
        // Uploads only while alive; cleanup always runs so shutdown drains the GPU.
        if (!shutdown) {
            for (int i = 0; i < MAX_UPLOADS_PER_FRAME; i++) {
                Ready r = readyToUpload.poll();
                if (r == null) break;
                inFlight.remove(r.pos);
                // Guard: entry may have been evicted while in flight.
                if (handles.containsKey(r.pos)) continue;
                try {
                    MmsRenderableHandle h = MmsRenderableHandle.upload(r.meshData);
                    handles.put(r.pos, new Entry(r.pos, h));
                } catch (Exception e) {
                    System.err.println("[LodManager] Upload failed for " + r.pos + ": " + e.getMessage());
                }
            }
        }

        int cleanupBudget = shutdown ? Integer.MAX_VALUE : MAX_CLEANUPS_PER_FRAME;
        for (int i = 0; i < cleanupBudget; i++) {
            MmsRenderableHandle h = cleanupQueue.poll();
            if (h == null) break;
            try { h.close(); } catch (Exception e) {
                System.err.println("[LodManager] Cleanup error: " + e.getMessage());
            }
        }
    }

    /** Snapshot of currently renderable LOD entries. GL thread only. */
    public Collection<Entry> visibleHandles() {
        return handles.values();
    }

    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    WorldConfiguration.CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        readyToUpload.clear();
        // Drain the map first, then queue for cleanup — same ordering contract
        // as evictAll, to avoid any late render-thread lookup observing a
        // handle that has already been closed.
        List<MmsRenderableHandle> drained = new ArrayList<>(handles.size());
        for (Iterator<Map.Entry<ChunkPosition, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            drained.add(it.next().getValue().handle);
            it.remove();
        }
        inFlight.clear();
        for (MmsRenderableHandle h : drained) {
            cleanupQueue.offer(h);
        }
    }

    private void runGenerate(ChunkPosition pos) {
        if (shutdown) return;
        try {
            LodChunk chunk = generator.generate(pos.getX(), pos.getZ());
            MmsMeshData mesh = mesher.build(chunk);
            if (mesh.isEmpty()) {
                inFlight.remove(pos);
                return;
            }
            readyToUpload.offer(new Ready(pos, mesh));
        } catch (Exception e) {
            inFlight.remove(pos);
            System.err.println("[LodManager] Generate failed for " + pos + ": " + e.getMessage());
        }
    }

    private void evictAll() {
        // See the ordering note in updateRing: drain the map before queuing
        // for cleanup so the render thread can never observe a handle that
        // applyGLUpdates may already have closed.
        List<MmsRenderableHandle> drained = new ArrayList<>(handles.size());
        for (Iterator<Map.Entry<ChunkPosition, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            drained.add(it.next().getValue().handle);
            it.remove();
        }
        inFlight.clear();
        readyToUpload.clear();
        for (MmsRenderableHandle h : drained) {
            cleanupQueue.offer(h);
        }
    }

    private static int chebyshev(ChunkPosition pos, int cx, int cz) {
        return Math.max(Math.abs(pos.getX() - cx), Math.abs(pos.getZ() - cz));
    }

    /** Renderable LOD entry — the GL handle is the only thing the render pass needs. */
    public static final class Entry {
        public final ChunkPosition pos;
        public final MmsRenderableHandle handle;
        Entry(ChunkPosition pos, MmsRenderableHandle handle) {
            this.pos = pos;
            this.handle = handle;
        }
    }

    private static final class Ready {
        final ChunkPosition pos;
        final MmsMeshData meshData;
        Ready(ChunkPosition pos, MmsMeshData meshData) {
            this.pos = pos;
            this.meshData = meshData;
        }
    }
}
