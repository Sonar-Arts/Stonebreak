package com.stonebreak.network.server.handlers;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.network.packet.world.BlockStateNamesS2C;
import com.stonebreak.network.packet.world.CubeDataS2C;
import com.stonebreak.network.server.PlayerCubeView;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.voxel.CubeEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streams cube terrain to every player's REMOTE client stream (cubic voxel runtime, #447). Server tick thread.
 *
 * <p><b>What a client gets.</b> The client's own stream decides what it wants ({@code CubeStream.missing()}) and
 * asks ({@code CubeRequestC2S}); the server's stream has a center per player with the same radii
 * ({@code ServerLevel.updateStreamCenters}), so it generates/loads the same cubes. A requested cube goes out
 * once the server has installed it and it has SETTLED (its column READY = every band cube installed and
 * complete, or installed for {@link #SETTLE_TICKS}); a cube the server knows is air (at or above its column's TOP) is answered by the
 * column record alone; anything else waits ({@link #REQUEST_TTL_TICKS}, then the client asks again).
 * Every cube is preceded by its COLUMN RECORD ({@code encodeColumn}) the first time and whenever that record
 * changed — a REMOTE world has no generator surface callback, so the record is what tells the client where
 * known air and the surface band are. Block-state registry names go out before any cube or echo that could
 * use them ({@link #syncStateNames}).
 *
 * <p><b>Staying in sync.</b> Edits replicate as per-block deltas ({@code ServerBlockHandler}); they advance the
 * per-player sent revision ({@link #onReplicatedEdit} → {@link #afterDeltasFlushed}). A held cube whose server
 * revision moved on WITHOUT a replicated edit (population into a cube already sent, tree growth through
 * {@code setBlockRaw}) is re-sent: checked on its CV_EV_CHANGED event ({@link CubeEvents.ChangeListener}; the
 * server stream is {@code NO_MESH}, so there are no REMESHED events) and by a slow per-player sweep (backstop for
 * layer/blob-only writes, which surface as CHANGED only with a later dirtying write). The client's
 * periodic hash audit ({@code CubeHashesC2S}, CRC32C of the network encode) catches anything else.
 *
 * <p><b>Pacing</b> (after Minecraft's chunk batching, re-sized for cubes): the client reports its install
 * rate in cubes per tick ({@code CubeAckC2S}); the server sends at that rate with a window of unacknowledged
 * cubes AND bytes (a uniform cube is ~30 bytes, a surface cube tens of KiB), within
 * {@link #STREAM_BUDGET_NANOS} of encoding per tick for all players. Batches ({@code CubeDataS2C}) carry up
 * to {@link #BATCH_RAW_BYTES}; over TCP each batch is compressed once.
 */
public final class ServerChunkHandler {

    /** Ticks a freshly installed cube waits for population before it is sent anyway (the revision checks
     *  re-send it if population changes it later). */
    static final int SETTLE_TICKS = 10;
    /** Ticks an unserved request stays pending (the client re-asks every few seconds while it wants it). */
    static final int REQUEST_TTL_TICKS = 200;
    /** A request for a cube sent less than this long ago is assumed to be crossing the cube on the wire. */
    static final int RESEND_GUARD_TICKS = 200;
    /** A known-air request re-sends its column record at most this often (the client may have dropped it). */
    static final int COLUMN_REFRESH_TICKS = 40;
    /** Ticks after a replicated edit during which the hash audit ignores the cube (deltas may be in flight). */
    static final int AUDIT_GRACE_TICKS = 240;
    /** Ticks after a send during which the audit ignores the cube (the client may hash an older copy). */
    static final int SENT_AUDIT_GRACE_TICKS = 40;
    /** Requests examined per player per tick (unserved ones stay in place). */
    static final int MAX_REQUEST_SCAN = 1024;
    /** Held cubes revision-checked per player per tick by the sweep. */
    static final int SWEEP_PER_TICK = 64;
    /** Raw bytes / cubes per {@link CubeDataS2C} batch. */
    static final int BATCH_RAW_BYTES = 256 * 1024;
    static final int BATCH_MAX_CUBES = 512;
    /** Encoding time all players' sends may take per tick. */
    static final long STREAM_BUDGET_NANOS = 8_000_000L;
    /** Cubes encoded once and shared by every viewer (LRU). */
    static final int ENCODE_CACHE = 2048;

    // Flow control. Rates until the first ack, and the most a client may ask for (cubes per tick).
    static final float LOCAL_DEFAULT_RATE = 256f;
    static final float REMOTE_DEFAULT_RATE = 32f;
    static final float LOCAL_MAX_RATE = 4096f;
    static final float REMOTE_MAX_RATE = 512f;
    static final float MIN_RATE = 1f;
    /** Unacknowledged cubes / raw bytes allowed in flight. */
    static final int LOCAL_WINDOW_CUBES = 16384;
    static final int REMOTE_WINDOW_CUBES = 2048;
    static final long LOCAL_WINDOW_BYTES = 256L << 20;
    static final long REMOTE_WINDOW_BYTES = 8L << 20;
    /** Cubes in flight with no ack for this long (5 s): reopen the window. */
    static final int ACK_WATCHDOG_TICKS = 100;
    /** Column records further than this (in cubes, beyond the view radius) are forgotten per player. */
    static final int COLUMN_FORGET_MARGIN = 4;

    /** Test seam: the revision sweep off proves that CHANGED events alone drive re-sends. */
    private static volatile boolean revisionSweep = true;

    private World world;
    private int tick;

    // ── server world events ──
    private final Map<CubePos, Integer> installedTick = new HashMap<>();
    /** Cubes whose content changed this tick (CV_EV_CHANGED: population, unreplicated writes, edits). */
    private final Set<CubePos> changedThisTick = new LinkedHashSet<>();

    // ── columns ──
    private record ColumnEntry(byte[] bytes, int version) {}
    private final Map<CubePos, ColumnEntry> columns = new HashMap<>();
    private final Set<CubePos> dirtyColumns = new HashSet<>();
    private int columnVersionSeq;

    // ── edits ──
    private final Set<CubePos> editedThisTick = new LinkedHashSet<>();
    private final Map<CubePos, Integer> lastEditTick = new HashMap<>();

    // ── caches ──
    private record Encoded(long revision, byte[] bytes) {}
    private final LinkedHashMap<CubePos, Encoded> encodeCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CubePos, Encoded> eldest) {
            return size() > ENCODE_CACHE;
        }
    };
    private record Hash(long revision, int hash) {}
    private final Map<CubePos, Hash> hashCache = new HashMap<>();

    private ServerWorldContext lastCtx;

    /** Join timing (#345): one log line per player when their first view completes. */
    private final ChunkJoinProbe joinProbe = new ChunkJoinProbe();
    private long lastChunkStepNs;

    // ─── lifecycle ──────────────────────────────────────────────────────────────

    /** TESTS ONLY: turns the slow revision sweep off (true restores it). JVM-wide. */
    public static void setRevisionSweepForTests(boolean on) {
        revisionSweep = on;
    }

    public void onSessionStart() {
        tick = 0;
        installedTick.clear();
        changedThisTick.clear();
        columns.clear();
        dirtyColumns.clear();
        editedThisTick.clear();
        lastEditTick.clear();
        encodeCache.clear();
        hashCache.clear();
    }

    private final CubeEvents.InstallListener installListener = (w, cube) -> onInstalled(cube);
    private final CubeEvents.UnloadListener unloadListener = (w, cube) -> onUnloaded(cube.pos());
    private final CubeEvents.ChangeListener changeListener = (w, cube) -> onChanged(cube.pos());

    /** Attaches to the authoritative world's stream events (once per session). */
    public void bind(World world) {
        if (this.world != null) {
            this.world.removeInstallListener(installListener);
            this.world.removeUnloadListener(unloadListener);
            this.world.removeChangeListener(changeListener);
        }
        this.world = world;
        if (world != null) {
            world.addInstallListener(installListener);
            world.addUnloadListener(unloadListener);
            world.addChangeListener(changeListener);
        }
    }

    private void onInstalled(Cube cube) {
        CubePos pos = cube.pos();
        installedTick.put(pos, tick);
        dirtyColumns.add(columnOf(pos));
        encodeCache.remove(pos);
        hashCache.remove(pos);
        // A cube a player already holds came back (store reload / regeneration): same content, new revision
        // baseline.
        ServerWorldContext ctx = lastCtx;
        if (ctx != null && world != null) {
            long rev = world.voxels().cubeRevision(pos.x(), pos.y(), pos.z());
            for (ServerPlayer sp : ctx.players()) {
                PlayerCubeView.Sent s = sp.cubeView().sent.get(pos);
                if (s != null) {
                    s.revision = rev;
                }
            }
        }
    }

    private void onUnloaded(CubePos pos) {
        installedTick.remove(pos);
        changedThisTick.remove(pos);
        encodeCache.remove(pos);
        hashCache.remove(pos);
        lastEditTick.remove(pos);
    }

    private void onChanged(CubePos pos) {
        changedThisTick.add(pos);
        dirtyColumns.add(columnOf(pos));
    }

    // ─── replicated edits (ServerBlockHandler) ──────────────────────────────────

    /**
     * A change at block (x, y, z) is going to clients as a per-block delta this tick: the cube's per-player
     * sent revision follows it ({@link #afterDeltasFlushed}) instead of re-sending the cube, and the audit
     * leaves the cube alone for a while.
     */
    public void onReplicatedEdit(long x, long y, long z) {
        CubePos pos = new CubePos(x >> 5, y >> 5, z >> 5);
        editedThisTick.add(pos);
        lastEditTick.put(pos, tick);
        dirtyColumns.add(columnOf(pos));
    }

    /** After the block handler sent this tick's deltas: holders now have the edited cubes' current content. */
    public void afterDeltasFlushed(ServerWorldContext ctx) {
        if (editedThisTick.isEmpty()) {
            return;
        }
        World w = ctx.world();
        if (w != null && w.voxels() != null) {
            for (CubePos pos : editedThisTick) {
                long rev = w.voxels().cubeRevision(pos.x(), pos.y(), pos.z());
                for (ServerPlayer sp : ctx.players()) {
                    PlayerCubeView.Sent s = sp.cubeView().sent.get(pos);
                    if (s != null) {
                        s.revision = rev;
                    }
                }
            }
        }
        editedThisTick.clear();
    }

    /**
     * The cube of block (x, y, z) changed in a way per-block deltas will not carry (the sim-edit overflow
     * guard): every holder gets the whole cube again.
     */
    public void markCubeModified(long x, long y, long z) {
        CubePos pos = new CubePos(x >> 5, y >> 5, z >> 5);
        ServerWorldContext ctx = lastCtx;
        if (ctx == null) {
            return;
        }
        for (ServerPlayer sp : ctx.players()) {
            if (sp.cubeView().holds(pos)) {
                sp.cubeView().queueResend(pos);
            }
        }
    }

    /** True when {@code sp}'s client holds the cube of block (x, y, z) (block deltas go only to holders). */
    public static boolean holdsBlock(ServerPlayer sp, long x, long y, long z) {
        return sp.cubeView().holds(new CubePos(x >> 5, y >> 5, z >> 5));
    }

    // ─── inbound (client) ───────────────────────────────────────────────────────

    /** C2S CubeRequestC2S. */
    public void handleCubeRequests(ServerPlayer sp, long[] xyz) {
        PlayerCubeView v = sp.cubeView();
        long now = System.nanoTime();
        for (int i = 0; i + 2 < xyz.length; i += 3) {
            CubePos pos = new CubePos(xyz[i], xyz[i + 1], xyz[i + 2]);
            if (!cubeInBounds(pos.y()) || !inRange(sp, pos)) {
                continue;
            }
            PlayerCubeView.Sent s = v.sent.get(pos);
            if (s != null) {
                if (tick - s.tick < RESEND_GUARD_TICKS) {
                    continue; // probably crossing on the wire / in the client's install queue
                }
                // The client lost a cube we sent: a resync, budgeted like one.
                if (!sp.allowResync()) {
                    continue;
                }
                v.sent.remove(pos);
            } else if (!v.requested.containsKey(pos) && !sp.allowCubeRequest(now)) {
                return; // over budget: the client asks again
            }
            v.addRequest(pos, tick);
        }
    }

    /** C2S CubeUnloadC2S: the client dropped these cubes. */
    public void handleCubesUnloaded(ServerPlayer sp, long[] xyz) {
        PlayerCubeView v = sp.cubeView();
        for (int i = 0; i + 2 < xyz.length; i += 3) {
            v.forget(new CubePos(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
    }

    /** C2S CubeAckC2S. */
    public void handleCubeAck(ServerPlayer sp, int cubes, int kib, float desiredPerTick) {
        float max = sp.isLocal() ? LOCAL_MAX_RATE : REMOTE_MAX_RATE;
        sp.cubeView().onAck(cubes, kib, Math.max(MIN_RATE, Math.min(max, desiredPerTick)));
    }

    /**
     * C2S CubeHashesC2S: the client's CRC32C of its network encode per cube; a held cube that differs (and was
     * not edited recently) is re-sent, spending a resync permit (fake hashes cannot buy endless re-sends).
     */
    public void handleCubeHashes(ServerPlayer sp, long[] xyz, int[] hashes, ServerWorldContext ctx) {
        World w = ctx.world();
        if (w == null) {
            return;
        }
        PlayerCubeView v = sp.cubeView();
        for (int i = 0; i < hashes.length; i++) {
            CubePos pos = new CubePos(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
            PlayerCubeView.Sent s = v.sent.get(pos);
            if (s == null || tick - s.tick < SENT_AUDIT_GRACE_TICKS
                    || tick - lastEditTick.getOrDefault(pos, Integer.MIN_VALUE / 2) < AUDIT_GRACE_TICKS) {
                continue;
            }
            Integer server = hashOf(w, pos);
            if (server == null || server == hashes[i]) {
                continue;
            }
            if (sp.allowResync()) {
                v.queueResend(pos);
            }
        }
    }

    /** CRC32C of the cube's network encode (the client computes the same), cached per revision. */
    Integer hashOf(World w, CubePos pos) {
        CubeWorld voxels = w.voxels();
        long rev = voxels.cubeRevision(pos.x(), pos.y(), pos.z());
        Hash h = hashCache.get(pos);
        if (h != null && h.revision() == rev) {
            return h.hash();
        }
        byte[] bytes = voxels.encode(pos.x(), pos.y(), pos.z(), CubeWorld.PARTS_NETWORK);
        if (bytes == null) {
            return null;
        }
        int hash = crc32c(bytes);
        hashCache.put(pos, new Hash(rev, hash));
        return hash;
    }

    /** The audit hash of network-encoded cube bytes (shared with the client). */
    public static int crc32c(byte[] bytes) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    // ─── block-state registry mirroring ─────────────────────────────────────────

    /**
     * Sends {@code sp} every block-state registry name registered since its last sync (the whole table on
     * join). Call before anything that can carry a state id or string: cube batches, block-state echoes.
     */
    public static void syncStateNames(ServerPlayer sp, World w) {
        if (w == null) {
            return;
        }
        PlayerCubeView v = sp.cubeView();
        com.stonebreak.world.voxel.BlockStateRegistry registry = w.blockStates();
        if (registry == null || registry.size() <= v.stateNamesSent) {
            return;
        }
        List<String> names = registry.names();
        for (int from = v.stateNamesSent; from < names.size(); from += BlockStateNamesS2C.MAX_NAMES) {
            int to = Math.min(names.size(), from + BlockStateNamesS2C.MAX_NAMES);
            sp.send(new BlockStateNamesS2C(from + 1, names.subList(from, to)), false);
        }
        v.stateNamesSent = names.size();
    }

    // ─── tick ───────────────────────────────────────────────────────────────────

    /** End of a server tick ({@code tickNs} = the whole replication tick): feeds the join probe. */
    public void onTickEnded(long tickNs, java.util.Collection<ServerPlayer> players) {
        joinProbe.tickEnded(tickNs, lastChunkStepNs, players);
    }

    public void tick(ServerWorldContext ctx) {
        long stepStart = System.nanoTime();
        try {
            streamTick(ctx);
        } finally {
            lastChunkStepNs = System.nanoTime() - stepStart;
        }
    }

    private void streamTick(ServerWorldContext ctx) {
        tick++;
        lastCtx = ctx;
        World w = ctx.world();
        if (w == null) {
            return;
        }
        if (w != world) {
            bind(w);
        }
        refreshDirtyColumns(ctx, w);
        checkChanged(ctx, w);
        long deadline = System.nanoTime() + STREAM_BUDGET_NANOS;
        for (ServerPlayer sp : ctx.players()) {
            PlayerCubeView v = sp.cubeView();
            refillQuota(sp, v);
            if (!sp.hasReportedPosition()) {
                continue;
            }
            joinProbe.streamable(sp);
            syncStateNames(sp, w);
            serve(sp, v, w, deadline);
            if (v.requested.isEmpty() && v.cubesSentTotal() > 0 && v.inFlightCubes() == 0) {
                joinProbe.viewComplete(sp);
            }
            if ((tick & 127) == 0) {
                forgetFarColumns(sp, v);
            }
        }
        if ((tick & 127) == 0) {
            pruneColumnCache(ctx);
        }
    }

    private static void refillQuota(ServerPlayer sp, PlayerCubeView v) {
        float rate = v.rate();
        if (Float.isNaN(rate)) {
            rate = sp.isLocal() ? LOCAL_DEFAULT_RATE : REMOTE_DEFAULT_RATE;
        }
        v.setQuota(Math.min(v.quota() + rate, Math.max(1f, rate)));
        if (v.ackOverdue(ACK_WATCHDOG_TICKS)) {
            System.err.println("[SERVER-CUBES] No cube ack from player " + sp.playerId() + " for "
                + ACK_WATCHDOG_TICKS + " ticks - reopening its stream window.");
        }
    }

    private static boolean canSend(ServerPlayer sp, PlayerCubeView v) {
        boolean local = sp.isLocal();
        return v.quota() >= 1f
            && v.inFlightCubes() < (local ? LOCAL_WINDOW_CUBES : REMOTE_WINDOW_CUBES)
            && v.inFlightBytes() < (local ? LOCAL_WINDOW_BYTES : REMOTE_WINDOW_BYTES);
    }

    /** Re-encodes dirty columns that players hold; a changed record goes out again to every holder. */
    private void refreshDirtyColumns(ServerWorldContext ctx, World w) {
        if (dirtyColumns.isEmpty()) {
            return;
        }
        for (CubePos col : dirtyColumns) {
            ColumnEntry old = columns.get(col);
            if (old == null) {
                continue; // nobody was sent this column since it was last cached: encoded fresh on demand
            }
            byte[] bytes = w.voxels().encodeColumn(col.x(), col.z());
            if (bytes == null) {
                columns.remove(col);
                continue;
            }
            if (Arrays.equals(bytes, old.bytes())) {
                continue;
            }
            ColumnEntry fresh = new ColumnEntry(bytes, ++columnVersionSeq);
            columns.put(col, fresh);
            for (ServerPlayer sp : ctx.players()) {
                if (sp.cubeView().sentColumns.containsKey(col)) {
                    sp.cubeView().columnResend.add(col);
                }
            }
        }
        dirtyColumns.clear();
    }

    /** CHANGED cubes a player holds at an older revision (population, unreplicated writes): re-send. */
    private void checkChanged(ServerWorldContext ctx, World w) {
        if (changedThisTick.isEmpty()) {
            return;
        }
        for (CubePos pos : changedThisTick) {
            long rev = -1;
            for (ServerPlayer sp : ctx.players()) {
                PlayerCubeView v = sp.cubeView();
                PlayerCubeView.Sent s = v.sent.get(pos);
                if (s == null) {
                    continue;
                }
                if (rev < 0) {
                    rev = w.voxels().cubeRevision(pos.x(), pos.y(), pos.z());
                }
                if (s.revision != rev) {
                    v.queueResend(pos);
                }
            }
        }
        changedThisTick.clear();
    }

    /** The slow half of revision tracking: a few held cubes per tick (changes no CHANGED event reported yet). */
    private void sweep(PlayerCubeView v, World w) {
        if (v.sent.isEmpty() || !revisionSweep) {
            return;
        }
        if (v.sweepIndex >= v.sweep.length) {
            v.sweep = v.sent.keySet().toArray(new CubePos[0]);
            v.sweepIndex = 0;
        }
        int end = Math.min(v.sweep.length, v.sweepIndex + SWEEP_PER_TICK);
        for (; v.sweepIndex < end; v.sweepIndex++) {
            CubePos pos = v.sweep[v.sweepIndex];
            PlayerCubeView.Sent s = v.sent.get(pos);
            if (s == null || editedThisTick.contains(pos) || !w.hasCube(pos.x(), pos.y(), pos.z())) {
                continue;
            }
            if (w.voxels().cubeRevision(pos.x(), pos.y(), pos.z()) != s.revision) {
                v.queueResend(pos);
            }
        }
    }

    /** A batch being filled for one player. */
    private final class Batch {
        final ServerPlayer sp;
        final List<CubeDataS2C.Column> cols = new ArrayList<>();
        final List<CubeDataS2C.CubeRecord> cubes = new ArrayList<>();
        int bytes;

        Batch(ServerPlayer sp) {
            this.sp = sp;
        }

        void flushIfFull() {
            if (bytes >= BATCH_RAW_BYTES || cubes.size() >= BATCH_MAX_CUBES) {
                flush();
            }
        }

        void flush() {
            if (cols.isEmpty() && cubes.isEmpty()) {
                return;
            }
            sp.send(new CubeDataS2C(cols, cubes), false);
            cols.clear();
            cubes.clear();
            bytes = 0;
        }
    }

    private void serve(ServerPlayer sp, PlayerCubeView v, World w, long deadline) {
        Batch batch = new Batch(sp);
        try {
            // 1. Column records that changed while held.
            if (!v.columnResend.isEmpty()) {
                for (Iterator<CubePos> it = v.columnResend.iterator(); it.hasNext(); ) {
                    CubePos col = it.next();
                    it.remove();
                    if (v.sentColumns.containsKey(col)) {
                        ensureColumn(batch, v, w, col, false);
                    }
                }
            }
            // 2. Revision sweep, then re-sends.
            sweep(v, w);
            while (!v.resend.isEmpty() && canSend(sp, v) && System.nanoTime() < deadline) {
                CubePos pos = v.pollResend();
                Cube cube = v.holds(pos) ? w.getCube(pos.x(), pos.y(), pos.z()) : null;
                if (cube != null) {
                    addCube(batch, v, w, cube);
                }
            }
            // 3. Requests, oldest (= nearest at request time) first.
            int scanned = 0;
            for (Iterator<Map.Entry<CubePos, Integer>> it = v.requested.entrySet().iterator();
                 it.hasNext() && scanned < MAX_REQUEST_SCAN && canSend(sp, v) && System.nanoTime() < deadline; ) {
                scanned++;
                Map.Entry<CubePos, Integer> e = it.next();
                CubePos pos = e.getKey();
                if (!inRange(sp, pos)) {
                    it.remove();
                    continue;
                }
                Cube cube = w.getCube(pos.x(), pos.y(), pos.z());
                if (cube != null) {
                    if (!settled(w, pos)) {
                        continue;
                    }
                    it.remove();
                    addCube(batch, v, w, cube);
                    continue;
                }
                if (knownAir(w, pos)) {
                    // The column record answers it (the client then sees the cube as known air).
                    if (ensureColumn(batch, v, w, columnOf(pos), true)) {
                        it.remove();
                        continue;
                    }
                }
                if (tick - e.getValue() > REQUEST_TTL_TICKS) {
                    it.remove();
                }
            }
        } finally {
            batch.flush();
        }
    }

    private boolean settled(World w, CubePos pos) {
        Integer installed = installedTick.get(pos);
        return installed == null || tick - installed >= SETTLE_TICKS || w.isColumnReady(pos.x(), pos.z());
    }

    private static boolean knownAir(World w, CubePos pos) {
        CubeWorld.TopRange r = w.voxels().topRange(pos.x(), pos.z());
        return r.known() && pos.minBlockY() >= r.topMax();
    }

    /**
     * Puts column {@code col}'s record in the batch when this player lacks its current version (or, with
     * {@code refresh}, when it went out more than {@link #COLUMN_REFRESH_TICKS} ago). False when the server
     * has no such column.
     */
    private boolean ensureColumn(Batch batch, PlayerCubeView v, World w, CubePos col, boolean refresh) {
        ColumnEntry entry = columns.get(col);
        if (entry == null) {
            byte[] bytes = w.voxels().encodeColumn(col.x(), col.z());
            if (bytes == null) {
                return false;
            }
            entry = new ColumnEntry(bytes, ++columnVersionSeq);
            columns.put(col, entry);
        }
        int[] held = v.sentColumns.get(col);
        if (held != null && held[0] == entry.version() && !(refresh && tick - held[1] > COLUMN_REFRESH_TICKS)) {
            return true;
        }
        batch.cols.add(new CubeDataS2C.Column(col.x(), col.z(), entry.bytes()));
        batch.bytes += entry.bytes().length + 24;
        v.sentColumns.put(col, new int[]{entry.version(), tick});
        return true;
    }

    private void addCube(Batch batch, PlayerCubeView v, World w, Cube cube) {
        CubePos pos = cube.pos();
        CubeWorld voxels = w.voxels();
        ensureColumn(batch, v, w, columnOf(pos), false);
        long rev = voxels.cubeRevision(pos.x(), pos.y(), pos.z()); // before the encode: a racing change re-sends
        Encoded enc = encodeCache.get(pos);
        if (enc == null || enc.revision() != rev) {
            byte[] bytes = voxels.encode(pos.x(), pos.y(), pos.z(), CubeWorld.PARTS_NETWORK);
            if (bytes == null) {
                return;
            }
            enc = new Encoded(rev, bytes);
            encodeCache.put(pos, enc);
        }
        Map<Integer, String> states = cube.payloadStates();
        int[] cells = new int[states.size()];
        String[] values = new String[states.size()];
        int k = 0;
        for (Map.Entry<Integer, String> e : states.entrySet()) {
            if (k == cells.length) {
                break; // concurrently grown; the rest goes with the next re-send
            }
            cells[k] = e.getKey();
            values[k] = e.getValue();
            k++;
        }
        if (k < cells.length) {
            cells = Arrays.copyOf(cells, k);
            values = Arrays.copyOf(values, k);
        }
        CubeDataS2C.CubeRecord record = new CubeDataS2C.CubeRecord(pos.x(), pos.y(), pos.z(), enc.bytes(), cells, values);
        batch.cubes.add(record);
        int size = record.payloadBytes();
        batch.bytes += size;
        v.markSent(pos, enc.revision(), tick, size);
        joinProbe.streamed(batch.sp);
        batch.flushIfFull();
    }

    private static boolean cubeInBounds(long cy) {
        return cy >= WorldConfiguration.MIN_CUBE_Y && cy < WorldConfiguration.MAX_CUBE_Y;
    }

    /** Horizontal reach of a player's requests: its view radius plus a margin (in cubes). */
    static boolean inRange(ServerPlayer sp, CubePos pos) {
        if (!cubeInBounds(pos.y())) {
            return false;
        }
        long pcx = (long) Math.floor(sp.x()) >> 5;
        long pcz = (long) Math.floor(sp.z()) >> 5;
        long r = ((long) sp.viewDistanceChunks() * 16L >> 5) + 3;
        long dx = pos.x() - pcx;
        long dz = pos.z() - pcz;
        if (Math.abs(dx) > r || Math.abs(dz) > r) {
            return false; // also keeps the squares below from overflowing far away
        }
        return dx * dx + dz * dz <= r * r;
    }

    private void forgetFarColumns(ServerPlayer sp, PlayerCubeView v) {
        long pcx = (long) Math.floor(sp.x()) >> 5;
        long pcz = (long) Math.floor(sp.z()) >> 5;
        long r = ((long) sp.viewDistanceChunks() * 16L >> 5) + COLUMN_FORGET_MARGIN;
        v.sentColumns.keySet().removeIf(c -> {
            long dx = c.x() - pcx;
            long dz = c.z() - pcz;
            return Math.abs(dx) > r || Math.abs(dz) > r || dx * dx + dz * dz > r * r;
        });
    }

    /** Drops cached column records nobody holds (they are re-encoded on demand). */
    private void pruneColumnCache(ServerWorldContext ctx) {
        Set<CubePos> held = new HashSet<>();
        for (ServerPlayer sp : ctx.players()) {
            held.addAll(sp.cubeView().sentColumns.keySet());
        }
        columns.keySet().retainAll(held);
        lastEditTick.values().removeIf(t -> tick - t > AUDIT_GRACE_TICKS);
    }

    static CubePos columnOf(CubePos pos) {
        return new CubePos(pos.x(), 0, pos.z());
    }

    /** Server tick counter (tests). */
    int tickCount() {
        return tick;
    }
}
