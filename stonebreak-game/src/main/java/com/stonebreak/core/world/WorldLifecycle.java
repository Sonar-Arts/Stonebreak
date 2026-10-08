package com.stonebreak.core.world;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles world replacement and reset flows for the two-world model. Builds the client RENDER
 * world ({@link #createClientWorldInstance}) and swaps it in ({@link #replaceWorldInstance});
 * the authoritative world is owned by the server ({@code ServerLevel}), not here.
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
        com.stonebreak.battletest.BattleTestSession.leave();
        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] Starting complete world reset...");
        System.out.println("========================================");

        // A UI drag in flight must end (returning its payload to its source) before anything
        // below snapshots the inventory (#288).
        com.stonebreak.ui.runtime.GameUiInput.get().cancelAll(
                com.openmason.engine.ui.runtime.input.CancelReason.DISCONNECT);

        // Before the save flush captures the inventory: anything still on the cursor or in
        // a crafting grid lives only in these per-world screens and would be lost (issue #307).
        returnCraftingGridsToPlayer(game);

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

        game.setCurrentWorldName(null);
        game.setCurrentWorldSeed(0);
        game.setCurrentWorldData(null);
        game.setSaveService(null);
        System.out.println("[WORLD-ISOLATION] ✓ Cleared game metadata and save system for world switching");

        DebugOverlay debugOverlay = Game.getDebugOverlay();
        if (debugOverlay != null) {
            debugOverlay.hide();
            System.out.println("[MAIN-MENU-TRANSITION] ✓ Hidden debug overlay (F3 menu)");
        }

        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] ✓ World reset completed - main menu is now clean!");
        System.out.println("[MAIN-MENU-TRANSITION] No background systems should be running.");
        System.out.println("========================================");
    }

    /**
     * Returns the inventory's 2x2 grid and every open screen's cursor stack to the player before
     * the save captures the inventory. Crafting tables and furnaces are not emptied: their slots are
     * block state saved with the chunk (issue #307); closing an open one puts its cursor stack back
     * and ships its slots, as Escape does (quitting from WORKBENCH_UI used to lose the stack, #300).
     * Also called by {@code GameShutdown}.
     */
    public static void returnCraftingGridsToPlayer(Game game) {
        try {
            if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
                cursorToInventory(game.getWorkbenchScreen().getSlotInput(), game);
                game.getWorkbenchScreen().close();
            }
            if (game.getFurnaceScreen() != null && game.getFurnaceScreen().isVisible()) {
                cursorToInventory(game.getFurnaceScreen().getController().getInputManager(), game);
                game.getFurnaceScreen().close();
            }
            if (game.getInventoryScreen() != null) game.getInventoryScreen().returnHeldItemsToPlayer();
        } catch (RuntimeException e) {
            System.err.println("[WORLD-ISOLATION] Error returning crafting grids: " + e.getMessage());
        }
    }

    /**
     * The open screen's cursor stack straight into the player's inventory, which the save that follows
     * captures: closing would put it back into the table or furnace slot, and that slot reaches the
     * server's save only by an asynchronous packet. What does not fit stays for {@code close()} (its slot,
     * else the world).
     */
    private static void cursorToInventory(com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput screen, Game game) {
        var player = Game.getPlayer();
        var drag = screen == null ? null : screen.getDragState();
        if (player == null || drag == null || !drag.isDragging()) {
            return;
        }
        com.stonebreak.items.ItemStack held = drag.draggedItemStack;
        held.setCount(held.getCount() - player.getInventory().addItemAndReturnCount(held));
        if (held.isEmpty()) {
            drag.clear();
        }
    }

    /**
     * Creates a client RENDER world (two-world model): fully rendered, but with terrain
     * generation disabled and no save service. Its contents stream in from the authoritative
     * server. MmsAPI must already be initialized.
     */
    public World createClientWorldInstance(long seed) {
        return World.createClientView(new WorldConfiguration(), seed);
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
