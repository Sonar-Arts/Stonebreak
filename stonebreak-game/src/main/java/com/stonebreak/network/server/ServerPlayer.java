package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.util.LongIntHashMap;

/**
 * Server-side state for one connected player. Successor to the old {@code RemoteClient}'s
 * cached fields, minus the transport (that now lives in the engine
 * {@link ServerConnection}). Created when a channel connects, promoted to a roster member
 * once its handshake completes.
 *
 * <p>All fields are touched only on the server tick thread (inbound is drained there), so
 * no synchronization is needed.
 */
public final class ServerPlayer {

    private final ServerConnection connection;
    /** Provisional (negative) until the handshake resolves the stable username-derived id. */
    private int playerId;
    private String username = "Player";
    /** True once a valid HandshakeC2S has been accepted (gates PLAY packet handling). */
    private boolean handshakeDone = false;
    /** True while waiting for the client to submit character creation data. */
    private boolean waitingForCharacterCreation = false;
    /** JSON blob of the client's submitted character creation (set by CharacterCreationC2S). */
    private volatile byte[] characterCreationBlob;

    // Last reported transform (cached from PlayerStateC2S) for reach checks + chunk targeting.
    private float x, y, z, yaw, pitch;
    private long lastStateNs = 0L;
    /** Last death/respawn edge the client reported (PlayerLifeC2S, reliable — #357). */
    private boolean dead = false;

    /** Nanos of this player's last authoritative shear request (containment rate limit). */
    private long lastShearNanos = 0L;

    // Per-player chunk-view tracker (was ChunkSynchronizer.PlayerView). Stores the chunk
    // VERSION last sent to this player so the server re-streams a chunk when it changes
    // server-side (player edits, water flow). Absent key = never sent. Primitive-keyed —
    // the steady-state view scan probes this per ring cell, and a boxed Map<Long, Integer>
    // allocated a Long per probe.
    private int lastCx = Integer.MIN_VALUE;
    private int lastCz = Integer.MIN_VALUE;
    private final LongIntHashMap sentChunkVersions = new LongIntHashMap();

    /**
     * True while this player's chunk view may be out of date and the streaming scan in
     * {@code ServerChunkHandler.tick} must run. Set on join (initial fill), on chunk-boundary
     * crossing, on view-distance change, and when any chunk version bumps; cleared by the
     * handler only after a scan confirms every in-view chunk is sent at its current version.
     * Lets the server skip the O(view²) ring walk entirely for stationary players.
     */
    private boolean viewScanPending = true;

    public boolean viewScanPending() { return viewScanPending; }
    public void markViewScanPending() { this.viewScanPending = true; }
    public void clearViewScanPending() { this.viewScanPending = false; }

    public ServerPlayer(ServerConnection connection, int playerId) {
        this.connection = connection;
        this.playerId = playerId;
    }

    public ServerConnection connection() { return connection; }
    public int playerId() { return playerId; }
    /** Assign the stable id at handshake time, BEFORE the player enters the roster. */
    public void assignPlayerId(int playerId) { this.playerId = playerId; }
    public String username() { return username; }
    public void setUsername(String username) { this.username = username; }
    public boolean handshakeDone() { return handshakeDone; }
    public void markHandshakeDone() { this.handshakeDone = true; }

    /** The entity this player last hit (accepted damage) and when — gates ability knockback. */
    private int lastHitTarget = -1;
    private long lastHitNanos;
    public void recordHit(int targetNetworkId, long nowNanos) {
        lastHitTarget = targetNetworkId;
        lastHitNanos = nowNanos;
    }
    public boolean hitRecently(int targetNetworkId, long nowNanos, long windowNanos) {
        return lastHitTarget == targetNetworkId && nowNanos - lastHitNanos <= windowNanos;
    }

    /** A handshake arrived (its saved record may still be loading); a repeat is ignored. */
    private boolean handshakeRequested;
    public boolean handshakeRequested() { return handshakeRequested; }
    public void markHandshakeRequested() { this.handshakeRequested = true; }

    public boolean waitingForCharacterCreation() { return waitingForCharacterCreation; }
    public void setWaitingForCharacterCreation(boolean waiting) { this.waitingForCharacterCreation = waiting; }
    public byte[] characterCreationBlob() { return characterCreationBlob; }
    public void setCharacterCreationBlob(byte[] blob) { this.characterCreationBlob = blob; }

    /** This player's saved record, read (and parse-checked) once at handshake — or handed
     *  over in memory from the session it replaced — and consumed by the replica seed. Null
     *  for a player with no saved data (issue #334). */
    private volatile byte[] savedBlob;
    public byte[] savedBlob() { return savedBlob; }
    public void setSavedBlob(byte[] blob) { this.savedBlob = blob; }

    /** One-shot notice delivered to the player after the welcome (e.g. a save set aside). */
    private volatile String joinNotice;
    public String joinNotice() { return joinNotice; }
    public void setJoinNotice(String notice) { this.joinNotice = notice; }

    public float x() { return x; }
    public float y() { return y; }
    public float z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public long lastStateNs() { return lastStateNs; }

    /** True between this player's reported death and its respawn (#357). */
    public boolean isDead() { return dead; }
    public void setDead(boolean dead) {
        this.dead = dead;
        if (!dead) {
            deathDropClaimed = false; // respawned: the next death may drain again (#420)
        }
    }

    /**
     * True when this player stands in the world as a live body: handshake done, a real
     * position reported (not the {@code (0,0,0)} default), and not dead. The one predicate
     * for roster loops that act on a player's body — drop pickup, mob spawning around it
     * (#357). A dead player still counts for what it SEES (chunk streaming, entity interest,
     * despawn radius): the world stays on screen behind the death menu.
     */
    public boolean isLiveInWorld() {
        return handshakeDone && lastStateNs != 0L && !dead;
    }

    public void updateState(float x, float y, float z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        this.lastStateNs = System.nanoTime();
    }

    /**
     * Seeds the transform from the player's stored record (or the spawn, for a fresh one)
     * when the replica is seeded (issue #334), WITHOUT counting as a client report
     * ({@link #lastStateNs} stays 0, so spawn anchors and roster bootstraps still wait for
     * the first PlayerStateC2S). A save taken before that first report keeps the stored
     * position instead of writing (0,0,0).
     */
    public void seedTransform(float x, float y, float z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    public long lastShearNanos() { return lastShearNanos; }
    public void markShearRequest(long nanos) { this.lastShearNanos = nanos; }

    /** Render distance reported by this client (ViewDistanceC2S), clamped to the
     *  allowed range. Drives this player's chunk-streaming view radius. */
    private int viewDistanceChunks = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_RENDER_DISTANCE;

    public int viewDistanceChunks() { return viewDistanceChunks; }

    public void setViewDistanceChunks(int chunks) {
        int clamped = Math.max(
            com.stonebreak.world.operations.WorldConfiguration.MIN_RENDER_DISTANCE,
            Math.min(com.stonebreak.world.operations.WorldConfiguration.MAX_RENDER_DISTANCE, chunks));
        if (clamped != this.viewDistanceChunks) {
            this.viewDistanceChunks = clamped;
            markViewScanPending(); // wider view needs new chunks; narrower needs a forget pass
        }
    }

    // ─── Keepalive / liveness (all touched on the server tick thread) ────────────
    /** When the outstanding keepalive was sent; 0 = none outstanding. */
    private long keepaliveSentNs = 0L;
    /** Nonce of the outstanding keepalive; the echo must match to count. */
    private long pendingKeepaliveNonce = 0L;
    /** Last time ANY packet arrived from this client (touched on every routed packet). */
    private long lastInboundNs = System.nanoTime();
    /** Last measured round-trip time (ms). 0 until the first echo. */
    private int rttMs = 0;

    public long keepaliveSentNs() { return keepaliveSentNs; }
    public long pendingKeepaliveNonce() { return pendingKeepaliveNonce; }
    public long lastInboundNs() { return lastInboundNs; }
    public int rttMs() { return rttMs; }
    public void touchInbound() { this.lastInboundNs = System.nanoTime(); }

    public void markKeepaliveSent(long nonce, long nowNs) {
        this.pendingKeepaliveNonce = nonce;
        this.keepaliveSentNs = nowNs;
    }

    /** Answer the outstanding keepalive; returns false (ignored) on a stale/unknown nonce. */
    public boolean answerKeepalive(long nonce, long nowNs) {
        if (keepaliveSentNs == 0L || nonce != pendingKeepaliveNonce) {
            return false;
        }
        this.rttMs = (int) Math.min(Integer.MAX_VALUE, (nowNs - keepaliveSentNs) / 1_000_000L);
        this.keepaliveSentNs = 0L;
        return true;
    }

    // ─── Resync rate limiting (chunk resync requests + entity resyncs) ──────────
    /** Max resync-type requests per rolling window (hostile/buggy client containment). */
    private static final int MAX_RESYNCS_PER_WINDOW = 32;
    private static final long RESYNC_WINDOW_NS = 10_000_000_000L; // 10 s
    private long resyncWindowStartNs = 0L;
    private int resyncCount = 0;

    /**
     * Chunk resync requests refused over budget, retried by the server as permits free up
     * ({@code ServerChunkHandler.tick}) — the client asks once per cooldown and a refused
     * request used to be lost for good (#363). Bounded; server tick thread only.
     */
    private final java.util.LinkedHashSet<Long> deferredChunkResyncs = new java.util.LinkedHashSet<>();
    private static final int MAX_DEFERRED_CHUNK_RESYNCS = 256;

    /** Queues a refused chunk resync for {@link #drainDeferredChunkResyncs}; drops it when full. */
    public void deferChunkResync(long key) {
        if (deferredChunkResyncs.size() < MAX_DEFERRED_CHUNK_RESYNCS) {
            deferredChunkResyncs.add(key);
        }
    }

    /** Hands deferred resyncs to {@code action} while permits allow, oldest first. */
    public void drainDeferredChunkResyncs(java.util.function.LongConsumer action) {
        var it = deferredChunkResyncs.iterator();
        while (it.hasNext() && allowResync()) {
            long key = it.next();
            it.remove();
            action.accept(key);
        }
    }

    /** Debits one resync-request permit; false = over budget, drop the request. */
    public boolean allowResync() {
        long now = System.nanoTime();
        if (now - resyncWindowStartNs > RESYNC_WINDOW_NS) {
            resyncWindowStartNs = now;
            resyncCount = 0;
        }
        return ++resyncCount <= MAX_RESYNCS_PER_WINDOW;
    }

    // ─── Server-owned inventory + progression (issue #334) ──────────────────────
    /** The authoritative replica of this player's inventory + carried stack + hotbar slot. */
    private final ServerInventory inventory = new ServerInventory();
    /** Seq of the newest numbered intent processed from this player (#334/#353) — container
     *  actions and every other {@code SequencedIntent} the client's mirror predicts. */
    private int lastActionSeq;
    /** The client skipped a stale sync and asked for a fresh one ({@code InventoryResyncC2S}). */
    private boolean inventoryResyncRequested;

    /** Records a processed (accepted or refused) action's seq; syncs acknowledge the newest. */
    public void recordActionSeq(int seq) {
        if (seq > lastActionSeq) {
            lastActionSeq = seq;
        }
    }

    /**
     * This player's inventory sync, acknowledging the newest action processed from them. Every
     * caller sends what this returns, so it also answers a pending resync request.
     */
    public com.stonebreak.network.packet.player.InventorySyncS2C inventorySync() {
        inventoryResyncRequested = false;
        return new com.stonebreak.network.packet.player.InventorySyncS2C(inventory.encode(), lastActionSeq);
    }

    // ─── Break timing (issue #333) ─────────────────────────────────────────────
    /** The block this player is mining ({@code BlockBreakStartC2S}); null when none. */
    private com.openmason.engine.util.BlockPos breakTarget;
    /** When mining {@link #breakTarget} started on the server's clock. */
    private long breakStartNs;
    /** Until when an earlier break that arrived early is still "being mined": breaks are
     *  sequential, so the next block's clock cannot start before this. */
    private long miningBusyUntilNs;

    /**
     * Mining started on {@code (x, y, z)} at arrival time {@code nowNs}. Its clock starts no
     * earlier than the end of a break still waiting to complete — a player mines one block at
     * a time, however early its finishes arrive.
     */
    public void startBreak(int x, int y, int z, long nowNs) {
        breakTarget = new com.openmason.engine.util.BlockPos(x, y, z);
        breakStartNs = Math.max(nowNs, miningBusyUntilNs);
    }

    /** Whether the player's current break is on {@code (x, y, z)}. */
    public boolean isBreaking(int x, int y, int z) {
        com.openmason.engine.util.BlockPos t = breakTarget;
        return t != null && t.x() == x && t.y() == y && t.z() == z;
    }

    /** When the current break's clock started (valid while {@link #isBreaking}). */
    public long breakStartNs() {
        return breakStartNs;
    }

    /** The current break was finished; the next one's clock starts no earlier than {@code busyUntilNs}. */
    public void finishBreak(long busyUntilNs) {
        breakTarget = null;
        miningBusyUntilNs = Math.max(miningBusyUntilNs, busyUntilNs);
    }

    /** Notes an {@code InventoryResyncC2S}; answered by {@link #flushInventoryResync}. */
    public void requestInventoryResync() {
        inventoryResyncRequested = true;
    }

    /**
     * Owes this player a sync, sent once at the next flush (after the inbound drain, and at the
     * end of each tick) however many changes queued it: a right-drag over nine slots or a tick
     * of drop pickups used to send a full 36-slot sync per action.
     */
    public void queueInventorySync() {
        inventoryResyncRequested = true;
    }

    /**
     * Sends the requested sync, if no sync went out since the request (that one already
     * answered it). Called once per inbound drain, so a burst of requests costs one sync.
     */
    public void flushInventoryResync() {
        if (inventoryResyncRequested) {
            send(inventorySync(), false);
        }
    }

    /** The last ACCEPTED progression/prefs state (parsed PlayerPrefsC2S, the creation blob,
     *  or the loaded save). The server-built persistence blob and every validation compare
     *  against this. Never null after join. */
    private volatile com.stonebreak.world.save.model.PlayerData progressionBaseline;

    /** Mirrored prefs from the accepted PlayerPrefsC2S (cheats-gated server-side). */
    private volatile float health = 20.0f;
    private volatile boolean flightEnabled = false;
    private volatile boolean flying = false;
    private volatile boolean spectator = false;
    private volatile int gameMode = 0;

    /** True once the replica has been seeded (welcome/creation) — syncs are meaningful after this. */
    private volatile boolean inventorySeeded = false;

    /** The server's own progression state (issue #334): level/xp advance ONLY through
     *  server-granted kill XP — a client report can never move them. Kept in lockstep
     *  with the accepted baseline via {@link #acceptBaseline}. */
    private final com.stonebreak.player.CharacterStats serverStats =
            new com.stonebreak.player.CharacterStats(null);

    public com.stonebreak.player.CharacterStats serverStats() { return serverStats; }

    public ServerInventory inventory() { return inventory; }

    /**
     * Publishes an accepted progression baseline and re-seeds the server stats from it,
     * so validation anchors (scores, AP pool, level) stay in lockstep with the accepted
     * state (issue #334).
     */
    public void acceptBaseline(com.stonebreak.world.save.model.PlayerData baseline) {
        this.progressionBaseline = baseline;
        serverStats.restore(baseline.getSelectedClassId(), baseline.getSpentAbilityCp(),
                baseline.getSkillLevels(), baseline.getAcquiredFeatIds(),
                baseline.getRemainingCp(), baseline.getRemainingSkillPoints(),
                baseline.getRemainingFeatPoints(), baseline.getAbilityScores(),
                baseline.getRemainingAp(), baseline.getLevel(), baseline.getXp());
    }

    /** Seq of the last PlayerPrefsC2S processed (accepted or refused); 0 before the first. */
    private int prefsAck = 0;
    public int prefsAck() { return prefsAck; }
    /** Records that the prefs report numbered {@code seq} was processed (issue #334). */
    public void recordPrefsAck(int seq) { this.prefsAck = seq; }

    /** Sends the client the server-authoritative progression fields (ProgressS2C), acking
     *  the last processed prefs report. */
    public void sendProgression() {
        send(buildProgression());
    }

    /** The ProgressS2C {@link #sendProgression} sends (package-private for tests). */
    com.stonebreak.network.packet.player.ProgressS2C buildProgression() {
        return new com.stonebreak.network.packet.player.ProgressS2C(prefsAck,
                serverStats.getLevel(), serverStats.getXp(),
                serverStats.getRemainingCp(), serverStats.getRemainingSkillPoints(),
                serverStats.getRemainingFeatPoints(), serverStats.getRemainingAp());
    }

    public com.stonebreak.world.save.model.PlayerData progressionBaseline() { return progressionBaseline; }

    /**
     * Re-derives the progression baseline from the server's own stats (issue #334): XP and the
     * level-up point pools granted server-side since the last accepted prefs commit must reach
     * disk and stay the validation anchor. Cosmetics/prefs sections keep their last accepted
     * values. No-op before a baseline exists.
     */
    public void refreshProgressionBaseline() {
        com.stonebreak.world.save.model.PlayerData base = progressionBaseline;
        if (base == null) {
            return;
        }
        acceptBaseline(new com.stonebreak.world.save.model.PlayerData.Builder(base)
                .selectedClassId(serverStats.getSelectedClassId())
                .spentAbilityCp(serverStats.getSpentAbilityCp())
                .skillLevels(serverStats.getSkillLevels())
                .acquiredFeatIds(serverStats.getAcquiredFeatIds())
                .remainingCp(serverStats.getRemainingCp())
                .remainingSp(serverStats.getRemainingSkillPoints())
                .remainingFp(serverStats.getRemainingFeatPoints())
                .abilityScores(serverStats.getAbilityScores())
                .remainingAp(serverStats.getRemainingAp())
                .level(serverStats.getLevel())
                .xp(serverStats.getXp())
                .build());
    }

    public boolean isInventorySeeded() { return inventorySeeded; }
    public void markInventorySeeded() { this.inventorySeeded = true; }

    public float health() { return health; }
    public void setHealth(float health) { this.health = Math.max(0f, Math.min(1024f, health)); }
    public boolean flightEnabled() { return flightEnabled; }
    public void setFlightEnabled(boolean flightEnabled) { this.flightEnabled = flightEnabled; }
    public boolean flying() { return flying; }
    public void setFlying(boolean flying) { this.flying = flying; }
    public boolean spectator() { return spectator; }
    public void setSpectator(boolean spectator) { this.spectator = spectator; }
    public int gameMode() { return gameMode; }
    public void setGameMode(int gameMode) { this.gameMode = gameMode; }

    // ─── Toss rate limiting (DropItemC2S is a debit request now) ────────────────
    /** Max toss requests per rolling window (hostile/buggy client containment). */
    private static final int MAX_DROPS_PER_WINDOW = 20;
    private static final long DROP_WINDOW_NS = 10_000_000_000L; // 10 s
    private long dropWindowStartNs = 0L;
    private int dropCount = 0;

    /** Debits one toss permit; false = over budget, refuse the request. */
    public boolean allowDrop() {
        long now = System.nanoTime();
        if (now - dropWindowStartNs > DROP_WINDOW_NS) {
            dropWindowStartNs = now;
            dropCount = 0;
        }
        return ++dropCount <= MAX_DROPS_PER_WINDOW;
    }

    // ─── Death drain latch (#420) ────────────────────────────────────────────────
    /** True once this death's DeathDropC2S drained the replica; cleared by the respawn edge. */
    private boolean deathDropClaimed = false;
    private long lastDeathDropNs = Long.MIN_VALUE;

    /**
     * Claims this death's single inventory drain: true only while the player is dead, once
     * per death, and at most once per toss window. Until #343 the server learns deaths from
     * the client's own PlayerLifeC2S, so the window is what keeps a client toggling
     * dead/alive from scattering its inventory faster than {@link #allowDrop} allows tosses.
     */
    public boolean claimDeathDrop() {
        if (!dead || deathDropClaimed) {
            return false;
        }
        long now = System.nanoTime();
        if (lastDeathDropNs != Long.MIN_VALUE && now - lastDeathDropNs <= DROP_WINDOW_NS) {
            return false;
        }
        deathDropClaimed = true;
        lastDeathDropNs = now;
        return true;
    }

    /** True for the in-process (host/singleplayer) player on the in-JVM Local channel. Its state
     *  is persisted same-JVM, so it is excluded from the network player-data sync. */
    public boolean isLocal() {
        return connection.channel() instanceof io.netty.channel.local.LocalChannel;
    }

    // ─── Chunk-view tracker ───────────────────────────────────────────────────
    public int lastCx() { return lastCx; }
    public int lastCz() { return lastCz; }
    public void setLastChunk(int cx, int cz) {
        if (cx != this.lastCx || cz != this.lastCz) {
            markViewScanPending(); // crossed a chunk boundary — the view edge moved
        }
        this.lastCx = cx;
        this.lastCz = cz;
    }
    // ─── Chunk flow control (after Minecraft's chunk batching) ─────────────────
    /** Client-reported install rate (chunks per tick); NaN until its first ChunkAckC2S. */
    private float chunkRate = Float.NaN;
    /** Chunks this tick may still stream; refilled by the rate each tick, capped at one tick's worth. */
    private float chunkQuota;
    /** Snapshots sent but not yet acknowledged as processed by the client. */
    private int chunksInFlight;
    /** Ticks since the last ack while snapshots were in flight (a lost-ack watchdog). */
    private int ticksSinceChunkAck;

    public float chunkRate() { return chunkRate; }
    public float chunkQuota() { return chunkQuota; }
    public void setChunkQuota(float quota) { this.chunkQuota = quota; }
    public int chunksInFlight() { return chunksInFlight; }

    /** One snapshot streamed: spends quota, joins the in-flight window. */
    public void onChunkStreamed() {
        chunkQuota -= 1f;
        chunksInFlight++;
    }

    /** C2S ChunkAckC2S: {@code processed} snapshots left the client's queue; its new rate. */
    public void onChunkAck(int processed, float desiredPerTick) {
        chunksInFlight = Math.max(0, chunksInFlight - processed);
        chunkRate = desiredPerTick;
        ticksSinceChunkAck = 0;
    }

    /**
     * Advances the lost-ack watchdog; true (and the window reopens) when snapshots have been
     * in flight for {@code limit} ticks with no ack — streaming must never stall for good on a
     * count that drifted.
     */
    public boolean chunkAckOverdue(int limit) {
        if (chunksInFlight == 0) {
            ticksSinceChunkAck = 0;
            return false;
        }
        if (++ticksSinceChunkAck < limit) {
            return false;
        }
        chunksInFlight = 0;
        ticksSinceChunkAck = 0;
        return true;
    }

    /** Version of {@code key} last sent to this player, or -1 if never sent. */
    public int sentChunkVersion(long key) { return sentChunkVersions.get(key, -1); }
    public void markChunkSent(long key, int version) { sentChunkVersions.put(key, version); }
    /** Forget a chunk (e.g. it left the view) so it re-streams if it returns. */
    public void forgetChunk(long key) { sentChunkVersions.remove(key); }
    /** Forget every sent chunk whose packed key matches the predicate (left the keep radius). */
    public void forgetChunksMatching(java.util.function.LongPredicate predicate) {
        sentChunkVersions.removeIf(predicate);
    }

    // ─── Slot-intent acks (issue #353) ─────────────────────────────────────────
    /** Per furnace/workbench this player edited: seq of the last slot intent processed. One
     *  entry per block the player ever opened and edited this session — tiny. */
    private final java.util.Map<com.openmason.engine.util.BlockPos, Integer> slotIntentSeqs =
            new java.util.HashMap<>();

    /** Records that the slot intent numbered {@code seq} for {@code pos} was processed
     *  (applied or refused): the next echo of that block to this player acknowledges it. */
    public void recordSlotIntent(com.openmason.engine.util.BlockPos pos, int seq) {
        slotIntentSeqs.put(pos, seq);
    }

    /** Seq of the last slot intent from this player processed at {@code pos}; 0 if none. */
    public int slotIntentAck(com.openmason.engine.util.BlockPos pos) {
        return slotIntentSeqs.getOrDefault(pos, 0);
    }

    // ─── Transport convenience ────────────────────────────────────────────────
    public boolean send(Packet packet, boolean droppable) { return connection.send(packet, droppable); }
    public boolean send(Packet packet) { return connection.send(packet, false); }
    public void setPhase(ProtocolPhase phase) { connection.setPhase(phase); }
    public ProtocolPhase phase() { return connection.phase(); }
    public void disconnect() { connection.close(); }
    /** Kicks with {@code reason}: the connection closes once the KickS2C has been flushed. */
    public void kick(String reason) {
        connection.sendAndClose(new com.stonebreak.network.packet.handshake.KickS2C(reason));
    }

    @Override
    public String toString() {
        return "ServerPlayer[" + playerId + " " + username + "]";
    }
}
