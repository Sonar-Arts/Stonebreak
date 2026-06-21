package com.stonebreak.core.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.stonebreak.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates {@link Game} shutdown: cleans up per-instance subsystems,
 * flushes the save service, terminates the world-update executor, and
 * tears down static resources (MMS API).
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
        logger.debug("Starting Game cleanup...");

        if (Game.getWorld() != null) {
            Game.getWorld().cleanup();
        }
        if (game.getPauseMenu() != null) {
            game.getPauseMenu().cleanup();
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

        if (game.getSaveService() != null) {
            try {
                logger.debug("Performing final save before shutdown...");
                CompletableFuture<Void> saveOperation = game.getSaveService().saveAll();
                saveOperation.get(5, TimeUnit.SECONDS);
                logger.debug("Final save completed successfully");
            } catch (TimeoutException e) {
                logger.warn("Final save timed out after 5 seconds - proceeding with shutdown");
            } catch (Exception e) {
                logger.error("Error during final save", e);
            }

            try {
                logger.debug("Closing SaveService...");
                game.getSaveService().close();
            } catch (Exception e) {
                logger.error("Error closing SaveService", e);
            }
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
