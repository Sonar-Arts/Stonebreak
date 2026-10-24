package com.stonebreak.network.client.handlers;

import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.core.Game;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side: installs authoritative chunk snapshots into the local world.
 * Successor of the old {@code ChunkSynchronizer} CLIENT path.
 *
 * <p>Payload decode (the expensive half — wire sections → detached paletted
 * storage; skipped for a local server's storage snapshot) and the sky
 * heightmap run on a small worker pool; the main thread only pays the
 * install/swap + metadata + mesh scheduling, and paces those with a per-frame
 * wall-clock budget. This is the client-side flow control that lets the server
 * stream without a fixed per-tick push cap: a burst lands in the decode pool
 * and the ready queue, and installs drain a few milliseconds per frame.
 *
 * <p>Re-streams of the same chunk are sequenced: each submitted payload gets a
 * monotonic sequence number and a ready item older than the latest submission
 * for its chunk is dropped (a newer decode is queued or in flight), so
 * out-of-order decode completion can never roll a chunk back.
 */
public final class ClientChunkHandler {

    // Install budget: installs are cheap (decode AND the sky-heightmap scan run on the
    // workers), but a backlog after a stall used to be installed in one frame. Each frame
    // installs for at most this long; the rest wait. The server paces what it sends to the
    // rate this client measures and reports (ChunkAckC2S, after Minecraft's chunk batching),
    // so the ready queue stays short.
    private static final long INSTALL_BUDGET_NS = 4_000_000L;
    /** Main-thread install time per 50 ms tick the reported rate is sized for (Minecraft: 7 ms). */
    private static final double INSTALL_NS_PER_TICK = 7_000_000.0;
    private static final float MAX_DESIRED_PER_TICK = 256f;
    private static final float MIN_DESIRED_PER_TICK = 1f;

    /** Snapshots taken off the queue (any outcome) since the last ack. Main thread. */
    private int processedSinceAck;
    /** Moving average of main-thread nanos per install; seeds the reported rate. */
    private double avgInstallNs = 100_000.0;

    /** Raw payloads that arrived before the client world existed (rebuild/rejoin). */
    private final Deque<ChunkDataS2C> pending = new ArrayDeque<>();
    /** Chunk keys with a payload in {@link #pending}; their edits wait like any pending install. */
    private final java.util.Set<Long> pendingKeys = new java.util.HashSet<>();

    private record DecodedChunk(int chunkX, int chunkZ, long seq,
                                CcoPalettedChunkStorage storage, int[] heights,
                                byte[] metaPayload) {}

    private final ConcurrentLinkedQueue<DecodedChunk> ready = new ConcurrentLinkedQueue<>();
    /** Latest submitted decode sequence per chunk key; main-thread only. */
    private final Map<Long, Long> latestSeq = new HashMap<>();
    private long nextSeq = 0;

    /**
     * Per-chunk ordering barrier: packets that depend on a chunk's block/meta
     * state (block changes, water levels, snow, block states) arrive AFTER the
     * chunk's snapshot on the wire, but the snapshot now installs
     * asynchronously — applying such an edit immediately would either fail
     * (chunk not resident yet → spurious resync requests) or be overwritten by
     * the older snapshot when it lands (→ server audit mismatches and
     * re-stream storms). While an install is pending, dependent actions queue
     * here and replay in arrival order right after the install completes.
     * Main-thread only.
     */
    private final Map<Long, java.util.List<Runnable>> deferredActions = new HashMap<>();

    /**
     * If the chunk at (cx, cz) has a snapshot install pending, defers
     * {@code action} until that install completes and returns true; otherwise
     * returns false and the caller applies immediately. Deferred actions are
     * DROPPED if the install fails — the resync that failure triggers streams
     * a fresh snapshot that already contains their effects.
     *
     * <p>A snapshot still parked in {@link #pending} (client world rebuilding)
     * counts as pending too: the server re-streams a chunk only when something
     * forgets it, so an edit lost here would stay lost.
     */
    public boolean runAfterPendingInstall(int cx, int cz, Runnable action) {
        long k = key(cx, cz);
        if (!latestSeq.containsKey(k) && !pendingKeys.contains(k)) {
            return false;
        }
        deferredActions.computeIfAbsent(k, unused -> new java.util.ArrayList<>()).add(action);
        return true;
    }

    /**
     * True while a decoded-or-decoding snapshot for (cx, cz) has not installed
     * yet. The hash audit skips such chunks — their resident state is by
     * definition behind the wire, and hashing it reports a false mismatch that
     * triggers a pointless re-stream.
     */
    public boolean hasPendingInstall(int cx, int cz) {
        return latestSeq.containsKey(key(cx, cz));
    }

    private final ExecutorService decodeExecutor = Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2), r -> {
            Thread t = new Thread(r, "Chunk-Decode");
            t.setDaemon(true);
            return t;
        });

    public void apply(ChunkDataS2C cd) {
        // isClientWorldReady (not a bare null check): during a world REBUILD (rejoin),
        // Game.getWorld() still returns the previous session's world — installing there
        // would silently lose the chunk (the server marks it sent exactly once).
        if (!Game.isClientWorldReady()) {
            pending.add(cd);
            pendingKeys.add(key(cd.chunkX(), cd.chunkZ()));
            return;
        }
        submitDecode(cd);
    }

    private void submitDecode(ChunkDataS2C cd) {
        if (decodeExecutor.isShutdown()) {
            return; // stray packet after session teardown
        }
        long seq = nextSeq++;
        latestSeq.put(key(cd.chunkX(), cd.chunkZ()), seq);
        decodeExecutor.execute(() -> {
            // A local (singleplayer) server hands over its copy-on-write storage snapshot: no
            // decode. The install copies it section by section and never writes into it.
            CcoPalettedChunkStorage snapshot = cd.blocks().snapshot();
            CcoPalettedChunkStorage storage = snapshot != null ? snapshot
                : com.stonebreak.network.client.NetworkChunkDecoder.decodeBlocks(cd.chunkX(), cd.chunkZ(), cd.payload());
            // Heightmap on the worker too — the install then only swaps storage.
            int[] heights = storage == null ? null : com.stonebreak.network.client.NetworkChunkDecoder.computeSkyHeights(storage);
            // storage == null → decode failed; carried through so the main
            // thread can request the resync (network calls stay off workers).
            ready.add(new DecodedChunk(cd.chunkX(), cd.chunkZ(), seq, storage, heights, cd.metaPayload()));
        });
    }

    public void tick() {
        boolean worldReady = Game.isClientWorldReady();
        if (worldReady && !pending.isEmpty()) {
            ChunkDataS2C cd;
            while ((cd = pending.poll()) != null) {
                submitDecode(cd);
            }
            pendingKeys.clear(); // every parked chunk now has a latestSeq entry
        }
        if (ready.isEmpty() || !worldReady) {
            return;
        }
        World world = Game.getWorld();
        var player = Game.getPlayer();
        int keepRadius = world.clientKeepRadius();
        int pcx = player != null ? (int) Math.floor(player.getPosition().x / 16.0) : Integer.MIN_VALUE;
        int pcz = player != null ? (int) Math.floor(player.getPosition().z / 16.0) : Integer.MIN_VALUE;
        long start = System.nanoTime();
        DecodedChunk dc;
        while (System.nanoTime() - start < INSTALL_BUDGET_NS && (dc = ready.poll()) != null) {
            processedSinceAck++;
            long chunkKey = key(dc.chunkX(), dc.chunkZ());
            Long latest = latestSeq.get(chunkKey);
            // Every submission records its seq before decoding, so a missing entry means a
            // NEWER snapshot already installed and cleared it: installing this one would roll
            // the chunk back (decodes run on a pool and can finish out of order).
            if (latest == null || dc.seq() != latest) {
                continue; // superseded by a newer submission for this chunk (defers stay queued)
            }
            latestSeq.remove(chunkKey);
            // Late install for a chunk the client already dropped (render-distance
            // shrink, fast travel): discard instead of resurrecting a zombie slot. The
            // server marked it sent, so report the drop or it never re-streams (#363).
            if (player != null && Math.max(Math.abs(dc.chunkX() - pcx), Math.abs(dc.chunkZ() - pcz)) > keepRadius) {
                deferredActions.remove(chunkKey);
                com.stonebreak.network.MultiplayerSession.notifyChunkUnloaded(dc.chunkX(), dc.chunkZ());
                continue;
            }
            long installStart = System.nanoTime();
            if (dc.storage() == null
                    || !world.installDecodedNetworkChunk(dc.chunkX(), dc.chunkZ(),
                        dc.storage(), dc.heights(), dc.metaPayload())) {
                // Decode/install failed but the server marked this chunk sent — without a
                // resync request the hole is permanent (the version never changes for us).
                // Deferred edits are dropped: the re-streamed snapshot includes them.
                deferredActions.remove(chunkKey);
                com.stonebreak.network.MultiplayerSession.requestChunkResync(dc.chunkX(), dc.chunkZ());
                continue;
            }
            java.util.List<Runnable> deferred = deferredActions.remove(chunkKey);
            if (deferred != null) {
                for (Runnable action : deferred) {
                    action.run();
                }
            }
            // Install + the edits that waited for it: the main-thread cost the rate is sized by.
            avgInstallNs += 0.1 * ((System.nanoTime() - installStart) - avgInstallNs);
        }
    }

    /**
     * Snapshots processed since the last call, and resets the count — the ChunkAckC2S body.
     */
    public int takeProcessedCount() {
        int n = processedSinceAck;
        processedSinceAck = 0;
        return n;
    }

    /** Chunks per server tick this client can install at its measured cost (ChunkAckC2S). */
    public float desiredChunksPerTick() {
        double perTick = INSTALL_NS_PER_TICK / Math.max(1.0, avgInstallNs);
        return (float) Math.max(MIN_DESIRED_PER_TICK, Math.min(MAX_DESIRED_PER_TICK, perTick));
    }

    public void onSessionEnd() {
        pending.clear();
        pendingKeys.clear();
        ready.clear();
        latestSeq.clear();
        deferredActions.clear();
        decodeExecutor.shutdownNow();
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }
}
