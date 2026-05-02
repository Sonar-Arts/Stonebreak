package com.stonebreak.network;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.client.ClientConnection;
import com.stonebreak.network.client.NetworkEventBus;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.SyncService;
import com.stonebreak.network.sync.synchronizers.BlockSynchronizer;
import com.stonebreak.network.sync.synchronizers.ChatSynchronizer;
import com.stonebreak.network.sync.synchronizers.ChunkSynchronizer;
import com.stonebreak.network.sync.synchronizers.EntitySynchronizer;
import com.stonebreak.network.sync.synchronizers.PlayerStateSynchronizer;
import org.joml.Vector3f;

import java.io.IOException;

/**
 * Lifecycle owner for the multiplayer session.
 *
 * <p>Responsibilities kept here:
 * <ul>
 *   <li>Start / stop / mode tracking (HOST | CLIENT | OFFLINE).</li>
 *   <li>Holding the {@link IntegratedServer} or {@link ClientConnection}.</li>
 *   <li>Initial handshake / welcome / join-snapshot — protocol bootstrapping
 *       that has to happen before per-domain sync makes sense.</li>
 *   <li>Per-tick pump that drains transport queues and forwards each packet
 *       into the {@link SyncService}.</li>
 * </ul>
 *
 * <p>All ongoing state replication (blocks, chat, entities, player state) is
 * delegated to {@link SyncService} and its registered synchronizers — this
 * class never talks to the world or entity systems directly outside of the
 * handshake path.
 */
public final class MultiplayerSession {

    private static volatile SyncMode mode = SyncMode.OFFLINE;
    private static volatile IntegratedServer hostServer;
    private static volatile ClientConnection clientConnection;
    private static volatile NetworkEventBus clientEventBus;
    private static volatile int localPlayerId = -1;

    /**
     * Spawn position the host advertised in {@link Packet.WelcomeS2C}, deferred
     * until the local Player exists (world generation runs on a worker thread).
     * Cleared on apply.
     */
    private static volatile Vector3f pendingSpawnTeleport;

    // Fixed 20 Hz server tick (Minecraft's standard rate).
    // Inbound packets are drained every frame for responsiveness; periodic
    // broadcasts (entity state, player state, chunk pushes) only fire on a
    // server tick boundary so cadence doesn't depend on render FPS.
    private static final long TICK_PERIOD_NS = 50_000_000L;
    private static final long MAX_ACCUMULATOR_NS = TICK_PERIOD_NS * 5;
    private static long lastTickNs = 0L;
    private static long tickAccumulatorNs = 0L;

    private static final SyncService SYNC = new SyncService();

    private static EntityManager.Listener entityListener;

    static {
        // One-time registration; synchronizers are stateless across sessions.
        // BlockSynchronizer needs a reference to ChunkSynchronizer so it can
        // mark chunks modified after applying inbound client edits (the normal
        // SyncEvent path is suppressed during inbound application).
        ChunkSynchronizer chunkSync = new ChunkSynchronizer();
        SYNC.register(new BlockSynchronizer(chunkSync));
        SYNC.register(new PlayerStateSynchronizer());
        SYNC.register(new ChatSynchronizer());
        SYNC.register(new EntitySynchronizer());
        SYNC.register(chunkSync);
    }

    private MultiplayerSession() {}

    // ────────────────────────────────────────────────── Mode queries

    public static SyncMode getMode() { return mode; }
    public static boolean isHosting() { return mode == SyncMode.HOST; }
    public static boolean isClient()  { return mode == SyncMode.CLIENT; }
    public static boolean isOnline()  { return mode != SyncMode.OFFLINE; }
    public static SyncService getSyncService() { return SYNC; }
    public static IntegratedServer getServer() { return hostServer; }

    // ────────────────────────────────────────────────── Lifecycle

    public static synchronized void startHosting(int port) throws IOException {
        if (mode != SyncMode.OFFLINE) shutdown();
        hostServer = new IntegratedServer(port);
        mode = SyncMode.HOST;
        localPlayerId = 0;
        lastTickNs = System.nanoTime();
        tickAccumulatorNs = 0L;
        SYNC.start(buildContext());
        attachEntityListener();
    }

    public static synchronized void joinServer(String host, int port, String username) throws IOException {
        if (mode != SyncMode.OFFLINE) shutdown();
        clientEventBus = new NetworkEventBus();
        clientConnection = new ClientConnection(host, port, clientEventBus);
        mode = SyncMode.CLIENT;
        lastTickNs = System.nanoTime();
        tickAccumulatorNs = 0L;
        SYNC.start(buildContext());
        clientConnection.send(new Packet.HandshakeC2S(Packet.PROTOCOL_VERSION, username));
    }

    public static synchronized void shutdown() {
        detachEntityListener();
        SYNC.stop();
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
        mode = SyncMode.OFFLINE;
        localPlayerId = -1;
        pendingSpawnTeleport = null;
    }

    // ────────────────────────────────────────────────── World/entity hooks

    /**
     * Hook from {@code World.setBlockAt}: forward a locally-driven block change
     * into the sync service. The service suppresses re-broadcast when the
     * change came from an inbound packet via its applyingInbound flag.
     */
    public static void onLocalBlockChange(int x, int y, int z, BlockType type) {
        if (!isOnline()) return;
        SYNC.notifyLocal(new SyncEvent.BlockChanged(x, y, z, type));
    }

    private static void attachEntityListener() {
        EntityManager em = Game.getEntityManager();
        if (em == null) {
            // World may not exist yet (host hasn't loaded a world). Listener
            // will be re-attached lazily on first tick once the manager exists.
            return;
        }
        if (entityListener != null) em.removeListener(entityListener);
        entityListener = new EntityManager.Listener() {
            @Override public void onEntityAdded(Entity e)   { SYNC.notifyLocal(new SyncEvent.EntitySpawned(e)); }
            @Override public void onEntityRemoved(Entity e) { SYNC.notifyLocal(new SyncEvent.EntityDespawned(e)); }
        };
        em.addListener(entityListener);
    }

    private static void detachEntityListener() {
        EntityManager em = Game.getEntityManager();
        if (em != null && entityListener != null) em.removeListener(entityListener);
        entityListener = null;
    }

    // ────────────────────────────────────────────────── Per-tick pump

    public static void tick() {
        // Synchronize on the same monitor as start/stop so we never read a
        // half-torn-down hostServer/clientConnection mid-shutdown.
        synchronized (MultiplayerSession.class) {
            tickLocked();
        }
    }

    private static void tickLocked() {
        if (!isOnline()) return;

        // Lazy listener attach (entity manager only exists after world load).
        if (entityListener == null && Game.getEntityManager() != null) {
            attachEntityListener();
            // Snapshot existing entities into the sync layer.
            for (Entity e : Game.getEntityManager().getAllEntities()) {
                if (mode == SyncMode.HOST) SYNC.notifyLocal(new SyncEvent.EntitySpawned(e));
            }
        }

        // Client: apply the host-advertised spawn once the local Player exists.
        // World gen is async, so the Player isn't around at handleWelcome time;
        // we poll here every tick until it appears, then teleport once.
        if (mode == SyncMode.CLIENT && pendingSpawnTeleport != null) {
            com.stonebreak.player.Player p = Game.getPlayer();
            if (p != null) {
                p.setPosition(pendingSpawnTeleport);
                System.out.println("[CLIENT] Teleported to host spawn " + pendingSpawnTeleport);
                pendingSpawnTeleport = null;
            }
        }

        // Drain transport queues, dispatch each packet through the sync layer
        // (handshake/welcome are special-cased here; everything else goes to SyncService).
        if (mode == SyncMode.HOST && hostServer != null) {
            hostServer.getInboundEvents().drain((p, originId) -> {
                if (p instanceof Packet.HandshakeC2S hs) {
                    handleHandshake(originId, hs);
                } else if (p instanceof Packet.DisconnectC2S) {
                    RemoteClient rc = hostServer.getClient(originId);
                    if (rc != null) rc.close();
                } else {
                    SYNC.onInbound(p, originId);
                }
            });
        } else if (mode == SyncMode.CLIENT && clientEventBus != null) {
            clientEventBus.drain(p -> {
                if (p instanceof Packet.WelcomeS2C w) {
                    handleWelcome(w);
                } else if (p instanceof Packet.KickS2C kick) {
                    System.out.println("[CLIENT] Kicked by server: " + kick.reason());
                    shutdown();
                    Game.getInstance().setState(GameState.MAIN_MENU);
                } else {
                    SYNC.onInbound(p, null);
                }
            });
            if (clientConnection == null || !clientConnection.isConnected()) {
                System.out.println("[NETWORK] Lost server connection; returning to main menu.");
                shutdown();
                Game.getInstance().setState(GameState.MAIN_MENU);
                return;
            }
        }

        // Fixed-step server tick: run synchronizers' periodic broadcasts at
        // exactly 20 Hz regardless of render frame rate.
        long now = System.nanoTime();
        if (lastTickNs == 0L) lastTickNs = now;
        tickAccumulatorNs += now - lastTickNs;
        lastTickNs = now;
        if (tickAccumulatorNs > MAX_ACCUMULATOR_NS) tickAccumulatorNs = MAX_ACCUMULATOR_NS;
        while (tickAccumulatorNs >= TICK_PERIOD_NS) {
            tickAccumulatorNs -= TICK_PERIOD_NS;
            SYNC.tick(TICK_PERIOD_NS / 1_000_000_000f);
        }
    }

    // ────────────────────────────────────────────────── Handshake / welcome

    private static void handleHandshake(Integer originId, Packet.HandshakeC2S hs) {
        if (originId == null || hostServer == null) return;
        RemoteClient rc = hostServer.getClient(originId);
        if (rc == null) return;
        if (hs.protocolVersion() != Packet.PROTOCOL_VERSION) {
            String msg = "Protocol mismatch (server=" + Packet.PROTOCOL_VERSION
                       + ", client=" + hs.protocolVersion() + ")";
            System.out.println("[SERVER] Rejecting client " + originId + ": " + msg);
            rc.send(new Packet.KickS2C(msg));
            rc.close();
            return;
        }
        rc.setUsername(hs.username());

        com.stonebreak.player.Player hostPlayer = Game.getPlayer();
        long seed = Game.getInstance().getCurrentWorldSeed();
        Vector3f spawn = hostPlayer != null ? hostPlayer.getPosition() : new Vector3f(0, 80, 0);

        // 1. Tell the new client who they are + the seed (so they can mirror terrain).
        rc.send(new Packet.WelcomeS2C(originId, seed, spawn.x, spawn.y, spawn.z));

        // 2. Roster bootstrap: tell new client about everyone already present.
        rc.send(new Packet.PlayerJoinS2C(0, "Host", spawn.x, spawn.y, spawn.z));
        for (RemoteClient other : hostServer.getClients().values()) {
            if (other.getPlayerId() != originId) {
                rc.send(new Packet.PlayerJoinS2C(other.getPlayerId(), other.getUsername(),
                        other.getX(), other.getY(), other.getZ()));
            }
        }

        // 3. Tell existing clients about the newcomer.
        hostServer.broadcastExcept(originId,
                new Packet.PlayerJoinS2C(originId, hs.username(), spawn.x, spawn.y, spawn.z));

        // 4. Fire PeerJoined so each interested synchronizer can deliver its
        //    snapshot (entities, modified chunks, future: weather, time-of-day...).
        SYNC.notifyLocal(new SyncEvent.PeerJoined(originId, hs.username()));

        System.out.println("[SERVER] " + hs.username() + " joined as id " + originId);
    }

    private static void handleWelcome(Packet.WelcomeS2C w) {
        localPlayerId = w.playerId();
        System.out.println("[CLIENT] Welcomed as id " + w.playerId()
                + " (seed=" + w.worldSeed() + ", spawn=" + w.spawnX() + "," + w.spawnY() + "," + w.spawnZ() + ")");
        // Defer applying the host's spawn until the local Player is created
        // (world gen runs async; tick() polls and applies once available).
        pendingSpawnTeleport = new Vector3f(w.spawnX(), w.spawnY(), w.spawnZ());
        String name = "mp_" + System.currentTimeMillis();
        Game.getInstance().startWorldGeneration(name, w.worldSeed());
    }

    // ────────────────────────────────────────────────── SyncContext

    private static SyncContext buildContext() {
        return new SyncContext() {
            @Override public SyncMode mode() { return mode; }
            @Override public int localPlayerId() { return localPlayerId; }

            @Override public void broadcast(Packet packet) {
                if (mode == SyncMode.HOST && hostServer != null) {
                    hostServer.broadcast(packet);
                } else if (mode == SyncMode.CLIENT && clientConnection != null) {
                    clientConnection.send(packet);
                }
            }

            @Override public void broadcastExcept(int excludePlayerId, Packet packet) {
                if (mode == SyncMode.HOST && hostServer != null) {
                    hostServer.broadcastExcept(excludePlayerId, packet);
                }
            }

            @Override public void sendTo(int playerId, Packet packet) {
                if (mode == SyncMode.HOST && hostServer != null) {
                    RemoteClient rc = hostServer.getClient(playerId);
                    if (rc != null) rc.send(packet);
                }
            }

            @Override public boolean isApplyingInbound() { return SYNC.isApplyingInbound(); }
        };
    }
}
