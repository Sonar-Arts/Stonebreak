package com.stonebreak.ui.furnace;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.furnace.core.FurnaceController;
import com.stonebreak.ui.furnace.core.FurnaceInputManager;
import com.stonebreak.ui.furnace.renderers.FurnaceRenderCoordinator;

/**
 * A furnace screen for smelting items.
 * Uses the modular inventory architecture and mirrors the Workbench pattern.
 */
public class FurnaceScreen {

    private final FurnaceController controller;

    public FurnaceScreen(Game game, Inventory inventory, Renderer renderer, UIRenderer uiRenderer,
                          InputHandler inputHandler, SmeltingManager smeltingManager) {
        // Break circular dependency: create controller with null-inputManager first,
        // then wire inputManager after both exist.
        FurnaceController controllerInstance = new FurnaceController(
            game, inventory, null, smeltingManager, null
        );

        FurnaceInputManager inputManager = new FurnaceInputManager(
            inputHandler, inventory, controllerInstance
        );

        // Retroactively wire inputManager into controller
        controllerInstance.setInputManager(inputManager);

        FurnaceRenderCoordinator renderCoordinator = new FurnaceRenderCoordinator(
            uiRenderer, renderer, inputHandler, inventory, controllerInstance, inputManager, smeltingManager
        );

        controllerInstance.setRenderCoordinator(renderCoordinator);
        this.controller = controllerInstance;
    }

    public void open() {
        controller.open();
    }

    public void close() {
        controller.close();
    }

    public void toggleVisibility() {
        controller.toggleVisibility();
    }

    public boolean isVisible() {
        return controller.isVisible();
    }

    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    public void render() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.render(screenWidth, screenHeight);
    }

    public void renderWithoutTooltips() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.renderWithoutTooltips(screenWidth, screenHeight);
    }

    public void renderTooltipsOnly() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.renderTooltipsOnly(screenWidth, screenHeight);
    }

    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        controller.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    public void handleInput(InputHandler inputHandler) {
        if (!isVisible()) return;

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.handleInput(screenWidth, screenHeight);
    }

    public void handleCloseRequest() {
        controller.handleCloseRequest();
    }

    public FurnaceController getController() {
        return controller;
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        controller.renderHotbar(screenWidth, screenHeight);
    }

    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        controller.renderHotbarWithoutTooltips(screenWidth, screenHeight);
    }

    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        controller.renderHotbarTooltipsOnly(screenWidth, screenHeight);
    }
}
