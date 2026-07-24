package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.rendering.textures.BlockTextureArray;
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
    private final Uploader uploader;

    /** GL upload seam — injectable so manager bookkeeping is testable headlessly. */
    interface Uploader {
        MmsRenderableHandle upload(MmsMeshData mesh);
    }

    /**
     * Optional shared-arena upload seam, supplied per-frame by the render side
     * (the {@code FastLodRegionBatcher}). When present and the mesh qualifies
     * (packed, u16), nodes upload into region arenas and draw via multidraw
     * batches; otherwise the legacy per-node {@link Uploader} path is used.
     */
    public interface RegionUploader {
        com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle upload(
                boolean water, int chunkX, int chunkZ, MmsMeshData mesh,
                float minY, float maxY);
    }

    private final Set<FastLodKey> inFlight = ConcurrentHashMap.newKeySet();
    private final Queue<Ready> readyToUpload = new ConcurrentLinkedQueue<>();
    private final Map<FastLodKey, Entry> handles = new ConcurrentHashMap<>();
    private final Queue<AutoCloseable> cleanupQueue = new ConcurrentLinkedQueue<>();
    /** Columns that have the node at the listed key already resident — used to avoid double-scheduling. */
    private final Map<Long, FastLodKey> residentByColumn = new ConcurrentHashMap<>();
    /** newKey → previous resident key at that column; retires the old handle only once the new one has uploaded. */
    private final Map<FastLodKey, FastLodKey> pendingSupersede = new ConcurrentHashMap<>();

    private volatile boolean shutdown = false;
    /**
     * Player chunk column from the most recent {@link #updateRing} tick,
     * packed via {@link #packColumn}. Lets the upload path re-validate a
     * finished mesh against the CURRENT ring instead of blindly uploading
     * work for a column the player has already left (fast-travel churn).
     */
    private volatile long lastPlayerColumn = packColumn(0, 0);

    public FastLodManager(WorldConfiguration config,
                          TerrainGenerationSystem terrain,
                          BlockTextureArray textureArray,
                          FastLodStore store) {
        this(config, terrain, textureArray, store,
            Executors.newFixedThreadPool(config.getChunkBuildThreads(),
                new ThreadFactory() {
                    private int i = 0;
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "FastLod-Worker-" + i++);
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    }
                }),
            MmsRenderableHandle::upload);
    }

    /** Test seam: injectable executor (e.g. same-thread) and GL-free uploader. */
    FastLodManager(WorldConfiguration config,
                   TerrainGenerationSystem terrain,
                   BlockTextureArray textureArray,
                   FastLodStore store,
                   ExecutorService executor,
                   Uploader uploader) {
        this.config   = config;
        this.sampler  = new FastLodSampler(terrain);
        this.mesher   = new FastLodMesher(textureArray);
        this.store    = store;
        this.executor = executor;
        this.uploader = uploader;
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
        lastPlayerColumn = packColumn(playerCx, playerCz);

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
                Entry entry = e.getValue();
                it.remove();
                long col = packColumn(key.chunkX(), key.chunkZ());
                residentByColumn.remove(col, key);
                enqueueCleanup(entry);
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

        // Purge supersede records whose target is no longer wanted at all —
        // covers records orphaned by a failed/empty generate (the job left
        // inFlight without ever reaching the upload path, so neither the
        // cancellation pass above nor applyGLUpdates would ever clean them).
        pendingSupersede.keySet().removeIf(key ->
                FastLodBandPolicy.levelFor(
                        chebyshev(key.chunkX(), key.chunkZ(), playerCx, playerCz), inner, range)
                != key.level());

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
        applyGLUpdates(null);
    }

    /**
     * Drains ready meshes to the GPU and closes retired handles. When
     * {@code regionUploader} is non-null, terrain/water meshes upload into the
     * shared region arenas (multidraw batching); null keeps the legacy
     * per-node handle path (regions disabled, headless tests, teardown drain).
     */
    public void applyGLUpdates(RegionUploader regionUploader) {
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
                        // Region path first (multidraw batching); legacy
                        // per-node handle when unavailable or unqualified.
                        com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle rh = null;
                        MmsRenderableHandle h = null;
                        if (regionUploader != null) {
                            rh = regionUploader.upload(false, r.key.chunkX(), r.key.chunkZ(),
                                    r.meshData, r.minY, r.maxY);
                        }
                        if (rh == null) {
                            h = uploader.upload(r.meshData);
                        }
                        com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle rwh = null;
                        MmsRenderableHandle wh = null;
                        if (r.waterMeshData != null) {
                            try {
                                if (regionUploader != null) {
                                    rwh = regionUploader.upload(true, r.key.chunkX(), r.key.chunkZ(),
                                            r.waterMeshData, r.minY, r.maxY);
                                }
                                if (rwh == null) {
                                    wh = uploader.upload(r.waterMeshData);
                                }
                            } catch (Exception waterEx) {
                                // Keep the pair atomic: a node must never render
                                // seabed without its sheet.
                                closeQuietly(rh);
                                closeQuietly(h);
                                throw waterEx;
                            }
                        }
                        Entry entry = new Entry(r.key, h, wh, rh, rwh, r.minY, r.maxY);
                        // Band-change replacement: inherit the outgoing node's
                        // crossfade state so the level swap doesn't re-dissolve.
                        FastLodKey oldKey = pendingSupersede.get(r.key);
                        Entry replacing = (oldKey != null) ? handles.get(oldKey) : null;
                        if (replacing != null) {
                            entry.fade = replacing.fade;
                            entry.nativeCovered = replacing.nativeCovered;
                        }
                        handles.put(r.key, entry);
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
            AutoCloseable h = cleanupQueue.poll();
            if (h == null) break;
            try { h.close(); } catch (Exception e) {
                System.err.println("[FastLodManager] Cleanup error: " + e.getMessage());
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
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
            enqueueCleanup(retired);
        }
        // residentByColumn was already updated to newKey by the caller before
        // this method ran; the conditional remove below is a no-op in the
        // normal case and safely handles any racy retire that beat it.
        residentByColumn.remove(packColumn(old.chunkX(), old.chunkZ()), old);
    }

    private boolean isStillWanted(FastLodKey key) {
        // Band-change supersedes are always honoured so transitions stay
        // seamless — the supersede record is dropped by updateRing the moment
        // its target stops being wanted, so its presence means "still valid".
        if (pendingSupersede.containsKey(key)) return true;
        int range = config.getLodRange();
        if (!config.isLodEnabled() || range <= 0) return false;
        // Re-validate against the ring as of the latest updateRing tick.
        // Rejecting here skips the GPU upload entirely — without this, a mesh
        // finished for a column the player has already left gets uploaded and
        // then immediately evicted next tick (wasted budget + handle churn
        // during fast travel).
        long col = lastPlayerColumn;
        int playerCx = (int) (col >> 32);
        int playerCz = (int) col;
        FastLodLevel wanted = FastLodBandPolicy.levelFor(
                chebyshev(key.chunkX(), key.chunkZ(), playerCx, playerCz),
                config.getRenderDistance(), range);
        return wanted == key.level();
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

        List<Entry> drained = new ArrayList<>(handles.size());
        for (Iterator<Map.Entry<FastLodKey, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            drained.add(it.next().getValue());
            it.remove();
        }
        inFlight.clear();
        residentByColumn.clear();
        pendingSupersede.clear();
        for (Entry e : drained) {
            enqueueCleanup(e);
        }

        if (store != null) {
            try { store.close(); } catch (Exception e) {
                System.err.println("[FastLodManager] Store close error: " + e.getMessage());
            }
        }
    }

    private void runGenerate(FastLodKey key) {
        // Cooperative cancellation: updateRing edits inFlight when a job's
        // target level stops being wanted, but the submitted task itself was
        // never cancelled — without these checks it would still burn a full
        // store load + terrain sample + mesh build for a node nobody wants.
        if (shutdown || !inFlight.contains(key)) return;
        try {
            FastLodChunkData data = null;
            if (store != null) {
                data = store.tryLoad(key);
                if (shutdown || !inFlight.contains(key)) return;
            }
            if (data == null) {
                data = sampler.sample(key);
                if (store != null) {
                    store.saveAsync(data);
                }
            }
            FastLodMesher.Result result = mesher.build(data);
            if (result.mesh().isEmpty() && result.waterMesh() == null) {
                inFlight.remove(key);
                pendingSupersede.remove(key);
                return;
            }
            // Pack to the interleaved GPU layout here on the worker: the GL
            // upload becomes a bulk copy and the meshes qualify for the shared
            // region arenas (multidraw batching) instead of per-node VAOs.
            readyToUpload.offer(new Ready(key, result.mesh().toPacked(),
                    result.waterMesh() != null ? result.waterMesh().toPacked() : null,
                    result.minY(), result.maxY()));
        } catch (Exception e) {
            inFlight.remove(key);
            pendingSupersede.remove(key);
            System.err.println("[FastLodManager] Generate failed for " + key + ": " + e.getMessage());
        }
    }

    private void evictAll() {
        List<Entry> drained = new ArrayList<>(handles.size());
        for (Iterator<Map.Entry<FastLodKey, Entry>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            drained.add(it.next().getValue());
            it.remove();
        }
        inFlight.clear();
        readyToUpload.clear();
        residentByColumn.clear();
        pendingSupersede.clear();
        for (Entry e : drained) {
            enqueueCleanup(e);
        }
    }

    /** Queues an entry's GPU handles (either representation) for release. */
    private void enqueueCleanup(Entry entry) {
        if (entry.handle != null) {
            cleanupQueue.offer(entry.handle);
        }
        if (entry.waterHandle != null) {
            cleanupQueue.offer(entry.waterHandle);
        }
        if (entry.regionHandle != null) {
            cleanupQueue.offer(entry.regionHandle);
        }
        if (entry.regionWaterHandle != null) {
            cleanupQueue.offer(entry.regionWaterHandle);
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
        /** Legacy per-node terrain handle; null when the mesh lives in a region arena. */
        public final MmsRenderableHandle handle;
        /**
         * Water-sheet mesh handle, or {@code null} for nodes without submerged
         * cells (or whose sheet lives in a region arena). Drawn by the
         * dedicated water pass (same shader as native water) at this entry's
         * crossfade opacity.
         */
        public final MmsRenderableHandle waterHandle;
        /** Region-arena terrain handle (multidraw batching); null on the legacy path. */
        public final com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle regionHandle;
        /** Region-arena water-sheet handle; null when no water or on the legacy path. */
        public final com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle regionWaterHandle;
        /** Exact vertex Y bounds of the node's meshes — feed per-node frustum AABBs. */
        public final float minY, maxY;
        /**
         * Crossfade opacity in [0,1], owned by the render pass (GL thread only).
         * New nodes dissolve in from 0; band-change replacements inherit the
         * superseded node's value so level swaps stay visually atomic.
         */
        public float fade;
        /**
         * Render-pass bookkeeping: true while a resident native chunk mesh
         * covers this column, so leaving the native disk snaps the node solid
         * instead of fading in over a hole.
         */
        public boolean nativeCovered;
        public Entry(FastLodKey key, MmsRenderableHandle handle, float minY, float maxY) {
            this(key, handle, null, null, null, minY, maxY);
        }

        public Entry(FastLodKey key, MmsRenderableHandle handle, MmsRenderableHandle waterHandle,
                     float minY, float maxY) {
            this(key, handle, waterHandle, null, null, minY, maxY);
        }

        public Entry(FastLodKey key, MmsRenderableHandle handle, MmsRenderableHandle waterHandle,
                     com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle regionHandle,
                     com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle regionWaterHandle,
                     float minY, float maxY) {
            this.key = key;
            this.handle = handle;
            this.waterHandle = waterHandle;
            this.regionHandle = regionHandle;
            this.regionWaterHandle = regionWaterHandle;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static final class Ready {
        final FastLodKey key;
        final MmsMeshData meshData;
        final MmsMeshData waterMeshData;   // null when the node has no water
        final float minY, maxY;
        Ready(FastLodKey key, MmsMeshData meshData, MmsMeshData waterMeshData,
              float minY, float maxY) {
            this.key = key;
            this.meshData = meshData;
            this.waterMeshData = waterMeshData;
            this.minY = minY;
            this.maxY = maxY;
        }
    }
}
