package com.stonebreak.core.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.stonebreak.core.Game;

/**
 * Orchestrates {@link Game} shutdown: cleans up per-instance subsystems,
 * flushes the save service, terminates the world-update executor, and
 * tears down static resources (MMS API, ModelLoader, CowTextureAtlas).
 * Extracted from {@code Game.cleanup()} / {@code Game.cleanupStaticResources()}.
 */
public final class GameShutdown {

    private GameShutdown() {
    }

    /**
     * Runs the full shutdown sequence.
     *
     * @param game                 the game instance whose subsystems should be torn down
     * @param worldUpdateExecutor  the executor owned by {@link Game} that must be stopped
     */
    public static void shutdown(Game game, ExecutorService worldUpdateExecutor) {
        System.out.println("Starting Game cleanup...");

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
                System.out.println("Performing final save before shutdown...");
                CompletableFuture<Void> saveOperation = game.getSaveService().saveAll();
                saveOperation.get(5, TimeUnit.SECONDS);
                System.out.println("Final save completed successfully");
            } catch (TimeoutException e) {
                System.err.println("Final save timed out after 5 seconds - proceeding with shutdown");
            } catch (Exception e) {
                System.err.println("Error during final save: " + e.getMessage());
            }

            try {
                System.out.println("Closing SaveService...");
                game.getSaveService().close();
            } catch (Exception e) {
                System.err.println("Error closing SaveService: " + e.getMessage());
            }
        }

        System.out.println("Shutting down world update executor...");
        worldUpdateExecutor.shutdownNow();
        try {
            if (!worldUpdateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("World update executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for world update executor shutdown");
        }

        cleanupStaticResources();

        System.out.println("Game cleanup completed");
    }

    private static void cleanupStaticResources() {
        try {
            System.out.println("Shutting down MMS API...");
            com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.shutdown();
        } catch (Exception e) {
            System.err.println("Error shutting down MMS API: " + e.getMessage());
        }

        try {
            System.out.println("Shutting down ModelLoader executor...");
            com.stonebreak.model.ModelLoader.shutdown();
        } catch (Exception e) {
            System.err.println("Error shutting down ModelLoader: " + e.getMessage());
        }

        try {
            System.out.println("Cleaning up CowTextureAtlas...");
            com.stonebreak.rendering.CowTextureAtlas.cleanup();
        } catch (Exception e) {
            System.err.println("Error cleaning up CowTextureAtlas: " + e.getMessage());
        }
    }
}
