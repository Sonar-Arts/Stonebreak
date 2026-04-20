package com.stonebreak.core.world;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles world replacement and reset flows. Extracted from
 * {@code Game.resetWorld() / createFreshWorldInstance / replaceWorldInstance}.
 */
public final class WorldLifecycle {

    private final Game game;

    public WorldLifecycle(Game game) {
        this.game = game;
    }

    /**
     * Resets the world state without fully cleaning up resources. Used when
     * returning to main menu from gameplay.
     */
    @SuppressWarnings("deprecation")
    public void resetWorld() {
        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] Starting complete world reset...");
        System.out.println("========================================");

        if (game.getSaveService() != null) {
            System.out.println("[WORLD-ISOLATION] Flushing saves before world reset");
            game.getSaveService().flushSavesBlocking("world reset");
        } else {
            System.out.println("[WORLD-ISOLATION] No save system present during world reset");
        }

        if (game.getSaveService() != null) {
            try {
                game.getSaveService().stopAutoSave();
                System.out.println("[WORLD-ISOLATION] Stopped auto-save system for clean reset");
            } catch (Exception e) {
                System.err.println("[WORLD-ISOLATION] Error stopping auto-save: " + e.getMessage());
            }
        }

        System.out.println("[WORLD-ISOLATION] Player data preserved for world switching");

        World world = Game.getWorld();
        if (world != null) {
            try {
                world.clearWorldData();
                System.out.println("[WORLD-ISOLATION] World chunks and caches cleared");
            } catch (Exception e) {
                System.err.println("[WORLD-ISOLATION] Error clearing world data: " + e.getMessage());
            }
        } else {
            System.out.println("[WORLD-ISOLATION] No world to clear");
        }

        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager != null) {
            try {
                entityManager.cleanup();
                System.out.println("[BACKGROUND-SYSTEMS] ✓ Stopped EntityManager - no more cows or entities running");
            } catch (Exception e) {
                System.err.println("[BACKGROUND-SYSTEMS] ✗ Error stopping EntityManager: " + e.getMessage());
            }
        } else {
            System.out.println("[BACKGROUND-SYSTEMS] ⚠ No EntityManager to stop (unexpected)");
        }

        com.stonebreak.rendering.WaterEffects waterEffects = Game.getWaterEffects();
        if (waterEffects != null) {
            try {
                waterEffects.detectExistingWater();
                System.out.println("[BACKGROUND-SYSTEMS] ✓ Reset WaterEffects - cleared water simulation data");
            } catch (Exception e) {
                System.err.println("[BACKGROUND-SYSTEMS] ✗ Error resetting WaterEffects: " + e.getMessage());
            }
        } else {
            System.out.println("[BACKGROUND-SYSTEMS] ⚠ No WaterEffects to reset (unexpected)");
        }

        game.setCurrentWorldName(null);
        game.setCurrentWorldSeed(0);
        game.setCurrentWorldData(null);
        game.setSaveService(null);
        System.out.println("[WORLD-ISOLATION] ✓ Cleared game metadata and save system for world switching");

        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] ✓ World reset completed - main menu is now clean!");
        System.out.println("[MAIN-MENU-TRANSITION] No background systems should be running.");
        System.out.println("========================================");
    }

    /**
     * Creates a fresh {@link World} instance with the specified seed.
     * MmsAPI must already be initialized.
     */
    public World createFreshWorldInstance(long seed) {
        WorldConfiguration config = new WorldConfiguration();
        return new World(config, seed);
    }

    /**
     * Replaces the current world with a new instance, disposes of the old
     * one, creates a fresh player, and re-runs world-dependent bootstrap.
     */
    public void replaceWorldInstance(World newWorld) {
        World oldWorld = Game.getWorld();
        if (oldWorld != null) {
            oldWorld.cleanup();
        }

        Player newPlayer = new Player(newWorld);
        System.out.println("[WORLD-ISOLATION] Created fresh player for new world to ensure inventory isolation");

        if (newWorld != null) {
            game.initWorldComponents(newWorld, newPlayer);
            System.out.println("[WORLD-ISOLATION] Initialized world components for new world");
        }

        System.out.println("[WORLD-ISOLATION] World instance replaced successfully");
    }
}
