package com.stonebreak.ui.recipeScreen;

import java.util.List;

import com.stonebreak.crafting.Recipe;

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

    /** The shipped document: {@code ui/documents/recipe_book.sbui} (#301). */
    public static final String DOCUMENT_ID = "recipe_book";

    private final com.stonebreak.ui.runtime.screens.PresentationSlot presentation =
            new com.stonebreak.ui.runtime.screens.PresentationSlot();

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
        if (paintDocument()) return; // the document (#301)
        coordinator.render(Game.getWindowWidth(), Game.getWindowHeight());
    }

    public void renderWithoutTooltips() {
        if (!state.isVisible()) return;
        if (paintDocument()) return;
        coordinator.renderWithoutTooltips(Game.getWindowWidth(), Game.getWindowHeight());
    }

    /** Publishes the book right before its document paints (the caret blinks on the wall clock). */
    private boolean paintDocument() {
        if (!presentation.showing()) return false;
        com.stonebreak.ui.runtime.GameUiHost host = com.stonebreak.ui.runtime.GameUiHost.get();
        if (host != null) host.recipeBook().poll();
        return presentation.paint(Game.getWindowWidth(), Game.getWindowHeight());
    }

    public void renderTooltipsOnly() {
        if (!state.isVisible() || presentation.showing()) return; // the document paints its own tooltip
        coordinator.renderTooltipsOnly(Game.getWindowWidth(), Game.getWindowHeight());
    }

    public void handleInput() {
        if (presentation.showing()) {
            inputHandler.getAndResetScrollY(); // the document takes the pointer and the wheel (#301)
            return;
        }
        inputHandlerModule.handleInput();
    }

    /** Installs (or, with null, removes) the document presentation; the legacy renderer is the default. */
    public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
        presentation.install(p);
    }

    /** True while the recipe book document, not the legacy renderer, shows the book. */
    public boolean presentationShowing() {
        return presentation.showing();
    }

    // ── what the document reads and asks (stonebreak:screen.recipe-book, #301) ──

    public RecipeBookState state() {
        return state;
    }

    /**
     * The recipes the grid shows now in a {@code w}x{@code h} window (0: the game's), row by row (the scroll
     * clamped as the legacy render clamps it).
     */
    public List<Recipe> window(int w, int h) {
        return coordinator.window(filterService.getFilteredRecipes(state.getRecipes(),
                state.getUiState().getSelectedCategory(), state.getSearchState().getSearchText()), w, h);
    }

    /** {recipes per row, visible rows} in a {@code w}x{@code h} window (0: the game's). */
    public int[] gridShape(int w, int h) {
        return coordinator.gridShape(w, h);
    }

    /** The legacy click and wheel rules, addressed by target. */
    public RecipeBookInputHandler intents() {
        return inputHandlerModule;
    }

    public void onOpen() {
        state.show();
        init();
        com.stonebreak.ui.runtime.GameUiHost.onUiThread(() -> presentation.setVisible(state.isVisible()));

        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    public void onClose() {
        state.hide();
        com.stonebreak.ui.runtime.GameUiHost.onUiThread(() -> presentation.setVisible(false));

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
