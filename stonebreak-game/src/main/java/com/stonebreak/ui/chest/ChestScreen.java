package com.stonebreak.ui.chest;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;

/**
 * The chest screen: 27 slots over the player's inventory. The controller keeps the lifecycle and
 * the input manager every slot rule; a {@link Presentation} (the shipped document
 * {@code ui/documents/chest.sbui}, {@link ChestDocument}) draws it and routes its input through
 * those rules.
 */
public class ChestScreen {

    /** The way the chest shows while the lifecycle stays here. */
    public interface Presentation extends com.stonebreak.ui.runtime.screens.ScreenPresentation {
    }

    private final ChestController controller;
    private Presentation presentation;

    public ChestScreen(Game game, Inventory inventory, InputHandler inputHandler) {
        this.controller = new ChestController(game);
        controller.setInputManager(new ChestInputManager(inputHandler, inventory, controller));
    }

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

    public boolean presentationShowing() {
        return presentation != null && presentation.showing();
    }

    public boolean isVisible() {
        return controller.isVisible();
    }

    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    public void render() {
        if (presentation != null && isVisible()) {
            presentation.paint(Game.getWindowWidth(), Game.getWindowHeight());
        }
    }

    public void handleCloseRequest() {
        controller.handleCloseRequest();
    }

    public ChestController getController() {
        return controller;
    }
}
