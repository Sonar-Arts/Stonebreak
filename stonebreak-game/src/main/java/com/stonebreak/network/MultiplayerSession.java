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

    private static volatile Mode mode = Mode.MENU;
    private static volatile IntegratedServer server;
    private static volatile ClientWorldView client;
    private static EntityManager.Listener entityListener;
    private static EntityManager listenerTarget;
    private static volatile boolean localPlayerRestored;

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
        final String username = Settings.getInstance().getMultiplayerUsername();
        final String localId = "sb-local-" + System.nanoTime();
        final NetAddress tcp = tcpPort > 0 ? NetAddress.tcpBind(tcpPort) : null;

        // Boot the authoritative world (blocking load/gen) + connect off the render thread.
        new Thread(() -> {
            try {
                IntegratedServer s = new IntegratedServer();
                s.start(NetAddress.local(localId), tcp, worldName, seed);
                server = s;

                ClientWorldView c = new ClientWorldView();
                c.connect(NetAddress.local(localId), username);
                client = c;
            } catch (Exception e) {
                System.err.println("[NET] Failed to start " + targetMode + ": " + e.getMessage());
                e.printStackTrace();
                shutdown();
            }
        }, targetMode + "-Start").start();
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
        detachEntityListener();
        if (client != null) {
            client.shutdown();
            client = null;
        }
        if (server != null) {
            ServerLevel level = server.worldContext().serverLevel();
            server.shutdown();
            if (level != null) {
                level.cleanup(); // flushes + closes the save service, then frees the world
            }
            server = null;
        }
        localPlayerRestored = false;
        mode = Mode.MENU;
    }

    // ─── Game-system hooks ──────────────────────────────────────────────────────

    /** Hook from {@code World.setBlockAt} for player-driven edits — routed via the local client. */
    public static void onLocalBlockChange(int x, int y, int z, BlockType type) {
        ClientWorldView c = client;
        if (c != null) {
            c.onLocalBlockChange(x, y, z, type);
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
        float rangeSq = range * range;
        for (ServerPlayer sp : s.worldContext().players()) {
            float dx = pos.x - sp.x();
            float dy = pos.y - sp.y();
            float dz = pos.z - sp.z();
            if (dx * dx + dy * dy + dz * dz <= rangeSq) {
                s.giveItemTo(sp.playerId(), itemId, count);
                return true;
            }
        }
        return false;
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

        IntegratedServer s = server;
        if (hasIntegratedServer() && s != null) {
            // Attach the spawn/despawn listener to the SERVER's entity manager so server-side
            // spawns (mobs, drops) replicate. Lazily, since it exists only after the level boots.
            if (entityListener == null) {
                EntityManager em = s.worldContext().entityManager();
                if (em != null) {
                    attachEntityListener(em);
                }
            }
            s.tick();
        }

        ClientWorldView c = client;
        if (c != null) {
            c.tick();
            restoreLocalPlayerIfReady(s);
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
     * Same-JVM player restoration for an integrated server: once the local client's player
     * exists, apply the server's loaded {@link com.stonebreak.world.save.model.PlayerData}
     * (inventory + position) to it and register it with the server save service so it persists.
     */
    private static void restoreLocalPlayerIfReady(IntegratedServer s) {
        if (localPlayerRestored || !hasIntegratedServer() || s == null) {
            return;
        }
        Player local = Game.getPlayer();
        ServerLevel level = s.worldContext().serverLevel();
        if (local == null || level == null) {
            return;
        }
        if (level.loadedPlayerData() != null) {
            StateConverter.applyPlayerData(local, level.loadedPlayerData());
            System.out.println("[NET] Restored saved player data into the local client.");
        } else {
            local.giveStartingItems();
            System.out.println("[NET] New world — gave the local player starting items.");
        }
        level.registerLocalPlayer(local);
        localPlayerRestored = true;
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
