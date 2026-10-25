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
import com.stonebreak.network.packet.player.DeathDropC2S;
import com.stonebreak.network.packet.player.HotbarSelectC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerPrefsC2S;
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
    /** Least time between two InventoryResyncC2S requests. */
    private static final long RESYNC_REQUEST_INTERVAL_NS = 250_000_000L;
    private long lastInventoryResyncNs = Long.MIN_VALUE / 2;
    /** Per-frame time for applying inbound packets; the rest wait for the next frame. */
    private static final long INBOUND_DRAIN_BUDGET_NS = 8_000_000L;

    private final NetworkClient networkClient = new NetworkClient(StonebreakProtocol.registry());
    private volatile ClientConnection connection;

    private final ClientChunkHandler chunkHandler = new ClientChunkHandler();
    private final ClientBlockHandler blockHandler = new ClientBlockHandler(chunkHandler);
    private final ClientEntityHandler entityHandler = new ClientEntityHandler();
    private final ClientPlayerHandler playerHandler = new ClientPlayerHandler();
    private final ClientChatHandler chatHandler = new ClientChatHandler();

    private int localPlayerId = -1;
    private int lastBroadcastHotbarSlot = -1;
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
    // Latest WeatherSyncS2C, buffered the same way for the weather clock.
    private volatile com.stonebreak.network.packet.world.WeatherSyncS2C pendingWeatherSync = null;

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
    /** Chunks dropped since the last flush, packed {@code [cx, cz, ...]}; main-thread only (#363). */
    /**
     * Chunk drops waiting to be reported. Appended from the main thread (late-install
     * discards) AND the world-update worker (the client unload sweep runs inside
     * {@code World.updateClient} on {@code worldUpdateExecutor}), so guarded by its own lock.
     */
    private final Object unloadedChunksLock = new Object();
    private int[] unloadedChunks = new int[64];
    private int unloadedChunkCount = 0;

    // Remote player-prefs sync (validated server-side, issue #334). The inventory is NOT in
    // here any more — the server owns it (InventorySyncS2C flows the other way).
    private static final long PLAYER_PREFS_SEND_PERIOD_NS = 3_000_000_000L; // 3s
    private final com.stonebreak.world.save.serialization.JsonPlayerSerializer playerSerializer =
            new com.stonebreak.world.save.serialization.JsonPlayerSerializer();
    private volatile boolean remote = false;
    private volatile boolean restoreReceived = false;
    private volatile byte[] restoreBlob;
    private boolean restoreApplied = false;
    private long lastPlayerPrefsSendNs = 0L;
    /** Numbers prefs reports and filters stale progression echoes (issue #334). */
    private final PrefsSequencer prefsSequencer = new PrefsSequencer();

    /** Join secret presented in the handshake; a different one in WelcomeS2C goes to the sink (#335). */
    private String presentedSecret = "";
    private java.util.function.Consumer<String> secretSink;

    /** Connect (Local or TCP) and send the handshake. */
    public void connect(NetAddress address, String username) throws InterruptedException {
        connect(address, username, "", null);
    }

    /**
     * Connect and present {@code secret}, the join secret this server issued us last time ("" if
     * none); a newly issued one is handed to {@code secretSink} on the welcome (issue #335).
     */
    public void connect(NetAddress address, String username, String secret,
                        java.util.function.Consumer<String> secretSink) throws InterruptedException {
        this.remote = address.type() == com.openmason.engine.net.transport.TransportType.TCP;
        this.presentedSecret = secret == null ? "" : secret;
        this.secretSink = secretSink;
        connection = networkClient.connect(address);
        // A new server acknowledges only this session's actions (issue #334, #353).
        com.stonebreak.network.SlotIntentSeq.markSessionStart();
        InventorySyncApplier.resetSession();
        connection.send(new HandshakeC2S(ProtocolVersion.CURRENT, username, presentedSecret));
        if (!remote) {
            // The in-process host created its character in the menu, before the server
            // existed. Hand it over right behind the handshake (the channel is FIFO): when the
            // world has no saved player the server waits for it and builds its record from
            // it — the same character this client plays — instead of a default one (issue
            // #334). Ignored by the server when the world already has a saved player.
            connection.send(new com.stonebreak.network.packet.player.CharacterCreationC2S(
                    hostCreationJson()), false);
        }
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
    /** Latest buffered authoritative weather sample, or null before the first WeatherSyncS2C. */
    public com.stonebreak.network.packet.world.WeatherSyncS2C pendingWeatherSync() { return pendingWeatherSync; }
    /** Live replicated entity shadows tracked by this client (debug overlay). */
    public int trackedEntityShadows() { return entityHandler.trackedShadowCount(); }
    public com.stonebreak.mobs.entities.Entity entityShadow(int networkId) { return entityHandler.shadow(networkId); }
    /** True once a remote client has applied its server-sent player data (or for a non-remote
     *  client, trivially true — it doesn't use the network restore path). */
    public boolean isRestoreApplied() { return !remote || restoreApplied; }

    // ─── Per-frame pump (game thread) ─────────────────────────────────────────

    public void suspendClock() {
        lastTickNs = System.nanoTime();
        tickAccumulatorNs = 0;
    }

    public void tick() {
        // Budgeted: after a stall the backlog spreads over a few frames instead of one long
        // one (and past a deep backlog the queue pauses network reads — engine backpressure).
        networkClient.inboundQueue().drain(this::dispatch, INBOUND_DRAIN_BUDGET_NS);
        applyPendingInventorySync();

        // Install pump runs PER FRAME, not on the 20 Hz client tick: decoded
        // chunk snapshots drain under a wall-clock budget each frame, so a
        // streaming burst lands in mass batches (at 20 Hz the same budget
        // capped installs at a visible trickle). Deferred per-chunk edits
        // replay here too, immediately after their chunk installs.
        chunkHandler.tick();
        flushUnloadedChunks();

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
            clientTick(tickAccumulatorNs < TICK_PERIOD_NS);
        }
    }

    /**
     * @param last the final tick of this frame's catch-up: only it reports the player's state —
     *             catching up after a hitch used to send up to five identical PlayerStateC2S.
     */
    private void clientTick(boolean last) {
        entityHandler.tick();
        playerHandler.tick();
        if (last) {
            sendLocalPlayerState();
        }
        sendChunkAck();
        sendPlayerPrefsIfDue();
        if (++auditCounter >= AUDIT_PERIOD_TICKS) {
            auditCounter = 0;
            sendChunkHashAudit();
        }
    }

    /** Chunk flow control: what this client processed since the last ack, and its install rate. */
    private void sendChunkAck() {
        ClientConnection conn = connection;
        if (conn == null || !conn.isActive()) {
            return;
        }
        int processed = chunkHandler.takeProcessedCount();
        float desired = chunkHandler.desiredChunksPerTick();
        if (processed == 0 && Math.abs(desired - lastSentChunkRate) < 0.5f) {
            return;
        }
        lastSentChunkRate = desired;
        conn.send(new com.stonebreak.network.packet.world.ChunkAckC2S(processed, desired), false);
    }

    private float lastSentChunkRate = Float.NaN;

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
            if (chunk == null || chunkHandler.hasPendingInstall(cx, cz)) {
                continue; // pending install: resident state is legitimately behind the wire
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
     * Records that this client dropped chunk ({@code cx}, {@code cz}); the next {@link #tick}
     * tells the server, which forgets it so it re-streams on return (issue #363). Any thread.
     */
    public void notifyChunkUnloaded(int cx, int cz) {
        synchronized (unloadedChunksLock) {
            if (unloadedChunkCount + 2 > unloadedChunks.length) {
                unloadedChunks = java.util.Arrays.copyOf(unloadedChunks, unloadedChunks.length * 2);
            }
            unloadedChunks[unloadedChunkCount++] = cx;
            unloadedChunks[unloadedChunkCount++] = cz;
        }
    }

    private void flushUnloadedChunks() {
        int[] drained;
        synchronized (unloadedChunksLock) {
            if (unloadedChunkCount == 0) {
                return;
            }
            drained = java.util.Arrays.copyOf(unloadedChunks, unloadedChunkCount);
            unloadedChunkCount = 0;
        }
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            // Where we stand now, so the server judges "still in view" from here (#363 follow-up:
            // after a teleport its own position sample still names the old area).
            Player p = Game.getPlayer();
            int ccx = p == null ? com.stonebreak.network.packet.world.ChunkUnloadC2S.UNKNOWN_CENTER
                    : (int) Math.floor(p.getPosition().x / 16.0);
            int ccz = p == null ? com.stonebreak.network.packet.world.ChunkUnloadC2S.UNKNOWN_CENTER
                    : (int) Math.floor(p.getPosition().z / 16.0);
            int maxPerPacket = com.stonebreak.network.packet.world.ChunkUnloadC2S.MAX_CHUNKS * 2;
            for (int from = 0; from < drained.length; from += maxPerPacket) {
                int to = Math.min(drained.length, from + maxPerPacket);
                conn.send(new com.stonebreak.network.packet.world.ChunkUnloadC2S(
                    java.util.Arrays.copyOfRange(drained, from, to), ccx, ccz), false);
            }
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
                if (chunk == null || chunkHandler.hasPendingInstall(cx, cz)) {
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

    /** The host's menu-created character (what {@code Game.initWorldComponents} applies to
     *  the local player), or a default character when no creation screen exists. */
    private static byte[] hostCreationJson() {
        com.stonebreak.ui.characterCreation.CharacterCreationScreen ccs =
                Game.getInstance() != null ? Game.getInstance().getCharacterCreationScreen() : null;
        com.stonebreak.player.CharacterStats stats = ccs != null ? ccs.getCharacterStats() : null;
        return (stats != null ? stats : new com.stonebreak.player.CharacterStats(null)).toCreationJson();
    }

    /** The server's restore blob ({@code PlayerDataS2C}); package-private for tests. */
    void onPlayerData(byte[] json) {
        if (restoreApplied || (!remote && welcomeSyncSeen)) {
            // Mid-session, the server only sends this to refuse a prefs report: it re-asserts the
            // progression it last accepted. Roll ours back to it — the refused spends/feats used
            // to stay in our stats, were re-sent and refused every 3 s, and blocked every other
            // part of the report (stats, cosmetics) for the rest of the session.
            rollBackRefusedProgression(json);
            return;
        }
        restoreBlob = json;
        restoreReceived = true;
    }

    private void rollBackRefusedProgression(byte[] json) {
        Player p = Game.getPlayer();
        if (p == null || json == null || json.length == 0) {
            return;
        }
        try {
            com.stonebreak.world.save.util.StateConverter.applyProgression(p, playerSerializer.deserialize(json));
            System.out.println("[CLIENT] The server refused our progression; rolled back to its record.");
        } catch (RuntimeException e) {
            System.err.println("[CLIENT] Could not apply the server's progression record: " + e.getMessage());
        }
    }

    /** Leave the session with {@code reason}: the restore this join depends on never came. */
    public void failRestore(String reason) {
        kickReason = reason;
        disconnected = true;
    }

    /** True once the server's PlayerData blob has arrived (remote restore can be applied). */
    public boolean isRestoreReceived() { return restoreReceived; }

    /**
     * Apply the server-restored PlayerData to a SPECIFIC player (the one the world bootstrap just
     * created). Driven from the build thread rather than auto-applied to {@code Game.getPlayer()},
     * so a re-open never restores into a stale previous-session player. Empty blob ⇒ a fresh
     * player on this server, so give starting items. A creation echo ⇒ the host built this
     * character, so only the fresh-spawn reset runs here (#367). A blob this client cannot
     * read ⇒ leave with a reason instead of playing on with a stand-in character: its prefs
     * push would replace the host's record of this player, which the host keeps (#367).
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
                p.prepareServerCreatedCharacter();
                System.out.println("[CLIENT] Applied character creation data from server.");
            } else {
                try {
                    com.stonebreak.world.save.util.StateConverter.applyPlayerData(
                            p, playerSerializer.deserialize(blob));
                    System.out.println("[CLIENT] Restored saved player data from server.");
                } catch (Exception e) {
                    System.err.println("[CLIENT] Failed to apply restored player data: " + e.getMessage());
                    // restoreApplied stays false, so no prefs are ever pushed; the tick sees the
                    // disconnect, returns to the menu and closes the connection.
                    kickReason = "Could not read your player data from the host"
                            + " (the host and this client may be on different builds)";
                    disconnected = true;
                    return;
                }
            }
        } else {
            p.giveStartingItems(); // first time on this server
            System.out.println("[CLIENT] New player on this server — gave starting items.");
        }
        // A welcome-time InventorySyncS2C that raced the world build: the replica state
        // (starting items included) is authoritative over whatever the restore produced.
        InventorySyncS2C sync = pendingInventorySync.getAndSet(null);
        if (sync != null && !InventorySyncApplier.applyServerSync(p, sync.payload(), sync.ack())) {
            requestInventoryResync();
        }
        restoreApplied = true;
    }

    /** Check if JSON blob is a character creation payload (vs. a full PlayerData save). */
    private boolean isCharacterCreationBlob(byte[] blob) {
        String s = new String(blob, java.nio.charset.StandardCharsets.UTF_8);
        return s.contains("\"background\"");
    }

    /** True once the server has seeded our authoritative state and can accept prefs. */
    private boolean serverCanAcceptPrefs() {
        // Remote: after our restored data is applied. Local: after the welcome sync
        // (the server has seeded our baseline; pushing earlier would just be refused).
        return connection != null && isRestoreApplied() && (remote || welcomeSyncSeen);
    }

    /** Periodically push our prefs + progression so the server can validate them. */
    private void sendPlayerPrefsIfDue() {
        if (!serverCanAcceptPrefs()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPlayerPrefsSendNs < PLAYER_PREFS_SEND_PERIOD_NS) {
            return;
        }
        lastPlayerPrefsSendNs = now;
        sendPlayerPrefsNow();
    }

    /** Sends a prefs report now (session ending: the host's save is built from the last one). */
    public void flushPlayerPrefs() {
        if (serverCanAcceptPrefs()) {
            sendPlayerPrefsNow();
        }
    }

    private void sendPlayerPrefsNow() {
        Player p = Game.getPlayer();
        if (p == null || connection == null) {
            return;
        }
        try {
            byte[] json = playerSerializer.serialize(
                    com.stonebreak.world.save.util.StateConverter.toPlayerData(
                            p, Game.getInstance().getCurrentWorldName()));
            String text = new String(json, java.nio.charset.StandardCharsets.UTF_8);
            if (!PlayerPrefsC2S.fits(text)) {
                // Bounded in bytes, the unit the server's decoder checks; an oversized report
                // would only be refused, so do not spend a seq on it.
                System.err.println("[CLIENT] Player prefs exceed " + PlayerPrefsC2S.MAX_JSON_BYTES
                        + " bytes; not sent.");
                return;
            }
            int seq = prefsSequencer.nextSeq(p.getCharacterStats().getLedgerVersion());
            // Provisional while the save-or-discard panel is open: the server lets this
            // report's spends be undone until a closing report commits them (#421).
            boolean provisional = Game.getInstance().getStateController().isRpgSessionOpen();
            connection.send(new PlayerPrefsC2S(seq, provisional, text), false);
        } catch (Exception e) {
            System.err.println("[CLIENT] Failed to send player prefs: " + e.getMessage());
        }
    }

    /**
     * Server progression echo (issue #334). Level/xp always apply; the point pools only when
     * the echo answers our latest prefs report and our ledger has not changed since — see
     * {@link PrefsSequencer}. A stale echo's pools are replaced by our own current ones.
     */
    private void applyProgress(com.stonebreak.network.packet.player.ProgressS2C pr) {
        Player p = Game.getPlayer();
        if (p == null) {
            return;
        }
        com.stonebreak.player.CharacterStats cs = p.getCharacterStats();
        if (prefsSequencer.poolsCurrent(pr.ackSeq(), cs.getLedgerVersion())) {
            playerHandler.handleProgress(pr);
        } else {
            playerHandler.handleProgress(new com.stonebreak.network.packet.player.ProgressS2C(
                    pr.ackSeq(), pr.level(), pr.xp(), cs.getRemainingCp(),
                    cs.getRemainingSkillPoints(), cs.getRemainingFeatPoints(), cs.getRemainingAp()));
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
        if (p.isPhysicallyInWater()) flags |= com.stonebreak.network.packet.player.PlayerStateFlags.PARTIALLY_IN_WATER;
        if (!p.isOnGround()) flags |= com.stonebreak.network.packet.player.PlayerStateFlags.AIRBORNE;
        if (p.isOnGround())  flags |= com.stonebreak.network.packet.player.PlayerStateFlags.ON_GROUND;
        if (p.isAttacking()) flags |= com.stonebreak.network.packet.player.PlayerStateFlags.ATTACKING;
        connection.send(new PlayerStateC2S(pos.x, pos.y, pos.z, yaw, pitch, flags), true);

        if (p.getInventory() != null) {
            int slot = p.getInventory().getSelectedHotbarSlotIndex();
            int held = p.getInventory().getSelectedBlockTypeId();
            // The slot OR the item in it re-announces: picking an item into the selected
            // slot changes the held item without a selection change (issue #334).
            if (slot != lastBroadcastHotbarSlot || held != lastBroadcastHeldItemId) {
                lastBroadcastHotbarSlot = slot;
                lastBroadcastHeldItemId = held;
                connection.send(new HotbarSelectC2S(slot), false);
            }
        }
    }

    /**
     * Server-owned inventory (issue #334): replace the local mirror with the authoritative
     * replica state — 36 slots, the carried cursor stack and the selected hotbar slot.
     * Optimistic UI edits continue locally; this sync is the reconciliation point. A sync
     * that arrives before the local player exists (the welcome burst races the async world
     * build) is buffered and applied by {@link #applyRestoreTo}.
     */
    private void applyInventorySync(InventorySyncS2C s) {
        // The server sends its first sync only after seeding this player's authoritative
        // state (welcome/creation) — its arrival is the local player's "server is ready"
        // signal, the analogue of the remote restore gate.
        welcomeSyncSeen = true;
        // Not ready = the world (and its Player) is still being built — or rebuilt, when
        // Game.getPlayer() is still the PREVIOUS session's player, which would swallow the
        // welcome sync (a fresh character then spawned empty-handed). Buffer; the restore
        // or the next tick applies it to the right player.
        Player p = Game.getPlayer();
        if (!Game.isClientWorldReady() || p == null || p.getInventory() == null) {
            pendingInventorySync.set(s);
            return;
        }
        if (!InventorySyncApplier.applyServerSync(p, s.payload(), s.ack())) {
            requestInventoryResync();
        }
    }

    /**
     * A sync was skipped as older than one of our numbered intents: ask for a fresh one. It is
     * answered after every intent we have sent, so it applies unless we send another first —
     * then that answer is skipped too and asked for again, until the player pauses.
     */
    private void requestInventoryResync() {
        if (!com.stonebreak.network.SlotIntentSeq.skippedSyncNeedsResync()) {
            return; // our newest intent is answered with a sync anyway
        }
        // While the player keeps acting faster than a round trip, every answer is stale again
        // and asked for again — a full sync per placement. A few per second is plenty: the
        // answer to the last intent always arrives (each intent is answered with a sync).
        long now = System.nanoTime();
        if (now - lastInventoryResyncNs < RESYNC_REQUEST_INTERVAL_NS) {
            return;
        }
        lastInventoryResyncNs = now;
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.player.InventoryResyncC2S(), false);
        }
    }

    /** Latest sync that landed before this session's player existed; null once applied. */
    private final java.util.concurrent.atomic.AtomicReference<InventorySyncS2C> pendingInventorySync =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Applies a buffered sync once the world is ready (one that landed after the restore drained). */
    private void applyPendingInventorySync() {
        if (pendingInventorySync.get() == null || !Game.isClientWorldReady()) {
            return;
        }
        Player p = Game.getPlayer();
        if (p == null || p.getInventory() == null) {
            return;
        }
        InventorySyncS2C sync = pendingInventorySync.getAndSet(null);
        if (sync != null && !InventorySyncApplier.applyServerSync(p, sync.payload(), sync.ack())) {
            requestInventoryResync();
        }
    }

    /** True once the server's welcome InventorySyncS2C arrived (seeding is done). */
    private volatile boolean welcomeSyncSeen;

    // ─── Local intents (wired from game systems in the lifecycle phase) ──────────

    /** Local-player block edit: send the intent (with the prev block the player saw) to the server. */
    public void onLocalBlockChange(int x, int y, int z, BlockType type, BlockType prevType) {
        onLocalBlockChange(x, y, z, type, prevType, null);
    }

    /**
     * Same, with an optional client-proposed placement state (see
     * {@link BlockChangeC2S#placementState()}); {@code null} when none.
     */
    public void onLocalBlockChange(int x, int y, int z, BlockType type, BlockType prevType,
                                   String placementState) {
        if (connection == null) {
            return;
        }
        short id = (short) (type == null ? 0 : type.getId());
        short prevId = (short) (prevType == null ? 0 : prevType.getId());
        // Numbered: a placement or bucket use has already changed our inventory mirror.
        connection.send(new BlockChangeC2S(x, y, z, id, prevId, placementState,
                com.stonebreak.network.SlotIntentSeq.next()), false);
    }

    /** Toss an item (Q / inventory drop / death scatter): a DEBIT request the server validates. */
    public void sendDropItem(com.stonebreak.network.packet.NetItemStack stack) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive() && !stack.isEmpty()) {
            conn.send(new com.stonebreak.network.packet.player.DropItemC2S(stack,
                    com.stonebreak.network.SlotIntentSeq.nextAnswered()), false);
        }
    }

    /** Fishing-rod use (see {@code FishingRodC2S}): the server casts or reels in. Numbered. */
    public void sendFishingRodUse(org.joml.Vector3f origin, org.joml.Vector3f direction) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.player.FishingRodC2S(
                    origin.x, origin.y, origin.z, direction.x, direction.y, direction.z,
                    com.stonebreak.network.SlotIntentSeq.nextAnswered()), false);
        }
    }

    /** Mirrored Deceit cast (see {@code DecoyCastC2S}): the server spawns the decoys. */
    public void sendDecoyCast() {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.entity.DecoyCastC2S(), false);
        }
    }

    /** One atomic container-screen slot action (see {@code ContainerActionC2S}). */
    public void sendContainerAction(com.stonebreak.network.packet.player.ContainerActionC2S a) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(a, false);
        }
    }

    /** Hotbar selection intent (see {@code HotbarSelectC2S}). */
    public void sendHotbarSelect(int slot) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new HotbarSelectC2S(slot), false);
        }
    }

    /** Death drain request (see {@code DeathDropC2S}): the server scatters the replica. */
    public void sendDeathDrop() {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new DeathDropC2S(com.stonebreak.network.SlotIntentSeq.nextAnswered()), false);
        }
    }

    /** Any vehicle intent or rower's report (see {@code VehicleActionC2S}, {@code VehicleMoveC2S}). */
    public void sendVehiclePacket(com.openmason.engine.net.protocol.Packet packet, boolean droppable) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(packet, droppable);
        }
    }

    /** Ate one held item (see {@code ConsumeHeldC2S}): the server debits its replica. */
    public void sendConsumeHeld(com.stonebreak.network.packet.player.ConsumeHeldC2S c) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(c, false);
        }
    }

    /** Death / respawn edge (see {@code PlayerLifeC2S}) — reliable, never the state stream. */
    public void sendPlayerLife(com.stonebreak.network.packet.player.PlayerLifeC2S l) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(l, false);
        }
    }

    /** /timeset intent: ask the server to set the authoritative world clock. */
    public void sendTimeSet(long ticks) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.TimeSetC2S(ticks), false);
        }
    }

    /** /weather intent: ask the server to set the world's weather mode. */
    public void sendWeatherSet(com.stonebreak.world.weather.WeatherMode mode, boolean world) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.WeatherSetC2S(mode, world), false);
        }
    }

    /** /cheats intent: ask the server to set the world's cheats flag. */
    public void sendCheatsSet(boolean enabled) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.CheatsSetC2S(enabled), false);
        }
    }

    /** Mining started on a block (see {@code BlockBreakStartC2S}): the server times the break. */
    public void sendBreakStart(int x, int y, int z) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.BlockBreakStartC2S(x, y, z), false);
        }
    }

    /** Snow-layer intent for a layer change with no block change (see {@code SnowLayerC2S}). */
    public void sendSnowLayer(int x, int y, int z, int layers) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            conn.send(new com.stonebreak.network.packet.world.SnowLayerC2S(x, y, z, (byte) layers,
                    com.stonebreak.network.SlotIntentSeq.next()), false);
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
            // Numbered: on a stalagmite the toggle is a water-bucket use our mirror already made.
            conn.send(new com.stonebreak.network.packet.world.BlockToggleC2S(x, y, z,
                    com.stonebreak.network.SlotIntentSeq.next()), false);
            return true;
        }
        return false;
    }

    /** Projectile / ability-entity launch intent (see {@code ProjectileSpawnC2S}). */
    public void sendProjectileSpawn(byte kind, Vector3f pos, Vector3f v, float... params) {
        ClientConnection conn = connection;
        if (conn != null && conn.isActive()) {
            // Only an arrow costs anything — it already left our mirror.
            int seq = kind == com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_ARROW
                    ? com.stonebreak.network.SlotIntentSeq.next() : 0;
            conn.send(new com.stonebreak.network.packet.entity.ProjectileSpawnC2S(
                kind, pos.x, pos.y, pos.z, v.x, v.y, v.z, params, seq), false);
        }
    }

    /** Local hit on a replicated entity: forward the damage intent to the authoritative server. */
    public void sendEntityDamage(int targetNetworkId, float amount,
                                 com.stonebreak.mobs.entities.LivingEntity.DamageSource source,
                                 com.stonebreak.items.weapon.DamageType type,
                                 com.stonebreak.mobs.entities.HitKind kind) {
        if (connection == null || targetNetworkId < 0 || amount <= 0f) {
            return;
        }
        connection.send(new EntityDamageC2S(targetNetworkId, amount, (byte) source.ordinal(),
                (byte) type.ordinal(), kind.wireId()), false);
    }

    /** Local ability status effect on a replicated entity: forward it to the authoritative server. */
    public void sendEntityStatusEffect(int targetNetworkId,
                                       com.stonebreak.mobs.entities.status.StatusEffectType type,
                                       float duration, float magnitude) {
        if (connection == null || targetNetworkId < 0) {
            return;
        }
        connection.send(new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(targetNetworkId,
                (byte) com.stonebreak.mobs.entities.status.StatusEffectBits.wireId(type),
                duration, magnitude), false);
    }

    public void sendEntityKnockback(int targetNetworkId, float dirX, float dirZ, float horizontal, float vertical) {
        if (connection == null || targetNetworkId < 0) {
            return;
        }
        connection.send(new com.stonebreak.network.packet.entity.EntityKnockbackC2S(
                targetNetworkId, dirX, dirZ, horizontal, vertical), false);
    }

    /** Local shear on a replicated sheep: forward the shear intent to the authoritative server. */
    public void sendEntityShear(int targetNetworkId) {
        if (connection == null || targetNetworkId < 0) {
            return;
        }
        connection.send(new com.stonebreak.network.packet.entity.EntityShearC2S(targetNetworkId), false);
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
                // Final prefs flush before leaving so the latest validated state is on record.
                if (serverCanAcceptPrefs()) {
                    sendPlayerPrefsNow();
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
            case com.stonebreak.network.packet.player.PlayerPositionS2C pp -> playerHandler.handlePosition(pp);
            case InventorySyncS2C s -> applyInventorySync(s);
            case com.stonebreak.network.packet.player.KillCreditS2C kc ->
                playerHandler.handleKillCredit(kc, entityHandler.shadow(kc.targetNetworkId()));
            case com.stonebreak.network.packet.player.ProgressS2C pr -> applyProgress(pr);
            case com.stonebreak.network.packet.player.StatCreditS2C sc -> playerHandler.handleStatCredit(sc);
            case com.stonebreak.network.packet.player.PlayerDataS2C pd -> onPlayerData(pd.json());
            case EntitySpawnS2C s -> entityHandler.applySpawn(s);
            case EntityDespawnS2C d -> entityHandler.applyDespawn(d.networkId());
            case EntityMoveS2C mv -> entityHandler.applyDelta(mv);
            case EntityTeleportS2C t -> entityHandler.applyTeleport(t);
            case EntityAnimS2C a -> entityHandler.applyAnim(a.networkId(), a.state());
            case com.stonebreak.network.packet.entity.EntityVariantS2C v ->
                entityHandler.applyVariant(v.networkId(), v.variant());
            case com.stonebreak.network.packet.entity.EntityStatusS2C st -> entityHandler.applyStatus(st);
            case KeepAliveS2C ka -> {
                lastRttMs = ka.lastRttMs();
                ClientConnection conn = connection;
                if (conn != null && conn.isActive()) {
                    conn.send(new KeepAliveC2S(ka.nonce()), false);
                }
            }
            case TimeSyncS2C ts -> handleTimeSync(ts);
            case com.stonebreak.network.packet.world.WeatherSyncS2C ws -> handleWeatherSync(ws);
            case com.stonebreak.network.packet.world.CheatsStateS2C cs ->
                Game.getInstance().setCheatsEnabled(cs.enabled());
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

    private void handleWeatherSync(com.stonebreak.network.packet.world.WeatherSyncS2C ws) {
        pendingWeatherSync = ws;
        com.stonebreak.world.weather.ClientWeather weather = Game.getWeather();
        if (weather != null) {
            weather.sync(ws.weatherSeconds(), ws.mode(), ws.zones());
        }
    }

    private void handleWelcome(WelcomeS2C w) {
        localPlayerId = w.playerId();
        if (secretSink != null && !w.clientSecret().isEmpty() && !w.clientSecret().equals(presentedSecret)) {
            secretSink.accept(w.clientSecret()); // kept for the next join to this server (#335)
        }
        Vector3f spawn = new Vector3f(w.spawnX(), w.spawnY(), w.spawnZ());
        // Clear character creation flow state — we're now in the world.
        needsCharacterCreation = false;
        characterCreationSubmitted = false;
        // Build the client RENDER world: no terrain generation, no save service — chunks stream
        // in from the server. The local player is positioned at the authoritative spawn.
        String label = Game.getInstance().getCurrentWorldName() != null
            ? Game.getInstance().getCurrentWorldName()
            : "mp_" + System.currentTimeMillis();
        Game.getInstance().startClientWorld(label, w.worldSeed(), w.generatorType(), spawn);
    }
}
