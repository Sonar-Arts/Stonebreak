package com.stonebreak.network;

import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.client.ClientWorldView;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.player.Player;
import com.stonebreak.world.save.util.StateConverter;
import org.joml.Vector3f;

/**
 * Lifecycle facade over the two-world networking stack. In every in-world mode the local
 * player is a <b>client</b>; singleplayer and host additionally run an in-process
 * {@link IntegratedServer} (the authoritative {@link ServerLevel} + persistence) that the local
 * client connects to over an in-JVM Local channel. A pure join runs the client only.
 *
 * <ul>
 *   <li>{@code MENU} — no session (main menu).</li>
 *   <li>{@code SINGLEPLAYER} — integrated server (Local listener) + local client.</li>
 *   <li>{@code HOST} — integrated server (Local + TCP) + local client.</li>
 *   <li>{@code JOIN} — client only, connected to a remote host over TCP.</li>
 * </ul>
 *
 * <p>Game-system hooks (block edits, chat, drop pickup) are routed to the right side; the
 * heavy lifting lives in the per-domain server/client handlers.
 */
public final class MultiplayerSession {

    public enum Mode { MENU, SINGLEPLAYER, HOST, JOIN }

    /** How often the dedicated server thread pumps {@code server.tick()} (inbound drain runs at
     *  this rate; the authoritative sim runs at 20 Hz via the tick's internal accumulator). */
    private static final long SERVER_POLL_MS = 5;
    /** Longer than the host's own player-load timeout, so a slow read is never cut short here. */
    private static final long RESTORE_WAIT_MS =
            (com.stonebreak.network.server.IntegratedServer.PLAYER_LOAD_TIMEOUT_SECONDS + 15) * 1000L;
    /** How long a stop waits for the server's tick in progress to finish before interrupting. */
    private static final long SERVER_STOP_WAIT_MS = 15_000;
    /** Between the two shutdown drains: lets a packet sent just before the stop arrive. */
    private static final long SHUTDOWN_DRAIN_GRACE_MS = 20;

    private static volatile Mode mode = Mode.MENU;
    private static volatile IntegratedServer server;
    private static volatile ClientWorldView client;
    private static EntityManager.Listener entityListener;
    private static EntityManager listenerTarget;
    private static volatile boolean localPlayerRestored;

    // Dedicated server-tick thread (singleplayer/host). The integrated server runs OFF the
    // render thread so world sim / terrain + feature generation / chunk encode don't hitch the
    // frame. It only touches server-owned state (its own world, EntityManager, ServerPlayers);
    // the sole cross-thread reads are player positions (benign).
    private static volatile Thread serverThread;
    private static volatile boolean serverRunning;

    private MultiplayerSession() {}

    // ─── Mode queries ──────────────────────────────────────────────────────────

    public static Mode getMode() { return mode; }

    private static final java.util.List<java.util.function.Consumer<Mode>> modeListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Notified (on the thread that changed it) after every session mode change: start, join,
     * leave or disconnect. UI data sources mirror the session from here instead of polling it (#289).
     */
    public static void addModeListener(java.util.function.Consumer<Mode> listener) {
        modeListeners.add(listener);
    }

    private static void setMode(Mode next) {
        Mode previous = mode;
        mode = next;
        if (previous != next) {
            for (java.util.function.Consumer<Mode> l : modeListeners) {
                try {
                    l.accept(next);
                } catch (RuntimeException e) {
                    System.err.println("[NET] session mode listener failed: " + e);
                }
            }
        }
    }
    /** In a world (any mode but MENU). The local player is a client whenever this is true. */
    public static boolean isInWorld() { return mode != Mode.MENU; }
    /** A real network is involved (host or remote join) — not pure singleplayer. */
    public static boolean isOnline() { return mode == Mode.HOST || mode == Mode.JOIN; }
    public static boolean isHosting() { return mode == Mode.HOST; }
    /** The local player is a client in every in-world mode (two-world model). */
    public static boolean isClient() { return mode != Mode.MENU && !com.stonebreak.battletest.BattleTestSession.isActive(); }
    /** True when an authoritative server runs in this process (singleplayer or host). */
    public static boolean hasIntegratedServer() { return mode == Mode.SINGLEPLAYER || mode == Mode.HOST; }
    public static IntegratedServer getServer() { return server; }
    public static ClientWorldView getClient() {
        return com.stonebreak.battletest.BattleTestSession.isActive() ? null : client;
    }

    /** True when the session is in the given mode. */
    public static boolean isInMode(Mode m) { return mode == m; }

    /**
     * Submit character creation data to the server (late-join flow).
     * No-op if not in JOIN mode or the client is unavailable.
     */
    public static void submitCharacterCreation(byte[] json) {
        ClientWorldView c = getClient();
        if (c != null) {
            c.submitCharacterCreation(json);
        }
    }

    /**
     * Latest authoritative world-time sample buffered by the client, or null when no session /
     * no sample yet. Used by the client world bootstrap to seed the render clock at the
     * server's actual time-of-day instead of a NOON default.
     */
    public static Long pendingServerTimeTicks() {
        ClientWorldView c = getClient();
        return c != null ? c.pendingServerTimeTicks() : null;
    }

    /** Last server-measured RTT for the local client in ms, or -1 when unknown / no session. */
    public static int lastRttMs() {
        ClientWorldView c = getClient();
        return c != null ? c.lastRttMs() : -1;
    }

    /**
     * Server-tick-thread hook from {@code ProjectileDamage}: credit a projectile hit/kill
     * back to the player who launched it. No-op without an integrated server.
     */
    public static void sendKillCredit(int ownerPlayerId, com.stonebreak.mobs.entities.LivingEntity victim,
                                      float dealt, boolean killed) {
        IntegratedServer s = com.stonebreak.battletest.BattleTestSession.isActive() ? null : server;
        if (s != null) {
            s.sendKillCreditTo(ownerPlayerId, victim, dealt, killed);
        }
    }

    /** Ask the server to re-stream one chunk (client-side decode/apply failure). */
    public static void requestChunkResync(int cx, int cz) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.requestChunkResync(cx, cz);
        }
    }

    /** The client dropped chunk ({@code cx}, {@code cz}); tell the server so it re-streams on return (#363). */
    public static void notifyChunkUnloaded(int cx, int cz) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.notifyChunkUnloaded(cx, cz);
        }
    }

    /**
     * User-initiated full resync (pause-menu button): audit every resident chunk against the
     * server plus re-request the entity snapshot. Returns the number of chunks audited, or
     * -1 when there is no live session.
     */
    public static int requestFullResync() {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            return c.requestFullResync();
        }
        return -1;
    }

    /** Ask the server to re-send the full entity spawn snapshot. */
    public static void requestEntityResync() {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.requestEntityResync();
        }
    }

    /** Started mining a block (see {@code BlockBreakStartC2S}); no-op without a live session. */
    public static void sendBreakStart(int x, int y, int z) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.sendBreakStart(x, y, z);
        }
    }

    /** Snow-layer intent (the increment-on-existing-snow case with no block change). */
    public static void sendSnowLayer(int x, int y, int z, int layers) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.sendSnowLayer(x, y, z, layers);
        }
    }

    /**
     * One atomic slot action on an open container screen (furnace / crafting table / the
     * player's own inventory screen) — see {@code ContainerActionC2S} (issue #334). The
     * server replays the click against its own state and syncs back.
     */
    public static void sendContainerAction(int x, int y, int z, byte containerKind, byte region,
                                           byte slot, byte action) {
        sendContainerAction(x, y, z, containerKind, region, slot,
                ContainerActionC2S.Region.NONE, (byte) -1, action, (short) 0, false);
    }

    /** Full overload — carries the drag origin (swap-back) and count/shift for bulk actions. */
    public static void sendContainerAction(int x, int y, int z, byte containerKind, byte region,
                                           byte slot, byte originRegion, byte originSlot,
                                           byte action, short count, boolean shift) {
        sendContainerAction(new ContainerActionC2S(x, y, z, containerKind, region, slot,
                originRegion, originSlot, action, count, shift));
    }

    /** Sends a built container action; no-op without a live session. */
    public static void sendContainerAction(ContainerActionC2S action) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.sendContainerAction(action);
        }
    }

    /** Hotbar selection intent (see {@code HotbarSelectC2S}); no-op without a live session. */
    public static void sendHotbarSelect(int slot) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.sendHotbarSelect(slot);
        }
    }

    /**
     * Death scatter as ONE request (see {@code DeathDropC2S}): the server spawns the
     * dying player's drops from its own replica — no toss rate limit — and syncs the
     * mirror. Returns false when there is no live client connection (caller falls back to
     * the local scatter).
     */
    public static boolean sendDeathDrop() {
        ClientWorldView c = getClient();
        if (c == null || c.isDisconnected()) {
            return false;
        }
        c.sendDeathDrop();
        return true;
    }

    /**
     * The local player ate one {@code itemId} from the selected hotbar slot (issue #355).
     * Numbered like a slot action, so a sync built before the server applied it cannot roll
     * back the local prediction. No-op without a live session.
     */
    public static void sendConsumeHeld(int itemId) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.sendConsumeHeld(new com.stonebreak.network.packet.player.ConsumeHeldC2S(
                    itemId, SlotIntentSeq.nextAnswered()));
        }
    }

    /**
     * Death ({@code dead}) or respawn edge of the local player at {@code pos}, on the reliable
     * channel: the server's only source for which players are dead (drop pickup, mob spawning
     * — #357). No-op without a live session.
     */
    public static void sendPlayerLife(boolean dead, Vector3f pos) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            c.sendPlayerLife(new com.stonebreak.network.packet.player.PlayerLifeC2S(dead, pos.x, pos.y, pos.z));
        }
    }

    /**
     * Toggleable-block interaction intent (door open/close — see {@code BlockToggleC2S}).
     * The server flips the authoritative state and echoes {@code BlockStateS2C} to all
     * clients. Returns false when there is no live connection (caller flips locally).
     */
    public static boolean sendBlockToggle(int x, int y, int z) {
        ClientWorldView c = getClient();
        if (c != null && !c.isDisconnected()) {
            return c.sendBlockToggle(x, y, z);
        }
        return false;
    }

    /**
     * Projectile / ability-entity launch intent — the SERVER spawns + simulates the
     * authoritative entity and replicates it to everyone (originator included). Returns
     * false when there is no live client connection (caller falls back to a local spawn).
     */
    public static boolean sendProjectileSpawn(byte kind, Vector3f pos, Vector3f v, float... params) {
        ClientWorldView c = getClient();
        if (c == null || c.isDisconnected()) {
            return false;
        }
        c.sendProjectileSpawn(kind, pos, v, params);
        return true;
    }

    /**
     * Toss/return an item stack as a world drop via the server (authoritative, replicated).
     * Returns false when there is no live client connection (caller may fall back locally).
     */
    public static boolean sendDropItem(int itemId, int count) {
        ClientWorldView c = getClient();
        if (c == null || c.isDisconnected()) {
            return false;
        }
        c.sendDropItem(itemId, count);
        return true;
    }

    /**
     * Route a /timeset to the authoritative server clock. Returns false when this client
     * has no authority to set time (a remote JOIN client — the server would ignore it),
     * so the command can tell the user instead of silently being snapped back.
     */
    public static boolean requestServerTimeSet(long ticks) {
        if (mode == Mode.JOIN) {
            return false; // host-only: the server rejects non-local time sets
        }
        ClientWorldView c = getClient();
        if (c == null || c.isDisconnected()) {
            return mode == Mode.MENU; // no session at all — the local clock is the only clock
        }
        c.sendTimeSet(ticks);
        return true;
    }

    /**
     * Route a /cheats toggle to the server, which owns the per-world flag and persists it with
     * the world (issue #318). Returns false when this client has no authority to change it (a
     * remote JOIN client — the server would refuse), mirroring {@link #requestServerTimeSet}.
     */
    public static boolean requestServerCheatsSet(boolean enabled) {
        if (mode == Mode.JOIN) {
            return false; // host-only: the server rejects non-local cheat toggles
        }
        ClientWorldView c = getClient();
        if (c == null || c.isDisconnected()) {
            return mode == Mode.MENU; // no session at all — the runtime flag is the only flag
        }
        c.sendCheatsSet(enabled);
        return true;
    }

    /**
     * True once the local player's saved data has been restored (or there's nothing to restore).
     * The world bootstrap waits on this before entering PLAY so the player never appears with a
     * momentarily-empty inventory while the restore is still in flight.
     * <ul>
     *   <li>MENU: nothing to do.</li>
     *   <li>SINGLEPLAYER/HOST: the same-JVM restore has run ({@link #localPlayerRestored}).</li>
     *   <li>JOIN: the client has applied the server's PlayerData.</li>
     * </ul>
     */
    public static boolean isLocalPlayerDataReady() {
        if (mode == Mode.MENU) {
            return true;
        }
        if (hasIntegratedServer()) {
            return localPlayerRestored;
        }
        ClientWorldView c = getClient();
        return c != null && c.isRestoreApplied();
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    /** Singleplayer: integrated server (Local only) + in-process client. */
    public static synchronized void startSingleplayer(String worldName, long seed) {
        startWithServer(worldName, seed, Mode.SINGLEPLAYER, -1);
        System.out.println("[NET] Singleplayer starting for world '" + worldName + "'");
    }

    /** Host: integrated server (Local + TCP) + in-process client. */
    public static synchronized void startHosting(String worldName, long seed, int port) {
        startWithServer(worldName, seed, Mode.HOST, port);
        System.out.println("[NET] Hosting world '" + worldName + "' on TCP port " + port);
    }

    private static void startWithServer(String worldName, long seed, Mode targetMode, int tcpPort) {
        if (mode != Mode.MENU) {
            shutdown();
        }
        localPlayerRestored = false;
        setMode(targetMode);
        serverRunning = true;
        final String username = Settings.getInstance().getMultiplayerUsername();
        final String localId = "sb-local-" + System.nanoTime();
        final NetAddress tcp = tcpPort > 0 ? NetAddress.tcpBind(tcpPort) : null;

        // Up from the start of the boot, not just from WelcomeS2C: service startup, spawn search
        // and spawn-area pre-gen all happen before the Welcome and are most of a diffusion load.
        com.stonebreak.ui.LoadingScreen loadingScreen = Game.getInstance().getLoadingScreen();
        if (loadingScreen != null) {
            loadingScreen.show();
        }

        // One dedicated thread: boot the authoritative world (blocking load/gen), connect the
        // in-process client, then run the server tick loop off the render thread.
        Thread t = new Thread(() -> {
            IntegratedServer s = null;
            try {
                s = new IntegratedServer();
                s.start(NetAddress.local(localId), tcp, worldName, seed);
                // If a shutdown raced our boot (quick start→quit), tear our own server down
                // here rather than publishing it — otherwise a stale server keeps auto-saving.
                if (!serverRunning) {
                    teardownServer(s);
                    return;
                }
                server = s;
                // Attach the spawn/despawn listener so server-side spawns (mobs, drops) replicate.
                attachEntityListener(s.worldContext().entityManager());

                ClientWorldView c = new ClientWorldView();
                c.connect(NetAddress.local(localId), username);
                client = c;

                // Authoritative tick loop (sim @ 20 Hz via the tick's accumulator).
                while (serverRunning) {
                    try {
                        s.tick();
                    } catch (Throwable th) {
                        System.err.println("[SERVER-THREAD] Tick error: " + th);
                        th.printStackTrace();
                    }
                    Thread.sleep(SERVER_POLL_MS);
                }
                // Stop requested: apply what the client sent last (shutdown() pushed a final
                // prefs report) before the host's record is saved from this server's state.
                s.drainInboundForShutdown();
                Thread.sleep(SHUTDOWN_DRAIN_GRACE_MS); // local-channel delivery is one event-loop hop
                s.drainInboundForShutdown();
                s.stopTicking();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // shutdown requested
            } catch (Exception e) {
                System.err.println("[NET] Failed to start " + targetMode + ": " + e.getMessage());
                e.printStackTrace();
                serverRunning = false;
                if (server == null && s != null) {
                    teardownServer(s); // never published — clean up our own instance
                }
                // Leave the session for real (it stayed in HOST/SINGLEPLAYER mode with no client,
                // issue #338) and don't strand the player on the loading screen. Only if no newer
                // session replaced this one meanwhile.
                Game game = Game.getInstance();
                Thread self = Thread.currentThread();
                game.runOnMainThread(() -> {
                    if (serverThread == self) {
                        shutdown();
                    }
                    if (game.getState() == GameState.LOADING) {
                        game.setState(GameState.MAIN_MENU);
                    }
                });
            }
        }, targetMode + "-Server");
        t.setDaemon(true);
        serverThread = t;
        t.start();
    }

    /**
     * Whether the singleplayer pause menu freezes the world ("Pause World" setting). Main thread,
     * every frame; the integrated server reads it on its tick. Ignored unless SINGLEPLAYER.
     */
    public static void setWorldPausedByMenu(boolean paused) {
        IntegratedServer s = server;
        if (s != null) {
            s.setPausedByHost(paused && mode == Mode.SINGLEPLAYER);
        }
    }

    /** Bumped by every join attempt and shutdown: a connect that finishes late sees it moved on. */
    private static volatile long joinGeneration;

    /**
     * Join a remote host: client only (the host owns the authoritative world + persistence).
     * Connects on a background thread — an unreachable host used to freeze the window for
     * Netty's 30 s default while holding the session lock every frame's tick takes. The session
     * goes live on the main thread once connected; {@code onFailure} gets the reason otherwise
     * (main thread). Main thread.
     */
    public static synchronized void joinServerAsync(String host, int port, String username,
                                                    java.util.function.Consumer<String> onFailure) {
        if (mode != Mode.MENU) {
            shutdown();
        }
        localPlayerRestored = false;
        long generation = ++joinGeneration;
        Game game = Game.getInstance();
        Thread t = new Thread(() -> {
            ClientWorldView c = new ClientWorldView();
            try {
                c.connect(NetAddress.tcp(host, port), username);
            } catch (Exception e) {
                c.shutdown();
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                game.runOnMainThread(() -> {
                    if (generation == joinGeneration) {
                        onFailure.accept(reason);
                    }
                });
                return;
            }
            game.runOnMainThread(() -> {
                synchronized (MultiplayerSession.class) {
                    if (generation != joinGeneration || mode != Mode.MENU) {
                        c.shutdown(); // cancelled (left the screen, started another session)
                        return;
                    }
                    client = c;
                    setMode(Mode.JOIN);
                }
                System.out.println("[NET] Joining " + host + ":" + port);
            });
        }, "Server-Connect");
        t.setDaemon(true);
        t.start();
    }

    /** Abandons a join still connecting (its late success is closed instead of entered). */
    public static void cancelPendingJoin() {
        joinGeneration++;
    }

    public static synchronized void shutdown() {
        joinGeneration++; // a connect still in flight must not revive the session after this
        com.stonebreak.battletest.BattleTestSession.leave();
        // Abort any in-flight client-world build FIRST: a stale "ClientWorld-Build" thread
        // finishing after this teardown would flip the game back to PLAYING with no session
        // (and race the next session's build) — the disconnect/reconnect crash chain.
        Game game = Game.getInstance();
        if (game != null) {
            game.cancelClientWorldBuild();
        }

        // The host's player.json is written from the server's record of it, which takes the
        // client-owned parts (progression, stats, discoveries) from its last prefs report:
        // send one now so nothing changed in the last few seconds is lost.
        if (client != null && hasIntegratedServer() && localPlayerRestored) {
            client.flushPlayerPrefs();
        }

        // Stop the server tick thread before tearing down its world/connections. Not an
        // interrupt first: the loop exits on the flag and drains its last packets (above);
        // interrupting only if it does not stop in time.
        serverRunning = false;
        Thread t = serverThread;
        if (t != null && t != Thread.currentThread()) {
            // Wait for the tick in progress to finish (a heavy generation tick can take a while):
            // tearing the world down under a running tick was issue #338. Interrupt only a
            // thread that is truly stuck.
            try {
                t.join(SERVER_STOP_WAIT_MS);
                if (t.isAlive()) {
                    System.err.println("[NET] Server thread still busy after "
                            + SERVER_STOP_WAIT_MS + " ms; interrupting it.");
                    t.interrupt();
                    t.join(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        serverThread = null;

        detachEntityListener();
        if (client != null) {
            client.shutdown();
            client = null;
        }
        if (server != null) {
            teardownServer(server); // close() flushes the (now-registered) player + chunks
            server = null;
        }
        localPlayerRestored = false;
        setMode(Mode.MENU);
    }

    static {
        // A kill or crash that still runs shutdown hooks (SIGTERM, System.exit elsewhere) used
        // to lose everything since the last autosave: the server thread is a daemon (#337).
        Runtime.getRuntime().addShutdownHook(new Thread(MultiplayerSession::flushOnJvmExit,
                "Session-Exit-Flush"));
    }

    /**
     * JVM exit with a server still up: stop its tick and flush its saves (players + world).
     * Deliberately not {@link #shutdown()} — no session lock, no client or GL teardown, which
     * a hook must not wait on.
     */
    private static void flushOnJvmExit() {
        IntegratedServer s = server;
        if (s == null) {
            return;
        }
        System.out.println("[NET] JVM exiting with a live world — flushing its save.");
        serverRunning = false;
        Thread t = serverThread;
        if (t != null) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            ServerLevel level = s.worldContext().serverLevel();
            s.shutdown();
            if (level != null && level.saveService() != null) {
                level.saveService().close();
            }
        } catch (RuntimeException e) {
            System.err.println("[NET] Exit flush failed: " + e);
        }
    }

    /** Shut down a server instance and its level (flush + close the save service, free the world). */
    private static void teardownServer(IntegratedServer s) {
        if (s == null) {
            return;
        }
        ServerLevel level = s.worldContext().serverLevel();
        s.shutdown();
        if (level != null) {
            level.cleanup();
        }
    }

    // ─── Game-system hooks ──────────────────────────────────────────────────────

    /**
     * Hook from {@code World.setBlockAt} for player-driven edits — routed via the local client.
     * {@code prevType} is the block the client just overwrote; the server uses it as the
     * authoritative source of "what the player broke" for drop spawning (its own snapshot may
     * lag behind under load).
     */
    public static void onLocalBlockChange(int x, int y, int z, BlockType type, BlockType prevType) {
        onLocalBlockChange(x, y, z, type, prevType, null);
    }

    /** Same, carrying an optional client-proposed placement state (torches). */
    public static void onLocalBlockChange(int x, int y, int z, BlockType type, BlockType prevType,
                                          String placementState) {
        ClientWorldView c = getClient();
        if (c != null) {
            c.onLocalBlockChange(x, y, z, type, prevType, placementState);
        }
    }

    /**
     * Hook from {@code LivingEntity.damage} when the local player hits a network-shadow
     * entity — forwards the damage intent to the authoritative server via the local client.
     */
    /** Local ability knockback on a replicated entity: forward it to the authoritative server. */
    public static void onLocalEntityKnockback(Entity target, org.joml.Vector3f direction,
                                              float horizontal, float vertical) {
        ClientWorldView c = getClient();
        if (c != null && target != null && target.getNetworkId() >= 0 && direction != null) {
            c.sendEntityKnockback(target.getNetworkId(), direction.x, direction.z, horizontal, vertical);
        }
    }

    public static void onLocalEntityDamage(Entity target, float amount,
                                           com.stonebreak.mobs.entities.LivingEntity.DamageSource source) {
        ClientWorldView c = getClient();
        if (c != null && target != null && target.getNetworkId() >= 0) {
            c.sendEntityDamage(target.getNetworkId(), amount, source);
        }
    }

    /**
     * Local shear on a replicated sheep: forward the shear intent to the
     * authoritative server (see {@code SheepShearing.tryShear} — the caller
     * already applied the predicted visual swap to its shadow).
     */
    public static void onLocalEntityShear(Entity target) {
        ClientWorldView c = getClient();
        if (c != null && target != null && target.getNetworkId() >= 0) {
            c.sendEntityShear(target.getNetworkId());
        }
    }

    /** Hook from the chat UI for a locally-submitted message — routed via the local client. */
    public static void submitChat(String text) {
        ClientWorldView c = getClient();
        if (c != null) {
            c.submitChat(text);
        }
    }

    /** Server-side: hand a connected client an item stack (drop pickup, command-give). */
    public static void giveItemTo(int playerId, int itemId, int count) {
        IntegratedServer s = com.stonebreak.battletest.BattleTestSession.isActive() ? null : server;
        if (s != null) {
            s.giveItemTo(playerId, itemId, count);
        }
    }

    /**
     * Server-authoritative drop pickup. Called from a drop entity on the server tick: the
     * nearest player within {@code range} with room for the item gets as much of the stack as
     * fits (over the wire; the local client adds it on receipt). Returns how many were taken
     * — the drop keeps the rest where it lies (#372; re-dropping the overflow used to throw it
     * ahead of a full-inventory player on every step). 0 when there is no server or no one in
     * range has room.
     */
    public static int tryServerPickup(Vector3f pos, float range, int itemId, int count) {
        IntegratedServer s = com.stonebreak.battletest.BattleTestSession.isActive() ? null : server;
        if (s == null || count <= 0) {
            return 0;
        }
        ServerPlayer nearest = nearestEligiblePickupCandidate(s.worldContext().players(), pos, range, itemId);
        if (nearest == null) {
            return 0;
        }
        int taken = nearest.inventory().roomFor(itemId, count);
        s.giveItemTo(nearest.playerId(), itemId, taken);
        return taken;
    }

    /**
     * Nearest-player-wins pickup selection: when several players stand in range, the closest
     * one gets the stack (roster iteration order is arbitrary and previously decided ties
     * unfairly). Only {@link ServerPlayer#isLiveInWorld() live} players with room for at least
     * one {@code itemId} qualify: one that has not reported a position yet would win at the
     * {@code (0,0,0)} default, a dead one would collect its own death drops from the death
     * screen (#357) — the drop stays in the world for other players or the post-respawn
     * pickup — and a full one would take the drop away from a player who can hold it (#372).
     */
    static ServerPlayer nearestEligiblePickupCandidate(Iterable<ServerPlayer> players,
                                                       Vector3f pos, float range, int itemId) {
        float rangeSq = range * range;
        ServerPlayer nearest = null;
        float nearestSq = Float.MAX_VALUE;
        for (ServerPlayer sp : players) {
            if (!sp.isLiveInWorld()) {
                continue;
            }
            float dx = pos.x - sp.x();
            float dy = pos.y - sp.y();
            float dz = pos.z - sp.z();
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= rangeSq && distSq < nearestSq && sp.inventory().roomFor(itemId, 1) > 0) {
                nearest = sp;
                nearestSq = distSq;
            }
        }
        return nearest;
    }

    // ─── Per-tick pump (called from the game loop) ───────────────────────────────

    public static void tick() {
        synchronized (MultiplayerSession.class) {
            tickLocked();
        }
    }

    private static void tickLocked() {
        if (com.stonebreak.battletest.BattleTestSession.isActive()) {
            if (client != null) client.suspendClock();
            return;
        }
        if (mode == Mode.MENU) {
            return;
        }

        // The integrated server ticks on its own thread (see startWithServer). The main thread
        // only pumps the CLIENT here — applying inbound packets to the render world + sending
        // local intents — so rendering never waits on server-side world generation.
        ClientWorldView c = getClient();
        if (c != null) {
            c.tick();
            if (c.isDisconnected()) {
                String reason = c.kickReason();
                System.out.println("[NET] Disconnected"
                        + (reason != null ? ": " + reason : "") + "; returning to menu.");
                com.stonebreak.ui.runtime.GameUiInput.get().cancelAll(
                        com.openmason.engine.ui.runtime.input.CancelReason.DISCONNECT);
                shutdown();
                Game.getInstance().setState(GameState.MAIN_MENU);
            }
        }
    }

    /**
     * Same-JVM player restoration for an integrated server. Called from the world bootstrap
     * (on the build thread) with the EXACT player just created — never via {@code Game.getPlayer()}
     * from another thread, which on a re-open could still be the previous session's stale player
     * (that bug applied the saved inventory to a dead object, leaving the live player empty).
     * Applies the server's loaded {@link com.stonebreak.world.save.model.PlayerData} (or starting
     * items for a fresh world) and registers the player with the save service so it persists.
     *
     * <p>For JOIN it waits (bounded) for the host's {@code PlayerDataS2C} and applies it to the
     * same player. Intentionally NOT synchronized — the JOIN wait must not hold the session lock,
     * or it would block the main-thread tick that receives the blob (deadlock).
     */
    public static void restoreLocalPlayer(Player player) {
        if (player == null) {
            return;
        }
        if (hasIntegratedServer()) {
            IntegratedServer s = com.stonebreak.battletest.BattleTestSession.isActive() ? null : server;
            ServerLevel level = (s != null) ? s.worldContext().serverLevel() : null;
            if (level == null) {
                return;
            }
            if (level.loadedPlayerData() != null) {
                StateConverter.applyPlayerData(player, level.loadedPlayerData());
                System.out.println("[NET] Restored saved player data into the local client.");
            } else {
                player.giveStartingItems();
                System.out.println("[NET] New world — gave the local player starting items.");
            }
            String notice = level.playerLoadNotice();
            if (notice != null) {
                Game game = Game.getInstance();
                game.runOnMainThread(() -> {
                    var chat = game.getChatSystem();
                    if (chat != null) {
                        chat.addMessage(notice, new float[]{1.0f, 0.35f, 0.35f, 1.0f});
                    }
                });
            }
            // Not bound to the save service: the host is persisted from the server's own record
            // of it (IntegratedServer binds that once the local player is seeded).
            localPlayerRestored = true;
            return;
        }
        if (mode == Mode.JOIN) {
            ClientWorldView c = getClient();
            if (c == null) {
                return;
            }
            // Wait for the host to send our saved data, then apply it to THIS player. The host
            // reads it off its tick and may take a while (it refuses the join rather than
            // guessing); giving up after a few seconds used to hand a returning player the
            // starter kit (issue #337) — so wait as long as the host may, then leave.
            long deadline = System.currentTimeMillis() + RESTORE_WAIT_MS;
            while (!c.isRestoreReceived() && !c.isDisconnected()
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!c.isRestoreReceived()) {
                c.failRestore("The host did not send your player data in time.");
                return;
            }
            c.applyRestoreTo(player);
        }
    }

    // ─── Entity spawn/despawn replication listener ──────────────────────────────

    private static void attachEntityListener(EntityManager em) {
        if (entityListener != null && listenerTarget != null) {
            listenerTarget.removeListener(entityListener);
        }
        listenerTarget = em;
        // Unconditional delegation: the listener is bound to the saved world's EntityManager and
        // the battletest arena has its own — an isActive() check here would drop a spawn/despawn
        // fired by a server tick straddling the /battletest enter/leave flag flip (issue #375).
        entityListener = new EntityReplicationListener(() -> server);
        em.addListener(entityListener);
    }

    private static void detachEntityListener() {
        if (listenerTarget != null && entityListener != null) {
            listenerTarget.removeListener(entityListener);
        }
        entityListener = null;
        listenerTarget = null;
    }
}
