package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeVariationService;
import com.stonebreak.ui.recipeScreen.renderers.RecipeRenderCoordinator;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Translates raw input events into state changes on the recipe book.
 *
 * Hit-testing is delegated to {@link RecipeRenderCoordinator} so this class
 * doesn't duplicate the renderer's layout math. It owns only flow:
 * scrolling, click prioritization, search/key forwarding.
 */
public class RecipeBookInputHandler {
    private final RecipeBookState state;
    private final InputHandler inputHandler;
    private final SearchInputHandler searchInputHandler;
    private final PopupInputHandler popupInputHandler;
    private final RecipeFilterService filterService;
    private final RecipeVariationService variationService;
    private RecipeRenderCoordinator coordinator;

    public RecipeBookInputHandler(RecipeBookState state, InputHandler inputHandler,
                                  RecipeFilterService filterService,
                                  RecipeVariationService variationService) {
        this.state = state;
        this.inputHandler = inputHandler;
        this.filterService = filterService;
        this.variationService = variationService;
        this.searchInputHandler = new SearchInputHandler(state.getSearchState(), state.getUiState());
        this.popupInputHandler  = new PopupInputHandler(state.getPopupState(), inputHandler);
    }

    /** Wired after construction once the coordinator exists (avoids cyclic init). */
    public void setCoordinator(RecipeRenderCoordinator coordinator) {
        this.coordinator = coordinator;
        this.popupInputHandler.setCoordinator(coordinator);
    }

    public void handleInput() {
        if (!state.isVisible() || coordinator == null) return;
        handleScrolling();
        handleMouseClicks();
    }

    public void handleCharacterInput(char character) {
        if (state.isVisible()) {
            searchInputHandler.handleCharacterInput(character);
        }
    }

    public void handleKeyInput(int key, int action) {
        if (!state.isVisible()) return;

        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            // Deselect detail pane on ESC; book closure is handled upstream by InputHandler.
            popupInputHandler.handleEscapeKey();
        } else {
            searchInputHandler.handleKeyInput(key, action);
        }
    }

    private void handleScrolling() {
        double scrollY = inputHandler.getAndResetScrollY();
        if (scrollY == 0) return;

        List<Recipe> filtered = filterService.getFilteredRecipes(
                state.getRecipes(),
                state.getUiState().getSelectedCategory(),
                state.getSearchState().getSearchText());

        int maxScroll = coordinator.maxScrollOffset(filtered.size());
        int newOffset = state.getUiState().getScrollOffset() - (int) scrollY;
        state.getUiState().setScrollOffset(newOffset);
        state.getUiState().limitScrollOffset(maxScroll);
    }

    private void handleMouseClicks() {
        if (!inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) return;

        Vector2f mouse = inputHandler.getMousePosition();

        // Detail-pane variation buttons take priority so a click between
        // detail-pane and grid (rare) goes to the variant cycler.
        if (popupInputHandler.handleVariationClick(mouse)) return;

        // Category sidebar
        String clickedCategory = coordinator.categoryAt(mouse.x, mouse.y);
        if (clickedCategory != null) {
            state.getUiState().setSelectedCategory(clickedCategory);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        // Search field — focus / unfocus
        if (coordinator.searchClicked(mouse.x, mouse.y)) {
            searchInputHandler.activateSearch();
        } else {
            searchInputHandler.deactivateSearch();
        }

        // Recipe in the grid → load detail pane
        Recipe clicked = coordinator.recipeAt(mouse.x, mouse.y);
        if (clicked != null) {
            List<Recipe> variations = variationService.getRecipeVariations(clicked, state.getRecipes());
            state.getPopupState().openPopup(clicked, variations);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
    }
}
