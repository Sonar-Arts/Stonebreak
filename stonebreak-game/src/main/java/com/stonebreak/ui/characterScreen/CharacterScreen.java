package com.stonebreak.ui.characterScreen;

import com.stonebreak.input.InputHandler;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;

/**
 * Facade for the Character Screen.
 * Mirrors InventoryScreen's delegation model: facade → controller → render coordinator.
 */
public class CharacterScreen {

    private final CharacterController controller;

    public CharacterScreen(Player player, Renderer renderer, InputHandler inputHandler) {
        CharacterStats stats = new CharacterStats(player);

        this.controller = new CharacterController();

        CharacterRenderCoordinator rc = new CharacterRenderCoordinator(
                renderer, inputHandler, stats, controller);

        this.controller.setRenderCoordinator(rc);
    }

    public void toggleVisibility() {
        controller.toggleVisibility();
    }

    public boolean isVisible() {
        return controller.isVisible();
    }

    public void render(int screenWidth, int screenHeight) {
        controller.render(screenWidth, screenHeight);
    }

    public void handleMouseInput(int screenWidth, int screenHeight) {
        controller.handleMouseInput(screenWidth, screenHeight);
    }
}
