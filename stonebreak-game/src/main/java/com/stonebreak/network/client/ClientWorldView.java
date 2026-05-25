package com.stonebreak.network.client;

import com.openmason.engine.net.client.ClientConnection;
import com.openmason.engine.net.client.ClientInboundQueue;
import com.openmason.engine.net.client.NetworkClient;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.network.client.handlers.ClientBlockHandler;
import com.stonebreak.network.client.handlers.ClientChatHandler;
import com.stonebreak.network.client.handlers.ClientChunkHandler;
import com.stonebreak.network.client.handlers.ClientEntityHandler;
import com.stonebreak.network.client.handlers.ClientPlayerHandler;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityStateS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

/**
 * Client-side counterpart to {@link com.stonebreak.network.server.IntegratedServer}: owns
 * the engine {@link NetworkClient}, applies authoritative S2C packets onto the local
 * {@code Game} world (via the per-domain client handlers), and sends local intents
 * (player state at 20 Hz, held item, block edits, chat).
 *
 * <p>Per the design, the client keeps {@code Game.get*()} — the locally rendered world is
 * the legitimate Game. {@link #tick()} runs on the game thread; the engine event-loop only
 * enqueues.
 */
public final class ClientWorldView {

    private static final long TICK_PERIOD_NS = 50_000_000L;        // 20 Hz
    private static final long MAX_ACCUMULATOR_NS = TICK_PERIOD_NS * 5;

    private final NetworkClient networkClient = new NetworkClient(StonebreakProtocol.registry());
    private volatile ClientConnection connection;

    private final ClientChunkHandler chunkHandler = new ClientChunkHandler();
    private final ClientBlockHandler blockHandler = new ClientBlockHandler();
    private final ClientEntityHandler entityHandler = new ClientEntityHandler();
    private final ClientPlayerHandler playerHandler = new ClientPlayerHandler();
    private final ClientChatHandler chatHandler = new ClientChatHandler();

    private int localPlayerId = -1;
    private Vector3f pendingSpawnTeleport;
    private int lastBroadcastHeldItemId = -1;

    private long lastTickNs = 0L;
    private long tickAccumulatorNs = 0L;

    private volatile boolean disconnected = false;
    private volatile String kickReason = null;

    /** Connect (Local or TCP) and send the handshake. */
    public void connect(NetAddress address, String username) throws InterruptedException {
        connection = networkClient.connect(address);
        connection.send(new HandshakeC2S(ProtocolVersion.CURRENT, username));
        lastTickNs = System.nanoTime();
        tickAccumulatorNs = 0L;
    }

    public int localPlayerId() { return localPlayerId; }
    public boolean isDisconnected() { return disconnected || connection == null || !connection.isActive(); }
    public String kickReason() { return kickReason; }

    // ─── Per-frame pump (game thread) ─────────────────────────────────────────

    public void tick() {
        networkClient.inboundQueue().drain(this::dispatch);

        // Apply the host-advertised spawn once the local Player exists (world gen is async).
        if (pendingSpawnTeleport != null) {
            Player p = Game.getPlayer();
            if (p != null) {
                p.setPosition(pendingSpawnTeleport);
                pendingSpawnTeleport = null;
            }
        }

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
            clientTick();
        }
    }

    private void clientTick() {
        chunkHandler.tick();
        entityHandler.tick();
        sendLocalPlayerState();
    }

    private void sendLocalPlayerState() {
        Player p = Game.getPlayer();
        if (p == null || connection == null) {
            return;
        }
        Vector3f pos = p.getPosition();
        float yaw = p.getCamera() != null ? p.getCamera().getYaw() : 0f;
        float pitch = p.getCamera() != null ? p.getCamera().getPitch() : 0f;
        connection.send(new PlayerStateC2S(pos.x, pos.y, pos.z, yaw, pitch), true);

        if (p.getInventory() != null) {
            int held = p.getInventory().getSelectedBlockTypeId();
            if (held != lastBroadcastHeldItemId) {
                lastBroadcastHeldItemId = held;
                connection.send(new PlayerHeldItemC2S(held), false);
            }
        }
    }

    // ─── Local intents (wired from game systems in the lifecycle phase) ──────────

    /** Local-player block edit: send the intent to the server. */
    public void onLocalBlockChange(int x, int y, int z, BlockType type) {
        if (connection == null) {
            return;
        }
        short id = (short) (type == null ? 0 : type.getId());
        connection.send(new BlockChangeC2S(x, y, z, id), false);
    }

    /** Local chat submission: send to the server, with an optimistic local echo. */
    public void submitChat(String text) {
        if (connection == null || text == null || text.isBlank()) {
            return;
        }
        connection.send(new ChatMessageC2S(text), false);
        String name = Settings.getInstance().getMultiplayerUsername();
        chatHandler.apply(new ChatMessageS2C(localPlayerId, name, text));
    }

    public void shutdown() {
        if (connection != null) {
            try {
                connection.send(new DisconnectC2S("client_quit"));
            } catch (RuntimeException ignored) {
                // best-effort notice
            }
        }
        chunkHandler.onSessionEnd();
        entityHandler.onSessionEnd();
        playerHandler.onSessionEnd();
        networkClient.shutdown();
        connection = null;
    }

    // ─── Inbound dispatch ─────────────────────────────────────────────────────

    private void dispatch(ClientInboundQueue.Event e) {
        switch (e.kind()) {
            case CONNECT -> { /* nothing — handshake already sent on connect() */ }
            case PACKET -> route(e.packet());
            case DISCONNECT -> disconnected = true;
        }
    }

    private void route(Packet packet) {
        switch (packet) {
            case WelcomeS2C w -> handleWelcome(w);
            case KickS2C k -> { kickReason = k.reason(); disconnected = true; }
            case ChunkDataS2C cd -> chunkHandler.apply(cd);
            case BlockChangeS2C b -> blockHandler.applyBlockChange(b);
            case MultiBlockChangeS2C m -> blockHandler.applyMultiBlock(m);
            case PlayerStateS2C ps -> playerHandler.handlePlayerState(localPlayerId, ps);
            case PlayerJoinS2C j -> playerHandler.handleJoin(localPlayerId, j);
            case PlayerLeaveS2C l -> playerHandler.handleLeave(l);
            case PlayerHeldItemS2C h -> playerHandler.handleHeldItem(localPlayerId, h);
            case GiveItemS2C g -> playerHandler.handleGiveItem(g);
            case EntitySpawnS2C s -> entityHandler.applySpawn(s);
            case EntityDespawnS2C d -> entityHandler.applyDespawn(d.networkId());
            case EntityMoveS2C mv -> entityHandler.applyDelta(mv);
            case EntityTeleportS2C t -> entityHandler.applyTeleport(t);
            case EntityStateS2C es -> entityHandler.applyAbsolute(es.networkId(), es.x(), es.y(), es.z(), es.yaw());
            default -> { /* unexpected clientbound packet — ignore */ }
        }
    }

    private void handleWelcome(WelcomeS2C w) {
        localPlayerId = w.playerId();
        pendingSpawnTeleport = new Vector3f(w.spawnX(), w.spawnY(), w.spawnZ());
        String name = "mp_" + System.currentTimeMillis();
        Game.getInstance().startWorldGeneration(name, w.worldSeed());
    }
}
