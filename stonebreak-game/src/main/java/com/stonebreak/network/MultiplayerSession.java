package com.stonebreak.network;

import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.client.ClientWorldView;
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
    /** In a world (any mode but MENU). The local player is a client whenever this is true. */
    public static boolean isInWorld() { return mode != Mode.MENU; }
    /** A real network is involved (host or remote join) — not pure singleplayer. */
    public static boolean isOnline() { return mode == Mode.HOST || mode == Mode.JOIN; }
    public static boolean isHosting() { return mode == Mode.HOST; }
    /** The local player is a client in every in-world mode (two-world model). */
    public static boolean isClient() { return mode != Mode.MENU; }
    /** True when an authoritative server runs in this process (singleplayer or host). */
    public static boolean hasIntegratedServer() { return mode == Mode.SINGLEPLAYER || mode == Mode.HOST; }
    public static IntegratedServer getServer() { return server; }
    public static ClientWorldView getClient() { return client; }

    /** True when the session is in the given mode. */
    public static boolean isInMode(Mode m) { return mode == m; }

    /**
     * Submit character creation data to the server (late-join flow).
     * No-op if not in JOIN mode or the client is unavailable.
     */
    public static void submitCharacterCreation(byte[] json) {
        ClientWorldView c = client;
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
        ClientWorldView c = client;
        return c != null ? c.pendingServerTimeTicks() : null;
    }

    /** Last server-measured RTT for the local client in ms, or -1 when unknown / no session. */
    public static int lastRttMs() {
        ClientWorldView c = client;
        return c != null ? c.lastRttMs() : -1;
    }

    /**
     * Server-tick-thread hook from {@code ProjectileDamage}: credit a projectile hit/kill
     * back to the player who launched it. No-op without an integrated server.
     */
    public static void sendKillCredit(int ownerPlayerId, com.stonebreak.mobs.entities.LivingEntity victim,
                                      float dealt, boolean killed) {
        IntegratedServer s = server;
        if (s != null) {
            s.sendKillCreditTo(ownerPlayerId, victim, dealt, killed);
        }
    }

    /** Ask the server to re-stream one chunk (client-side decode/apply failure). */
    public static void requestChunkResync(int cx, int cz) {
        ClientWorldView c = client;
        if (c != null && !c.isDisconnected()) {
            c.requestChunkResync(cx, cz);
        }
    }

    /** Ask the server to re-send the full entity spawn snapshot. */
    public static void requestEntityResync() {
        ClientWorldView c = client;
        if (c != null && !c.isDisconnected()) {
            c.requestEntityResync();
        }
    }

    /** Snow-layer intent (the increment-on-existing-snow case with no block change). */
    public static void sendSnowLayer(int x, int y, int z, int layers) {
        ClientWorldView c = client;
        if (c != null && !c.isDisconnected()) {
            c.sendSnowLayer(x, y, z, layers);
        }
    }

    /** Furnace slot intent: the open furnace UI's slots changed (see {@code FurnaceSlotsC2S}). */
    public static void sendFurnaceSlots(int x, int y, int z, String slots) {
        ClientWorldView c = client;
        if (c != null && !c.isDisconnected()) {
            c.sendFurnaceSlots(x, y, z, slots);
        }
    }

    /**
     * Toggleable-block interaction intent (door open/close — see {@code BlockToggleC2S}).
     * The server flips the authoritative state and echoes {@code BlockStateS2C} to all
     * clients. Returns false when there is no live connection (caller flips locally).
     */
    public static boolean sendBlockToggle(int x, int y, int z) {
        ClientWorldView c = client;
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
        ClientWorldView c = client;
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
        ClientWorldView c = client;
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
        ClientWorldView c = client;
        if (c == null || c.isDisconnected()) {
            return mode == Mode.MENU; // no session at all — the local clock is the only clock
        }
        c.sendTimeSet(ticks);
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
        ClientWorldView c = client;
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
        mode = targetMode;
        serverRunning = true;
        final String username = Settings.getInstance().getMultiplayerUsername();
        final String localId = "sb-local-" + System.nanoTime();
        final NetAddress tcp = tcpPort > 0 ? NetAddress.tcpBind(tcpPort) : null;

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
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // shutdown requested
            } catch (Exception e) {
                System.err.println("[NET] Failed to start " + targetMode + ": " + e.getMessage());
                e.printStackTrace();
                serverRunning = false;
                if (server == null && s != null) {
                    teardownServer(s); // never published — clean up our own instance
                }
            }
        }, targetMode + "-Server");
        t.setDaemon(true);
        serverThread = t;
        t.start();
    }

    /** Join a remote host: client only (the host owns the authoritative world + persistence). */
    public static synchronized void joinServer(String host, int port, String username) throws InterruptedException {
        if (mode != Mode.MENU) {
            shutdown();
        }
        localPlayerRestored = false;
        ClientWorldView c = new ClientWorldView();
        c.connect(NetAddress.tcp(host, port), username);
        client = c;
        mode = Mode.JOIN;
        System.out.println("[NET] Joining " + host + ":" + port);
    }

    public static synchronized void shutdown() {
        // Abort any in-flight client-world build FIRST: a stale "ClientWorld-Build" thread
        // finishing after this teardown would flip the game back to PLAYING with no session
        // (and race the next session's build) — the disconnect/reconnect crash chain.
        Game game = Game.getInstance();
        if (game != null) {
            game.cancelClientWorldBuild();
        }

        // Stop the server tick thread before tearing down its world/connections.
        serverRunning = false;
        Thread t = serverThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }

        // Robustly capture the local player's CURRENT inventory before teardown. The server
        // thread is now stopped, so this can't race the sim. Re-registering points the save
        // service at the live player so the close-flush below writes its latest state — this is
        // independent of whether the restore/registration happened earlier in the session, which
        // is what previously let an exit drop the inventory.
        if (server != null && hasIntegratedServer()) {
            ServerLevel level = server.worldContext().serverLevel();
            Player local = Game.getPlayer();
            if (level != null && local != null) {
                level.registerLocalPlayer(local);
            }
        }

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
        mode = Mode.MENU;
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
        ClientWorldView c = client;
        if (c != null) {
            c.onLocalBlockChange(x, y, z, type, prevType);
        }
    }

    /**
     * Hook from {@code LivingEntity.damage} when the local player hits a network-shadow
     * entity — forwards the damage intent to the authoritative server via the local client.
     */
    public static void onLocalEntityDamage(Entity target, float amount,
                                           com.stonebreak.mobs.entities.LivingEntity.DamageSource source) {
        ClientWorldView c = client;
        if (c != null && target != null && target.getNetworkId() >= 0) {
            c.sendEntityDamage(target.getNetworkId(), amount, source);
        }
    }

    /** Hook from the chat UI for a locally-submitted message — routed via the local client. */
    public static void submitChat(String text) {
        ClientWorldView c = client;
        if (c != null) {
            c.submitChat(text);
        }
    }

    /** Server-side: hand a connected client an item stack (drop pickup, command-give). */
    public static void giveItemTo(int playerId, int itemId, int count) {
        IntegratedServer s = server;
        if (s != null) {
            s.giveItemTo(playerId, itemId, count);
        }
    }

    /**
     * Server-authoritative drop pickup. Called from a drop entity on the server tick: if any
     * connected player (local or remote) is within {@code range}, give them the stack (over the
     * wire; the local client adds it on receipt) and report the pickup so the drop despawns.
     * Returns false when there is no server or nobody is close enough.
     */
    public static boolean tryServerPickup(Vector3f pos, float range, int itemId, int count) {
        IntegratedServer s = server;
        if (s == null) {
            return false;
        }
        // Nearest-player-wins: when several players stand in range, the closest one gets the
        // stack (roster iteration order is arbitrary and previously decided ties unfairly).
        float rangeSq = range * range;
        ServerPlayer nearest = null;
        float nearestSq = Float.MAX_VALUE;
        for (ServerPlayer sp : s.worldContext().players()) {
            if (!sp.handshakeDone() || sp.lastStateNs() == 0L) {
                continue; // no reported position yet — (0,0,0) default must not win pickups
            }
            float dx = pos.x - sp.x();
            float dy = pos.y - sp.y();
            float dz = pos.z - sp.z();
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= rangeSq && distSq < nearestSq) {
                nearest = sp;
                nearestSq = distSq;
            }
        }
        if (nearest == null) {
            return false;
        }
        s.giveItemTo(nearest.playerId(), itemId, count);
        return true;
    }

    // ─── Per-tick pump (called from the game loop) ───────────────────────────────

    public static void tick() {
        synchronized (MultiplayerSession.class) {
            tickLocked();
        }
    }

    private static void tickLocked() {
        if (mode == Mode.MENU) {
            return;
        }

        // The integrated server ticks on its own thread (see startWithServer). The main thread
        // only pumps the CLIENT here — applying inbound packets to the render world + sending
        // local intents — so rendering never waits on server-side world generation.
        ClientWorldView c = client;
        if (c != null) {
            c.tick();
            if (c.isDisconnected()) {
                String reason = c.kickReason();
                System.out.println("[NET] Disconnected"
                        + (reason != null ? ": " + reason : "") + "; returning to menu.");
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
            IntegratedServer s = server;
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
            level.registerLocalPlayer(player);
            localPlayerRestored = true;
            return;
        }
        if (mode == Mode.JOIN) {
            ClientWorldView c = client;
            if (c == null) {
                return;
            }
            // Wait for the host to send our saved data, then apply it to THIS player.
            long deadline = System.currentTimeMillis() + 5000L;
            while (!c.isRestoreReceived() && !c.isDisconnected()
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
        entityListener = new EntityManager.Listener() {
            @Override public void onEntityAdded(Entity e) {
                IntegratedServer s = server;
                if (s != null) s.onEntitySpawned(e);
            }
            @Override public void onEntityRemoved(Entity e) {
                IntegratedServer s = server;
                if (s != null) s.onEntityDespawned(e);
            }
        };
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
