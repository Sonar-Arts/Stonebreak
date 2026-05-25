package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The authoritative-world view that every server handler operates on, in place of direct
 * {@code Game.get*()} calls. This is the seam for a future dedicated server: today it
 * delegates to {@code Game} (the <b>shared-World shortcut</b> — for singleplayer/host the
 * authoritative world and the rendered world are the same instance), but a dedicated build
 * would back these accessors with the server's own {@code World}/{@code EntityManager}
 * without touching the handlers.
 *
 * <p>Player ids: {@code 0} is reserved for the co-located host; remote players are
 * allocated from {@code 1} upward.
 */
public final class ServerWorldContext {

    public static final int HOST_PLAYER_ID = 0;

    private final ConnectionRegistry connections;
    private final Map<Integer, ServerPlayer> players = new ConcurrentHashMap<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);

    public ServerWorldContext(ConnectionRegistry connections) {
        this.connections = connections;
    }

    // ─── Authoritative world (shared-World shortcut) ──────────────────────────
    public World world() { return Game.getWorld(); }
    public EntityManager entityManager() { return Game.getEntityManager(); }
    public Player hostPlayer() { return Game.getPlayer(); }
    public long worldSeed() { return Game.getInstance().getCurrentWorldSeed(); }

    /** Host spawn = the host player's position, or a sane default before it exists. */
    public Vector3f hostSpawn() {
        Player p = hostPlayer();
        return p != null ? new Vector3f(p.getPosition()) : new Vector3f(0, 80, 0);
    }

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
