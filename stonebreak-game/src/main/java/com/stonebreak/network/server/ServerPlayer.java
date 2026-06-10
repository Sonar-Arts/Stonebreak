package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.util.LongIntHashMap;

/**
 * Server-side state for one connected player. Successor to the old {@code RemoteClient}'s
 * cached fields, minus the transport (that now lives in the engine
 * {@link ServerConnection}). Created when a channel connects, promoted to a roster member
 * once its handshake completes.
 *
 * <p>All fields are touched only on the server tick thread (inbound is drained there), so
 * no synchronization is needed.
 */
public final class ServerPlayer {

    private final ServerConnection connection;
    private final int playerId;
    private String username = "Player";
    /** True once a valid HandshakeC2S has been accepted (gates PLAY packet handling). */
    private boolean handshakeDone = false;

    // Last reported transform (cached from PlayerStateC2S) for reach checks + chunk targeting.
    private float x, y, z, yaw, pitch;
    private long lastStateNs = 0L;
    /** Last reported held block/item id. 0 = empty/air. */
    private int heldItemId = 0;

    // Per-player chunk-view tracker (was ChunkSynchronizer.PlayerView). Stores the chunk
    // VERSION last sent to this player so the server re-streams a chunk when it changes
    // server-side (player edits, water flow). Absent key = never sent. Primitive-keyed —
    // the steady-state view scan probes this per ring cell, and a boxed Map<Long, Integer>
    // allocated a Long per probe.
    private int lastCx = Integer.MIN_VALUE;
    private int lastCz = Integer.MIN_VALUE;
    private final LongIntHashMap sentChunkVersions = new LongIntHashMap();

    /**
     * True while this player's chunk view may be out of date and the streaming scan in
     * {@code ServerChunkHandler.tick} must run. Set on join (initial fill), on chunk-boundary
     * crossing, on view-distance change, and when any chunk version bumps; cleared by the
     * handler only after a scan confirms every in-view chunk is sent at its current version.
     * Lets the server skip the O(view²) ring walk entirely for stationary players.
     */
    private boolean viewScanPending = true;

    public boolean viewScanPending() { return viewScanPending; }
    public void markViewScanPending() { this.viewScanPending = true; }
    public void clearViewScanPending() { this.viewScanPending = false; }

    public ServerPlayer(ServerConnection connection, int playerId) {
        this.connection = connection;
        this.playerId = playerId;
    }

    public ServerConnection connection() { return connection; }
    public int playerId() { return playerId; }
    public String username() { return username; }
    public void setUsername(String username) { this.username = username; }
    public boolean handshakeDone() { return handshakeDone; }
    public void markHandshakeDone() { this.handshakeDone = true; }

    public float x() { return x; }
    public float y() { return y; }
    public float z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public long lastStateNs() { return lastStateNs; }

    public void updateState(float x, float y, float z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        this.lastStateNs = System.nanoTime();
    }

    public int heldItemId() { return heldItemId; }
    public void setHeldItemId(int id) { this.heldItemId = id; }

    /** Render distance reported by this client (ViewDistanceC2S), clamped to the
     *  allowed range. Drives this player's chunk-streaming view radius. */
    private int viewDistanceChunks = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_RENDER_DISTANCE;

    public int viewDistanceChunks() { return viewDistanceChunks; }

    public void setViewDistanceChunks(int chunks) {
        int clamped = Math.max(
            com.stonebreak.world.operations.WorldConfiguration.MIN_RENDER_DISTANCE,
            Math.min(com.stonebreak.world.operations.WorldConfiguration.MAX_RENDER_DISTANCE, chunks));
        if (clamped != this.viewDistanceChunks) {
            this.viewDistanceChunks = clamped;
            markViewScanPending(); // wider view needs new chunks; narrower needs a forget pass
        }
    }

    // Latest serialized PlayerData (inventory + position + stats) reported by a REMOTE client,
    // persisted per username on disconnect / autosave / shutdown. Null until the client sends one.
    private volatile byte[] playerDataBlob;
    public byte[] playerDataBlob() { return playerDataBlob; }
    public void setPlayerDataBlob(byte[] blob) { this.playerDataBlob = blob; }

    /** True for the in-process (host/singleplayer) player on the in-JVM Local channel. Its state
     *  is persisted same-JVM, so it is excluded from the network player-data sync. */
    public boolean isLocal() {
        return connection.channel() instanceof io.netty.channel.local.LocalChannel;
    }

    // ─── Chunk-view tracker ───────────────────────────────────────────────────
    public int lastCx() { return lastCx; }
    public int lastCz() { return lastCz; }
    public void setLastChunk(int cx, int cz) {
        if (cx != this.lastCx || cz != this.lastCz) {
            markViewScanPending(); // crossed a chunk boundary — the view edge moved
        }
        this.lastCx = cx;
        this.lastCz = cz;
    }
    /** Version of {@code key} last sent to this player, or -1 if never sent. */
    public int sentChunkVersion(long key) { return sentChunkVersions.get(key, -1); }
    public void markChunkSent(long key, int version) { sentChunkVersions.put(key, version); }
    /** Forget a chunk (e.g. it left the view) so it re-streams if it returns. */
    public void forgetChunk(long key) { sentChunkVersions.remove(key); }
    /** Forget every sent chunk whose packed key matches the predicate (left the keep radius). */
    public void forgetChunksMatching(java.util.function.LongPredicate predicate) {
        sentChunkVersions.removeIf(predicate);
    }

    // ─── Transport convenience ────────────────────────────────────────────────
    public boolean send(Packet packet, boolean droppable) { return connection.send(packet, droppable); }
    public boolean send(Packet packet) { return connection.send(packet, false); }
    public void setPhase(ProtocolPhase phase) { connection.setPhase(phase); }
    public ProtocolPhase phase() { return connection.phase(); }
    public void disconnect() { connection.close(); }

    @Override
    public String toString() {
        return "ServerPlayer[" + playerId + " " + username + "]";
    }
}
