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
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
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

        System.out.println("[SERVER] " + hs.username() + " joined as id " + sp.playerId());
    }

    private void handleDisconnect(ServerConnection conn) {
        ServerPlayer sp = conn.attachment();
        if (sp == null) {
            return;
        }
        boolean wasRostered = ctx.player(sp.playerId()) != null;
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
        blockHandler.onSessionEnd();
        entityHandler.onSessionEnd();
        networkServer.shutdown();
        System.out.println("[SERVER] Shut down.");
    }
}
