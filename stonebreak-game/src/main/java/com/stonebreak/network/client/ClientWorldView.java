package com.stonebreak.network.client;

import com.openmason.engine.net.client.ClientConnection;
import com.openmason.engine.net.client.ClientInboundQueue;
import com.openmason.engine.net.client.NetworkClient;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.network.client.handlers.ClientBlockHandler;
import com.stonebreak.network.client.handlers.ClientChatHandler;
import com.stonebreak.network.client.handlers.ClientChunkHandler;
import com.stonebreak.network.client.handlers.ClientEntityHandler;
import com.stonebreak.network.client.handlers.ClientPlayerHandler;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.handshake.KeepAliveS2C;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.NeedsCharacterCreationS2C;
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
import com.stonebreak.network.packet.world.TimeSyncS2C;
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
    private int lastBroadcastHeldItemId = -1;

    private long lastTickNs = 0L;
    private long tickAccumulatorNs = 0L;

    private volatile boolean disconnected = false;
    private volatile String kickReason = null;

    // Connection liveness. The server keepalives every ~5 s; if NOTHING arrives for this
    // long the peer is considered gone and the client returns to the menu. Applies to
    // remote (TCP) sessions only — the in-JVM Local channel can't silently half-open, and
    // a long same-JVM stall (debugger, world gen hitch) must not self-disconnect SP/host.
    private static final long INBOUND_SILENCE_TIMEOUT_NS = 30_000_000_000L; // 30 s
    private volatile long lastInboundNs = 0L;
    /** Last RTT the server measured for us (ms), for the debug overlay. -1 = not yet known. */
    private volatile int lastRttMs = -1;

    // Latest authoritative TimeSyncS2C tick sample, buffered when it arrives before the
    // client world/clock exists (the world builds async after WelcomeS2C); the world
    // bootstrap seeds the clock from it. null until the first sample.
    private volatile Long pendingServerTimeTicks = null;

    // Late-join character creation flow: server sent NeedsCharacterCreationS2C instead of
    // WelcomeS2C, so we show the character creation screen before building the world.
    private volatile boolean needsCharacterCreation = false;
    private volatile long pendingWorldSeed = 0L;
    private volatile int pendingPlayerId = -1;
    /** True while waiting for the server's welcome after character creation submit. */
    private volatile boolean characterCreationSubmitted = false;

    // ─── Desync detection ────────────────────────────────────────────────────
    /** Per-chunk cooldown between resync requests (prevents request storms on a bad chunk). */
    private static final long RESYNC_COOLDOWN_NS = 5_000_000_000L; // 5 s
    private final java.util.Map<Long, Long> lastResyncRequestNs = new java.util.HashMap<>();
    /** Chunk-hash audit cadence: every 200 client ticks (10 s at 20 Hz), ≤16 chunks/round. */
    private static final int AUDIT_PERIOD_TICKS = 200;
    private static final int AUDIT_CHUNKS_PER_ROUND = 16;
    private int auditCounter = 0;
    private int auditCursor = 0;

    // Remote (TCP) player-data sync. The in-process local player is persisted same-JVM and does
    // NOT use this path. A remote client first receives its saved PlayerData (PlayerDataS2C),
    // applies it, THEN periodically sends its own (PlayerDataC2S) — so an empty inventory can't
    // overwrite the save before the restore lands.
    private static final long PLAYER_DATA_SEND_PERIOD_NS = 3_000_000_000L; // 3s
    private final com.stonebreak.world.save.serialization.JsonPlayerSerializer playerSerializer =
            new com.stonebreak.world.save.serialization.JsonPlayerSerializer();
    private volatile boolean remote = false;
    private volatile boolean restoreReceived = false;
    private volatile byte[] restoreBlob;
    private boolean restoreApplied = false;
    private long lastPlayerDataSendNs = 0L;

    /** Connect (Local or TCP) and send the handshake. */
    public void connect(NetAddress address, String username) throws InterruptedException {
        this.remote = address.type() == com.openmason.engine.net.transport.TransportType.TCP;
        connection = networkClient.connect(address);
        connection.send(new HandshakeC2S(ProtocolVersion.CURRENT, username));
        // Report our render distance right after the handshake (channel is FIFO, so the
        // server processes the handshake first). Without this the server streams its
        // default view radius no matter what the user's setting says.
        sendViewDistance(com.stonebreak.config.Settings.getInstance().getRenderDistance());
        lastTickNs = System.nanoTime();
        lastInboundNs = lastTickNs; // arm the silence timer from connect, not first packet
        tickAccumulatorNs = 0L;
    }

    /**
     * Tell the server our render distance (chunks). Called on connect and whenever the
     * player applies a new render-distance setting; the server resizes this player's
     * chunk-streaming view accordingly.
     */
    public void sendViewDistance(int chunks) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.player.ViewDistanceC2S(chunks));
        }
    }

    public int localPlayerId() { return localPlayerId; }
    public boolean isDisconnected() { return disconnected || connection == null || !connection.isActive(); }
    public String kickReason() { return kickReason; }
    /** Last server-measured round-trip time in ms; -1 until the first keepalive echo lands. */
    public int lastRttMs() { return lastRttMs; }
    /** Latest buffered authoritative world-time sample, or null before the first TimeSyncS2C. */
    public Long pendingServerTimeTicks() { return pendingServerTimeTicks; }
    /** Live replicated entity shadows tracked by this client (debug overlay). */
    public int trackedEntityShadows() { return entityHandler.trackedShadowCount(); }
    /** True once a remote client has applied its server-sent player data (or for a non-remote
     *  client, trivially true — it doesn't use the network restore path). */
    public boolean isRestoreApplied() { return !remote || restoreApplied; }

    // ─── Per-frame pump (game thread) ─────────────────────────────────────────

    public void tick() {
        networkClient.inboundQueue().drain(this::dispatch);

        // Dead-peer detection (remote only): the server keepalives every ~5 s, so 30 s of
        // total silence means the connection is gone even if the OS hasn't noticed yet.
        if (remote && !disconnected && lastInboundNs != 0L
                && System.nanoTime() - lastInboundNs > INBOUND_SILENCE_TIMEOUT_NS) {
            kickReason = "Connection timed out";
            disconnected = true;
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
        playerHandler.tick();
        sendLocalPlayerState();
        sendPlayerDataIfDue();
        if (++auditCounter >= AUDIT_PERIOD_TICKS) {
            auditCounter = 0;
            sendChunkHashAudit();
        }
    }

    /**
     * Chunk-hash desync audit: hash a small round-robin batch of resident chunks around the
     * player and ship them; the server re-streams any that mismatch its own state. ~0.1 ms
     * per chunk, 16 chunks per 10 s — near-zero steady-state cost, and it catches divergence
     * the block-change feed can't (missed applies, corruption).
     */
    private void sendChunkHashAudit() {
        ClientConnection conn = connection;
        com.stonebreak.world.World world = Game.getWorld();
        Player p = Game.getPlayer();
        if (conn == null || !conn.isActive() || world == null || p == null) {
            return;
        }
        int pcx = (int) Math.floor(p.getPosition().x / 16.0);
        int pcz = (int) Math.floor(p.getPosition().z / 16.0);
        int radius = com.stonebreak.config.Settings.getInstance().getRenderDistance();

        // Deterministic ring order; the cursor rotates coverage across rounds.
        int side = radius * 2 + 1;
        int total = side * side;
        int[] entries = new int[AUDIT_CHUNKS_PER_ROUND * 3];
        int n = 0;
        for (int step = 0; step < total && n < AUDIT_CHUNKS_PER_ROUND; step++) {
            int idx = (auditCursor + step) % total;
            int cx = pcx + (idx % side) - radius;
            int cz = pcz + (idx / side) - radius;
            var chunk = world.getChunkIfLoaded(cx, cz);
            if (chunk == null) {
                continue;
            }
            entries[n * 3] = cx;
            entries[n * 3 + 1] = cz;
            entries[n * 3 + 2] = com.stonebreak.network.bridge.ChunkHasher.hash(chunk);
            n++;
        }
        auditCursor = (auditCursor + Math.max(1, n)) % total;
        if (n > 0) {
            int[] trimmed = java.util.Arrays.copyOf(entries, n * 3);
            conn.send(new com.stonebreak.network.packet.world.ChunkHashesC2S(trimmed), false);
        }
    }

    /**
     * Ask the server for a fresh snapshot of one chunk (decode/apply failure locally).
     * Per-chunk cooldown so a persistently-bad chunk can't spam the wire.
     */
    public void requestChunkResync(int cx, int cz) {
        ClientConnection conn = connection;
        if (conn == null || !conn.isActive()) {
            return;
        }
        long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        long now = System.nanoTime();
        Long last = lastResyncRequestNs.get(key);
        if (last != null && now - last < RESYNC_COOLDOWN_NS) {
            return;
        }
        lastResyncRequestNs.put(key, now);
        conn.send(new com.stonebreak.network.packet.world.ChunkResyncRequestC2S(cx, cz), false);
        System.out.println("[CLIENT] Requested chunk resync for (" + cx + "," + cz + ")");
    }

    /**
     * User-initiated full resync (pause-menu button): hash EVERY resident chunk in render
     * distance and ship the batches at once — the server re-streams any that mismatch — plus
     * one entity-snapshot resync. Routed through the hash-audit path rather than per-chunk
     * {@code ChunkResyncRequestC2S} because explicit resync requests share the server's
     * 32-per-10s budget while hash audits are compared server-side for free.
     *
     * @return number of chunks audited, or -1 when there is no live connection
     */
    public int requestFullResync() {
        ClientConnection conn = connection;
        com.stonebreak.world.World world = Game.getWorld();
        Player p = Game.getPlayer();
        if (conn == null || !conn.isActive() || world == null || p == null) {
            return -1;
        }
        int pcx = (int) Math.floor(p.getPosition().x / 16.0);
        int pcz = (int) Math.floor(p.getPosition().z / 16.0);
        int radius = com.stonebreak.config.Settings.getInstance().getRenderDistance();
        int maxPerPacket = com.stonebreak.network.packet.world.ChunkHashesC2S.MAX_ENTRIES;

        int[] entries = new int[maxPerPacket * 3];
        int n = 0;
        int audited = 0;
        for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
            for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
                var chunk = world.getChunkIfLoaded(cx, cz);
                if (chunk == null) {
                    continue;
                }
                entries[n * 3] = cx;
                entries[n * 3 + 1] = cz;
                entries[n * 3 + 2] = com.stonebreak.network.bridge.ChunkHasher.hash(chunk);
                audited++;
                if (++n == maxPerPacket) {
                    conn.send(new com.stonebreak.network.packet.world.ChunkHashesC2S(
                            entries.clone()), false);
                    n = 0;
                }
            }
        }
        if (n > 0) {
            conn.send(new com.stonebreak.network.packet.world.ChunkHashesC2S(
                    java.util.Arrays.copyOf(entries, n * 3)), false);
        }
        requestEntityResync();
        System.out.println("[CLIENT] Manual full resync: audited " + audited + " chunks.");
        return audited;
    }

    /** Ask the server to re-send the full entity spawn snapshot (shadow map inconsistency). */
    public void requestEntityResync() {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.entity.EntityResyncC2S(), false);
            System.out.println("[CLIENT] Requested entity resync.");
        }
    }

    /** True once the server's PlayerData blob has arrived (remote restore can be applied). */
    public boolean isRestoreReceived() { return restoreReceived; }

    /**
     * Apply the server-restored PlayerData to a SPECIFIC player (the one the world bootstrap just
     * created). Driven from the build thread rather than auto-applied to {@code Game.getPlayer()},
     * so a re-open never restores into a stale previous-session player. Empty blob ⇒ a fresh
     * player on this server, so give starting items.
     */
    public void applyRestoreTo(Player p) {
        if (p == null) {
            return;
        }
        byte[] blob = restoreBlob;
        if (blob != null && blob.length > 0) {
            // Check if this is a character creation blob (has "background" key) or a full save
            if (isCharacterCreationBlob(blob)) {
                p.getCharacterStats().applyFromJoinData(blob);
                System.out.println("[CLIENT] Applied character creation data from server.");
            } else {
                try {
                    com.stonebreak.world.save.util.StateConverter.applyPlayerData(
                            p, playerSerializer.deserialize(blob));
                    System.out.println("[CLIENT] Restored saved player data from server.");
                } catch (Exception e) {
                    System.err.println("[CLIENT] Failed to apply restored player data: " + e.getMessage());
                    p.giveStartingItems();
                }
            }
        } else {
            p.giveStartingItems(); // first time on this server
            System.out.println("[CLIENT] New player on this server — gave starting items.");
        }
        restoreApplied = true;
    }

    /** Check if JSON blob is a character creation payload (vs. a full PlayerData save). */
    private boolean isCharacterCreationBlob(byte[] blob) {
        String s = new String(blob, java.nio.charset.StandardCharsets.UTF_8);
        return s.contains("\"background\"");
    }

    /** Periodically push our full inventory/stats to the server so it can persist them (remote). */
    private void sendPlayerDataIfDue() {
        if (!remote || !restoreApplied || connection == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPlayerDataSendNs < PLAYER_DATA_SEND_PERIOD_NS) {
            return;
        }
        lastPlayerDataSendNs = now;
        sendPlayerDataNow();
    }

    private void sendPlayerDataNow() {
        Player p = Game.getPlayer();
        if (p == null || connection == null) {
            return;
        }
        try {
            byte[] json = playerSerializer.serialize(
                    com.stonebreak.world.save.util.StateConverter.toPlayerData(
                            p, Game.getInstance().getCurrentWorldName()));
            connection.send(new com.stonebreak.network.packet.player.PlayerDataC2S(json), false);
        } catch (Exception e) {
            System.err.println("[CLIENT] Failed to send player data: " + e.getMessage());
        }
    }

    private void sendLocalPlayerState() {
        Player p = Game.getPlayer();
        if (p == null || connection == null) {
            return;
        }
        // During a client-world rebuild (rejoin) Game.getPlayer() is still the PREVIOUS
        // session's player — reporting its stale position would misdirect the server's
        // chunk streaming and reach checks until the swap lands.
        if (!Game.isClientWorldReady()) {
            return;
        }
        Vector3f pos = p.getPosition();
        float yaw = p.getCamera() != null ? p.getCamera().getYaw() : 0f;
        float pitch = p.getCamera() != null ? p.getCamera().getPitch() : 0f;
        byte flags = 0;
        if (p.isSprinting()) flags |= com.stonebreak.network.packet.player.PlayerStateFlags.SPRINTING;
        if (p.isInWater())   flags |= com.stonebreak.network.packet.player.PlayerStateFlags.SWIMMING;
        if (!p.isOnGround()) flags |= com.stonebreak.network.packet.player.PlayerStateFlags.AIRBORNE;
        if (p.isOnGround())  flags |= com.stonebreak.network.packet.player.PlayerStateFlags.ON_GROUND;
        if (p.isAttacking()) flags |= com.stonebreak.network.packet.player.PlayerStateFlags.ATTACKING;
        connection.send(new PlayerStateC2S(pos.x, pos.y, pos.z, yaw, pitch, flags), true);

        if (p.getInventory() != null) {
            int held = p.getInventory().getSelectedBlockTypeId();
            if (held != lastBroadcastHeldItemId) {
                lastBroadcastHeldItemId = held;
                connection.send(new PlayerHeldItemC2S(held), false);
            }
        }
    }

    // ─── Local intents (wired from game systems in the lifecycle phase) ──────────

    /** Local-player block edit: send the intent (with the prev block the player saw) to the server. */
    public void onLocalBlockChange(int x, int y, int z, BlockType type, BlockType prevType) {
        if (connection == null) {
            return;
        }
        short id = (short) (type == null ? 0 : type.getId());
        short prevId = (short) (prevType == null ? 0 : prevType.getId());
        connection.send(new BlockChangeC2S(x, y, z, id, prevId), false);
    }

    /**
     * Toss an item (Q / inventory drop) or return give-overflow: the server spawns the
     * authoritative drop entity, replicated to everyone including us. No local spawn — a
     * client-local drop would be invisible to other players.
     */
    public void sendDropItem(int itemId, int count) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive() && itemId > 0 && count > 0) {
            conn.send(new com.stonebreak.network.packet.player.DropItemC2S(itemId, count), false);
        }
    }

    /** /timeset intent: ask the server to set the authoritative world clock. */
    public void sendTimeSet(long ticks) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.TimeSetC2S(ticks), false);
        }
    }

    /** Snow-layer intent for a layer change with no block change (see {@code SnowLayerC2S}). */
    public void sendSnowLayer(int x, int y, int z, int layers) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.SnowLayerC2S(x, y, z, (byte) layers), false);
        }
    }

    /** Furnace slot intent from the open furnace UI (see {@code FurnaceSlotsC2S}). */
    public void sendFurnaceSlots(int x, int y, int z, String slots) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive() && slots != null) {
            conn.send(new com.stonebreak.network.packet.world.FurnaceSlotsC2S(x, y, z, slots), false);
        }
    }

    /**
     * Toggleable-block interaction intent (door open/close — see {@code BlockToggleC2S}).
     *
     * @return true when the intent was sent (the server will echo the new state)
     */
    public boolean sendBlockToggle(int x, int y, int z) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.BlockToggleC2S(x, y, z), false);
            return true;
        }
        return false;
    }

    /** Projectile / ability-entity launch intent (see {@code ProjectileSpawnC2S}). */
    public void sendProjectileSpawn(byte kind, Vector3f pos, Vector3f v, float... params) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.entity.ProjectileSpawnC2S(
                kind, pos.x, pos.y, pos.z, v.x, v.y, v.z, params), false);
        }
    }

    /** Local hit on a replicated entity: forward the damage intent to the authoritative server. */
    public void sendEntityDamage(int targetNetworkId, float amount,
                                 com.stonebreak.mobs.entities.LivingEntity.DamageSource source) {
        if (connection == null || targetNetworkId < 0 || amount <= 0f) {
            return;
        }
        connection.send(new EntityDamageC2S(targetNetworkId, amount, (byte) source.ordinal()), false);
    }

    /**
     * Local chat submission: send to the server. The server broadcasts to everyone INCLUDING
     * the sender, so there is no optimistic local echo (that would double-print).
     */
    public void submitChat(String text) {
        if (connection == null || text == null || text.isBlank()) {
            return;
        }
        connection.send(new ChatMessageC2S(text), false);
    }

    public void shutdown() {
        if (connection != null) {
            try {
                // Final inventory flush before leaving so the latest state persists (remote only).
                if (remote && restoreApplied) {
                    sendPlayerDataNow();
                }
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
        lastInboundNs = System.nanoTime();
        switch (e.kind()) {
            case CONNECT -> { /* nothing — handshake already sent on connect() */ }
            case PACKET -> {
                // Per-packet guard: this runs unguarded on the MAIN thread (Game.update →
                // MultiplayerSession.tick), where an escaping handler exception kills the
                // process with no crash log — the classic silent disconnect/reconnect crash.
                // One bad packet is logged and skipped; the stream continues.
                try {
                    route(e.packet());
                } catch (Exception ex) {
                    System.err.println("[CLIENT] Packet handler failed for "
                        + e.packet().getClass().getSimpleName() + ": " + ex);
                    ex.printStackTrace();
                }
            }
            case DISCONNECT -> disconnected = true;
        }
    }

    private void route(Packet packet) {
        switch (packet) {
            case WelcomeS2C w -> handleWelcome(w);
            case NeedsCharacterCreationS2C ncc -> handleNeedsCharacterCreation(ncc);
            case KickS2C k -> { kickReason = k.reason(); disconnected = true; }
            case ChunkDataS2C cd -> chunkHandler.apply(cd);
            case BlockChangeS2C b -> blockHandler.applyBlockChange(b);
            case MultiBlockChangeS2C m -> blockHandler.applyMultiBlock(m);
            case com.stonebreak.network.packet.world.BlockMetaS2C bm -> blockHandler.applyBlockMeta(bm);
            case com.stonebreak.network.packet.world.BlockStateS2C bs -> blockHandler.applyBlockState(bs);
            case ChatMessageS2C cm -> chatHandler.apply(cm);
            case PlayerStateS2C ps -> playerHandler.handlePlayerState(localPlayerId, ps);
            case PlayerJoinS2C j -> playerHandler.handleJoin(localPlayerId, j);
            case PlayerLeaveS2C l -> playerHandler.handleLeave(l);
            case PlayerHeldItemS2C h -> playerHandler.handleHeldItem(localPlayerId, h);
            case GiveItemS2C g -> playerHandler.handleGiveItem(g);
            case com.stonebreak.network.packet.player.KillCreditS2C kc -> playerHandler.handleKillCredit(kc);
            case com.stonebreak.network.packet.player.PlayerDataS2C pd -> {
                restoreBlob = pd.json();
                restoreReceived = true;
            }
            case EntitySpawnS2C s -> entityHandler.applySpawn(s);
            case EntityDespawnS2C d -> entityHandler.applyDespawn(d.networkId());
            case EntityMoveS2C mv -> entityHandler.applyDelta(mv);
            case EntityTeleportS2C t -> entityHandler.applyTeleport(t);
            case EntityAnimS2C a -> entityHandler.applyAnim(a.networkId(), a.state());
            case KeepAliveS2C ka -> {
                lastRttMs = ka.lastRttMs();
                ClientConnection conn = connection;
                if (conn != null && conn.isActive()) {
                    conn.send(new KeepAliveC2S(ka.nonce()), false);
                }
            }
            case TimeSyncS2C ts -> handleTimeSync(ts);
            default -> { /* unexpected clientbound packet — ignore */ }
        }
    }

    /**
     * Authoritative world-time sample. Before the client world/clock exists (the world
     * builds async after WelcomeS2C) the sample is buffered for the bootstrap to seed the
     * clock; afterwards the local clock free-runs and each sample snaps/converges it.
     */
    /** Called when the server tells us we need to create a character before entering the world. */
    private void handleNeedsCharacterCreation(NeedsCharacterCreationS2C ncc) {
        pendingPlayerId = ncc.playerId();
        pendingWorldSeed = ncc.worldSeed();
        needsCharacterCreation = true;
        // Signal the game to enter the character creation screen.
        Game.getInstance().setState(com.stonebreak.core.GameState.CHARACTER_CREATION);
        System.out.println("[CLIENT] Needs character creation, world seed " + pendingWorldSeed);
    }

    /** Submit the player's character creation data to the server. */
    public void submitCharacterCreation(byte[] json) {
        if (connection == null || !connection.isActive()) {
            return;
        }
        connection.send(new com.stonebreak.network.packet.player.CharacterCreationC2S(json), false);
        characterCreationSubmitted = true;
        System.out.println("[CLIENT] Submitted character creation data to server.");
    }

    /** True when the server asked for character creation and we haven't submitted yet. */
    public boolean isNeedsCharacterCreation() { return needsCharacterCreation; }

    /** True while waiting for the server's welcome after submitting character creation. */
    public boolean isCharacterCreationSubmitted() { return characterCreationSubmitted; }

    /** World seed to use after character creation is complete. */
    public long getPendingWorldSeed() { return pendingWorldSeed; }

    private void handleTimeSync(TimeSyncS2C ts) {
        pendingServerTimeTicks = ts.worldTimeTicks();
        com.stonebreak.world.TimeOfDay clock = Game.getTimeOfDay();
        if (clock != null) {
            clock.nudgeTo(ts.worldTimeTicks());
            clock.setTimeSpeed(ts.timeSpeed());
            clock.setFrozen(ts.frozen());
        }
    }

    private void handleWelcome(WelcomeS2C w) {
        localPlayerId = w.playerId();
        Vector3f spawn = new Vector3f(w.spawnX(), w.spawnY(), w.spawnZ());
        // Clear character creation flow state — we're now in the world.
        needsCharacterCreation = false;
        characterCreationSubmitted = false;
        // Build the client RENDER world: no terrain generation, no save service — chunks stream
        // in from the server. The local player is positioned at the authoritative spawn.
        String label = Game.getInstance().getCurrentWorldName() != null
            ? Game.getInstance().getCurrentWorldName()
            : "mp_" + System.currentTimeMillis();
        Game.getInstance().startClientWorld(label, w.worldSeed(), spawn);
    }
}
