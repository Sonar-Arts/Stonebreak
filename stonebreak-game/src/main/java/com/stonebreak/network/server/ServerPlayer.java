package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.ServerConnection;

import java.util.HashSet;
import java.util.Set;

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

    // Per-player chunk-view tracker (was ChunkSynchronizer.PlayerView).
    private int lastCx = Integer.MIN_VALUE;
    private int lastCz = Integer.MIN_VALUE;
    private final Set<Long> sentChunks = new HashSet<>();

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

    // ─── Chunk-view tracker ───────────────────────────────────────────────────
    public int lastCx() { return lastCx; }
    public int lastCz() { return lastCz; }
    public void setLastChunk(int cx, int cz) { this.lastCx = cx; this.lastCz = cz; }
    public boolean hasSentChunk(long key) { return sentChunks.contains(key); }
    public void markChunkSent(long key) { sentChunks.add(key); }

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
