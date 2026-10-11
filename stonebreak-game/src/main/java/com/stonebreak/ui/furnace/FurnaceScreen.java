package com.stonebreak.ui.furnace;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.ui.furnace.core.FurnaceController;
import com.stonebreak.ui.furnace.core.FurnaceInputManager;
import com.openmason.engine.util.BlockPos;

/**
 * A furnace screen for smelting items.
 * Uses the modular inventory architecture and mirrors the Workbench pattern.
 *
 * <p>The controller keeps the lifecycle and every slot rule; a {@link Presentation} (#298: the
 * shipped UI document, {@code ui.furnace.FurnaceDocument}) draws the screen and routes its input
 * through those rules ({@code stonebreak:inventory.slot-*} → {@code FurnaceInputManager}).
 */
public class FurnaceScreen {

    /** The way the furnace shows while the lifecycle stays here. Told when the screen opens and closes. */
    public interface Presentation extends com.stonebreak.ui.runtime.screens.ScreenPresentation {
    }

    private final FurnaceController controller;
    private Presentation presentation;

    public FurnaceScreen(Game game, Inventory inventory, InputHandler inputHandler, SmeltingManager smeltingManager) {
        // Break circular dependency: create controller with null-inputManager first,
        // then wire inputManager after both exist.
        FurnaceController controllerInstance = new FurnaceController(game, null, smeltingManager);

        FurnaceInputManager inputManager = new FurnaceInputManager(
            inputHandler, inventory, controllerInstance
        );

        // Retroactively wire inputManager into controller
        controllerInstance.setInputManager(inputManager);
        this.controller = controllerInstance;
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(Presentation presentation) {
        if (this.presentation != null && isVisible()) {
            this.presentation.hidden();
        }
        this.presentation = presentation;
        if (presentation != null && isVisible()) {
            presentation.shown();
        }
    }

    public void open(BlockPos pos) {
        controller.open(pos);
        if (presentation != null && isVisible()) {
            presentation.shown();
        }
    }

    public void close() {
        controller.close();
        if (presentation != null) {
            presentation.hidden();
        }
    }

    /** True while the {@link Presentation} shows the screen. */
    public boolean presentationShowing() {
        return presentation != null && presentation.showing();
    }

    public boolean isVisible() {
        return controller.isVisible();
    }

    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    /** Paints the presentation: panel, slots, tooltip and carried stack in one paint. */
    public void render() {
        if (presentation != null && isVisible()) {
            presentation.paint(Game.getWindowWidth(), Game.getWindowHeight());
        }
    }

    public void handleCloseRequest() {
        controller.handleCloseRequest();
    }

    public FurnaceController getController() {
        return controller;
    }
}
