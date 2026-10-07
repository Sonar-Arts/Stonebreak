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
import com.openmason.engine.util.BlockPos;

/**
 * A furnace screen for smelting items.
 * Uses the modular inventory architecture and mirrors the Workbench pattern.
 *
 * <p>The controller keeps the lifecycle and every slot rule whichever presentation shows the
 * screen: the legacy renderer and mouse poll, or a {@link Presentation} (#298: the shipped UI
 * document, {@code ui.furnace.FurnaceDocument}) that draws it and routes its input through the
 * same rules ({@code stonebreak:inventory.slot-*} → {@code FurnaceInputManager}).
 */
public class FurnaceScreen {

    /**
     * Another way to show the furnace while the lifecycle stays here. Told when the screen opens
     * and closes; while it is {@link #showing()} the legacy mouse poll, dragged-item overlay and
     * renderer stand down, and {@link #paint} returning false falls back to the legacy renderer.
     */
    public interface Presentation {
        void shown();

        void hidden();

        boolean paint(int windowWidth, int windowHeight);

        boolean showing();
    }

    private final FurnaceController controller;
    private Presentation presentation;

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

    /** Installs (or, with null, removes) the alternative presentation; the legacy one is the default. */
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

    /** True while a {@link Presentation} shows the screen instead of the legacy renderer. */
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
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        if (presentation != null && isVisible() && presentation.paint(screenWidth, screenHeight)) {
            return; // the document draws the panel, tooltip and carried stack in one paint
        }
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
        if (presentationShowing()) {
            return; // the document's cursor layer carries it
        }
        controller.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    public void handleInput(InputHandler inputHandler) {
        if (!isVisible() || presentationShowing()) return; // a document routes its own input

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
