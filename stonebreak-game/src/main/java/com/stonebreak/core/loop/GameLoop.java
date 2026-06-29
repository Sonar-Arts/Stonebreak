package com.stonebreak.core.loop;

import java.util.concurrent.ExecutorService;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;

/**
 * Per-frame game tick. Routes updates per {@link GameState} and advances
 * the world/player/entity/time systems when gameplay is active.
 * Extracted from {@code Game.update()}.
 */
public final class GameLoop {

    private final Game game;
    private final ExecutorService worldUpdateExecutor;

    private boolean hasInitializedMouseCaptureAfterLoading = false;

    public GameLoop(Game game, ExecutorService worldUpdateExecutor) {
        this.game = game;
        this.worldUpdateExecutor = worldUpdateExecutor;
    }

    /**
     * Advances the game one frame. Returns true when the caller should
     * continue to game-world updates; false when the loop routed early
     * (menu / loading / paused).
     */
    public void tick(float deltaTime) {
        // Network pump runs in every state once a session is established so
        // chunks/joins/disconnects keep flowing during loading screens too.
        com.stonebreak.network.MultiplayerSession.tick();

        if (!routeStateUpdate(deltaTime)) {
            return;
        }
        updateGameWorld(deltaTime);
    }

    private boolean routeStateUpdate(float deltaTime) {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        PauseMenu pauseMenu = game.getPauseMenu();
        MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();

        switch (game.getState()) {
            case STARTUP_INTRO -> {
                if (game.getStartupIntroScreen() != null) {
                    game.getStartupIntroScreen().update(deltaTime);
                }
                return false;
            }
            case MAIN_MENU -> {
                return false;
            }
            case LOADING -> {
                return false;
            }
            case PLAYING -> {
                if (!hasInitializedMouseCaptureAfterLoading && mouseCaptureManager != null) {
                    mouseCaptureManager.forceUpdate();
                    hasInitializedMouseCaptureAfterLoading = true;
                }
            }
            case WORKBENCH_UI -> {
                if (workbenchScreen != null && workbenchScreen.isVisible()) {
                    workbenchScreen.update(deltaTime);
                }
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
            }
            case PAUSED -> {
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
                if (workbenchScreen != null && workbenchScreen.isVisible()) {
                    workbenchScreen.update(deltaTime);
                }
                // pauseMenu has no per-frame update at present.
                if (pauseMenu != null && pauseMenu.isVisible()) {
                    // hook reserved for future timed elements
                }
                return false;
            }
            case INVENTORY_UI, CHARACTER_SHEET_UI -> {
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
            }
            case RECIPE_BOOK_UI -> {
                if (recipeScreen != null) {
                    recipeScreen.update(deltaTime);
                }
            }
            case FURNACE_UI -> {
                FurnaceScreen furnaceScreen = game.getFurnaceScreen();
                if (furnaceScreen != null && furnaceScreen.isVisible()) {
                    furnaceScreen.update(deltaTime);
                }
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
            }
            // No per-frame character screen update needed; world continues to run.
            default -> {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void updateGameWorld(float deltaTime) {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null) {
            inventoryScreen.update(deltaTime);
        }

        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null) {
            workbenchScreen.update(deltaTime);
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null) {
            chatSystem.update(deltaTime);
        }

        com.stonebreak.audio.emitters.SoundEmitterManager soundEmitterManager = Game.getSoundEmitterManager();
        if (soundEmitterManager != null) {
            soundEmitterManager.update(deltaTime);
        }

        World world = Game.getWorld();
        Renderer renderer = Game.getRenderer();
        // A render-only client world (two-world model) is driven by server packets: run the
        // reduced client update (mesh + chunk streaming around the local player), never the
        // authoritative sim. The co-located singleplayer/host world stays on the full update.
        final boolean renderOnly = world != null && world.isRenderOnly();
        if (world != null) {
            if (renderOnly) {
                worldUpdateExecutor.submit(() -> world.updateClient(renderer));
            } else {
                worldUpdateExecutor.submit(() -> world.update(renderer));
            }
            world.updateMainThread();
            world.processGpuCleanupQueue();
        }

        Player player = Game.getPlayer();
        DeathMenu deathMenu = game.getDeathMenu();
        MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();
        if (player != null) {
            player.update();

            if (player.isDead() && deathMenu != null && !deathMenu.isVisible()) {
                deathMenu.setVisible(true);
                if (mouseCaptureManager != null) {
                    mouseCaptureManager.updateCaptureState();
                }
            }
        }

        com.stonebreak.rendering.WaterEffects waterEffects = Game.getWaterEffects();
        if (waterEffects != null && player != null) {
            waterEffects.update(player, deltaTime);
        }

        // Entity manager always ticks: on a render-only client it advances only shadow
        // interpolation (EntityManager.update already skips AI/physics for network shadows).
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager != null) {
            entityManager.update(deltaTime);
        }

        // Entity sight tracking (client-side, throttled, FOV+proximity gated)
        if (player != null) {
            player.getEntitySightingTracker().update(deltaTime);
        }

        // Mob spawning and the day/night clock are server-authoritative. On a render-only
        // client they are driven by replication (entity spawns; a future TimeSyncS2C), so skip
        // them locally to avoid the client diverging from the server.
        if (!renderOnly) {
            com.stonebreak.mobs.entities.EntitySpawner entitySpawner = game.getEntitySpawner();
            if (entitySpawner != null) {
                entitySpawner.update(deltaTime);
            }

            TimeOfDay timeOfDay = Game.getTimeOfDay();
            if (timeOfDay != null) {
                timeOfDay.update(deltaTime);
            }
        }
    }
}
