package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.server.NetworkServer;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.net.server.ServerInboundQueue;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntitySpawner;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.handshake.KeepAliveS2C;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.NeedsCharacterCreationS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.PlayerDataC2S;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.ViewDistanceC2S;
import com.stonebreak.network.packet.player.CharacterCreationC2S;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.TimeSyncS2C;
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
 * <p>{@link #tick()} runs on the dedicated {@code <Mode>-Server} daemon thread (see
 * {@code MultiplayerSession.startWithServer}): it drains the inbound queue
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

    /** Keepalive probe + authoritative time sample cadence (100 ticks = 5 s at 20 Hz). */
    private static final int KEEPALIVE_INTERVAL_TICKS = 100;
    /** Kick a REMOTE player after this much total inbound silence (dead TCP peer). */
    private static final long INBOUND_SILENCE_TIMEOUT_NS = 30_000_000_000L; // 30 s
    private int keepaliveCounter = 0;
    private long nextKeepaliveNonce = 1L;

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
        level.entitySpawner().setSpawnAnchorSource(this::collectPlayerSpawnAnchors);

        // Replication funnel for authoritative SIM block mutations (water flow etc.):
        // installed on the HEADLESS world only, so server-side chunk.setBlock writes reach
        // clients live instead of waiting for an accidental chunk re-stream. Runs on the
        // server tick thread inside level.tick(), before blockHandler.tick flushes — same-tick.
        level.world().setServerMutationCallback(blockHandler::onServerBlockChange);
        // Same funnel pattern for snow-layer mutations → BlockMetaS2C broadcasts.
        level.world().setServerSnowCallback(blockHandler::onServerSnowChange);
        // And for water flow-level mutations → BlockMetaS2C (KIND_WATER_LEVEL), so clients
        // render live flow heights instead of full-height columns.
        level.world().setServerWaterCallback(blockHandler::onServerWaterChange);
        // Furnace state-string changes (lit flips, contents, cook progress) → BlockStateS2C.
        // The registry dedups (fires only on actual string change), so idle furnaces are free.
        if (level.world().getFurnaceRegistry() != null) {
            level.world().getFurnaceRegistry().setStateChangeListener((pos, state) ->
                ctx.broadcast(new com.stonebreak.network.packet.world.BlockStateS2C(
                    pos.x(), pos.y(), pos.z(), state), false));
        }

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
     * Snapshots a spawn anchor (position + view distance) for every player that has both completed
     * the handshake AND reported a position (so the dynamic mob cap sizes itself to each player's
     * actual loaded area, not the {@code (0,0,0)} default before the first PlayerStateC2S). Supplied
     * to the entity spawner so continuous spawning/despawning consider all players, not just the local one.
     */
    private List<EntitySpawner.SpawnAnchor> collectPlayerSpawnAnchors() {
        Collection<ServerPlayer> roster = ctx.players();
        if (roster.isEmpty()) return List.of();
        List<EntitySpawner.SpawnAnchor> out = new ArrayList<>(roster.size());
        for (ServerPlayer sp : roster) {
            if (!sp.handshakeDone() || sp.lastStateNs() == 0L) continue;
            out.add(new EntitySpawner.SpawnAnchor(
                new Vector3f(sp.x(), sp.y(), sp.z()), sp.viewDistanceChunks()));
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

        // Keepalive probes + authoritative time sample, every 5 s. Local channels get probes
        // too (free RTT signal) but are exempt from the silence kick — a same-JVM stall
        // (debugger, world-gen hitch on the game thread) must not tear down SP/host.
        if (++keepaliveCounter >= KEEPALIVE_INTERVAL_TICKS) {
            keepaliveCounter = 0;
            long now = System.nanoTime();
            TimeSyncS2C timeSync = currentTimeSync();
            for (ServerPlayer sp : ctx.players()) {
                if (!sp.isLocal() && now - sp.lastInboundNs() > INBOUND_SILENCE_TIMEOUT_NS) {
                    System.out.println("[SERVER] Kicking " + sp.username() + " (id "
                        + sp.playerId() + "): connection timed out.");
                    sp.disconnect(); // channelInactive → normal DISCONNECT flow (persist + leave)
                    continue;
                }
                long nonce = nextKeepaliveNonce++;
                sp.markKeepaliveSent(nonce, now);
                sp.send(new KeepAliveS2C(nonce, sp.rttMs()), false);
                if (timeSync != null) {
                    sp.send(timeSync, true); // droppable — the next sample self-heals
                }
            }
        }

        // Periodically persist connected remote players' inventories (crash safety).
        if (++remotePlayerSaveCounter >= REMOTE_PLAYER_SAVE_INTERVAL_TICKS) {
            remotePlayerSaveCounter = 0;
            for (ServerPlayer sp : ctx.players()) {
                persistPlayer(sp);
            }
        }
    }

    /**
     * C2S: /timeset — set the authoritative world clock. Host-only for now (the client-side
     * cheats gate can't be trusted from remote peers). Applies on the server tick thread and
     * broadcasts an immediate TimeSyncS2C so every client snaps at once instead of waiting
     * for (and previously being reverted by) the 5 s periodic sample.
     */
    private void handleTimeSet(ServerPlayer sp, com.stonebreak.network.packet.world.TimeSetC2S ts) {
        if (!sp.isLocal()) {
            return; // remote clients may not set server time
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null || level.timeOfDay() == null) {
            return;
        }
        long ticks = Math.floorMod(ts.ticks(), com.stonebreak.world.TimeOfDay.TICKS_PER_DAY);
        level.timeOfDay().setTicks(ticks);
        TimeSyncS2C sync = currentTimeSync();
        if (sync != null) {
            ctx.broadcast(sync, false);
        }
        System.out.println("[SERVER] World time set to " + ticks + " by " + sp.username());
    }

    /** Authoritative time sample for TimeSyncS2C, or null before the level is booted. */
    private TimeSyncS2C currentTimeSync() {
        ServerLevel level = ctx.serverLevel();
        if (level == null || level.timeOfDay() == null) {
            return null;
        }
        var time = level.timeOfDay();
        return new TimeSyncS2C(time.getTicks(), time.getTimeSpeed(), time.isFrozen());
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
        sp.touchInbound();
        if (!sp.handshakeDone()) {
            if (packet instanceof HandshakeC2S hs) {
                handleHandshake(sp, hs);
            } else if (packet instanceof CharacterCreationC2S cc) {
                handleCharacterCreation(sp, cc);
            }
            return; // ignore anything else until the handshake is accepted
        }
        routePlay(sp, packet);
    }

    private void routePlay(ServerPlayer sp, Packet packet) {
        switch (packet) {
            case BlockChangeC2S c -> blockHandler.handleBlockChange(sp, c, ctx);
            case com.stonebreak.network.packet.world.SnowLayerC2S s -> blockHandler.handleSnowLayer(sp, s, ctx);
            case com.stonebreak.network.packet.world.FurnaceSlotsC2S f -> blockHandler.handleFurnaceSlots(sp, f, ctx);
            case com.stonebreak.network.packet.world.BlockToggleC2S bt -> blockHandler.handleBlockToggle(sp, bt, ctx);
            case com.stonebreak.network.packet.world.ChunkResyncRequestC2S cr ->
                chunkHandler.handleResyncRequest(sp, cr.chunkX(), cr.chunkZ());
            case com.stonebreak.network.packet.world.ChunkHashesC2S ch ->
                chunkHandler.handleChunkHashes(sp, ch.entries(), ctx);
            case com.stonebreak.network.packet.entity.EntityResyncC2S ignored2 -> {
                if (sp.allowResync()) {
                    entityHandler.onPeerResync(sp, ctx); // idempotent client-side (known ids ignored)
                    playerHandler.onPeerResync(sp, ctx); // roster refresh — players aren't entities
                }
            }
            case com.stonebreak.network.packet.world.TimeSetC2S ts -> handleTimeSet(sp, ts);
            case PlayerStateC2S ps -> playerHandler.handlePlayerState(sp, ps, ctx);
            case PlayerHeldItemC2S h -> playerHandler.handleHeldItem(sp, h, ctx);
            case ChatMessageC2S cm -> chatHandler.handleChat(sp, cm, ctx);
            case EntityDamageC2S ed -> entityHandler.handleEntityDamage(sp, ed, ctx);
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S ps2 ->
                entityHandler.handleProjectileSpawn(sp, ps2, ctx);
            case PlayerDataC2S pd -> { if (!sp.isLocal()) sp.setPlayerDataBlob(pd.json()); }
            case ViewDistanceC2S vd -> sp.setViewDistanceChunks(vd.chunks());
            case KeepAliveC2S ka -> sp.answerKeepalive(ka.nonce(), System.nanoTime());
            case DropItemC2S di -> playerHandler.handleDropItem(sp, di, ctx);
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

        // Stable identity: the id is derived from the username, so the same player keeps
        // the same id across reconnects and server restarts (saves, kill credit, and
        // client rosters keyed by id all survive a rejoin).
        int stableId = ServerWorldContext.stablePlayerIdFor(hs.username());
        ServerPlayer existing = ctx.player(stableId);
        if (existing != null) {
            if (existing.username().equals(hs.username())) {
                // Session takeover: the same player reconnected before their old channel
                // died (or is deliberately re-logging). Kick the stale session cleanly so
                // the roster and every client's remote-player figure converge on the new
                // one. The old channel's late DISCONNECT is instance-guarded in
                // handleDisconnect/removePlayer and can't evict the new session.
                System.out.println("[SERVER] " + hs.username()
                    + " reconnected — replacing the previous session (id " + stableId + ").");
                existing.send(new KickS2C("You logged in from another location."));
                persistPlayer(existing);
                ctx.removePlayer(existing);
                entityHandler.onPeerLeft(existing);
                ctx.broadcastExcept(sp, new PlayerLeaveS2C(stableId), false);
                existing.disconnect();
            } else {
                // Different name hashing to the same id — refuse the newcomer clearly
                // rather than corrupting the roster.
                sp.send(new KickS2C("That name is unavailable on this server (id collision with '"
                    + existing.username() + "')."));
                sp.disconnect();
                return;
            }
        }
        sp.assignPlayerId(stableId);
        ctx.addPlayer(sp);

        // Late-join character creation: remote players with no saved data must create a
        // character before entering the world. Local players are handled same-JVM.
        if (!sp.isLocal() && !playerHasSavedData(sp)) {
            sp.setWaitingForCharacterCreation(true);
            sp.send(new NeedsCharacterCreationS2C(sp.playerId(), ctx.worldSeed()));
            System.out.println("[SERVER] " + hs.username() + " (id " + sp.playerId()
                    + ") — no saved data, waiting for character creation.");
            return;
        }

        // Standard welcome flow (player has saved data or is local).
        sendWelcomeSnapshot(sp);

        System.out.println("[SERVER] " + hs.username() + " joined as id " + sp.playerId());
    }

    /** Check if a remote player has any saved data in the per-username store. */
    private boolean playerHasSavedData(ServerPlayer sp) {
        if (sp.isLocal()) return false; // local handled same-JVM
        ServerLevel level = ctx.serverLevel();
        if (level == null || level.saveService() == null) return false;
        try {
            byte[] blob = level.saveService().loadNamedPlayer(sp.username())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            return blob != null;
        } catch (Exception e) {
            System.err.println("[SERVER] Failed to check saved data for "
                    + sp.username() + ": " + e.getMessage());
            return false;
        }
    }

    /** Send the full welcome snapshot (welcome, time, roster, snapshots, saved data). */
    private void sendWelcomeSnapshot(ServerPlayer sp) {
        Vector3f spawn = ctx.spawn();

        // 1. Welcome: who you are + the seed + the authoritative spawn.
        sp.send(new WelcomeS2C(sp.playerId(), ctx.worldSeed(), spawn.x, spawn.y, spawn.z));
        TimeSyncS2C timeSync = currentTimeSync();
        if (timeSync != null) {
            sp.send(timeSync, false);
        }

        // 2. Roster bootstrap: every player already present.
        for (ServerPlayer other : ctx.players()) {
            if (other.playerId() != sp.playerId()) {
                boolean reported = other.lastStateNs() != 0L;
                sp.send(new PlayerJoinS2C(other.playerId(), other.username(),
                    reported ? other.x() : spawn.x,
                    reported ? other.y() : spawn.y,
                    reported ? other.z() : spawn.z));
            }
        }

        // 3. Tell existing clients about the newcomer.
        ctx.broadcastExcept(sp, new PlayerJoinS2C(sp.playerId(), sp.username(), spawn.x, spawn.y, spawn.z), false);

        // 4. Mark handshaked and deliver per-domain join snapshots.
        sp.markHandshakeDone();
        entityHandler.onPeerJoined(sp, ctx);
        playerHandler.onPeerJoined(sp, ctx);

        // 5. Restore a REMOTE player's saved inventory/stats (the local player is restored
        //    same-JVM). An empty payload tells the client to use fresh starting items.
        sendInitialPlayerData(sp);
    }

    /** Handle the client's character creation submission. Validates data, then completes welcome. */
    private void handleCharacterCreation(ServerPlayer sp, CharacterCreationC2S cc) {
        if (!sp.waitingForCharacterCreation()) {
            return; // unexpected — only handle while waiting
        }
        byte[] json = cc.json();
        if (json == null || json.length == 0) {
            sp.send(new KickS2C("Empty character creation data."));
            sp.disconnect();
            return;
        }
        String error = validateCharacterCreation(json);
        if (error != null) {
            sp.send(new KickS2C("Invalid character data: " + error));
            sp.disconnect();
            return;
        }
        // Store the validated blob so we can send it back to the client and persist it.
        sp.setCharacterCreationBlob(json);
        sp.setWaitingForCharacterCreation(false);
        // Complete the welcome sequence.
        sendWelcomeSnapshot(sp);
        // Send the character creation data as PlayerData so the client can apply it.
        sp.send(new com.stonebreak.network.packet.player.PlayerDataS2C(json), false);
        System.out.println("[SERVER] " + sp.username() + " (id " + sp.playerId()
                + ") — character creation accepted.");
    }

    /** Validate character creation JSON. Returns null on success, or an error message. */
    private String validateCharacterCreation(byte[] json) {
        String s = new String(json, java.nio.charset.StandardCharsets.UTF_8);

        // Validate ability scores
        String scoresPattern = "\"abilityScores\"\\s*:\\s*\\[([^\\]]+)\\]";
        java.util.regex.Matcher scoresM = java.util.regex.Pattern.compile(scoresPattern).matcher(s);
        if (scoresM.find()) {
            String[] parts = scoresM.group(1).split(",");
            if (parts.length != 6) return "abilityScores must have 6 values";
            int sum = 0;
            for (String p : parts) {
                int v;
                try { v = Integer.parseInt(p.trim()); }
                catch (NumberFormatException e) { return "abilityScores contains non-integer values"; }
                if (v < 1 || v > 30) return "ability scores must be 1-30, got " + v;
                sum += v;
            }
            // 6*10 = 60 base + 27 AP = 87 max total
            if (sum > 87) return "ability scores sum too high (" + sum + "), max is 87";
            if (sum < 60) return "ability scores sum too low (" + sum + "), min is 60";
        } else {
            return "missing abilityScores";
        }

        // Validate remaining AP
        String apPattern = "\"remainingAp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher apM = java.util.regex.Pattern.compile(apPattern).matcher(s);
        if (apM.find()) {
            int ap = Integer.parseInt(apM.group(1));
            if (ap < 0 || ap > 27) return "remainingAp out of range (0-27), got " + ap;
        }

        // Validate remaining CP
        String cpPattern = "\"remainingCp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher cpM = java.util.regex.Pattern.compile(cpPattern).matcher(s);
        if (cpM.find()) {
            int cp = Integer.parseInt(cpM.group(1));
            if (cp < 0 || cp > 100) return "remainingCp out of range (0-100), got " + cp;
        }

        // Validate remaining SP
        String spPattern = "\"remainingSp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher spM = java.util.regex.Pattern.compile(spPattern).matcher(s);
        if (spM.find()) {
            int sp = Integer.parseInt(spM.group(1));
            if (sp < 0 || sp > 100) return "remainingSp out of range (0-100), got " + sp;
        }

        // Validate remaining FP
        String fpPattern = "\"remainingFp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher fpM = java.util.regex.Pattern.compile(fpPattern).matcher(s);
        if (fpM.find()) {
            int fp = Integer.parseInt(fpM.group(1));
            if (fp < 0 || fp > 100) return "remainingFp out of range (0-100), got " + fp;
        }

        // Validate class (if present, must be in registry)
        String classPattern = "\"class\"\\s*:\\s*(\"[^\"]*\"|null)";
        java.util.regex.Matcher classM = java.util.regex.Pattern.compile(classPattern).matcher(s);
        if (classM.find()) {
            String classVal = classM.group(1);
            if (!"null".equals(classVal)) {
                String classId = classVal.substring(1, classVal.length() - 1); // strip quotes
                if (!ClassRegistry.findById(classId).isPresent()) {
                    return "unknown class: " + classId;
                }
            }
        }

        // Validate background (if present, must be in registry)
        String bgPattern = "\"background\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher bgM = java.util.regex.Pattern.compile(bgPattern).matcher(s);
        if (bgM.find()) {
            String bgId = bgM.group(1);
            if (!BackgroundRegistry.findById(bgId).isPresent()) {
                return "unknown background: " + bgId;
            }
        }

        // Validate feats (each must be in registry)
        String featsPattern = "\"acquiredFeats\"\\s*:\\s*\\[([^\\]]*)\\]";
        java.util.regex.Matcher featsM = java.util.regex.Pattern.compile(featsPattern).matcher(s);
        if (featsM.find()) {
            String content = featsM.group(1).trim();
            if (!content.isEmpty()) {
                java.util.regex.Matcher featM = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(content);
                while (featM.find()) {
                    String featId = featM.group(1);
                    boolean found = FeatRegistry.ALL.stream()
                            .anyMatch(f -> f.id().equals(featId));
                    if (!found) return "unknown feat: " + featId;
                }
            }
        }

        return null; // valid
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
        // Instance check, not id check: after a session takeover (same player reconnected,
        // stable id reused) the roster maps this id to the NEW session — the stale channel's
        // disconnect must neither broadcast a leave (it would despawn the live figure on
        // every client) nor evict the new session (removePlayer is instance-guarded too).
        boolean wasRostered = ctx.player(sp.playerId()) == sp;
        ctx.removePlayer(sp);
        if (wasRostered) {
            // Persist only the LIVE session's data — a stale takeover victim persisting here
            // would overwrite the new session's fresher blob (takeover already saved it once).
            persistPlayer(sp);
            entityHandler.onPeerLeft(sp);
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

    /** Server-side: forward projectile hit/kill credit to the launching player's client. */
    public void sendKillCreditTo(int playerId, com.stonebreak.mobs.entities.LivingEntity victim,
                                 float dealt, boolean killed) {
        ServerPlayer sp = ctx.player(playerId);
        if (sp != null) {
            entityHandler.sendKillCredit(sp, victim, dealt, killed);
        }
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
