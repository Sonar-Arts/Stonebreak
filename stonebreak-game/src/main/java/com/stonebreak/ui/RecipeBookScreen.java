package com.stonebreak.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.recipeScreen.renderers.*;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemCategory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.Renderer;

public class RecipeBookScreen {
    private final UIRenderer uiRenderer;
    private final CraftingManager craftingManager;
    private final InputHandler inputHandler;
    private final Renderer renderer;
    private List<Recipe> recipes;

    private boolean visible = false;

    private int scrollOffset = 0;
    private final int RECIPE_DISPLAY_HEIGHT = 80;
    private final int ITEM_SLOT_SIZE = 40; // Match inventory slot size
    private final int PADDING = 20; // Match inventory padding
    private final int TITLE_HEIGHT = 30;

    private String searchText = "";
    private boolean searchActive = false;
    
    // Category filtering
    private String selectedCategory = "All";
    private static final String[] CATEGORIES = {"All", "Building", "Tools", "Food", "Decorative"};
    
    // UI state
    private ItemStack hoveredItemStack;
    private Recipe hoveredRecipe;
    
    // Popup state
    private boolean showingRecipePopup = false;
    private Recipe selectedRecipe = null;
    private boolean popupJustOpened = false; // Prevent immediate closure
    
    // Recipe pagination state
    private List<Recipe> currentRecipeVariations = new ArrayList<>();
    private int currentVariationIndex = 0;
    
    // Search state
    private boolean isTyping = false;
    private long lastTypingTime = 0;
    private static final long TYPING_TIMEOUT = 500; // 500ms timeout

    public RecipeBookScreen(UIRenderer uiRenderer, InputHandler inputHandler, Renderer renderer) {
        this.uiRenderer = uiRenderer;
        this.inputHandler = inputHandler;
        this.renderer = renderer;
        this.craftingManager = Game.getCraftingManager();
        this.recipes = new ArrayList<>();
        this.hoveredItemStack = null;
        this.hoveredRecipe = null;
        this.showingRecipePopup = false;
        this.selectedRecipe = null;
        this.popupJustOpened = false;
        this.isTyping = false;
        this.lastTypingTime = 0;
    }

    public void init() {
        if (craftingManager != null) {
            this.recipes = craftingManager.getAllRecipes();
            System.out.println("RecipeBookScreen: Loaded " + this.recipes.size() + " recipes.");
        }
        this.searchText = "";
        this.scrollOffset = 0;
        this.searchActive = false;
    }

    public boolean isVisible() {
        return visible;
    }

    public void update(double deltaTime) {
        // Handle typing timeout for search
        if (isTyping && System.currentTimeMillis() - lastTypingTime > TYPING_TIMEOUT) {
            isTyping = false;
        }
        
        // Reset popup just opened flag after one frame
        if (popupJustOpened) {
            popupJustOpened = false;
        }
    }

    public void render() {
        if (!visible || uiRenderer == null) {
            return;
        }

        // Reset hovered items
        hoveredItemStack = null;
        hoveredRecipe = null;

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        // Calculate panel dimensions to match inventory screen proportions
        int panelWidth = Math.min(screenWidth - 100, 900); // Max 900px width
        int panelHeight = Math.min(screenHeight - 100, 700); // Max 700px height
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;

        // Begin UIRenderer frame
        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);

        // Draw main panel background (matching inventory style)
        RecipePanelRenderer.drawRecipePanel(uiRenderer, panelX, panelY, panelWidth, panelHeight);

        // Draw title
        RecipePanelRenderer.drawRecipeTitle(uiRenderer, panelX + panelWidth / 2, panelY + PADDING + 12, "Recipe Book");

        // Category buttons area
        int categoryY = panelY + TITLE_HEIGHT + PADDING;
        RecipeCategoryRenderer.drawCategoryButtons(uiRenderer, inputHandler, panelX + PADDING, categoryY, panelWidth - 2 * PADDING, CATEGORIES, selectedCategory);

        // Search bar
        int searchBarY = categoryY + 35 + PADDING;
        RecipeSearchRenderer.drawSearchBar(uiRenderer, panelX + PADDING, searchBarY, panelWidth - 2 * PADDING, 30, searchText, searchActive, isTyping);

        // Recipe grid area
        int recipeGridY = searchBarY + 30 + PADDING;
        int recipeGridHeight = panelHeight - (recipeGridY - panelY) - PADDING;

        // Get filtered recipes for grid
        List<Recipe> filteredRecipes = getFilteredRecipes();

        // Draw recipe grid and capture hover results
        RecipeGridRenderer.GridHoverResult gridResult = RecipeGridRenderer.drawRecipeGrid(
            uiRenderer, renderer, inputHandler,
            panelX + PADDING, recipeGridY, panelWidth - 2 * PADDING, recipeGridHeight,
            filteredRecipes, scrollOffset
        );

        // Update hover states from grid rendering
        if (gridResult.hoveredRecipe != null) {
            hoveredRecipe = gridResult.hoveredRecipe;
        }
        if (gridResult.hoveredItemStack != null) {
            hoveredItemStack = gridResult.hoveredItemStack;
        }
        
        // End UIRenderer frame before drawing popup (to handle 3D items properly)
        uiRenderer.endFrame();
        
        // Draw recipe popup if showing (after main UI frame)
        if (showingRecipePopup && selectedRecipe != null) {
            RecipePopupRenderer.PopupHoverResult popupResult = RecipePopupRenderer.drawRecipePopup(
                uiRenderer, renderer, inputHandler, screenWidth, screenHeight,
                selectedRecipe, currentRecipeVariations, currentVariationIndex
            );

            // Update hover state from popup rendering
            if (popupResult.hoveredItemStack != null) {
                hoveredItemStack = popupResult.hoveredItemStack;
            }
        }
    }
    
    public void renderTooltipsOnly() {
        if (!visible) {
            return;
        }
        
        // Begin frame for tooltips
        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
        
        // Draw item tooltip if hovering over an item
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty()) {
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                RecipeTooltipRenderer.drawItemTooltip(uiRenderer, item.getName(), mousePos.x + 15, mousePos.y + 15);
            }
        }

        // Draw recipe tooltip if hovering over a recipe
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

    private List<Recipe> getFilteredRecipes() {
        List<Recipe> filtered = new ArrayList<>();
        String lowerSearchText = searchText.toLowerCase();
        
        // Group recipes by output block type to show only one recipe per output type
        Map<Integer, Recipe> uniqueOutputRecipes = new HashMap<>();
        
        for (Recipe recipe : recipes) {
            // Filter by category first
            if (!selectedCategory.equals("All")) {
                Item outputItem = recipe.getOutput().getItem();
                String category = getCategoryForItem(outputItem);
                if (!category.equals(selectedCategory)) {
                    continue;
                }
            }
            
            // Then filter by search text
            boolean matchesSearch = false;
            if (searchText.isEmpty()) {
                matchesSearch = true;
            } else {
                Item outputItem = recipe.getOutput().getItem();
                if (outputItem != null && outputItem.getName().toLowerCase().contains(lowerSearchText)) {
                    matchesSearch = true;
                } else if (recipe.getId() != null && recipe.getId().toLowerCase().contains(lowerSearchText)) {
                    matchesSearch = true;
                }
            }
            
            if (matchesSearch) {
                int outputBlockTypeId = recipe.getOutput().getBlockTypeId();
                // Only add one recipe per output type to the main grid
                if (!uniqueOutputRecipes.containsKey(outputBlockTypeId)) {
                    uniqueOutputRecipes.put(outputBlockTypeId, recipe);
                    filtered.add(recipe);
                }
            }
        }
        return filtered;
    }
    
    /**
     * Gets all recipe variations that produce the same output as the given recipe
     */
    private List<Recipe> getRecipeVariations(Recipe baseRecipe) {
        List<Recipe> variations = new ArrayList<>();
        int targetOutputBlockId = baseRecipe.getOutput().getBlockTypeId();
        
        for (Recipe recipe : recipes) {
            if (recipe.getOutput().getBlockTypeId() == targetOutputBlockId) {
                variations.add(recipe);
            }
        }
        return variations;
    }
    
    private String getCategoryForItem(Item item) {
        if (item == null) return "All";
        
        // Use the item's actual category if available
        if (item instanceof ItemType || item instanceof BlockType) {
            ItemCategory category = item.getCategory();
            switch (category) {
                case TOOLS -> { return "Tools"; }
                case FOOD -> { return "Food"; }
                case DECORATIVE -> { return "Decorative"; }
                case MATERIALS -> { return "Building"; } // Materials go under Building for now
                case BLOCKS -> { return "Building"; }
                default -> { return "Building"; }
            }
        }
        
        // Fallback to name-based categorization for compatibility
        String name = item.getName().toLowerCase();
        if (name.contains("tool") || name.contains("pick") || name.contains("axe") || name.contains("shovel")) {
            return "Tools";
        } else if (name.contains("food") || name.contains("bread") || name.contains("apple")) {
            return "Food";
        } else if (name.contains("flower") || name.contains("decoration")) {
            return "Decorative";
        } else {
            return "Building";
        }
    }


    public void handleInput() {
        if (!visible) return;
        
        // Handle scrolling
        double scrollY = inputHandler.getAndResetScrollY();
        if (scrollY != 0) {
            List<Recipe> filteredRecipes = getFilteredRecipes();
            int screenWidth = Game.getWindowWidth();
            int screenHeight = Game.getWindowHeight();
            int panelWidth = Math.min(screenWidth - 100, 900);
            int panelHeight = Math.min(screenHeight - 100, 700);

            // Calculate recipe grid dimensions
            int recipeGridWidth = panelWidth - 2 * PADDING;
            int categoryY = (screenHeight - panelHeight) / 2 + TITLE_HEIGHT + PADDING;
            int searchBarY = categoryY + 35 + PADDING;
            int recipeGridY = searchBarY + 30 + PADDING;
            int recipeGridHeight = panelHeight - (recipeGridY - (screenHeight - panelHeight) / 2) - PADDING;

            int maxScrollRows = RecipeGridRenderer.calculateMaxScrollOffset(recipeGridWidth, recipeGridHeight, filteredRecipes.size());

            scrollOffset -= (int) scrollY;
            if (scrollOffset < 0) {
                scrollOffset = 0;
            }
            if (scrollOffset > maxScrollRows) {
                scrollOffset = maxScrollRows;
            }
        }
        
        // Handle mouse clicks
        if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            Vector2f mousePos = inputHandler.getMousePosition();
            
            // Calculate UI areas
            int screenWidth = Game.getWindowWidth();
            int screenHeight = Game.getWindowHeight();
            int panelWidth = Math.min(screenWidth - 100, 900);
            int panelHeight = Math.min(screenHeight - 100, 700);
            int panelX = (screenWidth - panelWidth) / 2;
            int panelY = (screenHeight - panelHeight) / 2;
            
            // If popup is showing, check for popup interactions first (but not if just opened)
            if (showingRecipePopup && selectedRecipe != null && !popupJustOpened) {
                if (handlePopupClick(mousePos, screenWidth, screenHeight)) {
                    return; // Popup handled the click
                }
            }
            
            // Check category buttons
            int categoryY = panelY + TITLE_HEIGHT + PADDING;
            int buttonWidth = (panelWidth - 2 * PADDING - (CATEGORIES.length - 1) * 5) / CATEGORIES.length;
            boolean categoryClicked = false;
            
            for (int i = 0; i < CATEGORIES.length; i++) {
                int buttonX = panelX + PADDING + i * (buttonWidth + 5);
                if (mousePos.x >= buttonX && mousePos.x <= buttonX + buttonWidth &&
                    mousePos.y >= categoryY && mousePos.y <= categoryY + 25) {
                    selectedCategory = CATEGORIES[i];
                    scrollOffset = 0; // Reset scroll when changing category
                    categoryClicked = true;
                    // Consume the mouse click to prevent multiple triggers
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    break;
                }
            }
            
            if (!categoryClicked) {
                // Check search bar
                int searchBarY = categoryY + 35 + PADDING;
                int searchBarX = panelX + PADDING;
                int searchBarWidth = panelWidth - 2 * PADDING;
                
                boolean clickedSearchBar = (mousePos.x >= searchBarX && mousePos.x <= searchBarX + searchBarWidth &&
                                           mousePos.y >= searchBarY && mousePos.y <= searchBarY + 30);
                
                if (clickedSearchBar) {
                    searchActive = true;
                } else {
                    // Check for recipe clicks
                    Recipe clickedRecipe = getRecipeAtPosition(mousePos, panelX, panelY, panelWidth, panelHeight);
                    if (clickedRecipe != null) {
                        selectedRecipe = clickedRecipe;
                        currentRecipeVariations = getRecipeVariations(clickedRecipe);
                        currentVariationIndex = 0;
                        // Find the index of the clicked recipe in variations
                        for (int i = 0; i < currentRecipeVariations.size(); i++) {
                            if (currentRecipeVariations.get(i).equals(clickedRecipe)) {
                                currentVariationIndex = i;
                                break;
                            }
                        }
                        showingRecipePopup = true;
                        popupJustOpened = true; // Prevent immediate closure
                    } else {
                        searchActive = false;
                    }
                }
            }
        }
        
        // Handle ESC key to close popup
        if (inputHandler.isKeyPressedOnce(GLFW.GLFW_KEY_ESCAPE)) {
            if (showingRecipePopup) {
                showingRecipePopup = false;
                selectedRecipe = null;
                currentRecipeVariations.clear();
                currentVariationIndex = 0;
            }
        }
        
        // Search input is now handled via InputHandler callbacks (handleCharacterInput/handleKeyInput)
        // This prevents double input processing
    }

    public void onOpen() {
        this.visible = true;
        init(); // Load recipes, reset search, scroll
        
        // Update mouse capture state when recipe book opens
        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    public void onClose() {
        this.visible = false;
        searchActive = false;
        searchText = "";
        showingRecipePopup = false;
        selectedRecipe = null;
        popupJustOpened = false;
        isTyping = false;
        currentRecipeVariations.clear();
        currentVariationIndex = 0;
        // scrollOffset = 0; // Optionally reset scroll on close
        
        // Update mouse capture state when recipe book closes
        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }
    
    /**
     * Handle character input from InputHandler for search functionality
     */
    public void handleCharacterInput(char character) {
        if (!visible || !searchActive) return;
        
        // Add character to search text
        searchText += character;
        isTyping = true;
        lastTypingTime = System.currentTimeMillis();
        scrollOffset = 0; // Reset scroll when searching
    }
    
    /**
     * Handle key input from InputHandler for search functionality
     */
    public void handleKeyInput(int key, int action) {
        if (!visible) return;
        
        if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (searchActive && !searchText.isEmpty()) {
                        searchText = searchText.substring(0, searchText.length() - 1);
                        isTyping = true;
                        lastTypingTime = System.currentTimeMillis();
                        scrollOffset = 0;
                    }
                }
                case GLFW.GLFW_KEY_ENTER -> {
                    if (searchActive) {
                        searchActive = false;
                        isTyping = false;
                    }
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    // Escape handling is done in the main handleInput method
                }
            }
        }
    }
    
    // Helper method to get recipe at mouse position
    private Recipe getRecipeAtPosition(Vector2f mousePos, int panelX, int panelY, int panelWidth, int panelHeight) {
        List<Recipe> filteredRecipes = getFilteredRecipes();
        if (filteredRecipes.isEmpty()) {
            return null;
        }
        
        // Calculate recipe grid area
        int categoryY = panelY + TITLE_HEIGHT + PADDING;
        int searchBarY = categoryY + 35 + PADDING;
        int recipeGridY = searchBarY + 30 + PADDING;
        int recipeGridX = panelX + PADDING;
        int recipeGridWidth = panelWidth - 2 * PADDING;
        int recipeGridHeight = panelHeight - (recipeGridY - panelY) - PADDING;
        
        // Calculate grid layout
        int recipesPerRow = Math.max(1, recipeGridWidth / (RECIPE_DISPLAY_HEIGHT + 10));
        int maxVisibleRows = recipeGridHeight / (RECIPE_DISPLAY_HEIGHT + 10);
        
        // Check if mouse is within recipe grid bounds
        if (mousePos.x < recipeGridX || mousePos.x > recipeGridX + recipeGridWidth ||
            mousePos.y < recipeGridY || mousePos.y > recipeGridY + recipeGridHeight) {
            return null;
        }
        
        // Calculate which recipe was clicked
        int relativeX = (int)(mousePos.x - recipeGridX);
        int relativeY = (int)(mousePos.y - recipeGridY);
        
        int col = relativeX / (RECIPE_DISPLAY_HEIGHT + 10);
        int row = relativeY / (RECIPE_DISPLAY_HEIGHT + 10);
        
        if (col >= recipesPerRow || row >= maxVisibleRows) {
            return null;
        }
        
        // Check if click is within the actual recipe card bounds (not in spacing)
        int cardStartX = col * (RECIPE_DISPLAY_HEIGHT + 10);
        int cardStartY = row * (RECIPE_DISPLAY_HEIGHT + 10);
        int cardEndX = cardStartX + (RECIPE_DISPLAY_HEIGHT - 10); // Subtract spacing
        int cardEndY = cardStartY + (RECIPE_DISPLAY_HEIGHT - 10);
        
        if (relativeX < cardStartX || relativeX > cardEndX ||
            relativeY < cardStartY || relativeY > cardEndY) {
            return null;
        }
        
        int recipeIndex = (row + scrollOffset) * recipesPerRow + col;
        
        if (recipeIndex >= 0 && recipeIndex < filteredRecipes.size()) {
            Recipe clickedRecipe = filteredRecipes.get(recipeIndex);
            return clickedRecipe;
        }
        
        return null;
    }
    
    // Handle popup click interactions
    private boolean handlePopupClick(Vector2f mousePos, int screenWidth, int screenHeight) {
        // Calculate popup dimensions
        int popupWidth = Math.min(600, screenWidth - 80);
        int popupHeight = Math.min(480, screenHeight - 80);
        int popupX = (screenWidth - popupWidth) / 2;
        int popupY = (screenHeight - popupHeight) / 2;
        
        // Check if clicked outside popup (close popup)
        if (mousePos.x < popupX || mousePos.x > popupX + popupWidth ||
            mousePos.y < popupY || mousePos.y > popupY + popupHeight) {
            showingRecipePopup = false;
            selectedRecipe = null;
            currentRecipeVariations.clear();
            currentVariationIndex = 0;
            // Consume the mouse click to prevent multiple triggers
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true; // Handled the click
        }
        
        // Check close button (X button in top right) - updated for new size
        int closeButtonSize = 24;
        int closeButtonX = popupX + popupWidth - closeButtonSize - 16;
        int closeButtonY = popupY + 16;
        
        if (mousePos.x >= closeButtonX && mousePos.x <= closeButtonX + closeButtonSize &&
            mousePos.y >= closeButtonY && mousePos.y <= closeButtonY + closeButtonSize) {
            showingRecipePopup = false;
            selectedRecipe = null;
            currentRecipeVariations.clear();
            currentVariationIndex = 0;
            // Consume the mouse click to prevent multiple triggers
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true; // Handled the click
        }
        
        // Check pagination buttons if there are multiple variations
        if (currentRecipeVariations.size() > 1) {
            int buttonSize = 30;
            int buttonY = popupY + 60; // Below title bar
            int prevButtonX = popupX + 20;
            int nextButtonX = popupX + popupWidth - buttonSize - 20;
            
            // Previous button
            if (mousePos.x >= prevButtonX && mousePos.x <= prevButtonX + buttonSize &&
                mousePos.y >= buttonY && mousePos.y <= buttonY + buttonSize) {
                if (currentVariationIndex > 0) {
                    currentVariationIndex--;
                    selectedRecipe = currentRecipeVariations.get(currentVariationIndex);
                }
                // Consume the mouse click to prevent multiple triggers
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return true;
            }
            
            // Next button
            if (mousePos.x >= nextButtonX && mousePos.x <= nextButtonX + buttonSize &&
                mousePos.y >= buttonY && mousePos.y <= buttonY + buttonSize) {
                if (currentVariationIndex < currentRecipeVariations.size() - 1) {
                    currentVariationIndex++;
                    selectedRecipe = currentRecipeVariations.get(currentVariationIndex);
                }
                // Consume the mouse click to prevent multiple triggers
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return true;
            }
        }
        
        return true; // Click was within popup area, don't close
    }
    
    
    
    
}