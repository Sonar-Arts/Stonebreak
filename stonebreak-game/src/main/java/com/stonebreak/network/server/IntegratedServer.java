package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.server.NetworkServer;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.net.server.ServerInboundQueue;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntitySpawner;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.network.packet.NetItemStack;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.handshake.KeepAliveS2C;
import com.stonebreak.network.packet.handshake.NeedsCharacterCreationS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.DeathDropC2S;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.HotbarSelectC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.PlayerPrefsC2S;
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
import com.stonebreak.network.server.handlers.ServerFishingHandler;
import com.stonebreak.network.server.handlers.ServerIllusionHandler;
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
    private final ServerFishingHandler fishingHandler = new ServerFishingHandler();
    private final ServerIllusionHandler illusionHandler = new ServerIllusionHandler();

    private long lastTickNs = 0L;
    private long tickAccumulatorNs = 0L;
    /** Rate-limits the overload warning (the clamp drops wall time — say so, but not every tick). */
    private static final long OVERLOAD_WARN_INTERVAL_NS = 15_000_000_000L;
    private long lastOverloadWarnNs = Long.MIN_VALUE / 2;

    /** Work handed to the tick thread from other threads (save snapshots of server state). */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tickTasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The thread running {@link #tick}; null before the first tick. */
    private volatile Thread tickThread;
    /** False once the tick loop has stopped for good: tasks then run inline on the caller. */
    private volatile boolean ticking = true;
    /** Singleplayer host has the pause menu open with "Pause World" on: no simulation. */
    private volatile boolean pausedByHost;
    private boolean wasPausedByHost;

    public IntegratedServer() {
        this.networkServer = new NetworkServer(StonebreakProtocol.registry());
        this.ctx = new ServerWorldContext(networkServer.connections());
        // World traffic only reaches players past the handshake (not one still handshaking or
        // creating a character — unauthenticated, and not in the world yet).
        networkServer.connections().setBroadcastFilter(IntegratedServer::inWorld);
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
        // Same direction for mob AI: the world's mobs flee from / pursue the connected roster,
        // remote players included, instead of the client's local player (#340).
        level.world().setPlayerLocator(new ServerPlayerLocator(ctx.players()));

        // Replication funnel for authoritative SIM block mutations (water flow etc.):
        // installed on the HEADLESS world only, so server-side chunk.setBlock writes reach
        // clients live instead of waiting for an accidental chunk re-stream. Runs on the
        // server tick thread inside level.tick(), before blockHandler.tick flushes — same-tick.
        level.world().serverSinks().setBlockSink(blockHandler::onServerBlockChange);
        // Same funnel pattern for snow-layer mutations → BlockMetaS2C broadcasts.
        level.world().serverSinks().setSnowSink(blockHandler::onServerSnowChange);
        // And for water flow-level mutations → BlockMetaS2C (KIND_WATER_LEVEL), so clients
        // render live flow heights instead of full-height columns.
        level.world().serverSinks().setWaterSink(blockHandler::onServerWaterChange);
        // Furnace state-string changes (lit flips, contents, cook progress) → BlockStateS2C.
        // The registry dedups (fires only on actual string change), so idle furnaces are free.
        // Queued behind the block batches: a placement's first state write happens in the
        // same tick as its block change, and an echo that overtook the block change would be
        // wiped by it on every other client (issue #362 removed the re-stream that hid this).
        if (level.world().getFurnaceRegistry() != null) {
            level.world().getFurnaceRegistry().setStateChangeListener((pos, state) ->
                blockHandler.queueStateEcho(pos.x(), pos.y(), pos.z()));
        }
        // Crafting-table grid changes (issue #307) → BlockStateS2C, same dedup-at-source rule.
        level.world().getWorkbenchRegistry().setStateChangeListener((pos, state) ->
            blockHandler.queueStateEcho(pos.x(), pos.y(), pos.z()));

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

    private List<EntitySpawner.SpawnAnchor> collectPlayerSpawnAnchors() {
        return spawnAnchorsFor(ctx.players());
    }

    /**
     * Snapshots a spawn anchor (position + view distance) for every player that has both completed
     * the handshake AND reported a position (so the dynamic mob cap sizes itself to each player's
     * actual loaded area, not the {@code (0,0,0)} default before the first PlayerStateC2S). Supplied
     * to the entity spawner so continuous spawning/despawning consider all players, not just the local one.
     * A dead player's anchor keeps its area for despawning (the world stays on screen behind the
     * death menu) but spawns nothing around the corpse (#357).
     */
    static List<EntitySpawner.SpawnAnchor> spawnAnchorsFor(Collection<ServerPlayer> roster) {
        if (roster.isEmpty()) return List.of();
        List<EntitySpawner.SpawnAnchor> out = new ArrayList<>(roster.size());
        for (ServerPlayer sp : roster) {
            if (!sp.handshakeDone() || sp.lastStateNs() == 0L) continue;
            out.add(new EntitySpawner.SpawnAnchor(
                new Vector3f(sp.x(), sp.y(), sp.z()), sp.viewDistanceChunks(), sp.isLiveInWorld()));
        }
        return out;
    }

    // ─── Per-frame pump (host game thread) ────────────────────────────────────────

    /** A connection whose player is past the handshake (one not yet attached is let through). */
    static boolean inWorld(ServerConnection c) {
        ServerPlayer sp = c.attachment();
        return sp == null || sp.handshakeDone();
    }

    /**
     * Runs {@code task} on the tick thread (next tick), or inline when called from it or once
     * the loop has stopped — so a save that snapshots server state never reads it mid-tick,
     * and a final flush after the server thread exits still completes.
     */
    public void executeOnTick(Runnable task) {
        if (!ticking || Thread.currentThread() == tickThread) {
            task.run();
            return;
        }
        tickTasks.add(task);
        if (!ticking && tickTasks.remove(task)) {
            task.run(); // the loop stopped between the check and the add
        }
    }

    /** The tick loop has exited for good (called on the server thread as it ends). */
    public void stopTicking() {
        ticking = false;
        drainTickTasks();
    }

    private void drainTickTasks() {
        Runnable task;
        while ((task = tickTasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                System.err.println("[SERVER] Tick task failed: " + e);
            }
        }
    }

    /**
     * Last packets before the server thread exits: intents the client sent just before quitting
     * (a placement, a toss, the final prefs report) are applied, so the host's saved record —
     * built from this server's state — includes them.
     */
    public void drainInboundForShutdown() {
        tickThread = Thread.currentThread();
        networkServer.inboundQueue().drain(this::dispatch);
        drainTickTasks();
    }

    /** Main thread: the singleplayer pause menu freezes (true) or releases (false) the world. */
    public void setPausedByHost(boolean paused) {
        this.pausedByHost = paused;
    }

    public void tick() {
        tickThread = Thread.currentThread();
        drainTickTasks();
        if (pausedByHost) {
            // Like Minecraft's integrated server: a paused singleplayer world does not tick, and
            // is saved once as it pauses. Packets still drain (the client's last intents land).
            if (!wasPausedByHost) {
                wasPausedByHost = true;
                ServerLevel level = ctx.serverLevel();
                if (level != null && level.saveService() != null) {
                    System.out.println("[SERVER] Saving and pausing the world...");
                    level.saveService().saveAll().exceptionally(t -> {
                        System.err.println("[SERVER] Pause save failed: " + t.getMessage());
                        return null;
                    });
                }
            }
            networkServer.inboundQueue().drain(this::dispatch);
            for (ServerPlayer sp : ctx.players()) {
                sp.flushInventoryResync();
            }
            lastTickNs = System.nanoTime();
            tickAccumulatorNs = 0; // resume without a catch-up burst
            return;
        }
        wasPausedByHost = false;
        // The command-only local arena suspends the saved singleplayer world, including replication.
        if (com.stonebreak.battletest.BattleTestSession.isActive()) {
            lastTickNs = System.nanoTime();
            tickAccumulatorNs = 0;
            return;
        }
        ServerInboundQueue queue = networkServer.inboundQueue();
        queue.drain(this::dispatch);
        // After the drain: one answer per player however many InventoryResyncC2S arrived, built
        // after every intent drained with them.
        for (ServerPlayer sp : ctx.players()) {
            sp.flushInventoryResync();
        }

        long now = System.nanoTime();
        if (lastTickNs == 0L) {
            lastTickNs = now;
        }
        tickAccumulatorNs += now - lastTickNs;
        lastTickNs = now;
        if (tickAccumulatorNs > MAX_ACCUMULATOR_NS) {
            long skipped = (tickAccumulatorNs - MAX_ACCUMULATOR_NS) / TICK_PERIOD_NS;
            tickAccumulatorNs = MAX_ACCUMULATOR_NS;
            if (skipped > 0 && now - lastOverloadWarnNs > OVERLOAD_WARN_INTERVAL_NS) {
                lastOverloadWarnNs = now;
                System.err.println("[SERVER] Can't keep up! Skipping " + skipped + " tick(s).");
            }
        }
        while (tickAccumulatorNs >= TICK_PERIOD_NS) {
            tickAccumulatorNs -= TICK_PERIOD_NS;
            replicationTick();
            if (tickAccumulatorNs >= TICK_PERIOD_NS) {
                // Catching up: packets that arrived during the last tick apply before the next
                // one (as Minecraft runs its queued tasks between ticks), so input isn't held
                // back for a whole catch-up burst.
                queue.drain(this::dispatch);
                for (ServerPlayer sp : ctx.players()) {
                    sp.flushInventoryResync();
                }
            }
        }
    }

    private void replicationTick() {
        // Authoritative world simulation on the headless server world: water/furnace/features,
        // entity AI + physics, mob spawning, and time. Replication handlers then ship the
        // resulting state to clients.
        // Each step isolated (issue #337): one that throws used to skip every step after it,
        // on every tick, for as long as it kept throwing.
        long tickStart = System.nanoTime();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            runTickStep("world", () -> level.tick(TICK_PERIOD_NS / 1_000_000_000f));
        }
        runTickStep("blocks", () -> blockHandler.tick(ctx));
        runTickStep("entities", () -> entityHandler.tick(ctx));
        runTickStep("fishing", () -> fishingHandler.tick(ctx));
        runTickStep("decoys", () -> illusionHandler.tick(ctx, TICK_PERIOD_NS / 1_000_000_000f));
        runTickStep("chunks", () -> chunkHandler.tick(ctx));

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

        // Syncs the tick itself queued (pickups) — one per player.
        for (ServerPlayer sp : ctx.players()) {
            sp.flushInventoryResync();
        }

        // Periodically persist connected remote players' inventories (crash safety).
        if (++remotePlayerSaveCounter >= REMOTE_PLAYER_SAVE_INTERVAL_TICKS) {
            remotePlayerSaveCounter = 0;
            for (ServerPlayer sp : ctx.players()) {
                persistPlayerSnapshot(sp);
            }
        }
        chunkHandler.onTickEnded(System.nanoTime() - tickStart, ctx.players());
    }

    private static void runTickStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            System.err.println("[SERVER] Tick step '" + name + "' failed: " + e);
            e.printStackTrace();
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

    /**
     * C2S: /cheats — set the world's cheats flag (issue #318). Host-only, like /timeset: the
     * flag is a per-world setting persisted by {@link ServerLevel}, and remote peers must not
     * unlock cheats on someone else's world. A refused request gets the authoritative state
     * echoed back so the sender converges. Applied flags broadcast to every client.
     */
    private void handleCheatsSet(ServerPlayer sp, com.stonebreak.network.packet.world.CheatsSetC2S cs) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (!sp.isLocal()) {
            sp.send(new com.stonebreak.network.packet.world.CheatsStateS2C(level.cheatsEnabled()), false);
            return;
        }
        level.setCheatsEnabled(cs.enabled());
        ctx.broadcast(new com.stonebreak.network.packet.world.CheatsStateS2C(cs.enabled()), false);
        System.out.println("[SERVER] Cheats " + (cs.enabled() ? "enabled" : "disabled")
            + " by " + sp.username());
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
            case PACKET -> handlePacket(e.connection(), e.packet(), e.receivedNanos());
            case DISCONNECT -> handleDisconnect(e.connection());
        }
    }

    private void handleConnect(ServerConnection conn) {
        int id = ctx.allocatePlayerId();
        ServerPlayer sp = new ServerPlayer(conn, id);
        conn.setAttachment(sp);
        System.out.println("[SERVER] Connection opened, provisional id " + id);
    }

    private void handlePacket(ServerConnection conn, Packet packet, long receivedNs) {
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
        if (ctx.player(sp.playerId()) != sp) {
            // Retired session (taken over by a reconnect, or already removed): its record was
            // handed to the new session, so its queued drops/container actions/placements
            // would spend items the new session also holds.
            return;
        }
        routePlay(sp, packet, receivedNs);
    }

    /** {@code receivedNs}: when the packet arrived on the event loop — break timing measures
     *  the start→finish interval with it (issue #333). */
    private void routePlay(ServerPlayer sp, Packet packet, long receivedNs) {
        // A numbered intent is recorded on arrival, whatever its handler then decides — every
        // sync built from here on reflects it, so the client may stop skipping syncs older
        // than it. Handlers that debit, refuse or queue all act within this dispatch.
        if (packet instanceof com.stonebreak.network.packet.SequencedIntent intent) {
            sp.recordActionSeq(intent.seq());
        }
        if (isCombatIntent(packet)) {
            // Fighting closes any respec window: spends used in combat are committed (#421).
            sp.commitSpends();
        }
        switch (packet) {
            case BlockChangeC2S c -> blockHandler.handleBlockChange(sp, c, ctx, receivedNs);
            case com.stonebreak.network.packet.world.BlockBreakStartC2S bs ->
                blockHandler.handleBreakStart(sp, bs, receivedNs);
            case com.stonebreak.network.packet.world.SnowLayerC2S s -> blockHandler.handleSnowLayer(sp, s, ctx);
            case ContainerActionC2S ca -> playerHandler.handleContainerAction(sp, ca, ctx);
            case com.stonebreak.network.packet.world.BlockToggleC2S bt -> blockHandler.handleBlockToggle(sp, bt, ctx);
            case com.stonebreak.network.packet.world.ChunkResyncRequestC2S cr ->
                chunkHandler.handleResyncRequest(sp, cr.chunkX(), cr.chunkZ());
            case com.stonebreak.network.packet.world.ChunkHashesC2S ch ->
                chunkHandler.handleChunkHashes(sp, ch.entries(), ctx);
            case com.stonebreak.network.packet.world.ChunkUnloadC2S cu ->
                chunkHandler.handleChunksUnloaded(sp, cu.coords(), cu.centerX(), cu.centerZ());
            case com.stonebreak.network.packet.world.ChunkAckC2S ack ->
                chunkHandler.handleChunkAck(sp, ack.processed(), ack.desiredChunksPerTick());
            case com.stonebreak.network.packet.entity.EntityResyncC2S ignored2 -> {
                if (sp.allowResync()) {
                    entityHandler.onPeerResync(sp, ctx); // idempotent client-side (known ids ignored)
                    playerHandler.onPeerResync(sp, ctx); // roster refresh — players aren't entities
                }
            }
            case com.stonebreak.network.packet.world.TimeSetC2S ts -> handleTimeSet(sp, ts);
            case com.stonebreak.network.packet.world.CheatsSetC2S cs -> handleCheatsSet(sp, cs);
            case PlayerStateC2S ps -> playerHandler.handlePlayerState(sp, ps, ctx);
            case HotbarSelectC2S h -> playerHandler.handleHotbarSelect(sp, h, ctx);
            case ChatMessageC2S cm -> chatHandler.handleChat(sp, cm, ctx);
            case EntityDamageC2S ed -> entityHandler.handleEntityDamage(sp, ed, ctx);
            case com.stonebreak.network.packet.entity.EntityKnockbackC2S kb ->
                entityHandler.handleEntityKnockback(sp, kb);
            case com.stonebreak.network.packet.entity.EntityStatusEffectC2S se ->
                entityHandler.handleEntityStatusEffect(sp, se);
            case com.stonebreak.network.packet.entity.EntityShearC2S es ->
                entityHandler.handleEntityShear(sp, es, ctx);
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S ps2 ->
                entityHandler.handleProjectileSpawn(sp, ps2, ctx);
            case PlayerPrefsC2S pp -> handlePlayerPrefs(sp, pp);
            case ViewDistanceC2S vd -> sp.setViewDistanceChunks(vd.chunks());
            case KeepAliveC2S ka -> sp.answerKeepalive(ka.nonce(), System.nanoTime());
            case DropItemC2S di -> playerHandler.handleDropItem(sp, di, ctx);
            case DeathDropC2S dd -> playerHandler.handleDeathDrop(sp, dd, ctx);
            case com.stonebreak.network.packet.player.PlayerLifeC2S pl -> playerHandler.handlePlayerLife(sp, pl);
            case com.stonebreak.network.packet.player.ConsumeHeldC2S ch -> playerHandler.handleConsumeHeld(sp, ch);
            case com.stonebreak.network.packet.player.InventoryResyncC2S ignored3 -> sp.requestInventoryResync();
            case com.stonebreak.network.packet.player.FishingRodC2S fr -> fishingHandler.handleRodUse(sp, fr, ctx);
            case com.stonebreak.network.packet.entity.DecoyCastC2S dc -> illusionHandler.handleDecoyCast(sp, dc, ctx);
            case DisconnectC2S ignored -> sp.disconnect();
            default -> { /* unknown / unexpected serverbound packet — ignore */ }
        }
    }

    /**
     * Intents the player acts with right now using the character's build (attacks, projectiles,
     * a decoy cast). Status-effect intents are left out: a lingering ability zone (Rampage trail,
     * Skull Crusher crater) re-forwards them with no input, also while the character sheet is
     * open, and committing then would make a legitimate Discard be refused.
     */
    static boolean isCombatIntent(Packet packet) {
        return packet instanceof EntityDamageC2S
                || packet instanceof com.stonebreak.network.packet.entity.EntityKnockbackC2S
                || packet instanceof com.stonebreak.network.packet.entity.ProjectileSpawnC2S
                || packet instanceof com.stonebreak.network.packet.entity.DecoyCastC2S;
    }

    void handleHandshake(ServerPlayer sp, HandshakeC2S hs) {
        // One handshake per connection (#335): a repeat — while the first one's saved record is
        // still loading, during character creation, or after a refusal — is never processed (it
        // used to add the same connection to the roster again under a second name).
        if (sp.handshakeRequested()) {
            System.out.println("[SERVER] Ignoring a repeated handshake on connection id " + sp.playerId());
            return;
        }
        sp.markHandshakeRequested();
        if (hs.protocolVersion() != ProtocolVersion.CURRENT) {
            String msg = "Protocol mismatch (server=" + ProtocolVersion.CURRENT
                + ", client=" + hs.protocolVersion() + ")";
            System.out.println("[SERVER] Rejecting " + sp.playerId() + ": " + msg);
            sp.kick(msg);
            return;
        }
        // The host is never refused its own world; its name is coerced before it connects.
        if (!sp.isLocal() && !com.stonebreak.network.PlayerNames.isValid(hs.username())) {
            System.out.println("[SERVER] Rejecting " + sp.playerId() + ": invalid name");
            sp.kick("Invalid name. " + com.stonebreak.network.PlayerNames.RULE);
            return;
        }
        sp.setUsername(hs.username());
        sp.setPresentedSecret(hs.clientSecret());

        // Stable identity: the id is derived from the username, so the same player keeps
        // the same id across reconnects and server restarts (saves, kill credit, and
        // client rosters keyed by id all survive a rejoin).
        int stableId = ServerWorldContext.stablePlayerIdFor(hs.username());
        if (sp.isLocal() || ctx.player(stableId) != null) {
            // The host has no players/<name> file; a takeover continues from the live record,
            // and a refused one never reads the save (completeHandshake decides which).
            completeHandshake(sp, stableId, null);
            return;
        }

        // A remote player's saved record is read (and parse-checked) ONCE, before they join
        // the roster: a save that cannot be loaded right now refuses the join — it is never
        // mistaken for "no saved data", which would let the next persist overwrite it (#358's
        // rule for remote players; issue #334). The read runs OFF the tick (a busy save queue
        // used to stall the whole server for up to 10 s, issue #337); the join completes on
        // the tick when it returns.
        com.stonebreak.world.save.SaveService saves =
                ctx.serverLevel() != null ? ctx.serverLevel().saveService() : null;
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> loadSavedRecord(saves, hs.username()), PLAYER_LOAD_EXECUTOR)
                .exceptionally(t -> new SavedRecord(null, null, UNLOADABLE_SAVE))
                .thenAccept(saved -> executeOnTick(() -> completeHandshake(sp, stableId, saved)));
    }

    /** Reads joining players' saved records; daemon, so a stuck read never holds the JVM. */
    private static final java.util.concurrent.ExecutorService PLAYER_LOAD_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Player-Load");
                t.setDaemon(true);
                return t;
            });

    /**
     * Second half of the handshake, on the tick thread: who holds the name (checked again —
     * another session may have joined while this one's record loaded; see {@link NameClaims}),
     * the saved record, then the roster and the welcome (or character creation). {@code saved}
     * is null for the host and for a takeover, which continues from the live record.
     */
    private void completeHandshake(ServerPlayer sp, int stableId, SavedRecord saved) {
        if (!sp.connection().isActive()) {
            return; // left while their record loaded
        }
        String username = sp.username();
        ServerPlayer existing = ctx.player(stableId);
        byte[] handedOver = null;
        String handedOverSecret = null;
        if (existing != null) {
            String refusal = NameClaims.takeoverRefusal(existing, sp);
            if (refusal != null) {
                // The host's session, a live session whose secret was not presented, or a
                // different name hashing to the same id: refuse the newcomer, keep the roster.
                System.out.println("[SERVER] Refusing " + username + " (id " + stableId + "): " + refusal);
                sp.kick(refusal);
                return;
            }
            // Session takeover (the same player reconnecting before their old channel died),
            // or the host claiming its name from a remote that held it. Kick the stale session
            // cleanly so the roster and every client's remote-player figure converge on the new
            // one. The old channel's late DISCONNECT is instance-guarded in
            // handleDisconnect/removePlayer and can't evict the new session.
            System.out.println("[SERVER] " + username
                + " — replacing the previous session of '" + existing.username() + "' (id " + stableId + ").");
            persistPlayer(existing);
            if (!sp.isLocal()) {
                // The new session continues from the old one's IN-MEMORY record rather than
                // re-reading the file the save above is still writing (issue #334): a load
                // racing that save could restore the pre-drop inventory — a dupe.
                handedOver = handOverRecord(existing);
                handedOverSecret = existing.clientSecret();
            }
            ctx.removePlayer(existing);
            entityHandler.onPeerLeft(existing);
            ctx.broadcastExcept(sp, new PlayerLeaveS2C(stableId), false);
            existing.kick(sp.isLocal() ? NameClaims.HOST_NAME : "You logged in from another location.");
        }

        if (!sp.isLocal()) {
            if (handedOver != null) {
                saved = new SavedRecord(handedOver, null, null, handedOverSecret);
            } else if (saved == null) {
                // The live session this one was taking over left meanwhile: read the file now
                // (its leave just saved it; the named-save gate orders the read behind that).
                int id = stableId;
                com.stonebreak.world.save.SaveService saves =
                        ctx.serverLevel() != null ? ctx.serverLevel().saveService() : null;
                java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> loadSavedRecord(saves, username), PLAYER_LOAD_EXECUTOR)
                        .exceptionally(t -> new SavedRecord(null, null, UNLOADABLE_SAVE))
                        .thenAccept(rec -> executeOnTick(() -> completeHandshake(sp, id, rec)));
                return;
            }
            if (saved.refusal() != null) {
                System.err.println("[SERVER] Refusing " + username + ": " + saved.refusal());
                sp.kick(saved.refusal());
                return;
            }
            sp.setSavedBlob(saved.blob());
            sp.setJoinNotice(saved.notice());
            sp.setClientSecret(NameClaims.secretFor(sp.presentedSecret(), saved.secret()));
        }
        sp.assignPlayerId(stableId);
        ctx.addPlayer(sp);

        // Fresh characters create one before entering the world: a remote player is shown
        // the creation screen (NeedsCharacterCreationS2C); the in-process host's client sends
        // the character it created in the menu right after its handshake, unprompted, so the
        // server's record starts from the same character the host plays (issue #334).
        if (needsCharacterCreation(sp)) {
            sp.setWaitingForCharacterCreation(true);
            if (!sp.isLocal()) {
                sp.send(new NeedsCharacterCreationS2C(sp.playerId(), ctx.worldSeed()));
            }
            System.out.println("[SERVER] " + username + " (id " + sp.playerId()
                    + ") — no saved data, waiting for character creation.");
            return;
        }

        // Standard welcome flow (player has saved data).
        sendWelcomeSnapshot(sp);

        System.out.println("[SERVER] " + username + " joined as id " + sp.playerId());
    }

    /** A remote player's saved record as read at handshake: the blob (null = none), an
     *  optional notice for the player, or a refusal reason (join refused), and the join secret
     *  stored with it (null = none, #335). */
    record SavedRecord(byte[] blob, String notice, String refusal, String secret) {
        SavedRecord(byte[] blob, String notice, String refusal) {
            this(blob, notice, refusal, null);
        }
    }

    /** True when this player has no saved character and must create one before the welcome. */
    private boolean needsCharacterCreation(ServerPlayer sp) {
        if (sp.isLocal()) {
            ServerLevel level = ctx.serverLevel();
            return level != null && level.loadedPlayerData() == null;
        }
        return sp.savedBlob() == null;
    }

    /**
     * Reads {@code players/<username>.json} for a joining remote player. A missing file is a
     * new player. A load that fails or times out refuses the join (the file may be fine — it
     * just could not be read now). A file that loads but does not parse is set aside like
     * #358 does for {@code player.json}, and the player starts fresh with a notice; if it
     * cannot even be set aside, the join is refused rather than overwriting it.
     */
    static SavedRecord loadSavedRecord(com.stonebreak.world.save.SaveService saves, String username) {
        if (saves == null) {
            return new SavedRecord(null, null, null);
        }
        byte[] blob;
        try {
            // Off the tick (Player-Load): a slow disk or a long save queue may take a while;
            // only a read that truly hangs refuses the join.
            blob = saves.loadNamedPlayer(username)
                    .get(PLAYER_LOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SavedRecord(null, null, UNLOADABLE_SAVE);
        } catch (Exception e) {
            System.err.println("[SERVER] Failed to load saved data for " + username + ": " + e);
            return new SavedRecord(null, null, UNLOADABLE_SAVE);
        }
        if (blob == null) {
            return new SavedRecord(null, null, null);
        }
        try {
            com.stonebreak.world.save.model.PlayerData data =
                    new com.stonebreak.world.save.serialization.JsonPlayerSerializer().deserialize(blob);
            return new SavedRecord(blob, null, null, data.getClientSecret());
        } catch (RuntimeException unreadable) {
            System.err.println("[SERVER] Saved data for " + username + " is unreadable: "
                    + unreadable.getMessage());
        }
        try {
            java.nio.file.Path aside = saves.setNamedPlayerAside(username)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            String notice = "Your saved player data could not be read; it was kept as "
                    + aside.getFileName() + " and you start fresh.";
            System.err.println("[SERVER] " + username + ": " + notice);
            return new SavedRecord(null, notice, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SavedRecord(null, null, UNREADABLE_SAVE);
        } catch (Exception moveFailure) {
            System.err.println("[SERVER] Could not set aside " + username + "'s saved data: "
                    + moveFailure);
            return new SavedRecord(null, null, UNREADABLE_SAVE);
        }
    }

    /** How long a joining player's saved record may take to read (off the tick). */
    public static final long PLAYER_LOAD_TIMEOUT_SECONDS = 30;
    private static final String UNLOADABLE_SAVE =
            "Your saved player data could not be loaded right now. Please try again.";
    private static final String UNREADABLE_SAVE =
            "Your saved player data is unreadable and could not be set aside; ask the host to check it.";

    /**
     * The replaced session's live record, serialized, for the session that takes over
     * (issue #334) — or null when it has nothing worth handing over (never seeded, or local).
     */
    private byte[] handOverRecord(ServerPlayer old) {
        ServerLevel level = ctx.serverLevel();
        if (old.isLocal() || !old.isInventorySeeded() || level == null) {
            return null;
        }
        // Never hand over a stranded cursor stack or 2×2 grid (persistPlayer normally just did this).
        com.stonebreak.network.server.handlers.ServerPlayerHandler
                .returnHeldToInventoryOrWorld(old, old.inventory(), ctx);
        return new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                .serialize(com.stonebreak.network.server.ServerPlayerDataFactory.build(old,
                        level.worldData().getWorldName()));
    }

    /** Send the full welcome snapshot (welcome, time, roster, snapshots, saved data). */
    private void sendWelcomeSnapshot(ServerPlayer sp) {
        Vector3f spawn = ctx.spawn();

        // 1. Welcome: who you are + the seed + the authoritative spawn.
        sp.send(new WelcomeS2C(sp.playerId(), ctx.worldSeed(), ctx.generatorType(), spawn.x, spawn.y, spawn.z,
                sp.clientSecret()));
        TimeSyncS2C timeSync = currentTimeSync();
        if (timeSync != null) {
            sp.send(timeSync, false);
        }
        // The world's saved cheats flag — restores it on the client (issue #318).
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            sp.send(new com.stonebreak.network.packet.world.CheatsStateS2C(level.cheatsEnabled()), false);
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

    /**
     * Handle a character creation submission: a remote late-joiner's (after
     * NeedsCharacterCreationS2C) or the in-process host's (sent unprompted after its handshake
     * when the world has no saved player). Validates it, builds the fresh-character record
     * from it — level-1 pools, the background's bonuses and items applied HERE (issue #334) —
     * seeds the replica and baseline from that record, then completes the welcome.
     */
    private void handleCharacterCreation(ServerPlayer sp, CharacterCreationC2S cc) {
        if (!sp.waitingForCharacterCreation()) {
            return; // unexpected — only handle while waiting
        }
        if (!acceptCharacterCreation(sp, cc.json())) {
            return; // refused: the remote player was kicked
        }
        sp.setWaitingForCharacterCreation(false);
        sendWelcomeSnapshot(sp);
        System.out.println("[SERVER] " + sp.username() + " (id " + sp.playerId()
                + ") — character creation accepted.");
    }

    /**
     * Validates a creation blob and seeds the player's fresh character from it (replica,
     * baseline, transform, and the creation-shaped echo for the client). Returns false when a
     * REMOTE player's data was refused (they were kicked); the host always gets a character.
     */
    boolean acceptCharacterCreation(ServerPlayer sp, byte[] json) {
        String error;
        try {
            error = (json == null || json.length == 0)
                    ? "empty character creation data" : validateCharacterCreation(json);
        } catch (RuntimeException malformed) {
            error = "malformed character data"; // e.g. an out-of-range number
        }
        String background = error == null ? creationBackground(json) : null;
        com.stonebreak.world.save.model.PlayerData fresh = null;
        if (error == null) {
            fresh = freshFromCreation(json, background);
            if (fresh == null) {
                error = "spends exceed the level-1 grants, a feat needs a higher level, or ability"
                        + " scores + remaining AP exceed the creation budget ("
                        + PlayerPrefsValidator.CREATION_BUDGET + ")";
            }
        }
        if (error != null) {
            if (!sp.isLocal()) {
                sp.kick("Invalid character data: " + error);
                return false;
            }
            // Never kick the host out of its own world: start from the default character.
            System.err.println("[SERVER] Host character creation refused (" + error
                    + "); starting from the default character.");
            background = null;
            fresh = PlayerPrefsValidator.freshCharacter(null, null, worldName(), ctx.spawn());
        }
        seedFresh(sp, fresh, background);
        // What a remote client applies (applyFromJoinData): the SERVER's record in the
        // creation shape — background bonuses included, so its mirror matches the baseline.
        sp.setCharacterCreationBlob(creationEcho(fresh, background));
        return true;
    }

    /** Parses + normalizes a validated creation blob into the fresh-character record, or null. */
    private com.stonebreak.world.save.model.PlayerData freshFromCreation(byte[] json, String background) {
        com.stonebreak.world.save.model.PlayerData creation;
        try {
            creation = PlayerPrefsValidator.normalizeCreation(
                    new com.stonebreak.world.save.serialization.JsonPlayerSerializer().deserialize(json));
        } catch (RuntimeException e) {
            return null;
        }
        return creation == null ? null
                : PlayerPrefsValidator.freshCharacter(creation, background, worldName(), ctx.spawn());
    }

    /** The creation blob's background id ("adventurer" when absent). */
    static String creationBackground(byte[] json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"background\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "adventurer";
    }

    /** The fresh record as the creation-shaped JSON {@code CharacterStats.applyFromJoinData} reads. */
    static byte[] creationEcho(com.stonebreak.world.save.model.PlayerData fresh, String background) {
        com.stonebreak.player.CharacterStats echo = new com.stonebreak.player.CharacterStats(null);
        echo.restore(fresh.getSelectedClassId(), fresh.getSpentAbilityCp(), fresh.getSkillLevels(),
                fresh.getAcquiredFeatIds(), fresh.getRemainingCp(), fresh.getRemainingSkillPoints(),
                fresh.getRemainingFeatPoints(), fresh.getAbilityScores(), fresh.getRemainingAp(),
                fresh.getLevel(), fresh.getXp());
        echo.setSelectedBackground(background != null ? background : "adventurer");
        return echo.toCreationJson();
    }

    /**
     * Seeds a brand-new character (issue #334): the shared starting items plus the
     * background's items in the replica, the fresh record as the progression baseline, and
     * the transform at the record's (spawn) position.
     */
    private static void seedFresh(ServerPlayer sp, com.stonebreak.world.save.model.PlayerData fresh,
                                  String background) {
        ServerInventory inv = sp.inventory();
        inv.clear();
        for (com.stonebreak.items.StartingItems.Entry e : com.stonebreak.items.StartingItems.ENTRIES) {
            inv.add(e.itemId(), e.count());
        }
        for (com.stonebreak.items.StartingItems.Entry e
                : com.stonebreak.items.StartingItems.backgroundEntries(background)) {
            inv.add(e.itemId(), e.count());
        }
        sp.acceptBaseline(fresh);
        sp.setHealth(fresh.getHealth());
        Vector3f pos = fresh.getPosition();
        sp.seedTransform(pos.x, pos.y, pos.z, fresh.getRotation().x, fresh.getRotation().y);
    }

    private String worldName() {
        ServerLevel level = ctx.serverLevel();
        return level != null ? level.worldData().getWorldName() : null;
    }

    /** Validate character creation JSON. Returns null on success, or an error message. */
    private String validateCharacterCreation(byte[] json) {
        String s = new String(json, java.nio.charset.StandardCharsets.UTF_8);

        // Validate remaining AP (bounds the score budget check below)
        String apPattern = "\"remainingAp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher apM = java.util.regex.Pattern.compile(apPattern).matcher(s);
        // A point-buy may lower a score below 10 for AP, so the unspent pool can exceed 27;
        // what is conserved is scores + AP = the creation budget (issue #334).
        int maxAp = PlayerPrefsValidator.CREATION_BUDGET - 6;
        int ap = 27;
        if (apM.find()) {
            ap = Integer.parseInt(apM.group(1));
            if (ap < 0 || ap > maxAp) return "remainingAp out of range (0-" + maxAp + "), got " + ap;
        }

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
                int maxScore = PlayerPrefsValidator.CREATION_BUDGET - 5;
                if (v < 1 || v > maxScore) return "ability scores must be 1-" + maxScore + ", got " + v;
                sum += v;
            }
            // Score budget (issue #334): 6 x 10 base + 27 AP = 87 before the background
            // bonus, which the SERVER applies afterwards — so no client-claimed bonus fits.
            if (sum + ap > PlayerPrefsValidator.CREATION_BUDGET) {
                return "ability scores + remaining AP too high (" + (sum + ap) + "), max is "
                        + PlayerPrefsValidator.CREATION_BUDGET;
            }
        } else {
            return "missing abilityScores";
        }

        // Validate remaining CP
        String cpPattern = "\"remainingCp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher cpM = java.util.regex.Pattern.compile(cpPattern).matcher(s);
        if (cpM.find()) {
            int cp = Integer.parseInt(cpM.group(1));
            if (cp < 0 || cp > 1000) return "remainingCp out of range (0-1000), got " + cp;
        }

        // Validate remaining SP
        String spPattern = "\"remainingSp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher spM = java.util.regex.Pattern.compile(spPattern).matcher(s);
        if (spM.find()) {
            int sp = Integer.parseInt(spM.group(1));
            if (sp < 0 || sp > 1000) return "remainingSp out of range (0-1000), got " + sp;
        }

        // Validate remaining FP
        String fpPattern = "\"remainingFp\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher fpM = java.util.regex.Pattern.compile(fpPattern).matcher(s);
        if (fpM.find()) {
            int fp = Integer.parseInt(fpM.group(1));
            if (fp < 0 || fp > 1000) return "remainingFp out of range (0-1000), got " + fp;
        }

        // Validate class (if present, must be in registry). "characterClass" is the save
        // reader's key, which toCreationJson now writes (issue #334).
        String classPattern = "\"characterClass\"\\s*:\\s*(\"[^\"]*\"|null)";
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

    /**
     * Seed a player's server-owned inventory replica and progression baseline, then send the
     * client its saved {@code PlayerData} (restores client-side presentation) plus the
     * authoritative {@code InventorySyncS2C} and progression. Applies to remote and local
     * players alike — everyone's debits go through the replica (issue #334).
     */
    private void sendInitialPlayerData(ServerPlayer sp) {
        ServerLevel level = ctx.serverLevel();
        byte[] restore;
        if (sp.characterCreationBlob() != null) {
            // A fresh character: the replica + baseline were seeded from the server's
            // fresh-character record when the creation was accepted; the client gets that
            // record back in the creation shape.
            restore = sp.characterCreationBlob();
        } else {
            byte[] blob;
            if (sp.isLocal()) {
                blob = level != null && level.loadedPlayerData() != null
                        ? new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                                .serialize(level.loadedPlayerData())
                        : null;
            } else {
                blob = sp.savedBlob(); // read + parse-checked at handshake (or handed over)
                sp.setSavedBlob(null);
            }
            if (!seedFromBlob(sp, blob)) {
                // Unreadable after all: never mark this player seeded — a seeded player is
                // persisted, and that would overwrite the save we could not read (#358).
                sp.kick(UNREADABLE_SAVE);
                return;
            }
            restore = blob;
        }
        if (!sp.isLocal()) {
            sp.send(new PlayerDataS2C(restore == null ? new byte[0] : restore), false);
        }
        sp.send(sp.inventorySync(), false);
        sp.markInventorySeeded();
        if (sp.isLocal() && level != null && level.saveService() != null) {
            // The host is saved from this record (player.json), like a remote player is from
            // theirs — never from the client's predicted copy, which can hold intents the
            // server refused or never received.
            level.saveService().bindPlayerSource(
                    worldName -> ServerPlayerDataFactory.build(sp, worldName), this::executeOnTick);
        }
        if (sp.joinNotice() != null) {
            sp.send(new com.stonebreak.network.packet.chat.ChatMessageS2C(
                    sp.playerId(), "Server", sp.joinNotice()), false);
            sp.setJoinNotice(null);
        }
    }

    /**
     * Parses a saved blob into the replica + progression baseline + transform; null/empty →
     * the fresh default character at the spawn. Parses BEFORE touching the replica and
     * returns false (replica untouched) when the blob is unreadable — the local player then
     * falls back to the fresh character (its own save is client-side), a remote one must not
     * be seeded at all.
     */
    boolean seedFromBlob(ServerPlayer sp, byte[] blob) {
        if (blob == null || blob.length == 0) {
            seedFresh(sp, PlayerPrefsValidator.freshCharacter(null, null, worldName(), ctx.spawn()), null);
            return true;
        }
        com.stonebreak.world.save.model.PlayerData data;
        try {
            data = new com.stonebreak.world.save.serialization.JsonPlayerSerializer().deserialize(blob);
        } catch (RuntimeException e) {
            System.err.println("[SERVER] Failed to parse saved data for " + sp.username()
                    + ": " + e.getMessage());
            if (sp.isLocal()) {
                seedFresh(sp, PlayerPrefsValidator.freshCharacter(null, null, worldName(), ctx.spawn()), null);
                return true;
            }
            return false;
        }
        ServerInventory inv = sp.inventory();
        inv.clear();
        List<ItemStack> slots = new ArrayList<>(ServerInventory.TOTAL_SLOTS);
        for (ItemStack s : data.getInventory()) {
            slots.add(s);
        }
        inv.loadFrom(slots);
        inv.selectHotbarSlot(data.getSelectedHotbarSlot());
        sp.acceptBaseline(data);
        sp.setHealth(data.getHealth());
        sp.setFlightEnabled(data.isFlightEnabled());
        sp.setFlying(data.isFlying());
        sp.setSpectator(data.isSpectator());
        sp.setGameMode(data.getGameMode());
        // The stored transform, so a save before the first PlayerStateC2S keeps it (the
        // persistence blob reads the cached transform, which was (0,0,0) until then).
        Vector3f pos = data.getPosition();
        sp.seedTransform(pos.x, pos.y, pos.z, data.getRotation().x, data.getRotation().y);
        return true;
    }

    /**
     * Persist one LEAVING remote player's SERVER-BUILT PlayerData blob (async) — disconnect
     * and session takeover. No-op for local. The player is gone from this session, so the
     * cursor and the 2×2 craft grid are returned for real first (issue #307/#334): into the
     * replica's 36 slots, overflow dropped at the player — the save blob holds only the 36
     * slots, so anything left there would be lost.
     */
    private void persistPlayer(ServerPlayer sp) {
        if (sp == null || sp.isLocal()) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null || level.saveService() == null || !sp.isInventorySeeded()) {
            return;
        }
        com.stonebreak.network.server.handlers.ServerPlayerHandler
                .returnHeldToInventoryOrWorld(sp, sp.inventory(), ctx);
        persistPlayerSnapshot(sp);
    }

    /**
     * Persist a STILL-CONNECTED remote player's blob (async; the periodic crash-safety save)
     * WITHOUT touching the live replica (issue #334): the player may be mid-drag, so the
     * cursor and craft grid are only folded into a copy of the 36 slots by
     * {@link ServerPlayerDataFactory#build} — nothing is moved or dropped. No-op for local.
     */
    private void persistPlayerSnapshot(ServerPlayer sp) {
        if (sp == null || sp.isLocal()) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null || level.saveService() == null || !sp.isInventorySeeded()) {
            return;
        }
        byte[] blob = new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                .serialize(com.stonebreak.network.server.ServerPlayerDataFactory.build(sp,
                        level.worldData().getWorldName()));
        level.saveService().saveNamedPlayer(sp.username(), blob);
    }

    /**
     * C2S: the client's spend-ledger + cosmetic report (issue #334). Level/xp/pools are
     * SERVER-owned and overwritten from {@link ServerPlayer#serverStats()}; spends are
     * validated against the server-granted pools, cosmetics must be monotone/append-only,
     * and the inventory field is ignored entirely. A refusal keeps the accepted baseline
     * and re-asserts both it and the authoritative progression to the sender.
     *
     * <p>Refunds reach back only to the committed {@link SpendFloor} (issue #421): a
     * provisional report (character panel open) opens the respec window, a closing report is
     * judged against the floor and then commits whatever the baseline now holds.
     */
    private void handlePlayerPrefs(ServerPlayer sp, PlayerPrefsC2S pp) {
        withRespecWindow(sp, pp.provisional(), () -> judgePlayerPrefs(sp, pp));
    }

    /** Runs {@code judge} inside the report's respec-window transition (package-private for tests). */
    static void withRespecWindow(ServerPlayer sp, boolean provisional, Runnable judge) {
        if (provisional) {
            sp.openRespecWindow();
        }
        try {
            judge.run();
        } finally {
            if (!provisional) {
                sp.commitSpends();
            }
        }
    }

    private void judgePlayerPrefs(ServerPlayer sp, PlayerPrefsC2S pp) {
        // Every report is answered with a ProgressS2C acking its seq — accepted, refused or
        // unreadable — so the client can tell this answer from echoes of older reports.
        sp.recordPrefsAck(pp.seq());
        ServerLevel level = ctx.serverLevel();
        if (level == null || pp.json().isEmpty()) {
            refusePrefs(sp);
            return;
        }
        com.stonebreak.world.save.model.PlayerData candidate;
        try {
            candidate = new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                    .deserialize(pp.json().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            refusePrefs(sp);
            return;
        }
        com.stonebreak.world.save.model.PlayerData accepted =
                PlayerPrefsValidator.normalize(sp.serverStats(), sp.progressionBaseline(),
                        sp.spendFloor(), candidate);
        if (accepted == null) {
            System.out.println("[SERVER] Refused prefs from " + sp.username());
            refusePrefs(sp);
            return;
        }
        sp.acceptBaseline(accepted);
        sp.setHealth(PlayerPrefsValidator.acceptedHealth(sp.serverStats(), accepted.getHealth()));
        // Cheats-gated prefs: flight/spectator/creative only when the SERVER's flag is on.
        if (level.cheatsEnabled()) {
            sp.setFlightEnabled(accepted.isFlightEnabled());
            sp.setFlying(accepted.isFlying());
            sp.setSpectator(accepted.isSpectator());
            sp.setGameMode(accepted.getGameMode());
        }
        // Echo the authoritative progression so the client mirror cannot drift from it.
        sp.sendProgression();
    }

    private void refusePrefs(ServerPlayer sp) {
        if (sp.progressionBaseline() != null) {
            sp.send(new PlayerDataS2C(new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                    .serialize(sp.progressionBaseline())), false);
        }
        sp.sendProgression();
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
                                 float dealt, boolean killed,
                                 com.stonebreak.mobs.entities.LivingEntity.DamageSource source) {
        ServerPlayer sp = ctx.player(playerId);
        if (sp != null) {
            entityHandler.sendKillCredit(sp, victim, dealt, killed, source,
                    com.stonebreak.mobs.entities.HitKind.PROJECTILE);
        }
    }

    /**
     * Server-side: hand a connected client an item stack, state included (drop pickup,
     * command-give). Server thread only: {@link ServerPlayer} state is not thread-safe, so a
     * call from any other thread is refused (and reported once) instead of racing the tick.
     */
    public void giveItemTo(int playerId, NetItemStack stack) {
        if (stack == null || stack.isEmpty() || !requireTickThread("giveItemTo")) {
            return;
        }
        giveToPlayer(ctx.player(playerId), ctx, stack);
    }

    /**
     * True on the thread running {@link #tick} — and before the first tick or once the loop
     * has stopped, when no other thread can be mid-tick.
     */
    public boolean isTickThread() {
        Thread t = tickThread;
        return t == null || !ticking || Thread.currentThread() == t;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean OFF_THREAD_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Guard for server-thread-only entry points (#341): false — the caller must do nothing —
     * when called from another thread, with a stack trace the first time so the stray
     * caller is easy to find.
     */
    public boolean requireTickThread(String what) {
        if (isTickThread()) {
            return true;
        }
        if (OFF_THREAD_REPORTED.compareAndSet(false, true)) {
            new IllegalStateException("[SERVER] " + what + " called off the server thread ("
                    + Thread.currentThread().getName() + "); refused").printStackTrace();
        }
        return false;
    }

    /** {@link #giveToPlayer(ServerPlayer, ServerWorldContext, NetItemStack)} for default-state stacks. */
    void giveToPlayer(ServerPlayer sp, ServerWorldContext ctx, int itemId, int count) {
        giveToPlayer(sp, ctx, new NetItemStack(itemId, count));
    }

    /** The give ledger (issue #334): only what fits the replica is granted; the overflow
     *  re-drops here as a world drop and the sync is the client's reconciliation point.
     *  The stack keeps its state end to end (issue #342). Package-private so the ledger
     *  rules are unit-testable without a live server. */
    void giveToPlayer(ServerPlayer sp, ServerWorldContext ctx, NetItemStack stack) {
        if (sp == null || stack.isEmpty()) {
            return;
        }
        // The SERVER's replica is the ledger (issue #334): only what actually fits is given,
        // the overflow re-drops here as a world drop, and the sync that follows is the
        // client's reconciliation point. No client-side re-drop path exists any more.
        int added = sp.inventory().add(stack.itemId(), stack.count(), stack.state());
        int overflow = stack.count() - added;
        if (overflow > 0) {
            com.stonebreak.network.server.handlers.ServerPlayerHandler.spawnTossDrop(
                    sp, ctx, stack.itemId(), overflow, stack.state());
        }
        if (added > 0) {
            sp.send(new GiveItemS2C(new NetItemStack(
                    stack.itemId(), added, stack.state())), false);
        }
        sp.queueInventorySync(); // a tick of pickups (a death pile) is one sync, at tick end
    }

    public void shutdown() {
        ticking = false;
        drainTickTasks();
        persistAllRemotePlayersBlocking();
        blockHandler.onSessionEnd();
        entityHandler.onSessionEnd();
        fishingHandler.onSessionEnd();
        illusionHandler.onSessionEnd();
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
            if (!sp.isLocal() && sp.isInventorySeeded()) {
                // Same as persistPlayer (issue #307/#334): the session is ending, so the
                // cursor and the 2×2 craft grid are returned for real — whatever does not
                // fit drops at the player instead of vanishing with the unsaved grid.
                com.stonebreak.network.server.handlers.ServerPlayerHandler
                        .returnHeldToInventoryOrWorld(sp, sp.inventory(), ctx);
                byte[] blob = new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                        .serialize(com.stonebreak.network.server.ServerPlayerDataFactory.build(sp,
                                level.worldData().getWorldName()));
                futures.add(level.saveService().saveNamedPlayer(sp.username(), blob));
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
