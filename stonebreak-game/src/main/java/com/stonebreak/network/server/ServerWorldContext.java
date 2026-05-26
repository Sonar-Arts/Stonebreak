package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The authoritative-world view that every server handler operates on, in place of direct
 * {@code Game.get*()} calls. Backed by the headless {@link ServerLevel} (the two-world model):
 * {@link #world()}/{@link #entityManager()}/{@link #worldSeed()} resolve to the server's own
 * instances, never the client's render world.
 *
 * <p>Every player — including the in-process local (host) player — is a normal client with an
 * id allocated from {@code 1} upward; there is no reserved host id.
 */
public final class ServerWorldContext {

    private final ConnectionRegistry connections;
    private final Map<Integer, ServerPlayer> players = new ConcurrentHashMap<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);

    /** The authoritative headless server world. Set by {@code IntegratedServer.start}. */
    private volatile ServerLevel serverLevel;

    public ServerWorldContext(ConnectionRegistry connections) {
        this.connections = connections;
    }

    // ─── Authoritative world ──────────────────────────────────────────────────
    public World world() {
        ServerLevel level = serverLevel;
        return level != null ? level.world() : null;
    }

    public EntityManager entityManager() {
        ServerLevel level = serverLevel;
        return level != null ? level.entityManager() : null;
    }

    public long worldSeed() {
        ServerLevel level = serverLevel;
        return level != null ? level.seed() : 0L;
    }

    /** Authoritative world spawn (saved player/world spawn), or a sane default. */
    public Vector3f spawn() {
        ServerLevel level = serverLevel;
        return level != null ? level.spawn() : new Vector3f(0, 80, 0);
    }

    public ServerLevel serverLevel() { return serverLevel; }

    public void setServerLevel(ServerLevel serverLevel) { this.serverLevel = serverLevel; }

    // ─── Player registry ──────────────────────────────────────────────────────
    public int allocatePlayerId() { return nextPlayerId.getAndIncrement(); }
    public void addPlayer(ServerPlayer p) { players.put(p.playerId(), p); }
    public void removePlayer(ServerPlayer p) { players.remove(p.playerId()); }
    public ServerPlayer player(int id) { return players.get(id); }
    public Collection<ServerPlayer> players() { return players.values(); }

    // ─── Broadcast helpers ────────────────────────────────────────────────────
    public ConnectionRegistry connections() { return connections; }

    public void broadcast(Packet packet, boolean droppable) {
        connections.broadcast(packet, droppable);
    }

    public void broadcastExcept(ServerPlayer except, Packet packet, boolean droppable) {
        connections.broadcastExcept(except.connection(), packet, droppable);
    }
}
