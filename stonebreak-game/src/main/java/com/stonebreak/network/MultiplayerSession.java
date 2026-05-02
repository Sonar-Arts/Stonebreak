package com.stonebreak.network;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.client.ClientConnection;
import com.stonebreak.network.client.NetworkEventBus;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide multiplayer state. At most one of {hostServer, clientConnection}
 * is active at a time:
 * <ul>
 *   <li>HOST mode: this JVM owns the world; remote clients connect over TCP and
 *       receive broadcasts when local blocks/players change.</li>
 *   <li>CLIENT mode: this JVM connects to a remote host; the local world is
 *       generated from the host's seed (so terrain matches), and only block
 *       changes + player states are synced.</li>
 * </ul>
 */
public final class MultiplayerSession {

    public enum Mode { OFFLINE, HOST, CLIENT }

    private static volatile Mode mode = Mode.OFFLINE;
    private static volatile IntegratedServer hostServer;
    private static volatile ClientConnection clientConnection;
    private static volatile NetworkEventBus clientEventBus;
    private static volatile int localPlayerId = -1;          // server-assigned id for joined clients
    private static volatile String localUsername = "Player";
    private static volatile long pendingHostSeed = 0L;        // seed received in WelcomeS2C, awaiting world gen

    /** Player ids → remote player entities currently in the world. */
    private static final Map<Integer, RemotePlayer> remotePlayers = new HashMap<>();

    /** Suppress broadcast when applying an inbound network change. */
    private static final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> false);

    /** Throttle for periodic player-state broadcast (~20 Hz). */
    private static long lastPlayerStateBroadcastMs = 0L;
    private static final long PLAYER_STATE_PERIOD_MS = 50L;

    private MultiplayerSession() {}

    // ────────────────────────────────────────────────── Mode queries

    public static Mode getMode() { return mode; }
    public static boolean isHosting() { return mode == Mode.HOST; }
    public static boolean isClient() { return mode == Mode.CLIENT; }
    public static boolean isOnline() { return mode != Mode.OFFLINE; }

    // ────────────────────────────────────────────────── Lifecycle

    public static synchronized void startHosting(int port) throws IOException {
        if (mode != Mode.OFFLINE) shutdown();
        hostServer = new IntegratedServer(port);
        mode = Mode.HOST;
        localPlayerId = 0; // host is always id 0 for our purposes
        localUsername = com.stonebreak.config.Settings.getInstance().getMultiplayerUsername();
    }

    public static synchronized void joinServer(String host, int port, String username) throws IOException {
        if (mode != Mode.OFFLINE) shutdown();
        clientEventBus = new NetworkEventBus();
        clientConnection = new ClientConnection(host, port, clientEventBus);
        mode = Mode.CLIENT;
        localUsername = username;
        clientConnection.send(new Packet.HandshakeC2S(username));
    }

    public static synchronized void shutdown() {
        if (clientConnection != null) {
            try { clientConnection.send(new Packet.DisconnectC2S("client_quit")); } catch (Exception ignored) {}
            clientConnection.close();
            clientConnection = null;
        }
        if (hostServer != null) {
            hostServer.shutdown();
            hostServer = null;
        }
        clientEventBus = null;
        remotePlayers.clear();
        mode = Mode.OFFLINE;
        localPlayerId = -1;
        pendingHostSeed = 0L;
    }

    // ────────────────────────────────────────────────── World/block hooks

    /** Called by World.setBlockAt before mutating; returns true to suppress re-broadcast on inbound packets. */
    public static boolean isApplyingRemoteChange() { return applyingRemote.get(); }

    /**
     * Hook from World.setBlockAt: notify peers of a locally-driven block change.
     * Block id encodes a short; AIR is 0.
     */
    public static void onLocalBlockChange(int x, int y, int z, BlockType type) {
        if (mode == Mode.OFFLINE || applyingRemote.get()) return;
        short id = (short) (type == null ? 0 : type.getId());
        if (mode == Mode.HOST && hostServer != null) {
            hostServer.broadcast(new Packet.BlockChangeS2C(x, y, z, id));
        } else if (mode == Mode.CLIENT && clientConnection != null) {
            clientConnection.send(new Packet.BlockChangeC2S(x, y, z, id));
        }
    }

    // ────────────────────────────────────────────────── Per-tick pump (called from main thread)

    public static void tick() {
        if (mode == Mode.OFFLINE) return;

        if (mode == Mode.HOST && hostServer != null) {
            hostServer.getInboundEvents().drain(p -> handleServerPacket(hostServer, p));
        } else if (mode == Mode.CLIENT && clientEventBus != null) {
            clientEventBus.drain(MultiplayerSession::handleClientPacket);
            if (clientConnection == null || !clientConnection.isConnected()) {
                System.out.println("[NETWORK] Lost server connection; returning to main menu.");
                shutdown();
                Game.getInstance().setState(GameState.MAIN_MENU);
                return;
            }
        }

        // Broadcast local player state ~20 Hz
        long now = System.currentTimeMillis();
        if (now - lastPlayerStateBroadcastMs >= PLAYER_STATE_PERIOD_MS) {
            lastPlayerStateBroadcastMs = now;
            broadcastLocalPlayerState();
        }
    }

    private static void broadcastLocalPlayerState() {
        Player p = Game.getPlayer();
        if (p == null) return;
        Vector3f pos = p.getPosition();
        float yaw = p.getCamera() != null ? p.getCamera().getYaw() : 0f;
        float pitch = p.getCamera() != null ? p.getCamera().getPitch() : 0f;
        if (mode == Mode.HOST && hostServer != null) {
            // Host appears to clients as id 0
            hostServer.broadcast(new Packet.PlayerStateS2C(0, pos.x, pos.y, pos.z, yaw, pitch));
        } else if (mode == Mode.CLIENT && clientConnection != null) {
            clientConnection.send(new Packet.PlayerStateC2S(pos.x, pos.y, pos.z, yaw, pitch));
        }
    }

    // ────────────────────────────────────────────────── Server packet handling

    private static void handleServerPacket(IntegratedServer server, Packet packet) {
        Integer originId = server.getOrigin(packet);
        switch (packet) {
            case Packet.HandshakeC2S hs -> {
                if (originId == null) return;
                RemoteClient rc = server.getClient(originId);
                if (rc == null) return;
                rc.setUsername(hs.username());

                Player hostPlayer = Game.getPlayer();
                long seed = Game.getInstance().getCurrentWorldSeed();
                Vector3f spawn = hostPlayer != null ? hostPlayer.getPosition() : new Vector3f(0, 80, 0);
                rc.send(new Packet.WelcomeS2C(originId, seed, spawn.x, spawn.y, spawn.z));

                // Tell new client about everyone already present (host + other clients).
                rc.send(new Packet.PlayerJoinS2C(0, "Host", spawn.x, spawn.y, spawn.z));
                for (RemoteClient other : server.getClients().values()) {
                    if (other.getPlayerId() != originId) {
                        rc.send(new Packet.PlayerJoinS2C(other.getPlayerId(), other.getUsername(),
                                other.getX(), other.getY(), other.getZ()));
                    }
                }
                // Tell existing clients about the new one.
                server.broadcastExcept(originId, new Packet.PlayerJoinS2C(originId, hs.username(),
                        spawn.x, spawn.y, spawn.z));

                spawnRemotePlayerLocally(originId, hs.username(), spawn.x, spawn.y, spawn.z);
                System.out.println("[SERVER] " + hs.username() + " joined as id " + originId);
            }
            case Packet.BlockChangeC2S bc -> {
                applyBlockChangeFromNetwork(bc.x(), bc.y(), bc.z(), bc.blockTypeId());
                // Re-broadcast to everyone (including originator for echo confirmation).
                server.broadcast(new Packet.BlockChangeS2C(bc.x(), bc.y(), bc.z(), bc.blockTypeId()));
            }
            case Packet.PlayerStateC2S ps -> {
                if (originId == null) return;
                RemoteClient rc = server.getClient(originId);
                if (rc != null) rc.updateState(ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
                applyRemotePlayerState(originId, ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
                server.broadcastExcept(originId,
                        new Packet.PlayerStateS2C(originId, ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch()));
            }
            case Packet.DisconnectC2S dc -> {
                if (originId == null) return;
                RemoteClient rc = server.getClient(originId);
                if (rc != null) rc.close();
            }
            default -> { /* server ignores S2C packets */ }
        }
    }

    // ────────────────────────────────────────────────── Client packet handling

    private static void handleClientPacket(Packet packet) {
        switch (packet) {
            case Packet.WelcomeS2C w -> {
                localPlayerId = w.playerId();
                pendingHostSeed = w.worldSeed();
                System.out.println("[CLIENT] Welcomed as id " + w.playerId() + " (seed=" + w.worldSeed() + ")");
                // Generate a local world with the host's seed so terrain matches.
                String name = "mp_" + System.currentTimeMillis();
                Game.getInstance().startWorldGeneration(name, w.worldSeed());
                // Player will be spawned by world-gen flow; reposition once available.
            }
            case Packet.PlayerJoinS2C j -> {
                if (j.playerId() != localPlayerId) {
                    spawnRemotePlayerLocally(j.playerId(), j.username(), j.x(), j.y(), j.z());
                }
            }
            case Packet.PlayerLeaveS2C l -> {
                despawnRemotePlayerLocally(l.playerId());
            }
            case Packet.PlayerStateS2C s -> {
                if (s.playerId() != localPlayerId) {
                    applyRemotePlayerState(s.playerId(), s.x(), s.y(), s.z(), s.yaw(), s.pitch());
                }
            }
            case Packet.BlockChangeS2C b -> {
                applyBlockChangeFromNetwork(b.x(), b.y(), b.z(), b.blockTypeId());
            }
            case Packet.ChunkDataS2C cd -> {
                // v1: chunk data not used (clients regenerate from seed).
            }
            default -> { /* clients ignore C2S packets */ }
        }
    }

    // ────────────────────────────────────────────────── Apply helpers

    private static void applyBlockChangeFromNetwork(int x, int y, int z, short blockTypeId) {
        if (Game.getWorld() == null) return;
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        if (type == null) type = BlockType.AIR;
        applyingRemote.set(true);
        try {
            Game.getWorld().setBlockAt(x, y, z, type, true);
        } finally {
            applyingRemote.set(false);
        }
    }

    private static void spawnRemotePlayerLocally(int playerId, String username, float x, float y, float z) {
        if (Game.getWorld() == null) return;
        EntityManager em = Game.getEntityManager();
        if (em == null) return;
        if (remotePlayers.containsKey(playerId)) return;
        RemotePlayer rp = new RemotePlayer(Game.getWorld(), new Vector3f(x, y, z), playerId, username);
        em.addEntity(rp);
        remotePlayers.put(playerId, rp);
    }

    private static void despawnRemotePlayerLocally(int playerId) {
        RemotePlayer rp = remotePlayers.remove(playerId);
        if (rp != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(rp);
        }
    }

    private static void applyRemotePlayerState(int playerId, float x, float y, float z, float yaw, float pitch) {
        RemotePlayer rp = remotePlayers.get(playerId);
        if (rp == null) {
            // Late state for an unknown player — spawn lazily.
            spawnRemotePlayerLocally(playerId, "Player" + playerId, x, y, z);
            rp = remotePlayers.get(playerId);
        }
        if (rp != null) rp.applyNetworkState(x, y, z, yaw, pitch);
    }
}
