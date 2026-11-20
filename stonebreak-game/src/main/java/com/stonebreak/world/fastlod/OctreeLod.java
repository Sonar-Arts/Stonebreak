package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.world.CubeLod;
import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeStore;
import com.openmason.engine.voxel.world.LodEvents;
import com.openmason.engine.voxel.world.LodMesh;
import com.openmason.engine.voxel.world.LodPolicy;
import com.openmason.engine.voxel.world.LodStats;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.voxel.CubeEvents;
import com.stonebreak.world.voxel.StonebreakBlockTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The native octree LOD ({@link CubeLod}, {@code cv_lod_*}) of the RENDERED world (Phase 5, cubic runtime): nodes
 * downsampled from the real cubes (loaded ones, the integrated server's stored records in singleplayer, synthesized
 * deep rock below generated columns) replace FastLOD's generator heightfield wherever cube data exists; FastLOD only
 * draws the {@code FALLBACK} footprints the octree hands back (never-explored land). GL-free: the GPU side is
 * {@code rendering.gameWorld.fastlod.OctreeLodRenderer}, fed through a {@link NodeSink}.
 *
 * <p><b>Threads.</b> Everything but {@link #close()} and the coverage queries runs on the world's OWNER thread (the
 * client main/GL thread, which also ticks the client stream): listeners note cubes and column readiness inside
 * {@code World.tickStream}, {@link #tick} runs once per frame from the renderer. {@link #close()} may come from
 * another thread (the session teardown releasing the shared cube store), so every native call holds this object's
 * monitor and a closed LOD ignores everything.
 *
 * <p><b>Lifetime.</b> The {@link CubeLod} reads the client world and (singleplayer) the integrated server's
 * {@link CubeStore}; both must outlive it. It closes with the FastLOD manager it is attached to (world switch /
 * cleanup, before the world closes) and through {@link #releaseStore} before the server closes its store.
 */
public final class OctreeLod implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OctreeLod.class);

    /** Highest level built and persisted (level k node = 32 << k blocks; 8 = 8192-block nodes). */
    public static final int MAX_LEVEL = 8;
    /** Bump when the LOD-relevant content rules change (block classes, fill, face layers). */
    private static final long CONTENT_RULES = 1;
    /** Levels an incomplete (unexplored) footprint is refined before it falls back to FastLOD. */
    private static final int INCOMPLETE_DEPTH = 3;
    /** How long a column that just turned UNREADY makes newly shown LOD over it appear solid at once. */
    private static final long SNAP_WINDOW_NANOS = 600_000_000L;
    /** Node cache of the native LOD. */
    private static final long CACHE_BYTES = 192L << 20;

    /** What the renderer does with the node events of a tick (GL thread). */
    public interface NodeSink {
        /**
         * SHOW (replace = false) or UPDATE (replace = true) node (level, x, y, z) with {@code mesh} (valid only
         * during the call). {@code snap}: the node replaces detail that just stopped drawing - appear solid now.
         */
        void show(int level, long x, long y, long z, LodMesh mesh, boolean replace, boolean snap);

        /** HIDE: stop drawing the node (after its replacement had time to fade in). */
        void hide(int level, long x, long y, long z);
    }

    private static final Set<OctreeLod> LIVE = ConcurrentHashMap.newKeySet();

    private final World world;
    private final CubeStore store;
    private final Path storeDir;
    private CubeLod lod;
    private volatile boolean closed;

    /** FALLBACK footprints FastLOD must draw (owner thread). */
    private final FootprintSet fallback = new FootprintSet();
    /** Columns (cube columns cx, cz) that went UNREADY recently -> when. */
    /** Packed cube column ({@link #column}) -> when it went UNREADY. No boxing per lookup (render pass, per node). */
    private final LongKeyMap<Long> recentUnready = new LongKeyMap<>(64);

    private long[] notes = new long[3 * 64];
    private int noteCount;

    private final CubeEvents.InstallListener installListener;
    private final CubeEvents.MeshConsumer meshListener;
    private final CubeEvents.ColumnReadinessListener readinessListener;

    private long viewX = Long.MIN_VALUE, viewY, viewZ;
    private LodPolicy viewPolicy;
    private final long[] viewer = new long[3];

    // Counters (owner thread writes, F3 reads).
    private volatile int lastEventCount;
    private volatile long showsTotal, hidesTotal, fallbackOnTotal, fallbackOffTotal;

    private OctreeLod(World world, CubeStore store, Path storeDir, CubeLod lod) {
        this.world = world;
        this.store = store;
        this.storeDir = storeDir;
        this.lod = lod;
        this.installListener = (w, cube) -> note(cube.cx(), cube.cy(), cube.cz());
        this.meshListener = (w, mesh) -> note(mesh.cx(), mesh.cy(), mesh.cz());
        this.readinessListener = new CubeEvents.ColumnReadinessListener() {
            @Override
            public void onColumnReady(World w, long cx, long cz) {
                setReady(cx, cz, true);
            }

            @Override
            public void onColumnUnready(World w, long cx, long cz) {
                setReady(cx, cz, false);
            }
        };
    }

    /**
     * Creates the octree LOD of a rendered (CLIENT) world, or null when the native LOD is unavailable or refused
     * the store (logged). OWNER THREAD.
     *
     * @param cubeStore the integrated server's cube store (singleplayer / host: one store per directory - the
     *                  server's handle is shared, never reopened), or null (remote client: LOD from received
     *                  cubes only)
     * @param storeDir  where levels >= 1 persist (never the cube store's directory), or null: memory only
     */
    public static OctreeLod create(World world, CubeStore cubeStore, Path storeDir) {
        if (!CubeLod.isAvailable()) {
            LOGGER.warn("Octree LOD unavailable (native library lacks cv_lod); FastLOD draws the far terrain");
            return null;
        }
        try {
            if (storeDir != null) {
                java.nio.file.Files.createDirectories(storeDir);
            }
            CubeLod.Config config = new CubeLod.Config(MAX_LEVEL, 0, 0, BlockType.STONE.getId(), contentVersion(),
                CACHE_BYTES, 2, true);
            CubeLod lod = CubeLod.create(world.voxels(), cubeStore, storeDir, config);
            OctreeLod o = new OctreeLod(world, cubeStore, storeDir, lod);
            o.attach();
            LIVE.add(o);
            LOGGER.info("Octree LOD created: store={} dir={}", cubeStore != null ? "shared server store" : "none",
                storeDir);
            return o;
        } catch (Exception | LinkageError e) {
            LOGGER.error("Octree LOD creation failed (dir {}); FastLOD draws the far terrain", storeDir, e);
            return null;
        }
    }

    /** Version of everything LOD-relevant: block ids/names/flags; a change wipes the persisted levels. */
    static long contentVersion() {
        long h = CONTENT_RULES * 0x9E3779B97F4A7C15L;
        for (BlockType t : BlockType.values()) {
            h = h * 31 + t.name().hashCode();
            h = h * 31 + t.getId();
        }
        for (short f : StonebreakBlockTable.flags()) {
            h = h * 31 + f;
        }
        return h & Long.MAX_VALUE;
    }

    private void attach() {
        world.addInstallListener(installListener);
        world.addMeshConsumer(meshListener);
        world.addColumnReadinessListener(readinessListener);
        // Columns that turned READY before the LOD existed.
        java.util.HashSet<CubePos> seen = new java.util.HashSet<>();
        for (Cube cube : world.getLoadedCubes()) {
            CubePos col = new CubePos(cube.cx(), 0, cube.cz());
            if (seen.add(col) && world.isColumnReady(cube.cx(), cube.cz())) {
                lod.setColumnReady(cube.cx(), cube.cz(), true);
            }
        }
    }

    public World world() {
        return world;
    }

    public CubeStore cubeStore() {
        return store;
    }

    public Path storeDir() {
        return storeDir;
    }

    public boolean isClosed() {
        return closed;
    }

    private synchronized void note(long cx, long cy, long cz) {
        if (closed) {
            return;
        }
        if (noteCount + 3 > notes.length) {
            notes = java.util.Arrays.copyOf(notes, notes.length * 2);
        }
        notes[noteCount++] = cx;
        notes[noteCount++] = cy;
        notes[noteCount++] = cz;
    }

    private synchronized void setReady(long cx, long cz, boolean ready) {
        if (closed) {
            return;
        }
        if (!ready) {
            recentUnready.put(column(cx, cz), System.nanoTime());
        }
        try {
            lod.setColumnReady(cx, cz, ready);
        } catch (RuntimeException e) {
            LOGGER.error("cv_lod_set_column_ready({}, {}) failed", cx, cz, e);
        }
    }

    /**
     * OWNER THREAD, once per frame after the stream tick: notes the cubes seen since the last tick, updates the
     * view when the viewer block or the policy changed, ticks the LOD and applies every event (meshes to
     * {@code sink}, FALLBACK footprints to the coverage set FastLOD reads).
     *
     * @param policy null = draw nothing (LOD switched off)
     */
    public synchronized void tick(double vx, double vy, double vz, LodPolicy policy, long maxMeshBytes,
                                  NodeSink sink) {
        if (closed) {
            return;
        }
        long now = System.nanoTime();
        if (!recentUnready.isEmpty()) {
            recentUnready.removeValuesIf(t -> now - t > SNAP_WINDOW_NANOS);
        }
        if (noteCount > 0) {
            lod.noteCubes(java.util.Arrays.copyOf(notes, noteCount));
            noteCount = 0;
        }
        long bx = (long) Math.floor(vx), by = (long) Math.floor(vy), bz = (long) Math.floor(vz);
        if (bx != viewX || by != viewY || bz != viewZ || !java.util.Objects.equals(policy, viewPolicy)) {
            viewX = bx;
            viewY = by;
            viewZ = bz;
            viewPolicy = policy;
            if (policy == null) {
                lod.setView(new long[0], new LodPolicy(48, 0, 0, 0, -1));
            } else {
                viewer[0] = bx;
                viewer[1] = by;
                viewer[2] = bz;
                lod.setView(viewer, policy);
            }
        }
        LodEvents events = lod.tick(maxMeshBytes);
        int n = events.count();
        lastEventCount = n;
        for (int i = 0; i < n; i++) {
            int type = events.type(i);
            int level = events.level(i);
            long x = events.x(i), y = events.y(i), z = events.z(i);
            try {
                switch (type) {
                    case CubeLod.EV_SHOW, CubeLod.EV_UPDATE -> {
                        LodMesh mesh = lod.mesh(events.mesh(i));
                        boolean snap = type == CubeLod.EV_SHOW && touchesRecentUnready(level, x, z);
                        sink.show(level, x, y, z, mesh, type == CubeLod.EV_UPDATE, snap);
                        showsTotal++;
                    }
                    case CubeLod.EV_HIDE -> {
                        sink.hide(level, x, y, z);
                        hidesTotal++;
                    }
                    case CubeLod.EV_FALLBACK_ON -> {
                        fallback.add(level, x, z);
                        fallbackOnTotal++;
                    }
                    case CubeLod.EV_FALLBACK_OFF -> {
                        fallback.remove(level, x, z);
                        fallbackOffTotal++;
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                LOGGER.error("Octree LOD event {} (L{} {}, {}, {}) failed", type, level, x, y, z, e);
            }
        }
    }

    /** True when node/footprint (level, x, z) holds a column that went UNREADY within the snap window. */
    private boolean touchesRecentUnready(int level, long x, long z) {
        if (recentUnready.isEmpty()) {
            return false;
        }
        return recentUnready.anyKey(k -> (columnX(k) >> level) == x && (columnZ(k) >> level) == z);
    }

    private static long column(long cx, long cz) {
        return (cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long columnX(long k) {
        return k >> 32;
    }

    private static long columnZ(long k) {
        return (int) k;
    }

    /**
     * OWNER THREAD. Whether FastLOD should draw the 16-block column (chunkX16, chunkZ16): it lies in a FALLBACK
     * footprint.
     */
    /** OWNER THREAD. Stamp of the FALLBACK footprint set ({@link FootprintSet#stamp}). */
    public long fallbackStamp() {
        return fallback.stamp();
    }

    public boolean fallbackCovers(int chunkX16, int chunkZ16) {
        return fallback.covers((long) chunkX16 << 4, (long) chunkZ16 << 4);
    }

    /** OWNER THREAD. The cube column under a 16-block column turned UNREADY just now (show it solid at once). */
    public boolean justUnready(int chunkX16, int chunkZ16) {
        return !recentUnready.isEmpty() && recentUnready.get(column(chunkX16 >> 1, chunkZ16 >> 1)) != null;
    }

    public int fallbackFootprints() {
        return fallback.size();
    }

    public int lastEventCount() {
        return lastEventCount;
    }

    public long showsTotal() {
        return showsTotal;
    }

    public long hidesTotal() {
        return hidesTotal;
    }

    /** Native counters, or null once closed. */
    public synchronized LodStats stats() {
        return closed ? null : lod.stats();
    }

    /** The band policy for a FastLOD configuration: same bands, octree radius = the FastLOD ring's outer edge. */
    public static LodPolicy policy(int blocksPerCell, int radiusBlocks, int renderRadiusBlocks) {
        int k = Math.max(46, blocksPerCell);
        int maxLevel = 0;
        while (maxLevel < MAX_LEVEL && (long) (1 << (maxLevel + 1)) * k <= radiusBlocks) {
            maxLevel++;
        }
        int standby = Math.max(0, renderRadiusBlocks - 64);
        return new LodPolicy(k, maxLevel, radiusBlocks, INCOMPLETE_DEPTH, standby);
    }

    /**
     * Closes every live octree LOD reading {@code store} (the session closes the integrated server's world, and
     * with it the store, after this). Any thread.
     */
    public static void releaseStore(CubeStore store) {
        if (store == null) {
            return;
        }
        List<OctreeLod> users = new ArrayList<>();
        for (OctreeLod o : LIVE) {
            if (o.store == store) {
                users.add(o);
            }
        }
        for (OctreeLod o : users) {
            o.close();
        }
    }

    /** Waits for running jobs, commits the persisted levels, frees the LOD. Any thread; idempotent. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            LIVE.remove(this);
            try {
                world.removeInstallListener(installListener);
                world.removeMeshConsumer(meshListener);
                world.removeColumnReadinessListener(readinessListener);
            } catch (RuntimeException ignored) {
                // world already torn down
            }
            try {
                lod.close();
            } catch (RuntimeException e) {
                LOGGER.error("Octree LOD close failed", e);
            }
            lod = null;
            fallback.clear();
            recentUnready.clear();
        }
        LOGGER.info("Octree LOD closed (dir {})", storeDir);
    }

    /** Test seam: the native LOD (null once closed). */
    CubeLod nativeLod() {
        return lod;
    }

    /** Test seam: drops expired entries of the snap window. */
    void expireUnready() {
        recentUnready.clear();
    }
}
