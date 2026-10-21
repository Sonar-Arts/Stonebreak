package com.stonebreak.core.lifecycle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.stonebreak.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates {@link Game} shutdown: tears down the network session (flushing the
 * integrated server's save service), cleans up per-instance subsystems, terminates
 * the world-update executor, and tears down static resources (MMS API).
 * Extracted from {@code Game.cleanup()} / {@code Game.cleanupStaticResources()}.
 */
public final class GameShutdown {

    private static final Logger logger = LoggerFactory.getLogger(GameShutdown.class);

    private GameShutdown() {
    }

    /**
     * Runs the full shutdown sequence.
     *
     * @param game                 the game instance whose subsystems should be torn down
     * @param worldUpdateExecutor  the executor owned by {@link Game} that must be stopped
     */
    public static void shutdown(Game game, ExecutorService worldUpdateExecutor) {
        // Release the battle's camera/FOV/screen bindings while the arena session still exists.
        com.stonebreak.battle.stage.FocusBattle.shutdown();
        com.stonebreak.battletest.BattleTestSession.leave();
        // Before the world and entity manager go away: the final save must include anything
        // left in the inventory's crafting grid (issue #307).
        com.stonebreak.core.world.WorldLifecycle.returnCraftingGridsToPlayer(game);
        // Flush the integrated server's SaveService (player.json + dirty chunks) and send a
        // join client's final PlayerDataC2S — the same teardown Quit to Menu runs
        // (GameStateController), so closing the window doesn't lose the last autosave window
        // of edits (issue #364). No-op-safe when no session is active.
        com.stonebreak.network.MultiplayerSession.shutdown();
        logger.debug("Starting Game cleanup...");

        if (Game.getWorld() != null) {
            Game.getWorld().cleanup();
        }
        if (game.getFocusBattleScreen() != null) {
            game.getFocusBattleScreen().cleanup();
        }
        if (Game.getSoundSystem() != null) {
            Game.getSoundSystem().cleanup();
        }
        if (game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().cleanup();
        }
        if (Game.getMemoryLeakDetector() != null) {
            Game.getMemoryLeakDetector().stopMonitoring();
        }
        if (Game.getEntityManager() != null) {
            Game.getEntityManager().cleanup();
        }

        logger.debug("Shutting down world update executor...");
        worldUpdateExecutor.shutdownNow();
        try {
            if (!worldUpdateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warn("World update executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for world update executor shutdown");
        }

        cleanupStaticResources();

        logger.debug("Game cleanup completed");
    }

    private static void cleanupStaticResources() {
        try {
            logger.debug("Shutting down MMS API...");
            com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down MMS API", e);
        }

    }
}
