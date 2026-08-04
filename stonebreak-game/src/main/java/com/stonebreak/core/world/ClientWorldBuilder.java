package com.stonebreak.core.world;

import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.save.SaveService;

/**
 * Builds the client RENDER world off the main thread and installs it when it is ready.
 *
 * <p>Under the two-world model this is a {@code World.createClientView} world: it generates no
 * terrain and carries no {@link SaveService}, because its chunks, entities and blocks all stream in
 * from the authoritative server. The build starts on {@code WelcomeS2C}, the loading screen stays up
 * until the spawn chunk has arrived, and hiding it transitions the game to PLAYING.</p>
 *
 * <h2>Why the generation stamp</h2>
 *
 * <p>A build runs on its own thread and can outlive the session that started it — a disconnect back
 * to the menu, or a second join superseding the first. Every {@link #start} and every
 * {@link #cancel} bumps {@link #generation}, and the build thread re-checks it before each
 * irreversible step. Without that, a stale build could flip the game back to PLAYING with no live
 * session, or race another build's world swap.</p>
 */
public final class ClientWorldBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ClientWorldBuilder.class);

    /** How long to wait for the spawn chunk and the local player's data before entering play. */
    private static final long SPAWN_WAIT_MILLIS = 10_000L;
    private static final long SPAWN_POLL_MILLIS = 50L;

    private final Game game;
    private final WorldLifecycle worldLifecycle;

    private final AtomicInteger generation = new AtomicInteger();

    /** Serializes the world swap between competing build threads. */
    private final Object swapLock = new Object();

    /**
     * True from {@link #start} until the build thread has swapped the new world in (world + entity
     * manager fully replaced). While pending, {@code Game.getWorld()}/{@code getEntityManager()} may
     * still point at the PREVIOUS session's instances — applying inbound network state against them
     * would orphan it, so client handlers gate on {@link #isReady()} and buffer until the swap lands.
     */
    private volatile boolean pending = false;

    public ClientWorldBuilder(Game game, WorldLifecycle worldLifecycle) {
        this.game = game;
        this.worldLifecycle = worldLifecycle;
    }

    /** Invalidates any in-flight build. Called from session shutdown. */
    public void cancel() {
        generation.incrementAndGet();
        pending = false;
    }

    /** True when inbound network state can safely be applied to the current world. */
    public boolean isReady() {
        return !pending && Game.getWorld() != null && Game.getEntityManager() != null;
    }

    public void start(String worldName, long seed, Vector3f spawn) {
        // The client never persists — drop any save service so the chunk store stays read-only.
        SaveService previous = game.getSaveService();
        if (previous != null) {
            try {
                previous.stopAutoSave();
                previous.close();
            } catch (Exception ignored) {
                // A failed close on a world we are abandoning must not block the join.
            }
            game.setSaveService(null);
        }
        game.setCurrentWorldName(worldName);
        game.setCurrentWorldSeed(seed);
        game.setCurrentWorldData(null);

        LoadingScreen loadingScreen = game.getLoadingScreen();
        if (loadingScreen != null) {
            loadingScreen.show();
        }

        int buildGeneration = generation.incrementAndGet();
        pending = true;
        new Thread(() -> build(buildGeneration, seed, spawn), "ClientWorld-Build").start();
    }

    /** True while {@code buildGeneration} is still the latest build AND the session is alive. */
    private boolean stillCurrent(int buildGeneration) {
        return buildGeneration == generation.get() && MultiplayerSession.isInWorld();
    }

    private void build(int buildGeneration, long seed, Vector3f spawn) {
        try {
            World renderWorld = worldLifecycle.createClientWorldInstance(seed);
            if (!installWorld(buildGeneration, renderWorld)) {
                return;
            }

            renderWorld.setSpawnPosition(spawn);
            Player player = Game.getPlayer();
            if (player != null) {
                player.setPosition(spawn);
            }

            // Restore the local player's saved data NOW, on this thread, applied to the player we
            // just created. Doing it here — rather than from the main-thread tick reading
            // Game.getPlayer() — avoids the re-open bug where the restore hit the previous session's
            // stale player. For SP/host this is synchronous; for JOIN it is a no-op (the data
            // arrives via PlayerDataS2C and is applied by the client view).
            MultiplayerSession.restoreLocalPlayer(player);
            adoptServerClock();

            waitForSpawnChunk(buildGeneration, renderWorld, spawn);

            // The session may have died (disconnect to menu) or been superseded while we waited:
            // entering PLAYING from this stale thread would resurrect gameplay with no session.
            if (!stillCurrent(buildGeneration)) {
                logger.info("[CLIENT-WORLD] Build superseded/cancelled before enter-play — aborting.");
                return;
            }

            enterPlay();
        } catch (Exception e) {
            handleBuildFailure(buildGeneration, e);
        }
    }

    /** @return false if the build was superseded and the freshly built world was discarded */
    private boolean installWorld(int buildGeneration, World renderWorld) {
        synchronized (swapLock) {
            if (!stillCurrent(buildGeneration)) {
                logger.info("[CLIENT-WORLD] Build superseded/cancelled before install — aborting.");
                try {
                    renderWorld.cleanup();
                } catch (Exception ignored) {
                    // Discarding a world we never installed; a failed cleanup changes nothing.
                }
                return false;
            }
            worldLifecycle.replaceWorldInstance(renderWorld); // fresh player + world components
            pending = false; // world + entity manager are now the new session's
            return true;
        }
    }

    /**
     * The client clock is server-authoritative. Seed it from the TimeSyncS2C the server sends right
     * after WelcomeS2C when that already arrived (buffered by the client view); otherwise start at
     * NOON and let the first periodic sync snap it. Always replace — a leftover clock from a
     * previous session belongs to a different world.
     */
    private void adoptServerClock() {
        Long serverTicks = MultiplayerSession.pendingServerTimeTicks();
        game.setTimeOfDay(new TimeOfDay(serverTicks != null ? serverTicks : TimeOfDay.NOON));
    }

    /**
     * Waits (bounded) for two things, so the player neither falls into the void nor appears with an
     * empty inventory:
     * <ol>
     *   <li>the spawn chunk has STREAMED IN — not meshed; meshing happens in PLAYING and the player
     *       physics guard holds the player until it renders;</li>
     *   <li>the local player's saved data has been RESTORED.</li>
     * </ol>
     * The client tick that installs chunks runs during LOADING because the game loop pumps the
     * network before routing state updates.
     */
    private void waitForSpawnChunk(int buildGeneration, World renderWorld, Vector3f spawn)
            throws InterruptedException {
        int spawnChunkX = (int) Math.floor(spawn.x / 16.0);
        int spawnChunkZ = (int) Math.floor(spawn.z / 16.0);
        long deadline = System.currentTimeMillis() + SPAWN_WAIT_MILLIS;

        while (System.currentTimeMillis() < deadline
                && stillCurrent(buildGeneration)
                && (renderWorld.getChunkIfLoaded(spawnChunkX, spawnChunkZ) == null
                    || !MultiplayerSession.isLocalPlayerDataReady())) {
            Thread.sleep(SPAWN_POLL_MILLIS);
        }
    }

    private void enterPlay() {
        LoadingScreen loadingScreen = game.getLoadingScreen();
        if (loadingScreen != null) {
            loadingScreen.hide(); // → GameState.PLAYING
        }
        if (game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().forceUpdate();
        }
    }

    private void handleBuildFailure(int buildGeneration, Exception e) {
        logger.error("[CLIENT-WORLD] Failed to build render world", e);
        if (!stillCurrent(buildGeneration)) {
            return;
        }
        // The render world is unusable and Game.getWorld() still points at the PREVIOUS session's
        // world — clearing the pending flag here would let the client handlers drain buffered
        // chunks/spawns into it. Tear the session down and return to the menu instead (mirrors the
        // disconnect path; the session shutdown also cancels this builder).
        game.runOnMainThread(() -> {
            MultiplayerSession.shutdown();
            game.setState(GameState.MAIN_MENU);
        });
    }
}
