package com.stonebreak.network.client;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeStream;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.network.packet.world.BlockStateNamesS2C;
import com.stonebreak.network.packet.world.CubeAckC2S;
import com.stonebreak.network.packet.world.CubeDataS2C;
import com.stonebreak.network.packet.world.CubeHashesC2S;
import com.stonebreak.network.packet.world.CubeRequestC2S;
import com.stonebreak.network.packet.world.CubeUnloadC2S;
import com.stonebreak.network.server.handlers.ServerChunkHandler;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.voxel.BlockStateRegistry;
import com.stonebreak.world.voxel.CubeEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client half of cube streaming (cubic voxel runtime, #447), independent of the {@code Game} singletons so
 * tests can drive it against their own client world. OWNER thread (the client world's main thread) only.
 *
 * <ul>
 *   <li><b>Install</b> ({@link #pump}): received {@link CubeDataS2C} batches are installed under a per-frame
 *       time budget — column records first ({@code decodeColumn}: the REMOTE world learns its known air and
 *       surface band from them), then each cube ({@code decode}) and its payload states as the cube blob, which
 *       the world's INSTALLED handling reads into the {@code Cube} before the furnace/workbench registries scan
 *       it. The REMOTE stream finds decoded cubes through the dirty set and meshes them.</li>
 *   <li><b>Registry mirror</b> ({@link #acceptNames}): the server's block-state names, applied before any cube.</li>
 *   <li><b>Requests</b> ({@link #clientTick}): the stream's {@code missing()} cubes not asked for recently and
 *       not queued, nearest first, in {@link CubeRequestC2S} batches with a bounded number outstanding.</li>
 *   <li><b>Unloads</b>: the world's UNLOADED events become {@link CubeUnloadC2S} notices.</li>
 *   <li><b>Flow control</b>: {@link CubeAckC2S} with the cubes/bytes installed and the rate this client can take.</li>
 *   <li><b>Audit</b>: every {@link #AUDIT_PERIOD_TICKS} a few installed cubes' CRC32C go out ({@link CubeHashesC2S}).</li>
 * </ul>
 *
 * <p>Ordering barrier: block deltas for a cube still in the install queue (or decoded but not yet INSTALLED)
 * wait for it ({@link #runAfterPendingInstall}) — applying them first would fail or be overwritten.
 */
public final class ClientCubeStreamer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientCubeStreamer.class);

    /** Sends a reliable packet to the server; false when not connected. */
    public interface Sink {
        boolean send(Packet packet);
    }

    /** A request is repeated after this long while the cube is still missing. */
    static final long REQUEST_RETRY_NS = 4_000_000_000L;
    /** Requests outstanding at once (asked within {@link #REQUEST_RETRY_NS}, not yet answered). */
    static final int MAX_OUTSTANDING = 4096;
    /** Deferred edits of a decoded cube run at its INSTALLED event, or after this long anyway. */
    static final long INSTALL_WAIT_NS = 2_000_000_000L;
    static final int AUDIT_PERIOD_TICKS = 200;
    static final int AUDIT_CUBES_PER_ROUND = 16;
    /** Ticks between scans for columns of the disc whose record is missing ({@link #unknownColumnTops}). */
    static final int RECORD_SCAN_PERIOD_TICKS = 5;
    private static final long[] NONE = new long[0];
    /** Main-thread install time per 50 ms tick the reported rate is sized for (Minecraft: 7 ms). */
    private static final double INSTALL_NS_PER_TICK = 7_000_000.0;
    private static final float MAX_DESIRED_PER_TICK = 4096f;
    private static final float MIN_DESIRED_PER_TICK = 1f;

    /** One unit of install work: a column record or a cube record, in wire order. */
    private record Work(CubeDataS2C.Column column, CubeDataS2C.CubeRecord cube) {}

    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    /** Cubes in {@link #queue} (count per cube: a re-send can queue a cube twice). */
    private final Map<CubePos, Integer> queued = new HashMap<>();
    /** Decoded cubes waiting for their INSTALLED event -> decode time. */
    private final Map<CubePos, Long> awaitingInstall = new HashMap<>();
    /** Actions waiting for a cube's install, in arrival order. */
    private final Map<CubePos, List<Runnable>> deferred = new HashMap<>();
    /** Cube -> time last requested. */
    private final Map<CubePos, Long> requestedAt = new HashMap<>();
    /** Cubes the world created locally as a predicted edit in known air: their server records are asked for. */
    private final java.util.concurrent.ConcurrentLinkedQueue<CubePos> predicted =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Cubes the world unloaded, to report. */
    private final List<CubePos> unloaded = new ArrayList<>();
    /** The server's block-state names (index i = id i + 1), and how many reached the world's registry. */
    private final List<String> serverNames = new ArrayList<>();
    private boolean namesDirty;

    private int processedCubes;
    private long processedBytes;
    private double avgInstallNs = 50_000.0;
    private float lastSentRate = Float.NaN;
    private int auditCounter;
    private int auditCursor;
    private int recordScanCounter;
    private long cubesInstalled;

    private World boundWorld;
    private final CubeEvents.InstallListener installListener = (w, cube) -> onInstalled(cube.pos());
    private final CubeEvents.UnloadListener unloadListener = (w, cube) -> onUnloaded(cube.pos());

    // ─── inbound ────────────────────────────────────────────────────────────────

    /** A cube batch arrived (owner thread): queued for {@link #pump}. */
    public void accept(CubeDataS2C batch) {
        for (CubeDataS2C.Column c : batch.columns()) {
            queue.add(new Work(c, null));
        }
        for (CubeDataS2C.CubeRecord r : batch.cubes()) {
            queue.add(new Work(null, r));
            queued.merge(new CubePos(r.cx(), r.cy(), r.cz()), 1, Integer::sum);
        }
    }

    /** Server block-state names for ids firstId.. (owner thread); mirrored into the world before any cube. */
    public void acceptNames(BlockStateNamesS2C names) {
        int index = names.firstId() - 1;
        if (index > serverNames.size()) {
            LOGGER.warn("Block-state names from id {} skip ids {}..{}; ignoring", names.firstId(),
                serverNames.size() + 1, index);
            return;
        }
        for (int i = 0; i < names.names().size(); i++) {
            int at = index + i;
            if (at < serverNames.size()) {
                serverNames.set(at, names.names().get(i));
            } else {
                serverNames.add(names.names().get(i));
            }
        }
        namesDirty = true;
    }

    /**
     * Runs {@code action} once the cube of block (x, y, z) is installed when it is still queued or decoded but
     * not yet INSTALLED (returns true); otherwise returns false and the caller applies now.
     */
    public boolean runAfterPendingInstall(long x, long y, long z, Runnable action) {
        CubePos pos = new CubePos(x >> 5, y >> 5, z >> 5);
        if (!queued.containsKey(pos) && !awaitingInstall.containsKey(pos)) {
            return false;
        }
        deferred.computeIfAbsent(pos, p -> new ArrayList<>()).add(action);
        return true;
    }

    /** True while a cube record for (cx, cy, cz) waits in the install queue or for its INSTALLED event. */
    public boolean isPending(long cx, long cy, long cz) {
        CubePos pos = new CubePos(cx, cy, cz);
        return queued.containsKey(pos) || awaitingInstall.containsKey(pos);
    }

    // ─── per frame ──────────────────────────────────────────────────────────────

    /**
     * OWNER THREAD, every frame: binds to {@code world}, mirrors the registry and installs queued records for
     * at most {@code budgetNs}.
     */
    public void pump(World world, long budgetNs) {
        if (world == null) {
            return;
        }
        bind(world);
        applyNames(world);
        long start = System.nanoTime();
        CubeWorld voxels = world.voxels();
        Work w;
        while (System.nanoTime() - start < budgetNs && (w = queue.poll()) != null) {
            if (w.column() != null) {
                CubeDataS2C.Column c = w.column();
                try {
                    voxels.decodeColumn(c.cx(), c.cz(), c.bytes());
                } catch (RuntimeException e) {
                    LOGGER.error("Column record ({}, {}) did not decode: {}", c.cx(), c.cz(), e.getMessage());
                }
                continue;
            }
            installCube(world, w.cube());
        }
        runOverdueDeferred();
    }

    private void installCube(World world, CubeDataS2C.CubeRecord r) {
        CubePos pos = new CubePos(r.cx(), r.cy(), r.cz());
        queued.computeIfPresent(pos, (p, n) -> n > 1 ? n - 1 : null);
        processedCubes++;
        processedBytes += r.payloadBytes();
        requestedAt.remove(pos);
        if (queued.containsKey(pos)) {
            return; // a newer record of this cube is queued behind this one
        }
        long t0 = System.nanoTime();
        CubeWorld voxels = world.voxels();
        boolean existed = voxels.hasCube(pos.x(), pos.y(), pos.z());
        try {
            voxels.decode(pos.x(), pos.y(), pos.z(), r.bytes());
        } catch (RuntimeException e) {
            // Undecodable: drop it; the stream still misses it and asks again.
            LOGGER.error("Cube ({}, {}, {}) did not decode: {}", pos.x(), pos.y(), pos.z(), e.getMessage());
            runDeferred(pos);
            return;
        }
        byte[] blob = payloadBlob(world, pos, r);
        if (blob.length > 0) {
            voxels.setBlob(pos.x(), pos.y(), pos.z(), blob);
        }
        cubesInstalled++;
        Cube cube = world.getCube(pos.x(), pos.y(), pos.z());
        if (existed && cube != null) {
            // A replaced cube the stream already tracks: no INSTALLED event follows.
            cube.decodeBlob(blob);
            runDeferred(pos);
        } else {
            awaitingInstall.put(pos, System.nanoTime());
        }
        avgInstallNs += 0.05 * ((System.nanoTime() - t0) - avgInstallNs);
    }

    /** The cube blob carrying {@code r}'s payload states (the world's own blob format), or empty. */
    private static byte[] payloadBlob(World world, CubePos pos, CubeDataS2C.CubeRecord r) {
        if (r.stateCells().length == 0) {
            return new byte[0];
        }
        Cube scratch = new Cube(world, pos);
        for (int i = 0; i < r.stateCells().length; i++) {
            scratch.setPayloadState(r.stateCells()[i], r.stateValues()[i]);
        }
        return scratch.encodeBlob();
    }

    private void bind(World world) {
        if (boundWorld == world) {
            return;
        }
        if (boundWorld != null) {
            boundWorld.removeInstallListener(installListener);
            boundWorld.removeUnloadListener(unloadListener);
            boundWorld.setLocalCubeListener(null);
        }
        boundWorld = world;
        world.addInstallListener(installListener);
        world.addUnloadListener(unloadListener);
        world.setLocalCubeListener(predicted::add);
        predicted.clear();
        // A new client world starts with only the fixed names: mirror the whole table again.
        namesDirty = true;
        awaitingInstall.clear();
        requestedAt.clear();
        unloaded.clear();
    }

    private void onInstalled(CubePos pos) {
        if (awaitingInstall.remove(pos) != null) {
            runDeferred(pos);
        }
    }

    private void onUnloaded(CubePos pos) {
        unloaded.add(pos);
        requestedAt.remove(pos);
        awaitingInstall.remove(pos);
    }

    private void runDeferred(CubePos pos) {
        List<Runnable> actions = deferred.remove(pos);
        if (actions != null) {
            for (Runnable a : actions) {
                a.run();
            }
        }
    }

    private void runOverdueDeferred() {
        if (awaitingInstall.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        List<CubePos> overdue = null;
        for (Map.Entry<CubePos, Long> e : awaitingInstall.entrySet()) {
            if (now - e.getValue() > INSTALL_WAIT_NS) {
                if (overdue == null) overdue = new ArrayList<>();
                overdue.add(e.getKey());
            }
        }
        if (overdue != null) {
            for (CubePos p : overdue) {
                awaitingInstall.remove(p);
                runDeferred(p);
            }
        }
    }

    /**
     * Mirrors the server's names into the world's registry: appended in id order when the local table is a
     * prefix of the server's (the normal case), else rebuilt as the server's table followed by any names only
     * this client registered (a local prediction; its cells may show the wrong variant until re-sent).
     */
    private void applyNames(World world) {
        if (!namesDirty || serverNames.isEmpty()) {
            return;
        }
        namesDirty = false;
        mirror(world.blockStates(), serverNames);
    }

    static void mirror(BlockStateRegistry registry, List<String> server) {
        List<String> local = registry.names();
        if (local.size() <= server.size() && server.subList(0, local.size()).equals(local)) {
            for (int i = local.size(); i < server.size(); i++) {
                registry.idOf(server.get(i));
            }
            return;
        }
        List<String> merged = new ArrayList<>(server);
        Set<String> known = new HashSet<>(server);
        for (String n : local) {
            if (known.add(n)) {
                merged.add(n);
            }
        }
        try {
            registry.replaceAll(merged);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Server block-state table rejected: {}", e.getMessage());
        }
    }

    // ─── 20 Hz client tick ──────────────────────────────────────────────────────

    /** Requests, unload notices, the flow-control ack and the hash audit (owner thread, 20 Hz). */
    public void clientTick(World world, Sink sink) {
        if (world == null) {
            return;
        }
        bind(world);
        sendUnloads(sink);
        sendRequests(world, sink);
        sendAck(sink);
        if (++auditCounter >= AUDIT_PERIOD_TICKS) {
            auditCounter = 0;
            sendAudit(world, sink, AUDIT_CUBES_PER_ROUND);
        }
    }

    private void sendUnloads(Sink sink) {
        if (unloaded.isEmpty()) {
            return;
        }
        for (int from = 0; from < unloaded.size(); from += CubeUnloadC2S.MAX_CUBES) {
            int to = Math.min(unloaded.size(), from + CubeUnloadC2S.MAX_CUBES);
            sink.send(new CubeUnloadC2S(triples(unloaded.subList(from, to))));
        }
        unloaded.clear();
    }

    private void sendRequests(World world, Sink sink) {
        sendPredictedRequests(sink);
        CubeStream stream = world.stream();
        if (stream == null) {
            return;
        }
        long[] missing = stream.missing();
        long[] records = (++recordScanCounter % RECORD_SCAN_PERIOD_TICKS) == 0
            ? unknownColumnTops(world) : NONE;
        if (missing.length == 0 && records.length == 0) {
            return;
        }
        long now = System.nanoTime();
        int outstanding = 0;
        for (Iterator<Long> it = requestedAt.values().iterator(); it.hasNext(); ) {
            if (now - it.next() < REQUEST_RETRY_NS) {
                outstanding++;
            } else {
                it.remove();
            }
        }
        List<CubePos> batch = new ArrayList<>();
        CubeWorld voxels = world.voxels();
        for (int i = 0; i + 2 < missing.length && outstanding < MAX_OUTSTANDING; i += 3) {
            CubePos pos = new CubePos(missing[i], missing[i + 1], missing[i + 2]);
            if (requestedAt.containsKey(pos) || queued.containsKey(pos) || awaitingInstall.containsKey(pos)
                    || voxels.hasCube(pos.x(), pos.y(), pos.z())) {
                continue;
            }
            requestedAt.put(pos, now);
            batch.add(pos);
            outstanding++;
            if (batch.size() == CubeRequestC2S.MAX_CUBES) {
                sink.send(new CubeRequestC2S(triples(batch)));
                batch.clear();
            }
        }
        for (int i = 0; i + 2 < records.length && outstanding < MAX_OUTSTANDING; i += 3) {
            CubePos pos = new CubePos(records[i], records[i + 1], records[i + 2]);
            if (requestedAt.containsKey(pos) || queued.containsKey(pos) || awaitingInstall.containsKey(pos)) {
                continue;
            }
            requestedAt.put(pos, now);
            batch.add(pos);
            outstanding++;
            if (batch.size() == CubeRequestC2S.MAX_CUBES) {
                sink.send(new CubeRequestC2S(triples(batch)));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            sink.send(new CubeRequestC2S(triples(batch)));
        }
    }

    /**
     * The top in-bounds cube of every column inside the stream's horizontal discs whose COLUMN RECORD this
     * client lacks, nearest first. A REMOTE world has no surface callback: a column's surface band - what keeps
     * the terrain loaded at any height difference - exists only once the server's record arrived, and records
     * came only with cubes the ellipsoid asked for. A viewer flown above the ellipsoid's reach (about y 770 at the
     * default vertical distance) therefore never learned a new column: moving horizontally, the columns it left
     * unloaded and the ones it reached were never asked for. Asking for the top cube is enough: the server
     * answers a known-air request with the column record alone ({@code ServerChunkHandler}), and the band then
     * wants the terrain cubes.
     */
    static long[] unknownColumnTops(World world) {
        List<com.openmason.engine.voxel.world.StreamCenter> centers = world.streamCenters();
        if (centers.isEmpty()) {
            return NONE;
        }
        CubeWorld voxels = world.voxels();
        long topCy = com.stonebreak.world.operations.WorldConfiguration.MAX_CUBE_Y - 1;
        List<long[]> found = new ArrayList<>();
        Set<CubePos> seen = new HashSet<>();
        for (com.openmason.engine.voxel.world.StreamCenter c : centers) {
            // The stream's disc: column-cube centre within radius_h + half a cube diagonal of the viewer.
            double reach = Math.max(0, c.radiusH()) + 27.712812921102035;
            long r = (long) Math.ceil(reach / 32.0) + 1;
            long vcx = Math.floorDiv(c.x(), 32L), vcz = Math.floorDiv(c.z(), 32L);
            double fx = Math.floorMod(c.x(), 32L), fz = Math.floorMod(c.z(), 32L);
            for (long dz = -r; dz <= r; dz++) {
                for (long dx = -r; dx <= r; dx++) {
                    double ddx = dx * 32.0 + 16.0 - fx, ddz = dz * 32.0 + 16.0 - fz;
                    double d2 = ddx * ddx + ddz * ddz;
                    if (d2 > reach * reach) {
                        continue;
                    }
                    long cx = vcx + dx, cz = vcz + dz;
                    if (!seen.add(new CubePos(cx, 0, cz)) || voxels.topRange(cx, cz).known()) {
                        continue;
                    }
                    found.add(new long[] {(long) d2, cx, cz});
                }
            }
        }
        if (found.isEmpty()) {
            return NONE;
        }
        found.sort(java.util.Comparator.comparingLong(a -> a[0]));
        long[] out = new long[found.size() * 3];
        for (int i = 0; i < found.size(); i++) {
            out[i * 3] = found.get(i)[1];
            out[i * 3 + 1] = topCy;
            out[i * 3 + 2] = found.get(i)[2];
        }
        return out;
    }

    /**
     * A cube the local player built into (known air, {@code World.ensureCube}) exists locally only as a
     * prediction: the stream never reports it missing, so ask for the server's record explicitly. The server
     * created the same cube when it applied the edit; its record replaces ours on arrival (a refused edit is
     * reverted by the server's BlockChangeS2C either way).
     */
    private void sendPredictedRequests(Sink sink) {
        if (predicted.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        List<CubePos> batch = new ArrayList<>();
        for (CubePos pos; (pos = predicted.poll()) != null; ) {
            if (queued.containsKey(pos) || awaitingInstall.containsKey(pos)) {
                continue;
            }
            requestedAt.put(pos, now);
            batch.add(pos);
            if (batch.size() == CubeRequestC2S.MAX_CUBES) {
                sink.send(new CubeRequestC2S(triples(batch)));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            sink.send(new CubeRequestC2S(triples(batch)));
        }
    }

    private void sendAck(Sink sink) {
        float desired = desiredCubesPerTick();
        if (processedCubes == 0 && Math.abs(desired - lastSentRate) < Math.max(1f, lastSentRate * 0.1f)) {
            return;
        }
        int kib = (int) Math.min(CubeAckC2S.MAX_PROCESSED, (processedBytes + 1023) / 1024);
        if (sink.send(new CubeAckC2S(Math.min(processedCubes, CubeAckC2S.MAX_PROCESSED), kib, desired))) {
            lastSentRate = desired;
            processedCubes = 0;
            processedBytes = 0;
        }
    }

    /** Cubes per server tick this client can install at its measured cost. */
    public float desiredCubesPerTick() {
        double perTick = INSTALL_NS_PER_TICK / Math.max(1.0, avgInstallNs);
        return (float) Math.max(MIN_DESIRED_PER_TICK, Math.min(MAX_DESIRED_PER_TICK, perTick));
    }

    /**
     * Hashes up to {@code max} installed cubes (round robin; every cube when {@code max} is negative) and sends
     * them for the server to compare. Returns the number of cubes audited.
     */
    public int sendAudit(World world, Sink sink, int max) {
        List<Cube> cubes = new ArrayList<>(world.getLoadedCubes());
        if (cubes.isEmpty()) {
            return 0;
        }
        int n = max < 0 ? cubes.size() : Math.min(max, cubes.size());
        List<CubePos> positions = new ArrayList<>(n);
        List<Integer> hashes = new ArrayList<>(n);
        CubeWorld voxels = world.voxels();
        for (int k = 0; k < n; k++) {
            Cube c = cubes.get((auditCursor + k) % cubes.size());
            if (isPending(c.cx(), c.cy(), c.cz())) {
                continue;
            }
            byte[] bytes = voxels.encode(c.cx(), c.cy(), c.cz(), CubeWorld.PARTS_NETWORK);
            if (bytes == null) {
                continue;
            }
            positions.add(c.pos());
            hashes.add(ServerChunkHandler.crc32c(bytes));
        }
        auditCursor = (auditCursor + n) % Math.max(1, cubes.size());
        for (int from = 0; from < positions.size(); from += CubeHashesC2S.MAX_CUBES) {
            int to = Math.min(positions.size(), from + CubeHashesC2S.MAX_CUBES);
            int[] h = new int[to - from];
            for (int i = from; i < to; i++) {
                h[i - from] = hashes.get(i);
            }
            sink.send(new CubeHashesC2S(triples(positions.subList(from, to)), h));
        }
        return positions.size();
    }

    private static long[] triples(List<CubePos> list) {
        long[] out = new long[list.size() * 3];
        int i = 0;
        for (CubePos p : list) {
            out[i++] = p.x();
            out[i++] = p.y();
            out[i++] = p.z();
        }
        return out;
    }

    // ─── state ──────────────────────────────────────────────────────────────────

    /** Records waiting in the install queue (debug overlay / tests). */
    public int queuedRecords() {
        return queue.size();
    }

    /** Cubes installed since the session started (debug overlay / tests). */
    public long cubesInstalled() {
        return cubesInstalled;
    }

    /** Forgets everything (session end). */
    public void reset() {
        if (boundWorld != null) {
            boundWorld.removeInstallListener(installListener);
            boundWorld.removeUnloadListener(unloadListener);
            boundWorld.setLocalCubeListener(null);
            boundWorld = null;
        }
        predicted.clear();
        queue.clear();
        queued.clear();
        awaitingInstall.clear();
        deferred.clear();
        requestedAt.clear();
        unloaded.clear();
        serverNames.clear();
        namesDirty = false;
        processedCubes = 0;
        processedBytes = 0;
        lastSentRate = Float.NaN;
        cubesInstalled = 0;
    }
}
