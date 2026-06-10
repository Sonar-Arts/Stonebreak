package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.server.NetworkServer;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.net.server.ServerInboundQueue;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.PlayerDataC2S;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.ViewDistanceC2S;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.server.handlers.ServerBlockHandler;
import com.stonebreak.network.server.handlers.ServerChatHandler;
import com.stonebreak.network.server.handlers.ServerChunkHandler;
import com.stonebreak.network.server.handlers.ServerEntityHandler;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The world-authoritative integrated server. Wraps the engine {@link NetworkServer} and
 * owns the {@link ServerWorldContext} plus the per-domain handlers. Every player —
 * including the co-located host — is a client; this server holds the truth.
 *
 * <p>{@link #tick()} runs on the host's game/tick thread: it drains the inbound queue
 * (connect/disconnect/handshake inline, PLAY packets routed to handlers) and then advances
 * a fixed <b>20 Hz</b> replication step (the 50 ms accumulator + 5-tick spiral-of-death
 * clamp inherited from the old session pump). Netty event-loop threads only ever enqueue.
 */
public final class IntegratedServer {

    private static final long TICK_PERIOD_NS = 50_000_000L;        // 20 Hz
    private static final long MAX_ACCUMULATOR_NS = TICK_PERIOD_NS * 5;
    /** Persist connected remote players' inventories this often (~30 s at 20 Hz) for crash safety. */
    private static final int REMOTE_PLAYER_SAVE_INTERVAL_TICKS = 600;
    private int remotePlayerSaveCounter = 0;

    private final NetworkServer networkServer;
    private final ServerWorldContext ctx;

    private final ServerChunkHandler chunkHandler;
    private final ServerBlockHandler blockHandler;
    private final ServerEntityHandler entityHandler;
    private final ServerPlayerHandler playerHandler;
    private final ServerChatHandler chatHandler;

    private long lastTickNs = 0L;
    private long tickAccumulatorNs = 0L;

    public IntegratedServer() {
        this.networkServer = new NetworkServer(StonebreakProtocol.registry());
        this.ctx = new ServerWorldContext(networkServer.connections());
        this.chunkHandler = new ServerChunkHandler();
        this.blockHandler = new ServerBlockHandler(chunkHandler);
        this.entityHandler = new ServerEntityHandler();
        this.playerHandler = new ServerPlayerHandler();
        this.chatHandler = new ServerChatHandler();
    }

    /**
     * Boot the authoritative world and start listening. {@code worldName}/{@code fallbackSeed}
     * select the world to load-or-generate; {@code localAddress} (in-JVM, always for SP/host)
     * and/or {@code tcpAddress} (host/dedicated) are bound (pass null to omit either). The
     * {@link ServerLevel} is booted (world + persistence + spawn pre-gen) BEFORE accepting
     * clients so handshakes hand out a real spawn and entities snapshot the live world.
     */
    public void start(NetAddress localAddress, NetAddress tcpAddress, String worldName, long fallbackSeed)
            throws InterruptedException {
        ServerLevel level = ServerLevel.createAndLoad(worldName, fallbackSeed);
        ctx.setServerLevel(level);

        // Wire the spawner's player-position source to the live server roster. Kept here
        // (not in ServerLevel) so the mobs.entities package never imports network classes —
        // the dependency flows network -> mobs, not the reverse.
        level.entitySpawner().setPlayerPositionSource(this::collectPlayerSpawnAnchors);

        networkServer.start(localAddress, tcpAddress);
        chunkHandler.onSessionStart();
        playerHandler.onSessionStart();
        entityHandler.onSessionStart(ctx);
        lastTickNs = System.nanoTime();
        tickAccumulatorNs = 0L;
    }

    public ServerWorldContext worldContext() {
        return ctx;
    }

    /**
     * Snapshots the positions of every handshake-complete player on the server. Supplied
     * to the entity spawner so continuous spawning and despawning consider all players,
     * not just the local one.
     */
    private List<Vector3f> collectPlayerSpawnAnchors() {
        Collection<ServerPlayer> roster = ctx.players();
        if (roster.isEmpty()) return List.of();
        List<Vector3f> out = new ArrayList<>(roster.size());
        for (ServerPlayer sp : roster) {
            if (!sp.handshakeDone()) continue;
            out.add(new Vector3f(sp.x(), sp.y(), sp.z()));
        }
        return out;
    }

    // ─── Per-frame pump (host game thread) ────────────────────────────────────────

    public void tick() {
        ServerInboundQueue queue = networkServer.inboundQueue();
        queue.drain(this::dispatch);

        long now = System.nanoTime();
        if (lastTickNs == 0L) {
            lastTickNs = now;
        }
        tickAccumulatorNs += now - lastTickNs;
        lastTickNs = now;
        if (tickAccumulatorNs > MAX_ACCUMULATOR_NS) {
            tickAccumulatorNs = MAX_ACCUMULATOR_NS;
        }
        while (tickAccumulatorNs >= TICK_PERIOD_NS) {
            tickAccumulatorNs -= TICK_PERIOD_NS;
            replicationTick();
        }
    }

    private void replicationTick() {
        // Authoritative world simulation on the headless server world: water/furnace/features,
        // entity AI + physics, mob spawning, and time. Replication handlers then ship the
        // resulting state to clients.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.tick(TICK_PERIOD_NS / 1_000_000_000f);
        }
        blockHandler.tick(ctx);
        playerHandler.tick(ctx);
        entityHandler.tick(ctx);
        chunkHandler.tick(ctx);

        // Periodically persist connected remote players' inventories (crash safety).
        if (++remotePlayerSaveCounter >= REMOTE_PLAYER_SAVE_INTERVAL_TICKS) {
            remotePlayerSaveCounter = 0;
            for (ServerPlayer sp : ctx.players()) {
                persistPlayer(sp);
            }
        }
    }

    // ─── Inbound dispatch (tick thread) ───────────────────────────────────────────

    private void dispatch(ServerInboundQueue.Envelope e) {
        switch (e.kind()) {
            case CONNECT -> handleConnect(e.connection());
            case PACKET -> handlePacket(e.connection(), e.packet());
            case DISCONNECT -> handleDisconnect(e.connection());
        }
    }

    private void handleConnect(ServerConnection conn) {
        int id = ctx.allocatePlayerId();
        ServerPlayer sp = new ServerPlayer(conn, id);
        conn.setAttachment(sp);
        System.out.println("[SERVER] Connection opened, provisional id " + id);
    }

    private void handlePacket(ServerConnection conn, Packet packet) {
        ServerPlayer sp = conn.attachment();
        if (sp == null) {
            return;
        }
        if (!sp.handshakeDone()) {
            if (packet instanceof HandshakeC2S hs) {
                handleHandshake(sp, hs);
            }
            return; // ignore anything else until the handshake is accepted
        }
        routePlay(sp, packet);
    }

    private void routePlay(ServerPlayer sp, Packet packet) {
        switch (packet) {
            case BlockChangeC2S c -> blockHandler.handleBlockChange(sp, c, ctx);
            case PlayerStateC2S ps -> playerHandler.handlePlayerState(sp, ps, ctx);
            case PlayerHeldItemC2S h -> playerHandler.handleHeldItem(sp, h, ctx);
            case ChatMessageC2S cm -> chatHandler.handleChat(sp, cm, ctx);
            case EntityDamageC2S ed -> entityHandler.handleEntityDamage(sp, ed, ctx);
            case PlayerDataC2S pd -> { if (!sp.isLocal()) sp.setPlayerDataBlob(pd.json()); }
            case ViewDistanceC2S vd -> sp.setViewDistanceChunks(vd.chunks());
            case DisconnectC2S ignored -> sp.disconnect();
            default -> { /* unknown / unexpected serverbound packet — ignore */ }
        }
    }

    private void handleHandshake(ServerPlayer sp, HandshakeC2S hs) {
        if (hs.protocolVersion() != ProtocolVersion.CURRENT) {
            String msg = "Protocol mismatch (server=" + ProtocolVersion.CURRENT
                + ", client=" + hs.protocolVersion() + ")";
            System.out.println("[SERVER] Rejecting " + sp.playerId() + ": " + msg);
            sp.send(new KickS2C(msg));
            sp.disconnect();
            return;
        }
        sp.setUsername(hs.username());
        ctx.addPlayer(sp);

        Vector3f spawn = ctx.spawn();

        // 1. Welcome: who you are + the seed + the authoritative spawn.
        sp.send(new WelcomeS2C(sp.playerId(), ctx.worldSeed(), spawn.x, spawn.y, spawn.z));

        // 2. Roster bootstrap: every player already present (no synthetic host — the local
        //    player is a normal client that announced itself like any other).
        for (ServerPlayer other : ctx.players()) {
            if (other.playerId() != sp.playerId()) {
                sp.send(new PlayerJoinS2C(other.playerId(), other.username(), other.x(), other.y(), other.z()));
            }
        }

        // 3. Tell existing clients about the newcomer.
        ctx.broadcastExcept(sp, new PlayerJoinS2C(sp.playerId(), hs.username(), spawn.x, spawn.y, spawn.z), false);

        // 4. Mark handshaked and deliver per-domain join snapshots.
        sp.markHandshakeDone();
        entityHandler.onPeerJoined(sp);
        playerHandler.onPeerJoined(sp, ctx);

        // 5. Restore a REMOTE player's saved inventory/stats (the local player is restored
        //    same-JVM). An empty payload tells the client to use fresh starting items.
        sendInitialPlayerData(sp);

        System.out.println("[SERVER] " + hs.username() + " joined as id " + sp.playerId());
    }

    /** Load a remote player's saved PlayerData blob from per-username storage and send it. */
    private void sendInitialPlayerData(ServerPlayer sp) {
        if (sp.isLocal()) {
            return; // local player restored same-JVM (player.json)
        }
        byte[] blob = new byte[0];
        ServerLevel level = ctx.serverLevel();
        if (level != null && level.saveService() != null) {
            try {
                byte[] loaded = level.saveService().loadNamedPlayer(sp.username())
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (loaded != null) {
                    blob = loaded;
                }
            } catch (Exception e) {
                System.err.println("[SERVER] Failed to load saved data for "
                        + sp.username() + ": " + e.getMessage());
            }
        }
        sp.send(new PlayerDataS2C(blob), false);
    }

    /** Persist one remote player's last-reported PlayerData blob (async). No-op for local. */
    private void persistPlayer(ServerPlayer sp) {
        if (sp == null || sp.isLocal()) {
            return;
        }
        byte[] blob = sp.playerDataBlob();
        ServerLevel level = ctx.serverLevel();
        if (blob != null && level != null && level.saveService() != null) {
            level.saveService().saveNamedPlayer(sp.username(), blob);
        }
    }

    private void handleDisconnect(ServerConnection conn) {
        ServerPlayer sp = conn.attachment();
        if (sp == null) {
            return;
        }
        boolean wasRostered = ctx.player(sp.playerId()) != null;
        persistPlayer(sp); // save the leaving remote player's last inventory/stats
        ctx.removePlayer(sp);
        if (wasRostered) {
            ctx.broadcast(new PlayerLeaveS2C(sp.playerId()), false);
            System.out.println("[SERVER] Player " + sp.playerId() + " (" + sp.username() + ") left.");
        }
    }

    // ─── Host-originated hooks (wired from game systems in the lifecycle phase) ──────

    public void onEntitySpawned(Entity e) {
        entityHandler.onEntitySpawned(e, ctx);
    }

    public void onEntityDespawned(Entity e) {
        entityHandler.onEntityDespawned(e, ctx);
    }

    public void onLocalBlockChange(int x, int y, int z, BlockType type) {
        blockHandler.onLocalBlockChange(x, y, z, type, ctx);
    }

    /** Server-side: hand a connected client an item stack (drop pickup, command-give). */
    public void giveItemTo(int playerId, int itemId, int count) {
        if (count <= 0) {
            return;
        }
        ServerPlayer sp = ctx.player(playerId);
        if (sp != null) {
            sp.send(new GiveItemS2C(itemId, count), false);
        }
    }

    public void shutdown() {
        persistAllRemotePlayersBlocking();
        blockHandler.onSessionEnd();
        entityHandler.onSessionEnd();
        networkServer.shutdown();
        System.out.println("[SERVER] Shut down.");
    }

    /**
     * Flush every connected remote player's last-reported inventory/stats to disk and WAIT for
     * it — called on shutdown before the save service closes, so quitting never loses a remote
     * player's data.
     */
    private void persistAllRemotePlayersBlocking() {
        ServerLevel level = ctx.serverLevel();
        if (level == null || level.saveService() == null) {
            return;
        }
        java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ServerPlayer sp : ctx.players()) {
            if (!sp.isLocal() && sp.playerDataBlob() != null) {
                futures.add(level.saveService().saveNamedPlayer(sp.username(), sp.playerDataBlob()));
            }
        }
        if (futures.isEmpty()) {
            return;
        }
        try {
            java.util.concurrent.CompletableFuture
                .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[SERVER] Timed out flushing remote player data: " + e.getMessage());
        }
    }
}
