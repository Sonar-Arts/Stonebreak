package com.stonebreak.ui.recipeScreen;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.recipeScreen.input.RecipeBookInputHandler;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeSearchService;
import com.stonebreak.ui.recipeScreen.logic.RecipeVariationService;
import com.stonebreak.ui.recipeScreen.renderers.RecipeRenderCoordinator;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;

/**
 * Three-pane Skija/MasonryUI recipe browser.
 *
 * Owns the {@link RecipeRenderCoordinator} that does the actual drawing and
 * exposes the inventory-style three-entry render API
 * ({@link #render}, {@link #renderWithoutTooltips}, {@link #renderTooltipsOnly})
 * so callers can interleave block-drop sprites between chrome and tooltips.
 */
public class RecipeScreen {

    private final CraftingManager craftingManager;
    private final InputHandler inputHandler;

    private final RecipeBookState state;
    private final RecipeFilterService filterService;
    private final RecipeVariationService variationService;
    private final RecipeBookInputHandler inputHandlerModule;
    private final RecipeRenderCoordinator coordinator;

    public RecipeScreen(UIRenderer uiRenderer, InputHandler inputHandler, Renderer renderer) {
        this.inputHandler    = inputHandler;
        this.craftingManager = Game.getCraftingManager();

        this.state              = new RecipeBookState();
        RecipeSearchService searchService = new RecipeSearchService();
        this.filterService      = new RecipeFilterService(searchService);
        this.variationService   = new RecipeVariationService();
        this.inputHandlerModule = new RecipeBookInputHandler(state, inputHandler,
                filterService, variationService);

        this.coordinator = new RecipeRenderCoordinator(state, filterService,
                uiRenderer, renderer, inputHandler);
        this.inputHandlerModule.setCoordinator(coordinator);
    }

    public void init() {
        if (craftingManager != null) {
            state.setRecipes(craftingManager.getAllRecipes());
            System.out.println("RecipeBookScreen: Loaded " + state.getRecipes().size() + " recipes.");
        }
        state.initialize();
    }

    public boolean isVisible() {
        return state.isVisible();
    }

    public void update(double deltaTime) {
        state.update(deltaTime);
    }

    public void render() {
        if (!state.isVisible()) return;
        coordinator.render(Game.getWindowWidth(), Game.getWindowHeight());
    }

    public void renderWithoutTooltips() {
        if (!state.isVisible()) return;
        coordinator.renderWithoutTooltips(Game.getWindowWidth(), Game.getWindowHeight());
    }

    public void renderTooltipsOnly() {
        if (!state.isVisible()) return;
        coordinator.renderTooltipsOnly(Game.getWindowWidth(), Game.getWindowHeight());
    }

    public void handleInput() {
        inputHandlerModule.handleInput();
    }

    public void onOpen() {
        state.show();
        init();

        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    public void onClose() {
        state.hide();

        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    public void handleCharacterInput(char character) {
        inputHandlerModule.handleCharacterInput(character);
    }

    public void handleKeyInput(int key, int action) {
        inputHandlerModule.handleKeyInput(key, action);
    }

    public void dispose() {
        coordinator.dispose();
    }
}
