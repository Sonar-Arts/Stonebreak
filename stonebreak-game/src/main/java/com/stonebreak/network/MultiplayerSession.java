package com.stonebreak.network;

import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.client.ClientWorldView;
import com.stonebreak.network.server.IntegratedServer;

/**
 * Thin lifecycle facade over the world-authoritative networking stack. Owns the
 * {@link IntegratedServer} (HOST) or the {@link ClientWorldView} (JOIN), drives their tick,
 * and routes game-system hooks (block edits, chat, entity spawn/despawn, item gifts) to the
 * active side.
 *
 * <p>Modes: {@code OFFLINE} is singleplayer — it runs no networking and every hook is a
 * no-op (the same behavior as before this rewrite). True singleplayer-through-an-embedded-
 * server is a later phase; for now SP stays on its direct, non-networked path.
 *
 * <p>All ongoing replication lives in the server/client handlers; this class only handles
 * start/stop, the per-tick pump, and forwarding.
 */
public final class MultiplayerSession {

    public enum Mode { OFFLINE, HOST, JOIN }

    private static volatile Mode mode = Mode.OFFLINE;
    private static volatile IntegratedServer server;
    private static volatile ClientWorldView client;
    private static EntityManager.Listener entityListener;

    private MultiplayerSession() {}

    // ─── Mode queries ──────────────────────────────────────────────────────────

    public static Mode getMode() { return mode; }
    public static boolean isOnline() { return mode != Mode.OFFLINE; }
    public static boolean isHosting() { return mode == Mode.HOST; }
    public static boolean isClient() { return mode == Mode.JOIN; }
    public static IntegratedServer getServer() { return server; }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    public static synchronized void startHosting(int port) throws InterruptedException {
        if (mode != Mode.OFFLINE) {
            shutdown();
        }
        IntegratedServer s = new IntegratedServer();
        s.start(null, NetAddress.tcpBind(port)); // TCP for remotes; host plays directly (id 0)
        server = s;
        mode = Mode.HOST;
        System.out.println("[NET] Hosting on TCP port " + port);
    }

    public static synchronized void joinServer(String host, int port, String username) throws InterruptedException {
        if (mode != Mode.OFFLINE) {
            shutdown();
        }
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
            server.shutdown();
            server = null;
        }
        mode = Mode.OFFLINE;
    }

    // ─── Game-system hooks ──────────────────────────────────────────────────────

    /** Hook from {@code World.setBlockAt} for player-driven edits. */
    public static void onLocalBlockChange(int x, int y, int z, BlockType type) {
        IntegratedServer s = server;
        ClientWorldView c = client;
        if (mode == Mode.HOST && s != null) {
            s.onLocalBlockChange(x, y, z, type);
        } else if (mode == Mode.JOIN && c != null) {
            c.onLocalBlockChange(x, y, z, type);
        }
        // OFFLINE (singleplayer): no-op — the edit is purely local.
    }

    /** Hook from the chat UI for a locally-submitted multiplayer message. */
    public static void submitChat(String text) {
        IntegratedServer s = server;
        ClientWorldView c = client;
        if (mode == Mode.HOST && s != null) {
            s.onHostChat(text);
        } else if (mode == Mode.JOIN && c != null) {
            c.submitChat(text);
        }
    }

    /** Host-side: hand a connected client an item stack (drop pickup, command-give). */
    public static void giveItemTo(int playerId, int itemId, int count) {
        IntegratedServer s = server;
        if (mode == Mode.HOST && s != null) {
            s.giveItemTo(playerId, itemId, count);
        }
    }

    // ─── Per-tick pump (called from the game loop) ───────────────────────────────

    public static void tick() {
        synchronized (MultiplayerSession.class) {
            tickLocked();
        }
    }

    private static void tickLocked() {
        if (mode == Mode.HOST && server != null) {
            // The EntityManager only exists after world load — attach the spawn/despawn
            // listener lazily and snapshot whatever already exists into the server.
            if (entityListener == null && Game.getEntityManager() != null) {
                attachEntityListener();
                for (Entity e : Game.getEntityManager().getAllEntities()) {
                    server.onEntitySpawned(e);
                }
            }
            server.tick();
        } else if (mode == Mode.JOIN && client != null) {
            client.tick();
            if (client.isDisconnected()) {
                String reason = client.kickReason();
                System.out.println("[NET] Disconnected"
                        + (reason != null ? ": " + reason : "") + "; returning to menu.");
                shutdown();
                Game.getInstance().setState(GameState.MAIN_MENU);
            }
        }
    }

    // ─── Entity spawn/despawn replication listener ──────────────────────────────

    private static void attachEntityListener() {
        EntityManager em = Game.getEntityManager();
        if (em == null) {
            return;
        }
        if (entityListener != null) {
            em.removeListener(entityListener);
        }
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
        EntityManager em = Game.getEntityManager();
        if (em != null && entityListener != null) {
            em.removeListener(entityListener);
        }
        entityListener = null;
    }
}
