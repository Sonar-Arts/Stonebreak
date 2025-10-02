package com.stonebreak.ui.recipeScreen;

import java.util.List;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.recipeScreen.renderers.*;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import com.stonebreak.ui.recipeScreen.logic.*;
import com.stonebreak.ui.recipeScreen.input.RecipeBookInputHandler;
import com.stonebreak.ui.recipeScreen.core.PositionCalculator;
import com.stonebreak.ui.recipeScreen.core.RecipeBookConstants;
import org.joml.Vector2f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;

public class RecipeScreen {
    private final UIRenderer uiRenderer;
    private final CraftingManager craftingManager;
    private final InputHandler inputHandler;
    private final Renderer renderer;

    private final RecipeBookState state;
    private final RecipeFilterService filterService;
    private final RecipeSearchService searchService;
    private final RecipeVariationService variationService;
    private final RecipeBookInputHandler inputHandlerModule;

    public RecipeScreen(UIRenderer uiRenderer, InputHandler inputHandler, Renderer renderer) {
        this.uiRenderer = uiRenderer;
        this.inputHandler = inputHandler;
        this.renderer = renderer;
        this.craftingManager = Game.getCraftingManager();

        this.state = new RecipeBookState();
        this.searchService = new RecipeSearchService();
        this.filterService = new RecipeFilterService(searchService);
        this.variationService = new RecipeVariationService();
        this.inputHandlerModule = new RecipeBookInputHandler(state, inputHandler, filterService, variationService);
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
        if (!state.isVisible() || uiRenderer == null) {
            return;
        }

        state.getUiState().clearHoverStates();

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        PositionCalculator.PanelDimensions panel = PositionCalculator.calculateMainPanelDimensions();

        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);

        RecipePanelRenderer.drawRecipePanel(uiRenderer, panel.x, panel.y, panel.width, panel.height);
        RecipePanelRenderer.drawRecipeTitle(uiRenderer, panel.x + panel.width / 2, panel.y + 20 + 12, "Recipe Book");

        int categoryY = PositionCalculator.calculateCategoryButtonY(panel);
        RecipeCategoryRenderer.drawCategoryButtons(uiRenderer, inputHandler, panel.x + 20, categoryY, panel.width - 40,
                RecipeBookConstants.CATEGORIES, state.getUiState().getSelectedCategory());

        int searchBarY = PositionCalculator.calculateSearchBarY(panel);
        RecipeSearchRenderer.drawSearchBar(uiRenderer, panel.x + 20, searchBarY, panel.width - 40, 30,
                state.getSearchState().getSearchText(), state.getSearchState().isSearchActive(), state.getSearchState().isTyping());

        int recipeGridY = searchBarY + 30 + 20;
        int recipeGridHeight = panel.height - (recipeGridY - panel.y) - 20;

        List<Recipe> filteredRecipes = filterService.getFilteredRecipes(
                state.getRecipes(), state.getUiState().getSelectedCategory(), state.getSearchState().getSearchText());

        RecipeGridRenderer.GridHoverResult gridResult = RecipeGridRenderer.drawRecipeGrid(
            uiRenderer, renderer, inputHandler,
            panel.x + 20, recipeGridY, panel.width - 40, recipeGridHeight,
            filteredRecipes, state.getUiState().getScrollOffset()
        );

        if (gridResult.hoveredRecipe != null) {
            state.getUiState().setHoveredRecipe(gridResult.hoveredRecipe);
        }
        if (gridResult.hoveredItemStack != null) {
            state.getUiState().setHoveredItemStack(gridResult.hoveredItemStack);
        }

        uiRenderer.endFrame();

        if (state.getPopupState().isShowingRecipePopup() && state.getPopupState().getSelectedRecipe() != null) {
            RecipePopupRenderer.PopupHoverResult popupResult = RecipePopupRenderer.drawRecipePopup(
                uiRenderer, renderer, inputHandler, screenWidth, screenHeight,
                state.getPopupState().getSelectedRecipe(), state.getPopupState().getCurrentRecipeVariations(),
                state.getPopupState().getCurrentVariationIndex()
            );

            if (popupResult.hoveredItemStack != null) {
                state.getUiState().setHoveredItemStack(popupResult.hoveredItemStack);
            }
        }
    }
    
    public void renderTooltipsOnly() {
        if (!state.isVisible()) {
            return;
        }

        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);

        ItemStack hoveredItemStack = state.getUiState().getHoveredItemStack();
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty()) {
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                RecipeTooltipRenderer.drawItemTooltip(uiRenderer, item.getName(), mousePos.x + 15, mousePos.y + 15);
            }
        }

        Recipe hoveredRecipe = state.getUiState().getHoveredRecipe();
        if (hoveredRecipe != null) {
            Vector2f mousePos = inputHandler.getMousePosition();
            Item outputItem = hoveredRecipe.getOutput().getItem();
            if (outputItem != null) {
                String recipeName = "Recipe: " + outputItem.getName();
                RecipeTooltipRenderer.drawRecipeTooltip(uiRenderer, recipeName, mousePos.x + 15, mousePos.y + 15);
            }
        }

        uiRenderer.endFrame();
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
}