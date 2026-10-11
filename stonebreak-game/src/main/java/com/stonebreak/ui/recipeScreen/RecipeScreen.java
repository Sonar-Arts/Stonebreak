package com.stonebreak.ui.recipeScreen;

import java.util.List;

import com.stonebreak.crafting.Recipe;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.ui.recipeScreen.core.RecipeGridLayout;
import com.stonebreak.ui.recipeScreen.input.RecipeBookInputHandler;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeSearchService;
import com.stonebreak.ui.recipeScreen.logic.RecipeVariationService;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;

/**
 * Three-pane recipe browser (category sidebar, recipe grid, detail pane). Owns the book's state, its
 * rules and its grid geometry; the shipped document {@value #DOCUMENT_ID} (#301) draws it and takes
 * the pointer and the wheel ({@code stonebreak:screen.recipe-book}).
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
    private final RecipeGridLayout grid;

    public RecipeScreen(InputHandler inputHandler) {
        this.inputHandler    = inputHandler;
        this.craftingManager = Game.getCraftingManager();

        this.state              = new RecipeBookState();
        RecipeSearchService searchService = new RecipeSearchService();
        this.filterService      = new RecipeFilterService(searchService);
        this.variationService   = new RecipeVariationService();
        this.grid               = new RecipeGridLayout(state);
        this.inputHandlerModule = new RecipeBookInputHandler(state, filterService, variationService, grid);
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

    /** Publishes the book, then paints its document (the caret blinks on the wall clock). */
    public void render() {
        if (!state.isVisible() || !presentation.showing()) return;
        com.stonebreak.ui.runtime.GameUiHost host = com.stonebreak.ui.runtime.GameUiHost.get();
        if (host != null) host.recipeBook().poll();
        presentation.paint(Game.getWindowWidth(), Game.getWindowHeight());
    }

    /** The document takes the pointer and the wheel (#301): the wheel stored for polling is dropped. */
    public void handleInput() {
        inputHandler.getAndResetScrollY();
    }

    /** Installs (or, with null, removes) the document presentation. */
    public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
        presentation.install(p);
    }

    /** True while the recipe book document shows the book. */
    public boolean presentationShowing() {
        return presentation.showing();
    }

    // ── what the document reads and asks (stonebreak:screen.recipe-book, #301) ──

    public RecipeBookState state() {
        return state;
    }

    /**
     * The recipes the grid shows now in a {@code w}x{@code h} window (0: the game's), row by row (the scroll
     * clamped to the recipes there are).
     */
    public List<Recipe> window(int w, int h) {
        return grid.window(filterService.getFilteredRecipes(state.getRecipes(),
                state.getUiState().getSelectedCategory(), state.getSearchState().getSearchText()), w, h);
    }

    /** {recipes per row, visible rows} in a {@code w}x{@code h} window (0: the game's). */
    public int[] gridShape(int w, int h) {
        return grid.gridShape(w, h);
    }

    /** The book's press and wheel rules, addressed by target. */
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
}
