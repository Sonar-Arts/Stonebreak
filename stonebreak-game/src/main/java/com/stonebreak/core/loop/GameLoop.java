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
            case INVENTORY_UI -> {
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
            }
            case RECIPE_BOOK_UI -> {
                if (recipeScreen != null) {
                    recipeScreen.update(deltaTime);
                }
            }
            case CHARACTER_SHEET_UI -> {
                // No per-frame character screen update needed; world continues to run.
            }
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
        if (world != null) {
            worldUpdateExecutor.submit(() -> world.update(renderer));
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

        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager != null) {
            entityManager.update(deltaTime);
        }

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
