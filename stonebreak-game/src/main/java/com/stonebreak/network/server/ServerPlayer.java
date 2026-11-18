package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.ServerConnection;

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
    // World position (double, #445) + view angles.
    private double x, y, z;
    private float yaw, pitch;
    private long lastStateNs = 0L;
    /** Dead between the server's kill and the player's respawn (#343; was client-reported, #357). */
    private boolean dead = false;

    /** Nanos of this player's last authoritative shear request (containment rate limit). */
    private long lastShearNanos = 0L;

    /** Cube-stream state (requests, sent cubes/columns, flow control) - cubic voxel runtime, #447. */
    private final PlayerCubeView cubeView = new PlayerCubeView();

    public PlayerCubeView cubeView() { return cubeView; }


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

    /** Melee cooldown and ability/status/projectile budgets for this player's combat intents (#339). */
    private final com.stonebreak.network.server.combat.CombatLimiter combat =
            new com.stonebreak.network.server.combat.CombatLimiter();
    public com.stonebreak.network.server.combat.CombatLimiter combat() { return combat; }

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

    /** The join secret this player's client presented in its handshake ("" = none, #335). */
    private String presentedSecret = "";
    public String presentedSecret() { return presentedSecret; }
    public void setPresentedSecret(String secret) { this.presentedSecret = secret == null ? "" : secret; }

    /** The join secret this server holds for the player (saved with it, #335); null for the host. */
    private String clientSecret;
    public String clientSecret() { return clientSecret; }
    public void setClientSecret(String secret) { this.clientSecret = secret; }

    /** The boat this player last boarded; it still seats them while its seat names them. */
    private volatile com.stonebreak.vehicles.Dinghy boardedVehicle;
    public void setBoardedVehicle(com.stonebreak.vehicles.Dinghy boat) { this.boardedVehicle = boat; }
    /** The boat this player sits in now, or null on foot. */
    public com.stonebreak.vehicles.Dinghy seatedVehicle() {
        com.stonebreak.vehicles.Dinghy boat = boardedVehicle;
        return boat != null && boat.isAlive() && boat.seatOfPlayer(playerId()) >= 0 ? boat : null;
    }

    /** One-shot notice delivered to the player after the welcome (e.g. a save set aside). */
    private volatile String joinNotice;
    public String joinNotice() { return joinNotice; }
    public void setJoinNotice(String notice) { this.joinNotice = notice; }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public long lastStateNs() { return lastStateNs; }

    /** True between this player's death and its respawn — both decided by the server (#343). */
    public boolean isDead() { return dead; }
    public void setDead(boolean dead) { this.dead = dead; }

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

    /**
     * Live and not an {@linkplain #isObserver() observer}: the players mobs see, flee from,
     * pursue and hit.
     */
    public boolean isMobTargetable() {
        return isLiveInWorld() && !observer;
    }

    /** The main menu showcase's camera: in the world (it streams, anchors spawns) but no mob sees it. */
    public boolean isObserver() {
        return observer;
    }

    public void setObserver(boolean observer) {
        this.observer = observer;
    }

    private volatile boolean observer;

    public void updateState(double x, double y, double z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        this.lastStateNs = System.nanoTime();
    }

    /** Movement/action flags from the last PlayerStateC2S ({@code PlayerStateFlags}). */
    private short movementFlags;

    public void setMovementFlags(short flags) { this.movementFlags = flags; }
    public short movementFlags() { return movementFlags; }

    /** Whether the client last reported this player sprinting — what skittish mobs notice. */
    public boolean isSprinting() {
        return com.stonebreak.network.packet.player.PlayerStateFlags.has(
            movementFlags, com.stonebreak.network.packet.player.PlayerStateFlags.SPRINTING);
    }

    /**
     * Seeds the transform from the player's stored record (or the spawn, for a fresh one)
     * when the replica is seeded (issue #334), WITHOUT counting as a client report
     * ({@link #lastStateNs} stays 0, so spawn anchors and roster bootstraps still wait for
     * the first PlayerStateC2S). A save taken before that first report keeps the stored
     * position instead of writing (0,0,0).
     */
    public void seedTransform(double x, double y, double z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    public long lastShearNanos() { return lastShearNanos; }
    public void markShearRequest(long nanos) { this.lastShearNanos = nanos; }

    /** Render distance reported by this client (ViewDistanceC2S), clamped to the
     *  allowed range: this player's stream center horizontal radius (x 16 blocks). */
    private int viewDistanceChunks = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_RENDER_DISTANCE;
    /** Vertical render distance reported by this client (blocks): the stream ellipsoid's half-height. */
    private int verticalViewBlocks = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_VERTICAL_RENDER_DISTANCE;

    /** Vertical view distance bounds (blocks) the server accepts. */
    public static final int MIN_VERTICAL_VIEW = 32;
    public static final int MAX_VERTICAL_VIEW = 512;

    public int viewDistanceChunks() { return viewDistanceChunks; }
    public int verticalViewBlocks() { return verticalViewBlocks; }

    public void setViewDistanceChunks(int chunks) {
        this.viewDistanceChunks = Math.max(
            com.stonebreak.world.operations.WorldConfiguration.MIN_RENDER_DISTANCE,
            Math.min(com.stonebreak.world.operations.WorldConfiguration.MAX_RENDER_DISTANCE, chunks));
    }

    public void setVerticalViewBlocks(int blocks) {
        this.verticalViewBlocks = Math.max(MIN_VERTICAL_VIEW, Math.min(MAX_VERTICAL_VIEW, blocks));
    }

    /** True once this player reported a position (the stream centers on it; cube requests are served). */
    public boolean hasReportedPosition() {
        return lastStateNs() != 0L;
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

    /** Debits one resync-request permit; false = over budget, drop the request. */
    public boolean allowResync() {
        long now = System.nanoTime();
        if (now - resyncWindowStartNs > RESYNC_WINDOW_NS) {
            resyncWindowStartNs = now;
            resyncCount = 0;
        }
        return ++resyncCount <= MAX_RESYNCS_PER_WINDOW;
    }

    // ─── Abuse limits (issue #336) ───────────────────────────────────────────────
    /** Horizontal movement budget for this player's position reports. */
    private final MovementGuard movement = new MovementGuard();
    public MovementGuard movement() { return movement; }

    /**
     * Cube requests ({@code CubeRequestC2S}) this player may make. A full view at the largest view distance
     * (disc of 12 cubes radius x the ellipsoid and band, ~5k cubes) fits in one burst, so joining is never held
     * back; after that new cubes are asked for at {@link #CUBE_REQUESTS_PER_SECOND} - above what even creative
     * flight crosses into. Requests start no generation (the stream generates around the player's center);
     * this only bounds the server's per-player request bookkeeping and scan work.
     */
    static final int CUBE_REQUEST_BURST = 8192;
    static final double CUBE_REQUESTS_PER_SECOND = 1024.0;
    private final com.openmason.engine.net.pipeline.TokenBucket cubeRequests =
            new com.openmason.engine.net.pipeline.TokenBucket(CUBE_REQUEST_BURST, CUBE_REQUESTS_PER_SECOND);

    /** Debits one cube request; false = over budget, the client asks again later. */
    public boolean allowCubeRequest(long nowNanos) {
        return cubeRequests.tryTake(nowNanos);
    }

    /** Chat lines (and chat commands) this player may send: a burst of 8, then one a second. */
    static final double CHAT_BURST = 8;
    static final double CHAT_PER_SECOND = 1.0;
    private final com.openmason.engine.net.pipeline.TokenBucket chat =
            new com.openmason.engine.net.pipeline.TokenBucket(CHAT_BURST, CHAT_PER_SECOND);

    /** Debits one chat line; false = sending too fast, the line is not relayed. */
    public boolean allowChat(long nowNanos) {
        return chat.tryTake(nowNanos);
    }

    /** Held item id last broadcast for this player; the tick re-broadcasts only on change. */
    private int broadcastHeldItemId = Integer.MIN_VALUE;

    /**
     * The held item id to announce, or {@code Integer.MIN_VALUE} when every client already
     * has it — a client flipping its hotbar selection costs at most one broadcast per tick.
     */
    public int takeHeldItemChange() {
        int held = inventory.heldItemId();
        if (held == broadcastHeldItemId) {
            return Integer.MIN_VALUE;
        }
        broadcastHeldItemId = held;
        return held;
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
        miningHitDueNs = nowNs; // the client plays a hit as it starts; others hear it next tick
    }

    /** The block this player is mining, or null when none (finished, or never started). */
    public com.openmason.engine.util.BlockPos breakTarget() {
        return breakTarget;
    }

    /**
     * Whether the client last reported holding the attack on a block
     * ({@code PlayerStateFlags.MINING}) — the only way the server learns mining stopped short
     * of a finish (issue #344).
     */
    public boolean isMining() {
        return com.stonebreak.network.packet.player.PlayerStateFlags.has(
            movementFlags, com.stonebreak.network.packet.player.PlayerStateFlags.MINING);
    }

    /** When the next mining "hit" sound is due for other players (server clock). */
    private long miningHitDueNs;

    public long miningHitDueNs() {
        return miningHitDueNs;
    }

    public void setMiningHitDueNs(long dueNs) {
        this.miningHitDueNs = dueNs;
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

    /** Server-owned health and the rules that change it (#343); never taken from a client report. */
    private final com.stonebreak.network.server.PlayerVitals vitals =
            new com.stonebreak.network.server.PlayerVitals(20f, 20f);

    /** Mirrored prefs from the accepted PlayerPrefsC2S (cheats-gated server-side). */
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
        if (!respecWindowOpen) {
            spendFloor = SpendFloor.of(baseline); // accepted outside a window = committed
        }
        serverStats.restore(baseline.getSelectedClassId(), baseline.getClassInvestmentOrder(),
                baseline.getSpentAbilityCp(),
                baseline.getSkillLevels(), baseline.getAcquiredFeatIds(),
                baseline.getRemainingCp(), baseline.getRemainingSkillPoints(),
                baseline.getRemainingFeatPoints(), baseline.getAbilityScores(),
                baseline.getRemainingAp(), baseline.getLevel(), baseline.getXp());
    }

    /**
     * The committed spends no prefs report may refund (issue #421). Moves to the accepted
     * baseline whenever no respec window is open, and when one closes.
     */
    private SpendFloor spendFloor = SpendFloor.NONE;

    /** True while the client's character-panel session is open: spends accepted meanwhile
     *  stay refundable down to {@link #spendFloor} until the window closes. */
    private boolean respecWindowOpen = false;

    public SpendFloor spendFloor() { return spendFloor; }

    public boolean respecWindowOpen() { return respecWindowOpen; }

    /**
     * Opens the respec window (a provisional prefs report): the floor stays at the last
     * commit, so the session's spends can be undone. Idempotent while open.
     */
    public void openRespecWindow() {
        respecWindowOpen = true;
    }

    /**
     * Closes the respec window and commits everything accepted so far. Called for a closing
     * (non-provisional) report after it was judged, and for every combat intent: a client
     * that never closes its window still cannot refund spends it has fought with.
     */
    public void commitSpends() {
        respecWindowOpen = false;
        if (progressionBaseline != null) {
            spendFloor = SpendFloor.of(progressionBaseline);
        }
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
                .classInvestmentOrder(serverStats.getClassInvestmentOrder())
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

    public com.stonebreak.network.server.PlayerVitals vitals() { return vitals; }
    public float health() { return vitals.health(); }

    /** Sets the health outright (a save, a fresh character), against the server-computed maximum. */
    public void setHealth(float health) {
        vitals.applyMaxHealth(serverStats.computeMaxHealth());
        vitals.setHealth(health);
    }
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

    // ─── Claimed deaths (#420, #343) ─────────────────────────────────────────────
    private long lastClaimedDeathNs = Long.MIN_VALUE;

    /**
     * Whether a death the CLIENT claims ({@code PlayerLifeC2S} dead — giving up its life, as
     * a /kill would) may be honoured now: at most one per toss window. Every death scatters
     * the inventory, so a client toggling dead/alive must not dump it faster than
     * {@link #allowDrop} allows tosses. Deaths the server decides itself are not limited.
     */
    public boolean allowClaimedDeath() {
        long now = System.nanoTime();
        if (lastClaimedDeathNs != Long.MIN_VALUE && now - lastClaimedDeathNs <= DROP_WINDOW_NS) {
            return false;
        }
        lastClaimedDeathNs = now;
        return true;
    }

    /** True for the in-process (host/singleplayer) player on the in-JVM Local channel. Its state
     *  is persisted same-JVM, so it is excluded from the network player-data sync. */
    public boolean isLocal() {
        return connection.channel() instanceof io.netty.channel.local.LocalChannel;
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
