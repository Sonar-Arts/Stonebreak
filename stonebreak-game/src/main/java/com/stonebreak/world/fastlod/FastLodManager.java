package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.rendering.textures.TextureAtlas;
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
 * Orchestrates the Fast LOD system: schedules per-level coarse chunk sampling
 * on worker threads, batches GPU uploads on the render thread, and evicts
 * entries whose band no longer matches the player's position.
 *
 * <p>Successor to the single-level {@code LodManager}. Differences:
 * <ul>
 *   <li>Nodes are keyed by {@link FastLodKey} so the same chunk column can
 *       transition between detail levels as the player moves without ever
 *       flickering — eviction and scheduling both run off the key.</li>
 *   <li>Generated samples are optionally cached in a {@link FastLodStore}
 *       keyed by the same triple. The store is looked up before hitting the
 *       terrain system, so re-entering a previously visited ring is a pure
 *       blob read.</li>
 * </ul>
 *
 * <p>Threading contract (unchanged from the legacy manager):
 * <ul>
 *   <li>{@link #updateRing} runs on the logic thread.</li>
 *   <li>{@link #applyGLUpdates} and {@link #visibleHandles} run on the GL thread.</li>
 *   <li>Worker threads only touch the deterministic terrain system and the
 *       store; no GL calls happen off the render thread.</li>
 * </ul>
 */
public final class FastLodManager {

    private static final int MAX_SCHEDULES_PER_TICK = 256;
    private static final int MAX_UPLOADS_PER_FRAME  = 48;
    private static final int MAX_CLEANUPS_PER_FRAME = 48;
    /** Cap spent on GPU upload work per frame; prevents bursts from spiking frame time. */
    private static final long UPLOAD_BUDGET_NANOS = 3_000_000L; // 3 ms

    private final WorldConfiguration config;
    private final FastLodSampler sampler;
    private final FastLodMesher mesher;
    private final ExecutorService executor;
    private final FastLodStore store;   // may be null when persistence is disabled

    private final Set<FastLodKey> inFlight = ConcurrentHashMap.newKeySet();
    private final Queue<Ready> readyToUpload = new ConcurrentLinkedQueue<>();
    private final Map<FastLodKey, Entry> handles = new ConcurrentHashMap<>();
    private final Queue<MmsRenderableHandle> cleanupQueue = new ConcurrentLinkedQueue<>();
    /** Columns that have the node at the listed key already resident — used to avoid double-scheduling. */
    private final Map<Long, FastLodKey> residentByColumn = new ConcurrentHashMap<>();
    /** newKey → previous resident key at that column; retires the old handle only once the new one has uploaded. */
    private final Map<FastLodKey, FastLodKey> pendingSupersede = new ConcurrentHashMap<>();

    private volatile boolean shutdown = false;

    public FastLodManager(WorldConfiguration config,
                          TerrainGenerationSystem terrain,
                          TextureAtlas atlas,
                          FastLodStore store) {
        this.config  = config;
        this.sampler = new FastLodSampler(terrain);
        this.mesher  = new FastLodMesher(atlas);
        this.store   = store;
        this.executor = Executors.newFixedThreadPool(config.getChunkBuildThreads(),
            new ThreadFactory() {
                private int i = 0;
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "FastLod-Worker-" + i++);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });
    }

    public void updateRing(int playerCx, int playerCz) {
        if (shutdown) return;
        int range = config.getLodRange();
        if (!config.isLodEnabled() || range <= 0) {
            evictAll();
            return;
        }
        int inner = config.getRenderDistance();
        int outer = inner + range;

        // Pass 1: evict anything that has fallen outside the ring entirely.
        // Band-change transitions (wanted != key.level but wanted != null) are
        // NOT evicted here — the old node keeps rendering so the user never
        // sees a gap between retiring the old level and the new level showing
        // up. The handover happens inside applyGLUpdates when the replacement
        // uploads successfully.
        for (Iterator<Map.Entry<FastLodKey, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<FastLodKey, Entry> e = it.next();
            FastLodKey key = e.getKey();
            FastLodLevel wanted = FastLodBandPolicy.levelFor(
                    chebyshev(key.chunkX(), key.chunkZ(), playerCx, playerCz), inner, range);
            if (wanted == null) {
                MmsRenderableHandle h = e.getValue().handle;
                it.remove();
                long col = packColumn(key.chunkX(), key.chunkZ());
                residentByColumn.remove(col, key);
                cleanupQueue.offer(h);
            }
        }

        // Cancel any in-flight job whose target level is no longer desired at
        // its column. Also drop its supersede record so we never evict a live
        // handle for a replacement that's been abandoned.
        inFlight.removeIf(key -> {
            FastLodLevel wanted = FastLodBandPolicy.levelFor(
                    chebyshev(key.chunkX(), key.chunkZ(), playerCx, playerCz), inner, range);
            if (wanted != key.level()) {
                pendingSupersede.remove(key);
                return true;
            }
            return false;
        });

        // Pass 2: enumerate desired nodes in the ring. For each column pick
        // the detail level the band policy wants. If the resident node is
        // already at that level, nothing to do. If it's at a different level
        // schedule the correct one and record a supersede so the old node is
        // retired atomically with the new upload. Empty columns just schedule.
        List<FastLodKey> missing = new ArrayList<>();
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                FastLodLevel wanted = FastLodBandPolicy.levelFor(d, inner, range);
                if (wanted == null) continue;
                int cx = playerCx + dx, cz = playerCz + dz;
                FastLodKey target = FastLodKey.of(wanted, cx, cz);
                if (handles.containsKey(target) || inFlight.contains(target)) continue;

                FastLodKey resident = residentByColumn.get(packColumn(cx, cz));
                if (resident != null && resident.level() == wanted) continue;
                if (resident != null) {
                    // Band change — remember which handle to retire once the
                    // replacement lands. putIfAbsent so a late tick that sees
                    // the same transition doesn't stomp an earlier record.
                    pendingSupersede.putIfAbsent(target, resident);
                }
                missing.add(target);
            }
        }
        if (missing.isEmpty()) return;

        missing.sort((a, b) -> Integer.compare(
                chebyshev(a.chunkX(), a.chunkZ(), playerCx, playerCz),
                chebyshev(b.chunkX(), b.chunkZ(), playerCx, playerCz)));

        int toSchedule = Math.min(missing.size(), MAX_SCHEDULES_PER_TICK);
        for (int i = 0; i < toSchedule; i++) {
            FastLodKey key = missing.get(i);
            if (!inFlight.add(key)) continue;
            executor.submit(() -> runGenerate(key));
        }
    }

    public void applyGLUpdates() {
        if (!shutdown) {
            // Upload until we hit either the hard count cap or the wall-clock
            // budget. The count cap is a safety belt; the time budget is what
            // usually binds, so a cold reveal drains as fast as the GPU allows
            // without a single frame spiking beyond ~3ms of upload work.
            long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
            for (int i = 0; i < MAX_UPLOADS_PER_FRAME; i++) {
                Ready r = readyToUpload.poll();
                if (r == null) break;
                inFlight.remove(r.key);

                // If the in-flight cancellation pass already rejected this key,
                // drop the mesh on the floor and move on.
                if (!isStillWanted(r.key)) {
                    pendingSupersede.remove(r.key);
                } else if (handles.containsKey(r.key)) {
                    // Duplicate upload — shouldn't normally happen but is harmless.
                    pendingSupersede.remove(r.key);
                } else {
                    try {
                        MmsRenderableHandle h = MmsRenderableHandle.upload(r.meshData);
                        handles.put(r.key, new Entry(r.key, h));
                        residentByColumn.put(packColumn(r.key.chunkX(), r.key.chunkZ()), r.key);
                        retireSupersededFor(r.key);
                    } catch (Exception e) {
                        pendingSupersede.remove(r.key);
                        System.err.println("[FastLodManager] Upload failed for " + r.key + ": " + e.getMessage());
                    }
                }

                if (System.nanoTime() >= deadline) break;
            }
        }

        int cleanupBudget = shutdown ? Integer.MAX_VALUE : MAX_CLEANUPS_PER_FRAME;
        for (int i = 0; i < cleanupBudget; i++) {
            MmsRenderableHandle h = cleanupQueue.poll();
            if (h == null) break;
            try { h.close(); } catch (Exception e) {
                System.err.println("[FastLodManager] Cleanup error: " + e.getMessage());
            }
        }
    }

    /**
     * Retires the old-level handle that this {@code newKey} replaces.
     * Called immediately after the new upload enters {@link #handles} so the
     * swap is visually atomic to the renderer.
     */
    private void retireSupersededFor(FastLodKey newKey) {
        FastLodKey old = pendingSupersede.remove(newKey);
        if (old == null || old.equals(newKey)) return;
        Entry retired = handles.remove(old);
        if (retired != null) {
            cleanupQueue.offer(retired.handle);
        }
        // residentByColumn was already updated to newKey by the caller before
        // this method ran; the conditional remove below is a no-op in the
        // normal case and safely handles any racy retire that beat it.
        residentByColumn.remove(packColumn(old.chunkX(), old.chunkZ()), old);
    }

    private boolean isStillWanted(FastLodKey key) {
        // Mesh was generated; we only abandon it if the ring has moved so far
        // that this key's column is out of range entirely. Band-change
        // supersedes are always honoured so transitions stay seamless.
        if (pendingSupersede.containsKey(key)) return true;
        int range = config.getLodRange();
        if (!config.isLodEnabled() || range <= 0) return false;
        // We don't have player coords here, so fall back to "in handles map or
        // resident-by-column was expecting us". Either condition means a tick
        // validated this key recently. If neither, the key was cancelled.
        long col = packColumn(key.chunkX(), key.chunkZ());
        FastLodKey resident = residentByColumn.get(col);
        return resident == null || resident.level() == key.level() || resident.equals(key);
    }

    public Collection<Entry> visibleHandles() {
        return handles.values();
    }

    /** Inner radius (chunks) beyond which LOD is allowed to be drawn. */
    public int innerRadius() {
        return config.getRenderDistance();
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

        List<MmsRenderableHandle> drained = new ArrayList<>(handles.size());
        for (Iterator<Map.Entry<FastLodKey, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            drained.add(it.next().getValue().handle);
            it.remove();
        }
        inFlight.clear();
        residentByColumn.clear();
        pendingSupersede.clear();
        for (MmsRenderableHandle h : drained) {
            cleanupQueue.offer(h);
        }

        if (store != null) {
            try { store.close(); } catch (Exception e) {
                System.err.println("[FastLodManager] Store close error: " + e.getMessage());
            }
        }
    }

    private void runGenerate(FastLodKey key) {
        if (shutdown) return;
        try {
            FastLodChunkData data = null;
            if (store != null) {
                data = store.tryLoad(key);
            }
            if (data == null) {
                data = sampler.sample(key);
                if (store != null) {
                    store.saveAsync(data);
                }
            }
            MmsMeshData mesh = mesher.build(data);
            if (mesh.isEmpty()) {
                inFlight.remove(key);
                return;
            }
            readyToUpload.offer(new Ready(key, mesh));
        } catch (Exception e) {
            inFlight.remove(key);
            System.err.println("[FastLodManager] Generate failed for " + key + ": " + e.getMessage());
        }
    }

    private void evictAll() {
        List<MmsRenderableHandle> drained = new ArrayList<>(handles.size());
        for (Iterator<Map.Entry<FastLodKey, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            drained.add(it.next().getValue().handle);
            it.remove();
        }
        inFlight.clear();
        readyToUpload.clear();
        residentByColumn.clear();
        pendingSupersede.clear();
        for (MmsRenderableHandle h : drained) {
            cleanupQueue.offer(h);
        }
    }

    private static int chebyshev(int x, int z, int cx, int cz) {
        return Math.max(Math.abs(x - cx), Math.abs(z - cz));
    }

    /** Packs a chunk coordinate pair into a long for hash-map indexing. */
    private static long packColumn(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    public static final class Entry {
        public final FastLodKey key;
        public final MmsRenderableHandle handle;
        Entry(FastLodKey key, MmsRenderableHandle handle) {
            this.key = key;
            this.handle = handle;
        }
    }

    private static final class Ready {
        final FastLodKey key;
        final MmsMeshData meshData;
        Ready(FastLodKey key, MmsMeshData meshData) {
            this.key = key;
            this.meshData = meshData;
        }
    }
}
