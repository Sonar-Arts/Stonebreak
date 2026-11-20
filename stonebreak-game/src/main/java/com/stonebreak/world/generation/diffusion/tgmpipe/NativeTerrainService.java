package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.SurfaceChannel;
import com.stonebreak.world.generation.diffusion.SurfaceChannels;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainTile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The DaedalusTGM tile pipeline in-process (native engine phase 3; the only backend since the Python TGMPipe child
 * was removed 2026-11-20), on {@link NativeTerrain}: requests are served from the
 * on-disk tile cache when present (an IO thread), otherwise queued by priority (lower first, then FIFO) for one GPU
 * worker thread per native lane ({@link NativeTerrain#setLanes}: concurrent tile builders sharing the models and window
 * caches - the GPU works on one tile while another lane waits on the host; tiles are identical whatever lane builds
 * them); equal requests share one build; a request whose future is completed from outside (cancelled) is withdrawn
 * before its build starts. Tiles are cached per world in their own namespaces (the native pipeline's
 * model_id carries the ":nt1" marker, so its tiles never mix with tiles the retired Python service cached).
 */
final class NativeTerrainService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NativeTerrainService.class.getName());
    private static final int PLANES = 7;

    private record Key(long seed, int tileX, int tileZ, int lod, int world) {
    }

    private static final class Job implements Comparable<Job> {
        final Key key;
        int priority;           // the most urgent priority any of its requests asked for (guarded by jobs)
        final long order;
        final List<TileRequest> waiters = new ArrayList<>();
        boolean started;

        Job(Key key, int priority, long order) {
            this.key = key;
            this.priority = priority;
            this.order = order;
        }

        @Override
        public int compareTo(Job o) {
            int c = Integer.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }

    private final NativeTerrain terrain;
    private final TileDiskCache cache;
    private final List<String> worldJson;
    private final List<Integer> tileSize;
    private final List<String> namespaces;
    private final String modelId;
    /** Waiting jobs, most urgent first; guarded by {@code jobs} (its monitor also wakes the GPU workers). */
    private final PriorityQueue<Job> queue = new PriorityQueue<>();
    private final Map<Key, Job> jobs = new HashMap<>();
    private final AtomicLong order = new AtomicLong();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> daemon(r, "terrain-native-io"));
    private final List<Thread> gpus;
    private final boolean express = expressLane();
    private volatile boolean closed;
    private final AtomicLong served = new AtomicLong(), cacheHits = new AtomicLong(), generated = new AtomicLong(),
            failed = new AtomicLong(), genMs = new AtomicLong();
    private volatile long lastActivityNanos = System.nanoTime();
    /** Tiles being built right now (the idle check never trims under a build). */
    private final AtomicInteger building = new AtomicInteger();
    /** What the idle check did since the last activity: 0 nothing, 1 trimmed the pools, 2 released the caches. */
    private volatile int idleLevel;
    private final ScheduledExecutorService idle = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "terrain-native-idle"));
    /**
     * Idle this long ({@code -Dstonebreak.terrain.idleTrimSeconds}, default 5; 0 = never): unused pooled blocks (device
     * and pinned) go back to the driver; cached windows stay, nothing is recomputed (the old Python service did this
     * after 1 s).
     */
    static final long IDLE_TRIM_SECONDS = Long.getLong("stonebreak.terrain.idleTrimSeconds", 5L);
    /**
     * Idle this long ({@code -Dstonebreak.terrain.idleReleaseSeconds}, default 120; 0 = never): every cached window is
     * dropped too (a world left, a player standing still): host and GPU memory back to the models alone. The next tiles
     * recompute their windows (identical results, slower until the caches refill).
     */
    static final long IDLE_RELEASE_SECONDS = Long.getLong("stonebreak.terrain.idleReleaseSeconds", 120L);
    /** Dev: {@code -Dstonebreak.terrain.memLog=<s>} logs the pipeline's memory counters every s seconds (0 = off). */
    static final long MEM_LOG_SECONDS = Long.getLong("stonebreak.terrain.memLog", 0L);
    private long lastMemLogNanos;
    /** Requests served per lod (index = log2 lod) and how many of them the disk cache answered (dev memLog). */
    private final java.util.concurrent.atomic.AtomicLongArray servedByLod = new java.util.concurrent.atomic.AtomicLongArray(8),
            diskByLod = new java.util.concurrent.atomic.AtomicLongArray(8);

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * Opens and warms the pipeline and registers the handshake's worlds (the same list the Python service receives in
     * HELLO, in {@code TerrainScale.worldIndex} order).
     */
    NativeTerrainService(NativeTerrain terrain, List<String> worlds, Path cacheRoot, long cacheBytes, String exportKey) {
        this.terrain = terrain;
        this.worldJson = List.copyOf(worlds);
        ObjectMapper om = new ObjectMapper();
        try {
            this.modelId = om.readTree(terrain.info()).path("model_id").asText("");
            List<Integer> sizes = new ArrayList<>();
            for (int i = 0; i < worlds.size(); i++) {
                terrain.setWorld(i, worlds.get(i));
                JsonNode w = om.readTree(worlds.get(i));
                sizes.add(w.path("tile_size").asInt(256));
            }
            this.tileSize = List.copyOf(sizes);
        } catch (java.io.IOException e) {
            throw new TGMPipeException("bad native terrain info / world json", e);
        }
        List<String> ns = new ArrayList<>();
        for (String w : worlds) {
            ns.add(namespace(w, modelId, exportKey));
        }
        this.namespaces = List.copyOf(ns);
        this.cache = new TileDiskCache(cacheRoot, namespaces, cacheBytes);
        List<Thread> threads = new ArrayList<>();
        for (int lane = 0; lane < Math.max(1, terrain.lanes()); lane++) {
            final int l = lane;
            threads.add(daemon(() -> gpuLoop(l), lane == 0 ? "terrain-native-gpu" : "terrain-native-gpu" + lane));
        }
        this.gpus = List.copyOf(threads);
        this.gpus.forEach(Thread::start);
        idle.scheduleWithFixedDelay(this::checkIdle, 1, 1, TimeUnit.SECONDS);
    }

    /** Something used the pipeline: the idle clock starts again. */
    private void active() {
        lastActivityNanos = System.nanoTime();
        idleLevel = 0;
    }

    /** Idle-thread tick: trims, then releases, a pipeline nothing has asked anything of for a while. */
    void checkIdle() {
        if (MEM_LOG_SECONDS > 0 && System.nanoTime() - lastMemLogNanos >= TimeUnit.SECONDS.toNanos(MEM_LOG_SECONDS)) {
            lastMemLogNanos = System.nanoTime();
            LOG.info("[terrain-mem] " + memorySummary(terrain.stats()) + " generated=" + generated.get()
                    + " served=" + served.get() + " byLod(served/disk/distinct)=" + byLod());
        }
        if (closed || building.get() > 0) {
            return;
        }
        synchronized (jobs) {
            if (!jobs.isEmpty()) {
                return;
            }
        }
        long idleFor = System.nanoTime() - lastActivityNanos;
        try {
            if (idleLevel < 2 && IDLE_RELEASE_SECONDS > 0 && idleFor >= TimeUnit.SECONDS.toNanos(IDLE_RELEASE_SECONDS)) {
                terrain.release();
                idleLevel = 2;
                LOG.info(() -> "native terrain idle for " + IDLE_RELEASE_SECONDS + " s: window caches released");
            } else if (idleLevel < 1 && IDLE_TRIM_SECONDS > 0 && idleFor >= TimeUnit.SECONDS.toNanos(IDLE_TRIM_SECONDS)) {
                terrain.trim();
                idleLevel = 1;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "native terrain idle trim failed", e);
            idleLevel = 2;   // do not retry every second
        }
    }

    /**
     * Lanes on a GPU with at least {@link #AUTO_LANES_MIN_VRAM_MIB} MiB and a machine with at least
     * {@link #AUTO_LANES_MIN_RAM_MIB} MiB of RAM: a second lane costs ~2.5 GB of VRAM and its own pinned pool and execution
     * contexts in host memory (2026-11-20: a 32 GB machine with a 5090 ran out of RAM).
     */
    static final int DEFAULT_LANES = 2;
    static final long AUTO_LANES_MIN_VRAM_MIB = 16384;
    static final long AUTO_LANES_MIN_RAM_MIB = 48L * 1024 - 1024;   // a 48 GB machine reports a little under 48 GiB

    /** {@code auto} (default): {@link #DEFAULT_LANES} on a big GPU in a big machine, else 1 (unknown = 1). */
    static int lanesFor(long vramMiB, long ramMiB) {
        return vramMiB >= AUTO_LANES_MIN_VRAM_MIB && ramMiB >= AUTO_LANES_MIN_RAM_MIB ? DEFAULT_LANES : 1;
    }

    /** {@code -Dstonebreak.terrain.lanes}: a count (1 = the sequential pipeline, A/B and fallback) or "auto". */
    static int configuredLanes() {
        String v = System.getProperty("stonebreak.terrain.lanes", "auto").trim();
        if (!v.equalsIgnoreCase("auto")) {
            try {
                return Math.max(1, Math.min(8, Integer.parseInt(v)));
            } catch (NumberFormatException e) {
                // fall through to auto
            }
        }
        DaedalusRequirements.SystemSpecs specs = DaedalusRequirements.check().specs();
        return specs == null ? 1 : lanesFor(specs.vramMiB(), specs.ramMiB());
    }

    /** Cache namespace of one world: everything that decides its tiles (world config, model, native export). */
    static String namespace(String worldJson, String modelId, String exportKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] d = sha.digest(("native|v2|" + worldJson + "|" + modelId + "|" + exportKey)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> namespaces() {
        return namespaces;
    }

    String modelId() {
        return modelId;
    }

    int pendingCount() {
        synchronized (jobs) {
            return jobs.size();
        }
    }

    long lastActivityNanos() {
        return lastActivityNanos;
    }

    void submit(TileRequest r) {
        if (closed) {
            throw new TGMPipeException("native terrain pipeline is closed");
        }
        if (r.world() < 0 || r.world() >= worldJson.size()) {
            r.future().completeExceptionally(new TGMPipeException("unknown world " + r.world()));
            return;
        }
        io.execute(() -> lookup(r));
    }

    private void lookup(TileRequest r) {
        if (r.future().isDone()) {
            return;
        }
        Key key = new Key(r.seed(), r.tileX(), r.tileZ(), r.lod(), r.world());
        TileDiskCache.Entry hit = null;
        try {
            hit = cache.get(key.world, key.seed, key.tileX, key.tileZ, key.lod, PLANES);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "tile cache read failed for " + r, e);
        }
        if (hit != null) {
            cacheHits.incrementAndGet();
            served.incrementAndGet();
            countServed(key, true);
            r.future().complete(toTile(key, hit.rows(), hit.cols(), hit.planes(), hit.surface()));
            return;
        }
        synchronized (jobs) {
            Job job = jobs.get(key);
            if (job == null) {
                job = new Job(key, r.priority(), order.incrementAndGet());
                jobs.put(key, job);
                job.waiters.add(r);
                queue.add(job);
            } else {
                job.waiters.add(r);
                if (!job.started && r.priority() < job.priority && queue.remove(job)) {   // as the service: most urgent wins
                    job.priority = r.priority();
                    queue.add(job);
                }
            }
            jobs.notifyAll();
        }
        active();
    }

    /**
     * Lane 0 is the express lane when there are several ({@code -Dstonebreak.terrain.expressLane}, default on): it builds
     * only tiles at {@link TGMPipe#PRIORITY_WORLD} (what cubes / the player wait for), so such a tile never queues
     * behind far-LOD / relief tiles that the other lanes grind through; with one lane every tile goes to it.
     */
    static boolean expressLane() {
        return !"off".equalsIgnoreCase(System.getProperty("stonebreak.terrain.expressLane", "on").trim());
    }

    private boolean takes(int lane, Job head) {
        return head != null && (lane != 0 || gpus.size() < 2 || !express || head.priority <= TGMPipe.PRIORITY_WORLD);
    }

    private void gpuLoop(int lane) {
        while (!closed) {
            Job job;
            synchronized (jobs) {
                if (!takes(lane, queue.peek())) {
                    try {
                        jobs.wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                job = queue.poll();
            }
            List<TileRequest> waiters;
            synchronized (jobs) {
                job.waiters.removeIf(w -> w.future().isDone());   // cancelled while queued: withdrawn
                if (job.waiters.isEmpty()) {
                    jobs.remove(job.key);
                    continue;
                }
                job.started = true;
            }
            Key k = job.key;
            long t0 = System.nanoTime();
            building.incrementAndGet();
            active();
            try {
                NativeTerrain.Tile t;
                try {
                    t = terrain.tile(lane, k.world, k.seed, k.tileX, k.tileZ, k.lod);
                } finally {
                    building.decrementAndGet();
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                generated.incrementAndGet();
                genMs.addAndGet(ms);
                TerrainTile tile = toTile(k, t.rows(), t.cols(), t.planes(), t.surface());
                synchronized (jobs) {
                    jobs.remove(k);
                    waiters = new ArrayList<>(job.waiters);
                }
                for (TileRequest w : waiters) {
                    served.incrementAndGet();
                    countServed(k, false);
                    w.future().complete(tile);
                }
                TileDiskCache.Entry e = new TileDiskCache.Entry(t.rows(), t.cols(), t.planes(), t.surface());
                io.execute(() -> cache.put(k.world, k.seed, k.tileX, k.tileZ, k.lod, e, Map.of()));
                LOG.fine(() -> "native tile " + k + " built in " + ms + " ms (lane " + lane + ")");
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                LOG.log(Level.WARNING, "native tile " + k + " failed", e);
                synchronized (jobs) {
                    jobs.remove(k);
                    waiters = new ArrayList<>(job.waiters);
                }
                for (TileRequest w : waiters) {
                    w.future().completeExceptionally(new TGMPipeException("native terrain: " + e.getMessage(), e));
                }
            }
            active();
        }
    }

    /** Planes -> TerrainTile (the layout the retired TGMPipe TILE_DATA frame carried). */
    private TerrainTile toTile(Key k, int rows, int cols, short[][] planes, Map<String, byte[]> surface) {
        int n = tileSize.get(k.world);
        int i1 = k.tileX * n, j1 = k.tileZ * n;
        boolean anyTunnel = !allEqual(planes[3], TerrainTile.NO_TUNNEL);
        return new TerrainTile(k.tileX, k.tileZ, i1, j1, i1 + rows, j1 + cols, cols, rows,
                planes[0], planes[1], planes[2],
                anyTunnel ? planes[3] : null,
                anyTunnel ? planes[4] : null,
                allEqual(planes[5], TerrainTile.NO_FLOW) ? null : planes[5],
                allEqual(planes[6], (short) 0) ? null : planes[6],
                surfaceOf(surface));
    }

    static SurfaceChannels surfaceOf(Map<String, byte[]> surface) {
        if (surface == null || surface.isEmpty()) {
            return SurfaceChannels.NONE;
        }
        byte[][] bySlot = new byte[SurfaceChannel.SLOTS][];
        for (Map.Entry<String, byte[]> e : surface.entrySet()) {
            SurfaceChannel c = SurfaceChannel.byWireName(e.getKey());
            if (c != null && !allNoData(e.getValue())) {
                bySlot[c.slot()] = e.getValue();
            }
        }
        return new SurfaceChannels(bySlot);
    }

    private static boolean allNoData(byte[] plane) {
        for (byte b : plane) {
            if (b != (byte) SurfaceChannel.NO_DATA) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqual(short[] plane, short value) {
        for (short v : plane) {
            if (v != value) {
                return false;
            }
        }
        return true;
    }

    private String byLod() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < servedByLod.length(); i++) {
            if (servedByLod.get(i) > 0) {
                b.append(1 << i).append(':').append(servedByLod.get(i)).append('/').append(diskByLod.get(i))
                        .append('/').append(distinctByLod.get(i).size()).append(' ');
            }
        }
        return b.toString().strip();
    }

    /** Dev memLog only: distinct tiles served per lod index. */
    private final java.util.List<java.util.Set<Long>> distinctByLod = java.util.stream.IntStream.range(0, 8)
            .mapToObj(i -> (java.util.Set<Long>) java.util.concurrent.ConcurrentHashMap.<Long>newKeySet()).toList();

    private void countServed(Key k, boolean disk) {
        int i = Math.min(7, Integer.numberOfTrailingZeros(Math.max(1, k.lod)));
        if (MEM_LOG_SECONDS > 0) {
            distinctByLod.get(i).add(((long) k.tileX << 32) ^ (k.tileZ & 0xffffffffL) ^ ((long) k.world << 60));
        }
        servedByLod.incrementAndGet(i);
        if (disk) {
            diskByLod.incrementAndGet(i);
        }
    }

    /** The memory counters of a native stats json, in MiB: host caches, pinned / device pool live + cached. */
    static String memorySummary(String stats) {
        StringBuilder out = new StringBuilder();
        for (String k : List.of("host_cache_bytes", "host_evictions", "pool_host_live", "pool_host_cached", "pool_live",
                "pool_cached", "releases")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + k + "\":(\\d+)").matcher(stats);
            if (m.find()) {
                long v = Long.parseLong(m.group(1));
                boolean count = k.equals("host_evictions") || k.equals("releases");
                out.append(k.replace("_bytes", "")).append('=').append(count ? v : v >> 20).append(count ? " " : "M ");
            }
        }
        return out.toString().strip();
    }

    String statusJson() {
        int depth;
        synchronized (jobs) {
            depth = jobs.size();
        }
        return "{\"backend\":\"native\",\"model_id\":" + quote(modelId) + ",\"lanes\":" + gpus.size()
                + ",\"express_lane\":" + (express && gpus.size() > 1)
                + ",\"queue\":" + depth
                + ",\"served\":" + served.get() + ",\"cache_hits\":" + cacheHits.get()
                + ",\"generated\":" + generated.get() + ",\"failed\":" + failed.get() + ",\"gen_ms\":" + genMs.get()
                + ",\"cache\":" + cache.statsJson() + ",\"native\":" + terrain.stats()
                + ",\"cache_namespaces\":" + namespaces.stream().map(NativeTerrainService::quote).toList() + "}";
    }

    private static String quote(String s) {
        return "\"" + Objects.toString(s, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Requests not finished, for a restart on another backend. */
    List<TileRequest> unfinished() {
        List<TileRequest> out = new ArrayList<>();
        synchronized (jobs) {
            for (Job j : jobs.values()) {
                for (TileRequest w : j.waiters) {
                    if (!w.future().isDone()) {
                        out.add(w);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        idle.shutdown();
        try {
            idle.awaitTermination(30, TimeUnit.SECONDS);   // a release in progress finishes before the pipeline closes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (jobs) {
            jobs.notifyAll();
        }
        gpus.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + 30_000_000_000L;
        for (Thread g : gpus) {
            try {
                g.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (TileRequest r : unfinished()) {
            r.future().completeExceptionally(new TGMPipeException("native terrain pipeline stopped"));
        }
        if (gpus.stream().noneMatch(Thread::isAlive)) {
            terrain.close();
        }
    }
}
